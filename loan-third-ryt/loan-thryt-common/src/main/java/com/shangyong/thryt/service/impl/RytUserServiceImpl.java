package com.shangyong.thryt.service.impl;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shangyong.common.entity.RestResult;
import com.shangyong.common.utils.LocalDateUtil;
import com.shangyong.loan.autoconfigure.redis.BatchRedisTemplate;
import com.shangyong.rest.feign.BaseUserCloudHystrixService;
import com.shangyong.rest.feign.OrderCloudHystrixService;
import com.shangyong.rest.feign.ThirdInfoPushHystrixService;
import com.shangyong.rest.vo.ThirdCheckUserReqVo;
import com.shangyong.rest.vo.ThirdCheckUserRspVo;
import com.shangyong.thcore.dto.CheckUserInfoDto;
import com.shangyong.thcore.vo.BaseUserInfoVo;
import com.shangyong.thryt.bo.RytAuditUserInfoBo;
import com.shangyong.thryt.bo.RytUserInfoBo;
import com.shangyong.thryt.contants.UuidPrefix;
import com.shangyong.thryt.dao.RytCheckRMapper;
import com.shangyong.thryt.dao.RytLinkmanMapper;
import com.shangyong.thryt.dao.RytUserInfoMapper;
import com.shangyong.thryt.entity.RytCheckR;
import com.shangyong.thryt.entity.RytLinkman;
import com.shangyong.thryt.entity.RytUserInfo;
import com.shangyong.thryt.enums.RytResultEnum;
import com.shangyong.thryt.exception.CalfException;
import com.shangyong.thryt.service.RytUserService;
import com.shangyong.thryt.utils.IdUtil;
import com.shangyong.thryt.utils.JsonNodeUtil;
import com.shangyong.thryt.utils.RytUtil;

@Service
public class RytUserServiceImpl implements RytUserService {

	private Logger logger = LoggerFactory.getLogger(RytUserServiceImpl.class);

	@Autowired
	private RytLinkmanMapper rytLinkmanMapper;

	@Autowired
	private RytUserInfoMapper rytUserInfoMapper;

	@Autowired
	private RytCheckRMapper rytCheckRMapper;

	@Autowired
	private ThirdInfoPushHystrixService thirdInfoPushHystrixService;

	@Autowired
	private OrderCloudHystrixService orderCloudHystrixService;

	@Autowired
	private BaseUserCloudHystrixService baseUserCloudHystrixService;

	@Autowired
	private BatchRedisTemplate batchRedisTemplate;

	@Override
	public RytUserInfoBo getUserInfo(String orderNo) {
		return rytUserInfoMapper.getUserInfoBo(orderNo);
	}

	@Override
	public RytAuditUserInfoBo getRytAuditUserInfoBo(String orderNo) {
		return rytUserInfoMapper.getRytAuditUserInfoBo(orderNo);
	}

	@Override

	public ObjectNode checkUser(String mobileHidden, String idCardHidden, String userName, String md5) {

		if (batchRedisTemplate.isRepeatClick(RytUtil.getChannel() + ":" + md5, TimeUnit.DAYS.toSeconds(1))) {
			logger.error("存在重复推单校验情况,mobileHidden:{},idCardHidden:{},userName:{},md5:{}", mobileHidden, idCardHidden,
					userName, md5);
			throw new CalfException(RytResultEnum.CLICK_REPEAT);
		}

		String appid = RytUtil.getAppid();
		String channel = RytUtil.getChannel();

		// app用户校验是否存在
		ThirdCheckUserReqVo thirdCheckUserReqVo = new ThirdCheckUserReqVo();
		thirdCheckUserReqVo.setAppid(null);
		thirdCheckUserReqVo.setChannel(channel);
		thirdCheckUserReqVo.setMd5PhoneIdCardNo(md5);
		thirdCheckUserReqVo.setIdCardNo(idCardHidden);
		thirdCheckUserReqVo.setPhone(mobileHidden);

		RestResult<ThirdCheckUserRspVo> appResult = thirdInfoPushHystrixService.checkUser(thirdCheckUserReqVo);
		if (appResult == null || !appResult.isSuccess()) {
			logger.error("thirdInfoPush远程校验异常：{}", appResult);
			throw new CalfException(RytResultEnum.REMOTE_ERROR);
		}
		ThirdCheckUserRspVo appCheckResult = appResult.getData().getBody();

		// 自有平台黑名单 (新用户或者合作老用户)
		logger.info("app校验结果：{}", appCheckResult);
		// 不允许准入
		if (!appCheckResult.isPass()) {
			return buildResult(0, appCheckResult.getReasonType().intValue() == 4 ? 2 : 1, null, 1);
		}

		// 本地校验用户信息
		CheckUserInfoDto checkUserInfoDto = new CheckUserInfoDto();
		checkUserInfoDto.setAppid(appid);
		checkUserInfoDto.setIdentityNoPrefix(idCardHidden.replace("*", ""));
		checkUserInfoDto.setMobilePrefix(mobileHidden.replace("*", ""));
		checkUserInfoDto.setSignMd5(md5.toUpperCase());
		checkUserInfoDto.setUserName(userName);
		RestResult<BaseUserInfoVo> baseUserResult = baseUserCloudHystrixService.checkAndSearch(appid, checkUserInfoDto);
		if (baseUserResult == null || !baseUserResult.isSuccess()) {
			logger.error("baseUser本地校验异常：{}", baseUserResult);
			throw new CalfException(RytResultEnum.REMOTE_ERROR);
		}
		BaseUserInfoVo baseUserInfoVo = baseUserResult.getData().getBody();
		// 说明用户信息不存在，直接返回
		if (!baseUserInfoVo.isIfExistUser()) {
			// #新用户
			return buildResult(1, 1, null, null);
		}
		// 说明用户信息存在，是隔离用户
		if (baseUserInfoVo.isIfQuarantine()) {
			// #老用户隔离
			return buildResult(0, 0, LocalDateUtil.dateStrToString(baseUserInfoVo.getQuarantineEndTime(),
					"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"), 3);
		}

		// 校验在途
		RestResult<Void> result = orderCloudHystrixService.orderOnWayCheck(appid, baseUserInfoVo.getIdentityNo());
		if (result == null) {
			logger.error("order在途校验异常：{}", result);
			throw new CalfException(RytResultEnum.REMOTE_ERROR);
		}
		if (result.isSuccess()) {
			// #老用户在途
			return buildResult(0, 0, null, 2);
		} else {
			// #老用户复借
			return buildResult(1, 0, null, null);
		}

	}

	@Override
	public boolean createCheckRecord(ObjectNode data, ObjectNode result) {
		RytCheckR rytCheckR = new RytCheckR();
		rytCheckR.setCheckId(IdUtil.getNumberUuid(UuidPrefix.CHECK_RECORD));
		rytCheckR.setCreateTime(new Date());
		rytCheckR.setIdCardMask(data.get("id_card").asText());
		rytCheckR.setMobileMask(data.get("mobile").asText());
		rytCheckR.setUserName(data.get("user_name").asText());
		rytCheckR.setmCMd5(data.get("md5").asText());
		
		rytCheckR.setValid(result.get("valid").asInt());
		rytCheckR.setUserType(result.get("user_type").asInt());
		rytCheckR.setCanLoanTime(result.get("can_loan_time").asText(null));
		rytCheckR.setReason(result.get("reason").asText(null));
		
		rytCheckRMapper.insert(rytCheckR);
		return true;
	}

	@Override
	public boolean batchSave(List<RytLinkman> rytLinkmanList) {
		int count = rytLinkmanMapper.batchSave(rytLinkmanList);
		return count == rytLinkmanList.size();
	}

	@Override
	public boolean save(RytUserInfo rytUserInfo) {
		return rytUserInfoMapper.insert(rytUserInfo) > 0;
	}

	private ObjectNode buildResult(int valid, int userType, String canLoanTime, Integer reason) {
		return JsonNodeUtil.data().put("valid", valid)//
				.put("user_type", userType)//
				.put("can_loan_time", canLoanTime)//
				.put("reason", reason);
	}

}

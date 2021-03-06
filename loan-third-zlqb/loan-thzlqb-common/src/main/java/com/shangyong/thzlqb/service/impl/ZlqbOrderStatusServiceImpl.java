package com.shangyong.thzlqb.service.impl;

import com.shangyong.common.entity.RestResult;
import com.shangyong.loan.ext.exception.CalfException;
import com.shangyong.loan.ext.util.ResultUtil;
import com.shangyong.rest.feign.OrderBankCloudHystrixService;
import com.shangyong.rest.feign.OrderRepaymentCloudHystrixService;
import com.shangyong.thcore.dto.OrderBankH5Dto;
import com.shangyong.thcore.vo.*;
import com.shangyong.thzlqb.bo.OrderSimpleBo;
import com.shangyong.thzlqb.entity.ZlqbOrderReview;
import com.shangyong.thzlqb.enums.ZlqbIsSignEnum;
import com.shangyong.thzlqb.enums.ZlqbResultEnum;
import com.shangyong.thzlqb.service.ZlqbOrderReviewService;
import com.shangyong.thzlqb.utils.DateUtil;
import com.shangyong.thzlqb.zlqb.utils.ZlqbUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shangyong.loan.ext.util.JsonNodeUtil;
import com.shangyong.thzlqb.client.ZlqbClient;
import com.shangyong.thzlqb.service.CoreOrderService;
import com.shangyong.thzlqb.service.ZlqbOrderStatusService;
import com.shangyong.thzlqb.utils.OrderUtil;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.util.Objects;

@Service
public class ZlqbOrderStatusServiceImpl implements ZlqbOrderStatusService {

    private Logger logger = LoggerFactory.getLogger(ZlqbOrderStatusServiceImpl.class);


    @Autowired
    private ZlqbClient zlqbClient;

    @Autowired
    private CoreOrderService coreOrderService;


    @Autowired
    private OrderBankCloudHystrixService orderBankCloudHystrixService;

    @Autowired
    private ZlqbOrderReviewService zlqbOrderReviewService;

    @Autowired
    private OrderRepaymentCloudHystrixService orderRepaymentService;

    @Autowired
    private OrderRepaymentCloudHystrixService orderRepaymentCloudHystrixService;

    @Override
    public JsonNode pullOrder(ObjectNode request) {
        return pullOrderByOrderNo(request.get("orderNo").asText(), null);
    }

    @Override
    public boolean pushOrder(String orderNo) {
        if (StringUtils.isEmpty(orderNo)) {
            logger.error("手动推订单时;订单号为空");
            return false;
        }
        return zlqbClient.pushObjectData(pullOrderByOrderNo(orderNo, null));
    }

    @Override
    public boolean pushMiddleOrder(String orderNo,OrderLoanVo orderLoanVoCheck) {
		return zlqbClient.pushObjectData(pullOrderByOrderNo(orderNo, orderLoanVoCheck));
	}
    
    @Override
    public boolean concelBankInfoAndPushOrder(String orderNo,String bankCardNo) {

        //取消绑卡信息
        ObjectNode node =JsonNodeUtil.data().put("orderNo", orderNo).put("bankCardNo",bankCardNo);

        boolean flag= zlqbClient.cancelBankInfo(node);

        logger.info("取消绑卡请求第三方订单号 {}, 银行卡号 {}返回结果 {}",orderNo,bankCardNo,flag);

        return true;
    }

    private ObjectNode pullOrderByOrderNo(String orderNo, OrderLoanVo orderLoanVoCheck) {

        ObjectNode objectNode = JsonNodeUtil.data().put("orderNo", orderNo);

        OrderLoanVo orderLoanVo =orderLoanVoCheck==null?baseOrderInfoGet(orderNo):orderLoanVoCheck;

        OrderSimpleBo orderInfo = isSignGet(orderNo);
        // 助力钱包状态
        objectNode.put("status", OrderUtil.getZlqbOrderStatus(orderLoanVo,
                orderInfo.getIsSign() == ZlqbIsSignEnum.IS_SIGN_Y.getValue()));

        ZlqbOrderReview dto = multipleParmGet(orderNo);
        // 必填项
        objectNode.put("loan_period", dto.getLoanPeriod());

        objectNode.put("loan_terms", dto.getLoanTerms());
        OrderRepaymentPlanVo pilotcalculation =null;
        // 已经审核成功
        if (OrderUtil.isAuditSuccess(orderLoanVo)) {

            pilotcalculation = pilotcalOrderInfoGet(orderLoanVo); // orderRepaymentCloudHystrixService.pilotcalculation(ZlqbUtil.getAppid(), orderInfo.getOrderId());
            //获取本金
            objectNode.put("approve_amount", getIntFromBigdecimal(pilotcalculation.getPrincipal()));
            objectNode.put("approve_date", dto.getApproveDate());
            objectNode.put("total_principal", getIntFromBigdecimal(dto.getTotalPrincipal()));
            objectNode.put("total_repay_money", getIntFromBigdecimal(pilotcalculation.getPrincipal())+getIntFromBigdecimal(pilotcalculation.getInterestFee()));
        }
        // 已经绑卡成功

        OrderBankVo bankVo =null;
        if (OrderUtil.isBindSuccess(orderLoanVo)) {
                     bankVo = bankInfoDtoGet(orderLoanVo);
            objectNode.set("bankCardInfo", JsonNodeUtil.data() //
                    .put("bank", bankVo.getBankName())   //
                    .put("bankCardNum", bankVo.getBankCardNo())  //
                    .put("bankPhone", bankVo.getReservedMobile()));
            objectNode.set("bankCardList", JsonNodeUtil.arrayData().add(JsonNodeUtil.data() //
                    .put("bank", bankVo.getBankName())   //
                    .put("bankCardNum", bankVo.getBankCardNo())  //
                    .put("bankPhone", bankVo.getReservedMobile())));
        }
        // 没有绑卡绑卡成功
        else {
            OrderBankH5Vo bankH5Vo = h5UrlBindCardGet(orderLoanVo);
            // 返回绑卡链接
            try {
				objectNode.put("bindCardUrl",  URLDecoder.decode(bankH5Vo.getH5Url(), "utf-8"));
			} catch (UnsupportedEncodingException e) {
				logger.error("转码失败!");
				objectNode.put("bindCardUrl",  bankH5Vo.getH5Url());
			}
        }
        // 已经放款成功
        if (OrderUtil.isLoanSuccess(orderLoanVo)) {
            //判断是否还款成功
            int repayMent=OrderUtil.isRepaymentSuccess(orderLoanVo)?1:2;
            switch (repayMent){
                case 1:

                    OrderRepaymentPlanVo haveRepayMent = haveRepayMentGet(orderInfo);
                    //逾期总费用
                    int overDueFeeWait = getIntFromBigdecimal(haveRepayMent.getTotalPenaltyInterestFee());
                    //总还款金额 =本金+总利息
                    int fromBigdecimal = getIntFromBigdecimal(haveRepayMent.getTotalInterestFee());
                    objectNode.put("total_repay_money", getIntFromBigdecimal(haveRepayMent.getPrincipal())+fromBigdecimal+overDueFeeWait);
                    objectNode.put("total_already_paid", getIntFromBigdecimal(haveRepayMent.getPrincipal())+fromBigdecimal+overDueFeeWait);
                    objectNode.put("total_overdue_fee", overDueFeeWait);
                    objectNode.put("loan_time", dto.getApproveDate());
                    objectNode.set("repayPlans", JsonNodeUtil.arrayData().add(JsonNodeUtil.data() //
                            .put("number", "1")   //
                            .put("state", OrderUtil.isRepaymentSuccess(orderLoanVo)?"601":"602" )  //
                            .put("principal", getIntFromBigdecimal(haveRepayMent.getPrincipal()))//
                            .put("interest",fromBigdecimal)//
                            .put("repay_money", getIntFromBigdecimal(haveRepayMent.getPrincipal())+fromBigdecimal+overDueFeeWait)//
                            .put("already_paid",  getIntFromBigdecimal(haveRepayMent.getPrincipal())+fromBigdecimal+overDueFeeWait)//
                            .put("overdue_fee",overDueFeeWait)//
                            .put("repay_date", DateUtil.getEndOfDay(DateUtil.StrToDate(haveRepayMent.getRepayDate())))
                            .put("realRepay_date", adaptationDateToZlqb(haveRepayMent.getActualrepayDate()))//
                            .put("overdue_days", haveRepayMent.getOverDueDays())
                            .set("bankCardInfo", JsonNodeUtil.data() //
                                    .put("bank",bankVo==null?"": bankVo.getBankName())   //
                                    .put("bankCardNum",bankVo==null?"": bankVo.getBankCardNo())  //
                                    .put("bankPhone", bankVo==null?"":bankVo.getReservedMobile()))
                    ));
                    break;
                case 2:
                    RestResult<OrderRepaymentPlanVo> yetRepaymentResult = orderRepaymentService.orderRepaymentPlanSearch(ZlqbUtil.getAppid(), orderInfo.getOrderId());
                    ResultUtil.checkAndGet(yetRepaymentResult);
                    OrderRepaymentPlanVo planVo= yetRepaymentResult.getData().getBody();
                    if(Objects.isNull(planVo)){
                        logger.error("订单手动推时获取订单已放款未还款订单相关信息出现空值");
                        throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
                    }
                    //总还款金额 =本金+总利息
                    int totalInterestFee = getIntFromBigdecimal(planVo.getTotalInterestFee());
                    //逾期费用
                    int overDueFee=  getIntFromBigdecimal(planVo.getTotalPenaltyInterestFee()) ;
                    objectNode.put("total_repay_money", getIntFromBigdecimal(planVo.getPrincipal())+totalInterestFee+overDueFee);
                    objectNode.put("total_already_paid", new BigDecimal(0));
                    objectNode.put("total_overdue_fee",overDueFee);
                    objectNode.put("loan_time", dto.getApproveDate());
                    objectNode.set("repayPlans", JsonNodeUtil.arrayData().add(JsonNodeUtil.data() //
                            .put("number", "1")   //
                            .put("state",  OrderUtil.isRepaymentSuccess(orderLoanVo)?"601":"602")  //
                            .put("principal", getIntFromBigdecimal(planVo.getPrincipal()))//
                            .put("interest", totalInterestFee)//
                            .put("repay_money",getIntFromBigdecimal(planVo.getPrincipal())+totalInterestFee+overDueFee)//
                            .put("already_paid", new BigDecimal(0))//
                            .put("overdue_fee",overDueFee)//
                            .put("repay_date", DateUtil.getEndOfDay(DateUtil.StrToDate(planVo.getRepayDate())))
                            .put("realRepay_date", "")//
                            .put("overdue_days", planVo.getOverDueDays())
                            .set("bankCardInfo", JsonNodeUtil.data() //
                                    .put("bank",bankVo==null?"": bankVo.getBankName())   //
                                    .put("bankCardNum",bankVo==null?"": bankVo.getBankCardNo())  //
                                    .put("bankPhone", bankVo==null?"":bankVo.getReservedMobile()))
                    ));
                    break;
                    default:
                        break;
            }


        }

        return objectNode;
    }

    private OrderRepaymentPlanVo pilotcalOrderInfoGet(OrderLoanVo orderLoanVo) {
        RestResult<OrderRepaymentPlanVo> pilotcalculation = orderRepaymentCloudHystrixService.pilotcalculation(ZlqbUtil.getAppid(), orderLoanVo.getOrderId());
        ResultUtil.checkAndGet(pilotcalculation);
        OrderRepaymentPlanVo haveRepayMent = pilotcalculation.getData().getBody();
        if(Objects.isNull(haveRepayMent)){
            logger.error("订单手动推时获取订单预算相关信息出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return haveRepayMent;
    }


    //获取已还款的放款订单状态信息
    private OrderRepaymentPlanVo haveRepayMentGet(OrderSimpleBo orderInfo) {
        RestResult<OrderRepaymentPlanVo> haveRepaymentResult = orderRepaymentService.orderRepaymentPlanSuccessSearch(ZlqbUtil.getAppid(), orderInfo.getOrderId());
        ResultUtil.checkAndGet(haveRepaymentResult);
        OrderRepaymentPlanVo haveRepayMent = haveRepaymentResult.getData().getBody();
        if(Objects.isNull(haveRepayMent)){
            logger.error("订单手动推时获取订单已还款订单相关信息出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return haveRepayMent;
    }

    //获取H5绑卡地址的函数
    private OrderBankH5Vo h5UrlBindCardGet(OrderLoanVo orderLoanVo) {
        OrderBankH5Dto h5Dto = new OrderBankH5Dto();
        h5Dto.setAppName(String.valueOf(ZlqbUtil.getAppName()));
        h5Dto.setSuccessReturnUrl(ZlqbUtil.getBankBindSuccessUrl());
        h5Dto.setFailReturnUrl(ZlqbUtil.getBankBindSuccessUrl());
        RestResult<OrderBankH5Vo> h5VoRestResult = orderBankCloudHystrixService.bindH5Search(ZlqbUtil.getAppid(), orderLoanVo.getOrderId(), h5Dto);
        ResultUtil.checkAndGet(h5VoRestResult);
        OrderBankH5Vo bankH5Vo = h5VoRestResult.getData().getBody();
        if (Objects.isNull(bankH5Vo)) {
            logger.error("h5UrlBindCardGet 函数下订单手动推时获取订单H5绑卡地址的  相关信息出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return bankH5Vo;
    }

    //获取银行卡相关信息
    public OrderBankVo bankInfoDtoGet(OrderLoanVo orderLoanVo) {
        RestResult<OrderBankVo> bankResult = orderBankCloudHystrixService.orderBankSearch(ZlqbUtil.getAppid(), orderLoanVo.getOrderId());
        ResultUtil.checkAndGet(bankResult);
        OrderBankVo orderBankVo = bankResult.getData().getBody();
        if (Objects.isNull(orderBankVo)) {
            logger.error("bankInfoDtoGet 函数下订单手动推时获取订单的银行卡相关信息出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return orderBankVo;
    }

    //获取订单的基础信息 订单状态 顶到orderId
    private OrderLoanVo baseOrderInfoGet(String orderNo) {
        OrderLoanVo orderLoanVo = coreOrderService.getRemoteOrder(orderNo);

        if (Objects.isNull(orderLoanVo)) {
            logger.error("baseOrderInfoGet 函数下订单手动推时获取订单的基础信息时出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return orderLoanVo;
    }

    //获取订单审核表中多个参数值
    private ZlqbOrderReview multipleParmGet(String orderNo) {
        ZlqbOrderReview dto = zlqbOrderReviewService.getOrderReviewDto(orderNo);
        if (Objects.isNull(dto)) {
            logger.error("multipleParmGet 函数下订单手动推时获取订单审批记录表中多个字段值时出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return dto;
    }

    //获取订单是否签约状态
    private OrderSimpleBo isSignGet(String orderNo) {
        OrderSimpleBo orderInfo = coreOrderService.getOrderSimpleBo(orderNo);
        if (Objects.isNull(orderInfo)) {
            logger.error("isSignGet 函数下订单手动推时获取基础订单的是否签约时出现空值");
            throw new CalfException(ZlqbResultEnum.NULL_ERROR.getMessage());
        }
        return orderInfo;
    }

    private int getIntFromBigdecimal(BigDecimal decimal){
        if(Objects.isNull(decimal)){
            return 0;
        }
       return decimal.multiply(new BigDecimal(100)).setScale(2,BigDecimal.ROUND_HALF_UP).intValue();

    }
    //第三方需要年月日 时分秒   账务系统回传给我们的  还款日期 目前是  年月日  到后期如果他们修改了 年月日 时分秒 格式 我这边需要做适配
    private String adaptationDateToZlqb(String actualrepayDate) {
        if(StringUtils.isEmpty(actualrepayDate)){
            return "1970-01-01 00:00:00";
        }
        if(actualrepayDate.trim().length()>11){
            return actualrepayDate;
        }
        return actualrepayDate+" 08:12:20";
    }

}


package com.shangyong.thorder.service;

import com.shangyong.thcore.dto.OrderLoanDto;
import com.shangyong.thcore.vo.OrderLoanVo;

/**
 * 订单服务
 * 
 * @author caijunjun
 * @date 2019年3月15日
 */
public interface OrderService {

	/**
	 * 创建订单服务
	 * 
	 * @param appid
	 * @param orderLoanDto
	 * @return
	 */
	OrderLoanVo createOrder(String appid, OrderLoanDto orderLoanDto);

	/**
	 * 取消订单
	 * 
	 * @param appid
	 * @param orderId
	 * @return
	 */
	boolean cancelOrder(String appid, String orderId);

	/**
	 * 获取订单对象
	 * 
	 * @param appid
	 * @param otherOrderId
	 * @return
	 */
	OrderLoanVo getOrderLoanVo(String appid, String otherOrderId);

	/**
	 * 进入待审核状态
	 * 
	 * @param appid
	 * @param orderId
	 * @return
	 */
	boolean toWaitAudit(String appid, String orderId);

	/**
	 * 校验是否有在途订单
	 * 
	 * @param appid
	 * @param identityNo
	 * 
	 * @return
	 */
	boolean checkOnWayOrder(String appid, String identityNo);

	/**
	 * 状态统一校验接口
	 * 
	 * @param appid
	 * @param orderId
	 * @param expectStatuses
	 * @param business 
	 */
	void checkStatus(String appid, String orderId, int expectStatuses, String business);

}

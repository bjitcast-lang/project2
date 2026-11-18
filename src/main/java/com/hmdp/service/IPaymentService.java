package com.hmdp.service;

import com.hmdp.dto.PayNotifyDTO;
import com.hmdp.dto.Result;

/**
 * 模拟支付/退款渠道：发起支付、处理回调、申请退款。
 */
public interface IPaymentService {

    /**
     * 发起支付，返回模拟渠道参数（含签名）。
     */
    Result createPay(Long orderId);

    /**
     * 支付回调：验签、校验金额、CAS 1->2，幂等。
     */
    Result handlePayNotify(PayNotifyDTO dto);

    /**
     * 申请退款：2->5，生成退款流水并返回回调参数。
     */
    Result applyRefund(Long orderId);

    /**
     * 退款回调：验签、CAS 5->6（失败则 5->2），幂等。
     */
    Result handleRefundNotify(PayNotifyDTO dto);
}

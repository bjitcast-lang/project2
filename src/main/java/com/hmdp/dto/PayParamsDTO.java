package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 发起支付/退款时返回给调用方的模拟渠道参数。
 * 调用方把同样内容回传给 notify 接口即可完成「渠道回调」。
 */
@Data
@Accessors(chain = true)
public class PayParamsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    /**
     * PAY{orderId} / REFUND{orderId}
     */
    private String tradeNo;

    /**
     * 金额，单位分
     */
    private Long amount;

    private Integer channel;

    /**
     * 毫秒时间戳
     */
    private Long timestamp;

    private String sign;
}

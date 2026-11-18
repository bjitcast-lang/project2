package com.hmdp.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟渠道回调参数。支付和退款共用，success=false 表示渠道侧失败。
 */
@Data
public class PayNotifyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tradeNo;

    private Long orderId;

    /**
     * 金额，单位分
     */
    private Long amount;

    private Integer channel;

    /**
     * 渠道侧结果：true 成功，false 失败
     */
    private Boolean success;

    private Long timestamp;

    private String sign;
}

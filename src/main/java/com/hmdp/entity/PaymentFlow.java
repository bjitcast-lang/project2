package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付/退款流水。
 * <p>
 * 一个订单的支付和退款各一条流水，由 {@code (order_id, flow_type)} 唯一索引兜底。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_payment_flow")
public class PaymentFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /**
     * 渠道流水号，例如 PAY{orderId} / REFUND{orderId}
     */
    private String tradeNo;

    /**
     * 1支付 2退款
     */
    private Integer flowType;

    /**
     * 金额，单位分
     */
    private Long amount;

    /**
     * 1余额 2支付宝 3微信
     */
    private Integer channel;

    /**
     * 0初始化 1成功 2失败
     */
    private Integer status;

    private LocalDateTime callbackTime;

    private String rawBody;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单展示对象，避免直接暴露实体中的内部字段。
 */
@Data
@Accessors(chain = true)
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long voucherId;

    private Long shopId;

    private String voucherTitle;

    /**
     * 实付金额，单位分
     */
    private Long amount;

    private Integer status;

    private String statusDesc;

    private LocalDateTime createTime;

    private LocalDateTime payTime;
}

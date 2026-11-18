package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异记录。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reconcile_diff")
public class ReconcileDiff implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    private Long orderId;

    /**
     * PAID_WITHOUT_FLOW / FLOW_WITHOUT_PAID_ORDER / AMOUNT_MISMATCH ...
     */
    private String diffType;

    private String expected;

    private String actual;

    /**
     * 0待处理 1已处理
     */
    private Integer handleStatus;

    private LocalDateTime createTime;
}

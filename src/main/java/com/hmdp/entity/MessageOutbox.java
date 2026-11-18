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
 * 本地消息表：业务数据与待发送事件在同一个事务中落库，
 * 由后台任务可靠投递，解决“业务成功但消息丢失”的问题。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_message_outbox")
public class MessageOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 事件类型：ORDER_CREATED / ORDER_CANCELLED
     */
    private String bizType;

    /**
     * 业务唯一键，例如 order-created:{orderId}
     */
    private String bizKey;

    private String payload;

    /**
     * 0待发送 1成功 2发送中
     */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

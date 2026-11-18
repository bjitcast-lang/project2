package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单消息体，写入 Redis Stream 后由消费者异步落库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    private Long userId;

    private Long voucherId;
}

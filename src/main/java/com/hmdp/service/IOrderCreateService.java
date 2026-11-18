package com.hmdp.service;

import com.hmdp.dto.SeckillMessage;

/**
 * 消费秒杀消息并落库。
 */
public interface IOrderCreateService {

    /**
     * 幂等创建订单：订单已存在直接返回 true；库存不足抛异常。
     */
    boolean createFromMessage(SeckillMessage message);
}

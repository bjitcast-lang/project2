package com.hmdp.service;

/**
 * 订单状态统一变更入口。
 * <p>
 * 所有状态变更都必须经过条件更新（CAS），禁止先查后改。
 */
public interface IOrderStateService {

    /**
     * 尝试把订单从 fromStatus 流转到 toStatus。
     *
     * @param orderId    订单 id
     * @param fromStatus 期望的当前状态
     * @param toStatus   目标状态
     * @param operator   操作来源，例如 USER / PAY_CALLBACK / TIMEOUT
     * @return true 表示 CAS 成功；false 表示当前状态已变化或流转非法
     */
    boolean change(Long orderId, int fromStatus, int toStatus, String operator);
}

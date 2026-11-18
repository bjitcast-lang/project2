package com.hmdp.service;

/**
 * 秒杀库存服务：Redis 预热、Lua 原子预扣、失败补偿。
 */
public interface ISeckillStockService {

    /**
     * 执行秒杀原子操作。
     *
     * @return 0 成功；1 库存不足；2 重复下单
     */
    long trySeckill(Long voucherId, Long userId, long orderId);

    /**
     * 预热库存。
     *
     * @param force true 时强制覆盖库存并清空一人一单集合（仅测试环境使用）
     */
    void preheat(Long voucherId, boolean force);

    /**
     * 启动时预热所有进行中的秒杀券。
     */
    void preheatActive();

    /**
     * 消费失败时回补 Redis 预扣的库存，并移除一人一单标记。
     */
    void compensate(Long voucherId, Long userId);

    /**
     * 订单取消后回补 Redis 库存（不释放一人一单名额，防止刷单）。
     * 调用方需用 reback:{orderId} 保证幂等。
     */
    void restoreStock(Long voucherId);
}

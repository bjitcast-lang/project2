package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillStockService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeckillStockServiceImpl implements ISeckillStockService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public long trySeckill(Long voucherId, Long userId, long orderId) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        // 容错：库存 key 不存在时先补一次预热（SETNX，不会覆盖已有库存）
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            preheat(voucherId, false);
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        stockKey,
                        RedisConstants.SECKILL_ORDER_KEY + voucherId,
                        RedisConstants.SECKILL_STREAM_KEY),
                userId.toString(),
                String.valueOf(orderId),
                String.valueOf(voucherId));
        return result == null ? 1L : result;
    }

    @Override
    public void preheat(Long voucherId, boolean force) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            throw new IllegalArgumentException("秒杀券不存在：" + voucherId);
        }
        int stock = voucher.getStock() == null ? 0 : voucher.getStock();
        long ttl = ttlSeconds(voucher);
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;

        if (force) {
            stringRedisTemplate.opsForValue()
                    .set(stockKey, String.valueOf(stock), ttl, TimeUnit.SECONDS);
            // 仅测试环境使用：清空一人一单集合，允许重新抢购
            stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            log.warn("强制预热库存并清空一人一单集合，voucherId={}, stock={}", voucherId, stock);
        } else {
            stringRedisTemplate.opsForValue()
                    .setIfAbsent(stockKey, String.valueOf(stock), ttl, TimeUnit.SECONDS);
            log.info("秒杀库存预热，voucherId={}, stock={}", voucherId, stock);
        }
    }

    @Override
    public void preheatActive() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillVoucher> vouchers = seckillVoucherService.query()
                .le("begin_time", now)
                .ge("end_time", now)
                .gt("stock", 0)
                .list();
        for (SeckillVoucher voucher : vouchers) {
            try {
                preheat(voucher.getVoucherId(), false);
            } catch (Exception e) {
                log.warn("秒杀库存预热失败，voucherId={}", voucher.getVoucherId(), e);
            }
        }
        log.info("启动秒杀库存预热完成，共处理 {} 张券", vouchers.size());
    }

    @Override
    public void compensate(Long voucherId, Long userId) {
        stringRedisTemplate.opsForValue()
                .increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.opsForSet()
                .remove(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString());
        log.warn("已回补 Redis 秒杀库存并移除一人一单标记，voucherId={}, userId={}",
                voucherId, userId);
    }

    @Override
    public void restoreStock(Long voucherId) {
        stringRedisTemplate.opsForValue()
                .increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        log.info("订单取消，已回补 Redis 秒杀库存，voucherId={}", voucherId);
    }

    /**
     * 库存 key 至少保留 1 小时，活动结束后再多留 1 天，便于排查与对账。
     */
    private long ttlSeconds(SeckillVoucher voucher) {
        LocalDateTime end = voucher.getEndTime() == null
                ? LocalDateTime.now().plusDays(1)
                : voucher.getEndTime();
        long seconds = Duration.between(LocalDateTime.now(), end).getSeconds();
        return Math.max(seconds + 86400, 3600);
    }
}

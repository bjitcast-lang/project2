package com.hmdp.job;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;

/**
 * 秒级超时关单：从 ZSet 取到期订单，ZREM 成功者才处理（多实例防重）。
 */
@Slf4j
@Component
public class OrderTimeoutJob {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelay = 5000L)
    public void closeTimeoutOrders() {
        long now = System.currentTimeMillis();
        Set<String> orderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                RedisConstants.DELAY_ORDER_KEY, 0, now, 0, 100);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String orderId : orderIds) {
            Long removed = stringRedisTemplate.opsForZSet()
                    .remove(RedisConstants.DELAY_ORDER_KEY, orderId);
            if (removed == null || removed == 0) {
                continue;
            }
            try {
                boolean cancelled = voucherOrderService.cancelBySystem(
                        Long.valueOf(orderId), "TIMEOUT");
                log.info("超时关单处理完成，orderId={}, cancelled={}", orderId, cancelled);
            } catch (Exception e) {
                // 失败不重新入队，由 OrderTimeoutScanJob 扫表兜底
                log.error("超时关单失败，orderId={}", orderId, e);
            }
        }
    }
}

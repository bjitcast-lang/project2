package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.entity.MessageOutbox;
import com.hmdp.mapper.MessageOutboxMapper;
import com.hmdp.service.ISeckillStockService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表投递器。
 * <p>
 * 业务事务内写 outbox，这里异步投递到下游（延迟队列 / Redis 库存回补）。
 * 通过 {@code UPDATE ... WHERE status=0} 抢占，多实例安全；
 * 失败按 1s/5s/30s 退避重试，下游用业务键幂等。
 */
@Slf4j
@Component
public class OutboxDispatcher {

    private static final long ORDER_TIMEOUT_MILLIS = 15 * 60 * 1000L;
    private static final long[] BACKOFF_SECONDS = {1L, 5L, 30L};

    @Resource
    private MessageOutboxMapper outboxMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillStockService seckillStockService;

    @Scheduled(fixedDelay = 1000L)
    public void dispatch() {
        List<MessageOutbox> events = outboxMapper.selectList(
                Wrappers.<MessageOutbox>lambdaQuery()
                        .eq(MessageOutbox::getStatus, 0)
                        .le(MessageOutbox::getNextTime, LocalDateTime.now())
                        .orderByAsc(MessageOutbox::getId)
                        .last("limit 50"));
        for (MessageOutbox event : events) {
            dispatchOne(event);
        }
    }

    /**
     * 发送中状态超过 1 分钟的记录重置为待发送，防止实例宕机后消息卡死。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void resetStuckEvents() {
        int count = outboxMapper.update(null, Wrappers.<MessageOutbox>lambdaUpdate()
                .eq(MessageOutbox::getStatus, 2)
                .lt(MessageOutbox::getUpdateTime, LocalDateTime.now().minusMinutes(1))
                .set(MessageOutbox::getStatus, 0)
                .set(MessageOutbox::getNextTime, LocalDateTime.now()));
        if (count > 0) {
            log.warn("重置 {} 条卡在发送中的 outbox 事件", count);
        }
    }

    private void dispatchOne(MessageOutbox event) {
        // 抢占：只有把 status 从 0 改成 2 成功的实例才负责投递
        int locked = outboxMapper.update(null, Wrappers.<MessageOutbox>lambdaUpdate()
                .eq(MessageOutbox::getId, event.getId())
                .eq(MessageOutbox::getStatus, 0)
                .set(MessageOutbox::getStatus, 2)
                .set(MessageOutbox::getUpdateTime, LocalDateTime.now()));
        if (locked == 0) {
            return;
        }

        try {
            handle(event);
            outboxMapper.update(null, Wrappers.<MessageOutbox>lambdaUpdate()
                    .eq(MessageOutbox::getId, event.getId())
                    .set(MessageOutbox::getStatus, 1)
                    .set(MessageOutbox::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            int retry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            long backoff = BACKOFF_SECONDS[Math.min(retry - 1, BACKOFF_SECONDS.length - 1)];
            outboxMapper.update(null, Wrappers.<MessageOutbox>lambdaUpdate()
                    .eq(MessageOutbox::getId, event.getId())
                    .set(MessageOutbox::getStatus, 0)
                    .set(MessageOutbox::getRetryCount, retry)
                    .set(MessageOutbox::getNextTime, LocalDateTime.now().plusSeconds(backoff))
                    .set(MessageOutbox::getUpdateTime, LocalDateTime.now()));
            log.error("outbox 投递失败，bizKey={}, retry={}", event.getBizKey(), retry, e);
        }
    }

    private void handle(MessageOutbox event) {
        SeckillMessage message = JSONUtil.toBean(event.getPayload(), SeckillMessage.class);
        if ("ORDER_CREATED".equals(event.getBizType())) {
            // 订单创建 -> 15 分钟后进入超时关单扫描
            double score = System.currentTimeMillis() + ORDER_TIMEOUT_MILLIS;
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.DELAY_ORDER_KEY, String.valueOf(message.getOrderId()), score);
        } else if ("ORDER_CANCELLED".equals(event.getBizType())) {
            // 订单取消 -> 幂等回补 Redis 库存，不释放一人一单名额
            String rebackKey = RedisConstants.SECKILL_REBACK_KEY + message.getOrderId();
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(rebackKey, "1", 7, TimeUnit.DAYS);
            if (Boolean.TRUE.equals(first)) {
                seckillStockService.restoreStock(message.getVoucherId());
            }
        } else {
            log.warn("未知 outbox 事件类型，bizType={}, bizKey={}",
                    event.getBizType(), event.getBizKey());
        }
    }
}

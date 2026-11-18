package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.exception.SeckillStockException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderCreateService;
import com.hmdp.service.ISeckillStockService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀消息处理：消费者线程和 pending 恢复任务共用同一套逻辑。
 */
@Slf4j
@Component
public class SeckillMessageHandler {

    private static final int MAX_RETRY = 3;

    @Resource
    private IOrderCreateService orderCreateService;

    @Resource
    private ISeckillStockService seckillStockService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void handle(MapRecord<String, Object, Object> record) {
        handle(record.getId(), record.getValue());
    }

    /**
     * 处理一条消息。value 允许为任意键值类型，内部统一用 String.valueOf 读取字段。
     */
    public void handle(RecordId recordId, Map<?, ?> value) {
        MDC.put("traceId", "mq-" + recordId);
        try {
            doHandle(recordId, value);
        } finally {
            MDC.remove("traceId");
        }
    }

    private void doHandle(RecordId recordId, Map<?, ?> value) {
        SeckillMessage message;
        try {
            message = parse(value);
        } catch (Exception e) {
            log.error("秒杀消息解析失败，recordId={}", recordId, e);
            sendRawToDlq(recordId, value, e);
            ack(recordId);
            return;
        }

        try {
            orderCreateService.createFromMessage(message);
            ack(recordId);
        } catch (SeckillStockException e) {
            // DB 库存不足：Redis 预扣了但 DB 无法落单，必须回补
            log.error("DB库存不足，回补 Redis 预扣，orderId={}", message.getOrderId(), e);
            seckillStockService.compensate(message.getVoucherId(), message.getUserId());
            ack(recordId);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：订单已存在，重复消息直接确认
            log.info("唯一索引拦截重复消息，orderId={}", message.getOrderId());
            ack(recordId);
        } catch (Exception e) {
            long retry = incrementRetry(message.getOrderId());
            if (retry >= MAX_RETRY) {
                log.error("秒杀消息处理失败超过 {} 次，进入死信，orderId={}",
                        MAX_RETRY, message.getOrderId(), e);
                if (voucherOrderMapper.selectById(message.getOrderId()) == null) {
                    seckillStockService.compensate(message.getVoucherId(), message.getUserId());
                }
                sendToDlq(message, e);
                ack(recordId);
            } else {
                // 不 ACK，交给 StreamRecoveryJob 用 XCLAIM 重投
                log.warn("秒杀消息处理失败，等待重投，orderId={}, retry={}",
                        message.getOrderId(), retry, e);
            }
        }
    }

    private SeckillMessage parse(Map<?, ?> value) {
        return new SeckillMessage(
                Long.valueOf(String.valueOf(value.get("orderId"))),
                Long.valueOf(String.valueOf(value.get("userId"))),
                Long.valueOf(String.valueOf(value.get("voucherId"))));
    }

    private void ack(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(
                RedisConstants.SECKILL_STREAM_KEY,
                RedisConstants.SECKILL_GROUP,
                recordId);
    }

    private long incrementRetry(Long orderId) {
        String key = RedisConstants.SECKILL_RETRY_KEY + orderId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
        }
        return count == null ? 1L : count;
    }

    private void sendToDlq(SeckillMessage message, Exception e) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("orderId", String.valueOf(message.getOrderId()));
        data.put("userId", String.valueOf(message.getUserId()));
        data.put("voucherId", String.valueOf(message.getVoucherId()));
        data.put("error", errorMessage(e));
        data.put("payload", JSONUtil.toJsonStr(message));
        try {
            stringRedisTemplate.opsForStream().add(RedisConstants.SECKILL_DLQ_KEY, data);
        } catch (Exception ex) {
            log.error("写入秒杀死信失败，orderId={}", message.getOrderId(), ex);
        }
    }

    private void sendRawToDlq(RecordId recordId, Map<?, ?> value, Exception e) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("recordId", String.valueOf(recordId));
        data.put("raw", String.valueOf(value));
        data.put("error", errorMessage(e));
        try {
            stringRedisTemplate.opsForStream().add(RedisConstants.SECKILL_DLQ_KEY, data);
        } catch (Exception ex) {
            log.error("写入秒杀死信失败，recordId={}", recordId, ex);
        }
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}

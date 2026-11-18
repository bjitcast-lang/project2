package com.hmdp.mq;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 每 60 秒检查一次 pending 消息，把空闲超过 60 秒的消息 XCLAIM 回来重投。
 * <p>
 * Spring Data Redis 2.3 的 StreamOperations 没有封装 XCLAIM，
 * 这里直接通过 RedisCallback 调用底层 RedisStreamCommands。
 */
@Slf4j
@Component
public class StreamRecoveryJob {

    private static final long MIN_IDLE_SECONDS = 60L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillMessageHandler messageHandler;

    private final String recoveryConsumer = "recovery-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Scheduled(fixedDelay = 60_000L)
    public void recover() {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_GROUP,
                    Range.unbounded(),
                    100L);
            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<RecordId> ids = pending.stream()
                    .filter(message -> message.getElapsedTimeSinceLastDelivery().getSeconds()
                            >= MIN_IDLE_SECONDS)
                    .map(PendingMessage::getId)
                    .collect(Collectors.toList());
            if (ids.isEmpty()) {
                return;
            }

            byte[] streamKey = RedisConstants.SECKILL_STREAM_KEY
                    .getBytes(StandardCharsets.UTF_8);
            List<ByteRecord> claimed = stringRedisTemplate.execute(
                    (RedisCallback<List<ByteRecord>>) connection -> connection.xClaim(
                            streamKey,
                            RedisConstants.SECKILL_GROUP,
                            recoveryConsumer,
                            Duration.ofSeconds(MIN_IDLE_SECONDS),
                            ids.toArray(new RecordId[0])));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }

            log.info("恢复 {} 条超时未确认的秒杀消息", claimed.size());
            for (ByteRecord byteRecord : claimed) {
                MapRecord<String, String, String> record =
                        byteRecord.deserialize(RedisSerializer.string());
                messageHandler.handle(record.getId(), record.getValue());
            }
        } catch (Exception e) {
            log.error("秒杀 pending 消息恢复失败", e);
        }
    }
}

package com.hmdp.mq;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 秒杀消息消费者：独立守护线程阻塞读取 Redis Stream 消费组。
 */
@Slf4j
@Component
public class SeckillStreamConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillMessageHandler messageHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean groupReady = false;

    private Thread worker;

    private String consumerName;

    @PostConstruct
    public void start() {
        consumerName = "consumer-" + hostName() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        running.set(true);
        worker = new Thread(this::loop, "seckill-stream-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("秒杀消费者已启动，consumerName={}", consumerName);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        log.info("秒杀消费者已停止，consumerName={}", consumerName);
    }

    private void loop() {
        while (running.get()) {
            try {
                ensureGroup();
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(RedisConstants.SECKILL_GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(RedisConstants.SECKILL_STREAM_KEY,
                                ReadOffset.lastConsumed()));
                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        messageHandler.handle(record);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.error("读取秒杀消息失败，1 秒后重试", e);
                sleepQuietly();
            }
        }
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            // 使用 MKSTREAM=true：Stream 不存在时一并创建消费组
            stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.xGroupCreate(
                    RedisConstants.SECKILL_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                    RedisConstants.SECKILL_GROUP,
                    ReadOffset.from("0"),
                    true));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw e;
            }
        }
        groupReady = true;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

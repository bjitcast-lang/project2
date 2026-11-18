package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 的全局唯一 ID 生成器（黑马点评课程）。
 * <p>
 * ID 结构（64 位 long）：
 * <pre>
 *   0 | 0000000...0000000 | 000000...0000000
 *   符号位(1) | 时间戳(31)   | 序列号(32)
 * </pre>
 * <ul>
 *     <li>符号位恒为 0，保证 ID 为正数</li>
 *     <li>31 位时间戳：从 BEGIN_TIMESTAMP 起算，约可用 68 年</li>
 *     <li>32 位序列号：同一天内按 Redis 自增，约 43 亿/天，跨天自动归零</li>
 * </ul>
 * 相比数据库自增 / UUID 的优势：全局唯一、趋势递增（利于索引）、
 * 无规律（不暴露订单量）、性能高（纯内存自增）。
 */
@Component
public class RedisIdWorker {

    /**
     * 起始时间戳：2022-01-01 00:00:00（UTC）
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号占用的位数
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个 ID
     *
     * @param keyPrefix 业务前缀，例如订单用 "order"，最终 Redis key 为 icr:order:2026:10:21
     * @return 全局唯一 ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳（当前秒数 - 起始时间戳，仅用 31 位）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号：按天分隔 key，让 Redis 自增每天自动从 0 重新开始
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接：时间戳左移 32 位，低位补序列号
        return timestamp << COUNT_BITS | count;
    }
}

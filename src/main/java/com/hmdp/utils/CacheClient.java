package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * Redis 缓存工具类（黑马点评课程封装）。
 * <p>
 * 提供三种缓存查询方案，按业务场景选择其中一种：
 * <ul>
 *     <li>{@link #queryWithPassThrough}：缓存穿透</li>
 *     <li>{@link #queryWithMutex}：互斥锁解决缓存击穿</li>
 *     <li>{@link #queryWithLogicalExpire}：逻辑过期解决缓存击穿（需提前预热缓存）</li>
 * </ul>
 *
 * @author hmdp
 */
@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 缓存重建线程池，避免缓存过期瞬间大量线程同时查数据库
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 互斥锁前缀，最终 key 形如 lock:cache:shop:1
     */
    private static final String LOCK_KEY_PREFIX = "lock:";

    /**
     * 互斥锁默认有效期（秒）
     */
    private static final Long LOCK_TTL = 10L;

    /**
     * 写入缓存（带 TTL）
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入缓存（逻辑过期，Redis 中不设置 TTL）
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透方案：缓存空值
     *
     * @param keyPrefix Redis key 前缀，例如 cache:shop:
     * @param id        业务 id
     * @param type      返回值类型
     * @param dbFallback 数据库查询逻辑，例如 this::getById
     * @param time      TTL 数值
     * @param unit      TTL 单位
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中真实数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中空值标记（""），说明数据库里也没有，直接返回
        if (json != null) {
            return null;
        }

        // 4. 未命中，查询数据库
        R r = dbFallback.apply(id);

        // 5. 数据库也没有，写入空值标记，防止缓存穿透
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 写回缓存
        set(key, r, time, unit);
        return r;
    }

    /**
     * 缓存击穿方案：互斥锁
     * <p>
     * 只有抢到锁的线程去查数据库并重建缓存，其余线程等待后重试。
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中真实数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中空值标记，直接返回
        if (json != null) {
            return null;
        }

        // 4. 未命中，尝试获取互斥锁
        String lockKey = LOCK_KEY_PREFIX + key;
        if (!tryLock(lockKey)) {
            // 没抢到锁：等待 50ms 后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
        }

        // 5. 抢到锁，只有这时才需要释放
        try {
            // 双重检查：等锁期间可能已经有别的线程重建了缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            // 6. 查询数据库
            R r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 7. 写回缓存
            set(key, r, time, unit);
            return r;
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * 缓存击穿方案：逻辑过期
     * <p>
     * Redis 中不设置 TTL，过期时间写在 value 里。命中过期数据时，
     * 抢到锁的线程异步重建缓存，其余线程先返回旧数据。
     * <p>
     * 注意：使用前必须先调用 {@link #setWithLogicalExpire} 预热缓存。
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 未命中直接返回（逻辑过期方案依赖预热，不做数据库兜底）
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3. 反序列化出数据和逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 未过期，直接返回
        if (expireTime == null || expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 5. 已过期，尝试获取互斥锁
        String lockKey = LOCK_KEY_PREFIX + key;
        if (tryLock(lockKey)) {
            // 6. 抢到锁，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    if (newR != null) {
                        setWithLogicalExpire(key, newR, time, unit);
                    }
                } catch (Exception e) {
                    log.error("缓存重建失败，key={}", key, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 7. 无论是否抢到锁，先返回旧数据
        return r;
    }

    /**
     * 获取互斥锁
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式锁。
 * <p>
 * 锁的 value 记录持有者的标识：{@code UUID前缀 + 线程id}。
 * 释放锁时先比对 value，只有仍然是自己持有的锁才删除，
 * 避免误删其他线程/其他 JVM 重新获取到的锁。
 */
public class SimpleRedisLock implements ILock {

    /**
     * 锁 key 前缀，最终 key 形如 lock:order:1:10
     */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 每个 JVM 一个 UUID 前缀，防止不同实例的线程 id 冲突
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private final String name;

    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识：UUID前缀 + 线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // SET key value NX EX timeout
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        // 1. 获取当前线程的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 2. 获取锁中保存的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 3. 判断是否一致，是自己的锁才删除
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}

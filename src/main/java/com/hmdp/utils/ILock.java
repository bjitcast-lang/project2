package com.hmdp.utils;

/**
 * 分布式锁接口
 */
public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的超时时间（秒），超时自动释放，避免死锁
     * @return true 表示获取成功
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}

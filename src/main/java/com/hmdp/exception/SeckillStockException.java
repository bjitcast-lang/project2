package com.hmdp.exception;

/**
 * DB 库存不足，消费端需要回补 Redis 预扣的库存。
 */
public class SeckillStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeckillStockException(String message) {
        super(message);
    }
}

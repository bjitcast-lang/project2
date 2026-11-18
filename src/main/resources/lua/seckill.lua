-- 秒杀原子脚本：库存判断 + 一人一单 + 扣减 + 写入 Stream
-- KEYS[1] = 库存 key       seckill:stock:{voucherId}
-- KEYS[2] = 一人一单 set   seckill:order:{voucherId}
-- KEYS[3] = Stream key     seckill:stream
-- ARGV[1] = userId
-- ARGV[2] = orderId
-- ARGV[3] = voucherId
-- 返回值：0 成功；1 库存不足；2 重复下单
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    return 1
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('XADD', KEYS[3], 'MAXLEN', '~', '100000', '*',
        'orderId', ARGV[2],
        'userId', ARGV[1],
        'voucherId', ARGV[3])
return 0

# 秒杀压测说明

## 1. 准备

```bash
# 1) 生成 500 个压测用户 token（写入 tokens.csv）
cd D:/Java-code/hm-dianping/jmeter
bash gen-tokens.sh 500 http://127.0.0.1:8081

# 2) 准备一张进行中的秒杀券，并强制预热 Redis 库存
curl -X POST "http://127.0.0.1:8081/admin/seckill/preheat/1?force=true" \
  -H "X-Admin-Token: hmdp-admin-token"
```

## 2. 运行

```bash
jmeter -n -t seckill.jmx -l result.jtl -e -o report
```

默认参数：500 线程、ramp-up 10s、循环 5 次。可在 `seckill.jmx` 的「用户定义变量」中修改
`host / port / voucherId / threads / loops`。

## 3. 结果校验

压测结束后等待 10 秒（等消费者把 Stream 消费完），执行 SQL：

```sql
-- 订单总数（预期 = 库存消耗数，不超过 Redis 初始库存）
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = 1;

-- 重复下单（预期 0 行）
SELECT user_id, voucher_id, COUNT(*) c
FROM tb_voucher_order
WHERE voucher_id = 1
GROUP BY user_id, voucher_id HAVING c > 1;

-- DB 库存（预期 = 初始库存 - 订单数）
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 1;
```

再调用 `GET /admin/seckill/stats?voucherId=1` 查看 Redis 库存、Stream 长度、pending、DLQ，
预期 `pending = 0`、`dlq = 0`，且 `dbStock + orderCount = 初始库存`。

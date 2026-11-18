# 本地生活交易平台

基于 Spring Boot + Redis 的**本地生活交易平台**：在店铺 / 优惠券 / 缓存 / 一人一单的基础上，
实现了完整的交易链路 —— 秒杀异步下单、订单状态机、支付 / 退款、超时关单、可靠消息、对账。

## 技术栈

Java 8 · Spring Boot 2.3.12 · MyBatis-Plus 3.4.3 · Spring Data Redis 2.3.9 · MySQL 8 · Redis Stream

## 模块与核心链路

```
请求 → 校验券/时间 → RedisIdWorker 预生成订单号
     → Lua 原子脚本（库存判断 + 一人一单 + DECR + XADD）
     → 返回 orderId（受理成功）
     → Redis Stream 消费组 → 事务（插入订单 + 扣 DB 库存 + 写 outbox）
     → outbox 投递 ZSet 延迟队列 → 15 分钟未支付自动关单
     → 支付回调 → 状态机 1→2 → 对账任务核对
```

| 能力 | 实现 |
|---|---|
| 秒杀预扣 | Lua 原子脚本，`0成功 / 1库存不足 / 2重复下单` |
| 异步落库 | Redis Stream + 消费组 + pending 恢复 + DLQ |
| 一人一单 | Redis Set（预扣）+ DB 唯一索引 `uk_user_voucher`（兜底） |
| 订单状态机 | `OrderStateMachine` + 条件 UPDATE（禁止先查后改） |
| 支付 / 退款 | HMAC-SHA256 模拟签名回调，幂等，CAS 竞争 |
| 超时关单 | ZSet 秒级扫描 + 5 分钟扫表兜底 |
| 可靠消息 | `tb_message_outbox` 本地消息表 + 抢占投递 + 退避重试 |
| 对账 | 每日扫描订单与流水，差异写入 `tb_reconcile_diff` |
| 观测 | TraceIdFilter（`%X{traceId}`）+ `/admin/seckill/stats` |

## 数据库

```bash
# 0. 先查历史重复（有结果则先清理，否则唯一索引建失败）
mysql -uroot -p123456 -h127.0.0.1 hmdp -e \
  "SELECT user_id, voucher_id, COUNT(*) c FROM tb_voucher_order GROUP BY user_id, voucher_id HAVING c > 1;"

# 1. 执行迁移
mysql -uroot -p123456 -h127.0.0.1 hmdp < src/main/resources/db/migration_v1.sql
```

新增表：`tb_payment_flow`、`tb_message_outbox`、`tb_order_status_log`、`tb_reconcile_diff`。

## 编译 & 启动

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

> 依赖 Redis 与 MySQL。连接信息见 `src/main/resources/application.yaml`，
> 账号密码、Redis 地址请改成自己的环境；生产环境请放到环境变量 / 配置中心。

## 接口清单

`Result` 统一包装：`{success, errorMsg, data, total}`。除标注外都需要登录（`authorization` 头放 token）。

### 秒杀与订单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/voucher-order/seckill/{id}` | 秒杀下单，`data=orderId`，语义「受理成功」 |
| GET | `/voucher-order/{id}` | 订单详情（校验归属） |
| GET | `/voucher-order/list?status=&current=` | 我的订单分页 |
| POST | `/voucher-order/{id}/cancel` | 用户取消（1→4） |
| POST | `/voucher-order/{id}/use` | 核销（2→3） |

### 支付与退款

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/voucher-order/{id}/pay` | 发起支付，返回含 `sign` 的渠道参数 |
| POST | `/voucher-order/pay/notify` | 支付回调（**免登录**，验签） |
| POST | `/voucher-order/{id}/refund` | 申请退款（2→5） |
| POST | `/voucher-order/refund/notify` | 退款回调（**免登录**，验签） |

### 管理端（`X-Admin-Token: hmdp-admin-token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/seckill/preheat/{voucherId}?force=` | 预热 Redis 库存 |
| GET | `/admin/seckill/stats?voucherId=` | 库存 / Stream / pending / DLQ 观测 |
| GET | `/admin/reconcile/diff?date=` | 查对账差异 |
| POST | `/admin/reconcile/run?date=` | 手动对账 |
| POST | `/admin/test/token?userId=` | 压测用签发 token |

## 验收步骤（curl / Apifox）

```bash
BASE=http://127.0.0.1:8081

# 1. 造一张进行中的秒杀券后预热（假设 voucherId=1）
curl -X POST "$BASE/admin/seckill/preheat/1?force=true" -H "X-Admin-Token: hmdp-admin-token"

# 2. 用 token 秒杀
curl -X POST "$BASE/voucher-order/seckill/1" -H "authorization: <你的token>"

# 3. 查订单 → status=1
curl "$BASE/voucher-order/<orderId>" -H "authorization: <你的token>"

# 4. 发起支付 → 拿到 {orderId,tradeNo,amount,channel,timestamp,sign}
curl -X POST "$BASE/voucher-order/<orderId>/pay" -H "authorization: <你的token>"

# 5. 支付回调（把上一步返回的参数原样放回，success=true）
curl -X POST "$BASE/voucher-order/pay/notify" -H "Content-Type: application/json" \
  -d '{"orderId":..,"tradeNo":"..","amount":..,"channel":1,"success":true,"timestamp":..,"sign":".."}'

# 6. 重复回调仍成功且只有一条成功流水（幂等）

# 7. 对账
curl -X POST "$BASE/admin/reconcile/run?date=2026-11-18" -H "X-Admin-Token: hmdp-admin-token"
curl "$BASE/admin/reconcile/diff?date=2026-11-18" -H "X-Admin-Token: hmdp-admin-token"
```

对账差异类型：`PAID_WITHOUT_FLOW` / `FLOW_WITHOUT_PAID_ORDER` / `AMOUNT_MISMATCH` /
`CANCELLED_WITH_PAYMENT` / `REFUNDED_WITHOUT_FLOW`。演示时可手动删一条流水或改一条状态再触发对账。

## 压测

```bash
cd jmeter
bash gen-tokens.sh 500 http://127.0.0.1:8081   # 生成 tokens.csv
jmeter -n -t seckill.jmx -l result.jtl -e -o report
```

校验：`GET /admin/seckill/stats?voucherId=1`，预期 `pending=0`、`dlq=0`，且 `dbStock + orderCount = 初始库存`，无重复下单。

## 关键文件

- 状态机：`utils/OrderStateMachine.java`、`utils/OrderStatus.java`
- 秒杀：`lua/seckill.lua`、`mq/SeckillStreamConsumer.java`、`mq/StreamRecoveryJob.java`、`mq/SeckillMessageHandler.java`
- 可靠消息：`mq/OutboxDispatcher.java`
- 支付：`service/impl/PaymentServiceImpl.java`、`utils/PaySignUtil.java`
- 关单：`job/OrderTimeoutJob.java`、`job/OrderTimeoutScanJob.java`
- 对账：`service/impl/ReconcileServiceImpl.java`、`job/ReconcileJob.java`
- 观测：`utils/TraceIdFilter.java`、`controller/AdminController.java`

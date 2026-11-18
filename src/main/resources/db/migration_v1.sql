-- ============================================================
-- 本地生活交易平台 v1 迁移脚本
-- 执行前请先备份 hmdp 数据库
-- ============================================================

-- 0. 执行前检查是否存在重复的 (user_id, voucher_id)
--    如果有结果，先清理重复数据，否则唯一索引会创建失败
-- SELECT user_id, voucher_id, COUNT(*) c
-- FROM tb_voucher_order GROUP BY user_id, voucher_id HAVING c > 1;

-- 1. 订单表补唯一索引与查询索引
ALTER TABLE tb_voucher_order
  ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id),
  ADD KEY idx_status_create (status, create_time),
  ADD KEY idx_voucher (voucher_id);

-- 2. 支付/退款流水（一个订单每种类型各一条）
CREATE TABLE tb_payment_flow (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  trade_no VARCHAR(64) NOT NULL,
  flow_type TINYINT NOT NULL COMMENT '1支付 2退款',
  amount BIGINT NOT NULL COMMENT '单位分',
  channel TINYINT NOT NULL DEFAULT 1 COMMENT '1余额 2支付宝 3微信',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0初始化 1成功 2失败',
  callback_time DATETIME NULL,
  raw_body TEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_trade_no (trade_no),
  UNIQUE KEY uk_order_type (order_id, flow_type),
  KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付/退款流水';

-- 3. 本地消息表（可靠事件）
CREATE TABLE tb_message_outbox (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  biz_type VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATED / ORDER_CANCELLED',
  biz_key VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待发送 1成功 2发送中',
  retry_count INT NOT NULL DEFAULT 0,
  next_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_biz_key (biz_key),
  KEY idx_status_next (status, next_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表';

-- 4. 订单状态流水
CREATE TABLE tb_order_status_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  from_status TINYINT NOT NULL,
  to_status TINYINT NOT NULL,
  operator VARCHAR(32) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单状态流水';

-- 5. 对账差异
CREATE TABLE tb_reconcile_diff (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  biz_date DATE NOT NULL,
  order_id BIGINT NOT NULL,
  diff_type VARCHAR(32) NOT NULL,
  expected VARCHAR(128) NULL,
  actual VARCHAR(128) NULL,
  handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_date_order_type (biz_date, order_id, diff_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账差异';

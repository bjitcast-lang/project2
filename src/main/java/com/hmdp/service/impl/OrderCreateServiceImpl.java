package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.SeckillStockException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderCreateService;
import com.hmdp.service.IOutboxService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 秒杀消息落库：订单 + DB 库存 + outbox 在同一个事务里完成。
 */
@Slf4j
@Service
public class OrderCreateServiceImpl implements IOrderCreateService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IOutboxService outboxService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createFromMessage(SeckillMessage message) {
        // 1. 订单已存在：重复消息，直接视为成功
        if (voucherOrderMapper.selectById(message.getOrderId()) != null) {
            log.info("秒杀消息重复，订单已存在，orderId={}", message.getOrderId());
            return true;
        }

        // 2. 插入订单。唯一索引 uk_user_voucher 兜底一人一单
        LocalDateTime now = LocalDateTime.now();
        VoucherOrder order = new VoucherOrder()
                .setId(message.getOrderId())
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId())
                .setPayType(1)
                .setStatus(OrderStatus.UNPAID.getCode())
                .setCreateTime(now)
                .setUpdateTime(now);
        try {
            voucherOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 同一用户对同一张券已有订单（不同 orderId 的重复消息）
            log.info("唯一索引拦截重复下单，userId={}, voucherId={}",
                    message.getUserId(), message.getVoucherId());
            return true;
        }

        // 3. 扣减 DB 库存，DB 是最终真相源
        boolean deducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!deducted) {
            throw new SeckillStockException("DB库存不足，voucherId=" + message.getVoucherId());
        }

        // 4. 同事务写订单创建事件，后续由 outbox 投递延迟关单
        outboxService.saveEvent(
                "ORDER_CREATED",
                "order-created:" + message.getOrderId(),
                JSONUtil.toJsonStr(message));
        return true;
    }
}

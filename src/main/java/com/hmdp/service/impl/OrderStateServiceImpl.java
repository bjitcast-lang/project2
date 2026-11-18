package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.entity.OrderStatusLog;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderStatusLogMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderStateService;
import com.hmdp.utils.OrderStateMachine;
import com.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 订单状态机落地实现。
 * <p>
 * 关键点：使用 {@code WHERE id = ? AND status = ?} 的条件更新，
 * 影响行数为 0 即视为并发失败，避免“先查后改”的竞态。
 */
@Slf4j
@Service
public class OrderStateServiceImpl implements IOrderStateService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean change(Long orderId, int fromStatus, int toStatus, String operator) {
        if (!OrderStateMachine.canTransit(fromStatus, toStatus)) {
            log.warn("非法订单状态流转，orderId={}, {} -> {}", orderId, fromStatus, toStatus);
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<VoucherOrder> wrapper = Wrappers.<VoucherOrder>lambdaUpdate()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, fromStatus)
                .set(VoucherOrder::getStatus, toStatus)
                .set(VoucherOrder::getUpdateTime, now);

        // 按目标状态写入对应的时间字段
        if (fromStatus == OrderStatus.UNPAID.getCode()
                && toStatus == OrderStatus.PAID.getCode()) {
            wrapper.set(VoucherOrder::getPayTime, now);
        } else if (toStatus == OrderStatus.USED.getCode()) {
            wrapper.set(VoucherOrder::getUseTime, now);
        } else if (toStatus == OrderStatus.REFUNDED.getCode()) {
            wrapper.set(VoucherOrder::getRefundTime, now);
        }

        int rows = voucherOrderMapper.update(null, wrapper);
        if (rows == 0) {
            log.info("订单状态 CAS 失败（并发或状态已变化），orderId={}, {} -> {}",
                    orderId, fromStatus, toStatus);
            return false;
        }

        OrderStatusLog statusLog = new OrderStatusLog()
                .setOrderId(orderId)
                .setFromStatus(fromStatus)
                .setToStatus(toStatus)
                .setOperator(operator)
                .setCreateTime(now);
        orderStatusLogMapper.insert(statusLog);
        return true;
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.entity.PaymentFlow;
import com.hmdp.entity.ReconcileDiff;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.PaymentFlowMapper;
import com.hmdp.mapper.ReconcileDiffMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IReconcileService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账实现：每日扫描当天创建的订单，与成功流水互相核对。
 * <p>
 * 差异表以 (biz_date, order_id, diff_type) 唯一，重复执行只更新 expected/actual。
 */
@Slf4j
@Service
public class ReconcileServiceImpl implements IReconcileService {

    private static final int FLOW_PAY = 1;
    private static final int FLOW_REFUND = 2;
    private static final int FLOW_SUCCESS = 1;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private PaymentFlowMapper paymentFlowMapper;

    @Resource
    private ReconcileDiffMapper reconcileDiffMapper;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public int reconcile(String bizDate) {
        LocalDate date;
        try {
            date = LocalDate.parse(bizDate);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }

        SimpleRedisLock lock = new SimpleRedisLock("reconcile:" + bizDate, stringRedisTemplate);
        if (!lock.tryLock(3600)) {
            log.warn("对账任务已在执行，date={}", bizDate);
            return -1;
        }
        try {
            doReconcile(date);
            Integer count = reconcileDiffMapper.selectCount(Wrappers.<ReconcileDiff>lambdaQuery()
                    .eq(ReconcileDiff::getBizDate, date));
            log.info("对账完成，date={}, diffCount={}", bizDate, count);
            return count == null ? 0 : count;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ReconcileDiff> listDiff(String bizDate) {
        LocalDate date;
        try {
            date = LocalDate.parse(bizDate);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
        return reconcileDiffMapper.selectList(Wrappers.<ReconcileDiff>lambdaQuery()
                .eq(ReconcileDiff::getBizDate, date)
                .orderByAsc(ReconcileDiff::getId));
    }

    private void doReconcile(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                Wrappers.<VoucherOrder>lambdaQuery()
                        .ge(VoucherOrder::getCreateTime, start)
                        .lt(VoucherOrder::getCreateTime, end));
        Set<Long> orderIds = orders.stream()
                .map(VoucherOrder::getId)
                .collect(Collectors.toSet());

        Map<Long, PaymentFlow> payFlows = loadFlows(orderIds, FLOW_PAY);
        Map<Long, PaymentFlow> refundFlows = loadFlows(orderIds, FLOW_REFUND);
        Map<Long, Voucher> vouchers = loadVouchers(orders);

        for (VoucherOrder order : orders) {
            checkOrder(date, order, payFlows.get(order.getId()),
                    refundFlows.get(order.getId()), vouchers.get(order.getVoucherId()));
        }

        // 当天产生的成功支付流水，反向核对订单状态
        List<PaymentFlow> dayPayFlows = paymentFlowMapper.selectList(
                Wrappers.<PaymentFlow>lambdaQuery()
                        .eq(PaymentFlow::getFlowType, FLOW_PAY)
                        .eq(PaymentFlow::getStatus, FLOW_SUCCESS)
                        .ge(PaymentFlow::getCreateTime, start)
                        .lt(PaymentFlow::getCreateTime, end));
        for (PaymentFlow flow : dayPayFlows) {
            if (orderIds.contains(flow.getOrderId())) {
                continue;
            }
            VoucherOrder order = voucherOrderMapper.selectById(flow.getOrderId());
            if (order == null || !paidLikeStatus(order.getStatus())) {
                saveDiff(date, flow.getOrderId(), "FLOW_WITHOUT_PAID_ORDER",
                        "PAID_ORDER", "ORDER_STATUS=" + (order == null ? "MISSING" : order.getStatus()));
            }
        }
    }

    private void checkOrder(LocalDate date, VoucherOrder order, PaymentFlow payFlow,
                            PaymentFlow refundFlow, Voucher voucher) {
        Integer status = order.getStatus();

        if ((Objects.equals(status, OrderStatus.PAID.getCode())
                || Objects.equals(status, OrderStatus.USED.getCode())) && payFlow == null) {
            saveDiff(date, order.getId(), "PAID_WITHOUT_FLOW",
                    "PAY_FLOW_SUCCESS", "NONE");
        }

        if (payFlow != null) {
            if (voucher != null && !Objects.equals(payFlow.getAmount(), voucher.getPayValue())) {
                saveDiff(date, order.getId(), "AMOUNT_MISMATCH",
                        "PAY_VALUE=" + voucher.getPayValue(), "FLOW_AMOUNT=" + payFlow.getAmount());
            }
            if (Objects.equals(status, OrderStatus.CANCELLED.getCode())) {
                saveDiff(date, order.getId(), "CANCELLED_WITH_PAYMENT",
                        "NO_SUCCESS_PAY_FLOW", "FLOW=" + payFlow.getTradeNo());
            }
            if (!paidLikeStatus(status)) {
                saveDiff(date, order.getId(), "FLOW_WITHOUT_PAID_ORDER",
                        "PAID_ORDER", "ORDER_STATUS=" + status);
            }
        }

        if (Objects.equals(status, OrderStatus.REFUNDED.getCode()) && refundFlow == null) {
            saveDiff(date, order.getId(), "REFUNDED_WITHOUT_FLOW",
                    "REFUND_FLOW_SUCCESS", "NONE");
        }
    }

    private boolean paidLikeStatus(Integer status) {
        return Objects.equals(status, OrderStatus.PAID.getCode())
                || Objects.equals(status, OrderStatus.USED.getCode())
                || Objects.equals(status, OrderStatus.REFUNDING.getCode())
                || Objects.equals(status, OrderStatus.REFUNDED.getCode());
    }

    private Map<Long, PaymentFlow> loadFlows(Set<Long> orderIds, int flowType) {
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PaymentFlow> flows = paymentFlowMapper.selectList(
                Wrappers.<PaymentFlow>lambdaQuery()
                        .in(PaymentFlow::getOrderId, orderIds)
                        .eq(PaymentFlow::getFlowType, flowType)
                        .eq(PaymentFlow::getStatus, FLOW_SUCCESS));
        return flows.stream().collect(Collectors.toMap(
                PaymentFlow::getOrderId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, Voucher> loadVouchers(List<VoucherOrder> orders) {
        Set<Long> voucherIds = orders.stream()
                .map(VoucherOrder::getVoucherId)
                .collect(Collectors.toSet());
        if (voucherIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return voucherService.listByIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, Function.identity()));
    }

    private void saveDiff(LocalDate date, Long orderId, String diffType,
                          String expected, String actual) {
        ReconcileDiff existing = reconcileDiffMapper.selectOne(
                Wrappers.<ReconcileDiff>lambdaQuery()
                        .eq(ReconcileDiff::getBizDate, date)
                        .eq(ReconcileDiff::getOrderId, orderId)
                        .eq(ReconcileDiff::getDiffType, diffType)
                        .last("limit 1"));
        if (existing == null) {
            reconcileDiffMapper.insert(new ReconcileDiff()
                    .setBizDate(date)
                    .setOrderId(orderId)
                    .setDiffType(diffType)
                    .setExpected(expected)
                    .setActual(actual)
                    .setHandleStatus(0)
                    .setCreateTime(LocalDateTime.now()));
        } else {
            existing.setExpected(expected).setActual(actual);
            reconcileDiffMapper.updateById(existing);
        }
    }
}

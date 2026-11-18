package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.PayNotifyDTO;
import com.hmdp.dto.PayParamsDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.PaymentFlow;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.PaymentFlowMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderStateService;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.PaySignUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模拟支付渠道实现。
 * <p>
 * 真实网关的职责（下单、验签、异步回调）在这里用「HMAC 签名 + notify 接口」模拟，
 * 订单状态变更全部走 {@link IOrderStateService} 的条件更新，避免先查后改。
 */
@Slf4j
@Service
public class PaymentServiceImpl implements IPaymentService {

    private static final int FLOW_PAY = 1;
    private static final int FLOW_REFUND = 2;
    private static final int FLOW_INIT = 0;
    private static final int FLOW_SUCCESS = 1;
    private static final int FLOW_FAIL = 2;

    private static final long NOTIFY_EXPIRE_MILLIS = 5 * 60 * 1000L;

    @Resource
    private PaymentFlowMapper paymentFlowMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private IOrderStateService orderStateService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${pay.secret:hmdp-pay-secret}")
    private String paySecret;

    @Override
    public Result createPay(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.UNPAID.getCode())) {
            return Result.fail("订单状态不允许支付");
        }

        PaymentFlow flow = findFlow(orderId, FLOW_PAY);
        if (flow == null) {
            Voucher voucher = voucherService.getById(order.getVoucherId());
            if (voucher == null || voucher.getPayValue() == null) {
                return Result.fail("优惠券金额异常");
            }
            flow = new PaymentFlow()
                    .setOrderId(orderId)
                    .setTradeNo("PAY" + orderId)
                    .setFlowType(FLOW_PAY)
                    .setAmount(voucher.getPayValue())
                    .setChannel(1)
                    .setStatus(FLOW_INIT);
            try {
                paymentFlowMapper.insert(flow);
            } catch (DuplicateKeyException e) {
                // 并发发起支付，复用已创建的流水
                flow = findFlow(orderId, FLOW_PAY);
            }
        }
        if (flow == null) {
            return Result.fail("支付流水创建失败");
        }
        if (Objects.equals(flow.getStatus(), FLOW_SUCCESS)) {
            return Result.fail("订单已支付");
        }
        return Result.ok(buildParams(flow));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result handlePayNotify(PayNotifyDTO dto) {
        if (!verifyNotify(dto)) {
            return Result.fail("验签失败或回调已过期");
        }
        PaymentFlow flow = findFlowByTradeNo(dto.getTradeNo());
        if (flow == null || !Objects.equals(flow.getFlowType(), FLOW_PAY)) {
            return Result.fail("支付流水不存在");
        }
        if (!Objects.equals(flow.getAmount(), dto.getAmount())) {
            return Result.fail("回调金额与流水不一致");
        }

        String raw = JSONUtil.toJsonStr(dto);
        if (!Boolean.TRUE.equals(dto.getSuccess())) {
            markFlow(flow, FLOW_FAIL, raw);
            return Result.fail("渠道侧支付失败");
        }
        // 重复回调：流水已成功直接返回成功
        if (Objects.equals(flow.getStatus(), FLOW_SUCCESS)) {
            return Result.ok();
        }

        // 锁订单行：支付回调与超时关单/用户取消靠状态 CAS 竞争，只有一方成功
        VoucherOrder order = voucherOrderMapper.selectForUpdate(flow.getOrderId());
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (Objects.equals(order.getStatus(), OrderStatus.PAID.getCode())) {
            markFlow(flow, FLOW_SUCCESS, raw);
            return Result.ok();
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.UNPAID.getCode())) {
            markFlow(flow, FLOW_FAIL, raw);
            log.warn("支付回调时订单状态不允许支付，orderId={}, status={}",
                    order.getId(), order.getStatus());
            return Result.fail("订单状态不允许支付");
        }

        boolean changed = orderStateService.change(order.getId(),
                OrderStatus.UNPAID.getCode(), OrderStatus.PAID.getCode(), "PAY_NOTIFY");
        if (!changed) {
            VoucherOrder latest = voucherOrderMapper.selectById(order.getId());
            if (latest != null && Objects.equals(latest.getStatus(), OrderStatus.PAID.getCode())) {
                markFlow(flow, FLOW_SUCCESS, raw);
                return Result.ok();
            }
            markFlow(flow, FLOW_FAIL, raw);
            return Result.fail("订单状态不允许支付");
        }

        markFlow(flow, FLOW_SUCCESS, raw);
        // 已支付订单不再参与超时关单
        stringRedisTemplate.opsForZSet()
                .remove(RedisConstants.DELAY_ORDER_KEY, String.valueOf(order.getId()));
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result applyRefund(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (Objects.equals(order.getStatus(), OrderStatus.REFUNDING.getCode())) {
            PaymentFlow exist = findFlow(orderId, FLOW_REFUND);
            return exist == null ? Result.fail("退款流水缺失") : Result.ok(buildParams(exist));
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.PAID.getCode())) {
            return Result.fail("只有已支付订单可以申请退款");
        }

        PaymentFlow paid = findFlow(orderId, FLOW_PAY);
        if (paid == null || !Objects.equals(paid.getStatus(), FLOW_SUCCESS)) {
            return Result.fail("支付流水异常，无法退款");
        }

        PaymentFlow refund = new PaymentFlow()
                .setOrderId(orderId)
                .setTradeNo("REFUND" + orderId)
                .setFlowType(FLOW_REFUND)
                .setAmount(paid.getAmount())
                .setChannel(paid.getChannel())
                .setStatus(FLOW_INIT);
        try {
            paymentFlowMapper.insert(refund);
        } catch (DuplicateKeyException e) {
            refund = findFlow(orderId, FLOW_REFUND);
        }
        if (refund == null) {
            return Result.fail("退款流水创建失败");
        }

        boolean changed = orderStateService.change(orderId,
                OrderStatus.PAID.getCode(), OrderStatus.REFUNDING.getCode(), "USER");
        if (!changed) {
            return Result.fail("订单状态不允许退款");
        }
        return Result.ok(buildParams(refund));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result handleRefundNotify(PayNotifyDTO dto) {
        if (!verifyNotify(dto)) {
            return Result.fail("验签失败或回调已过期");
        }
        PaymentFlow flow = findFlowByTradeNo(dto.getTradeNo());
        if (flow == null || !Objects.equals(flow.getFlowType(), FLOW_REFUND)) {
            return Result.fail("退款流水不存在");
        }
        if (!Objects.equals(flow.getAmount(), dto.getAmount())) {
            return Result.fail("回调金额与流水不一致");
        }
        if (Objects.equals(flow.getStatus(), FLOW_SUCCESS)) {
            return Result.ok();
        }

        String raw = JSONUtil.toJsonStr(dto);
        VoucherOrder order = voucherOrderMapper.selectForUpdate(flow.getOrderId());
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (Objects.equals(order.getStatus(), OrderStatus.REFUNDED.getCode())) {
            markFlow(flow, FLOW_SUCCESS, raw);
            return Result.ok();
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.REFUNDING.getCode())) {
            markFlow(flow, FLOW_FAIL, raw);
            return Result.fail("订单状态不允许退款回调");
        }

        boolean success = Boolean.TRUE.equals(dto.getSuccess());
        boolean changed = success
                ? orderStateService.change(order.getId(),
                        OrderStatus.REFUNDING.getCode(), OrderStatus.REFUNDED.getCode(), "REFUND_NOTIFY")
                : orderStateService.change(order.getId(),
                        OrderStatus.REFUNDING.getCode(), OrderStatus.PAID.getCode(), "REFUND_FAIL");
        if (!changed) {
            return Result.fail("退款订单状态变更失败");
        }
        markFlow(flow, success ? FLOW_SUCCESS : FLOW_FAIL, raw);
        if (success) {
            return Result.ok();
        }
        log.warn("渠道退款失败，订单恢复为已支付，orderId={}", order.getId());
        return Result.fail("渠道退款失败，订单已恢复为已支付");
    }

    private PayParamsDTO buildParams(PaymentFlow flow) {
        long timestamp = System.currentTimeMillis();
        int channel = flow.getChannel() == null ? 1 : flow.getChannel();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderId", String.valueOf(flow.getOrderId()));
        params.put("tradeNo", flow.getTradeNo());
        params.put("amount", String.valueOf(flow.getAmount()));
        params.put("channel", String.valueOf(channel));
        params.put("timestamp", String.valueOf(timestamp));

        return new PayParamsDTO()
                .setOrderId(flow.getOrderId())
                .setTradeNo(flow.getTradeNo())
                .setAmount(flow.getAmount())
                .setChannel(channel)
                .setTimestamp(timestamp)
                .setSign(PaySignUtil.sign(params, paySecret));
    }

    private boolean verifyNotify(PayNotifyDTO dto) {
        if (dto.getTimestamp() == null
                || Math.abs(System.currentTimeMillis() - dto.getTimestamp()) > NOTIFY_EXPIRE_MILLIS) {
            return false;
        }
        // success 是渠道处理结果，不参与签名；签名只覆盖金额/流水等核心字段
        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderId", string(dto.getOrderId()));
        params.put("tradeNo", dto.getTradeNo());
        params.put("amount", string(dto.getAmount()));
        params.put("channel", string(dto.getChannel()));
        params.put("timestamp", string(dto.getTimestamp()));
        params.put("sign", dto.getSign());
        return PaySignUtil.verify(params, paySecret);
    }

    private PaymentFlow findFlow(Long orderId, int flowType) {
        return paymentFlowMapper.selectOne(Wrappers.<PaymentFlow>lambdaQuery()
                .eq(PaymentFlow::getOrderId, orderId)
                .eq(PaymentFlow::getFlowType, flowType)
                .last("limit 1"));
    }

    private PaymentFlow findFlowByTradeNo(String tradeNo) {
        if (tradeNo == null) {
            return null;
        }
        return paymentFlowMapper.selectOne(Wrappers.<PaymentFlow>lambdaQuery()
                .eq(PaymentFlow::getTradeNo, tradeNo)
                .last("limit 1"));
    }

    private void markFlow(PaymentFlow flow, int status, String rawBody) {
        flow.setStatus(status)
                .setCallbackTime(LocalDateTime.now())
                .setRawBody(rawBody);
        paymentFlowMapper.updateById(flow);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

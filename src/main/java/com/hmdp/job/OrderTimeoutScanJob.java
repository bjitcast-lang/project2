package com.hmdp.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底扫描：ZSet 丢失或关单异常时，按 DB 中「待支付且创建超过 15 分钟」补关。
 */
@Slf4j
@Component
public class OrderTimeoutScanJob {

    private static final int TIMEOUT_MINUTES = 15;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelay = 300_000L)
    public void scan() {
        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                Wrappers.<VoucherOrder>lambdaQuery()
                        .eq(VoucherOrder::getStatus, OrderStatus.UNPAID.getCode())
                        .lt(VoucherOrder::getCreateTime,
                                LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES))
                        .orderByAsc(VoucherOrder::getCreateTime)
                        .last("limit 100"));
        for (VoucherOrder order : orders) {
            try {
                voucherOrderService.cancelBySystem(order.getId(), "TIMEOUT_SCAN");
                log.info("兜底扫描关单，orderId={}", order.getId());
            } catch (Exception e) {
                log.error("兜底扫描关单失败，orderId={}", order.getId(), e);
            }
        }
    }
}

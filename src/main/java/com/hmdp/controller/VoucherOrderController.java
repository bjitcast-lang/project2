package com.hmdp.controller;

import com.hmdp.dto.PayNotifyDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IPaymentService paymentService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @GetMapping("list")
    public Result queryMyOrders(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return voucherOrderService.queryMyOrders(status, current);
    }

    @GetMapping("{id}")
    public Result queryOrderById(@PathVariable("id") Long id) {
        return voucherOrderService.queryOrderById(id);
    }

    @PostMapping("{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long id) {
        return voucherOrderService.cancelOrder(id);
    }

    @PostMapping("{id}/use")
    public Result useVoucher(@PathVariable("id") Long id) {
        return voucherOrderService.useVoucher(id);
    }

    @PostMapping("{id}/pay")
    public Result createPay(@PathVariable("id") Long id) {
        return paymentService.createPay(id);
    }

    /**
     * 模拟渠道支付回调，免登录，靠签名鉴权。
     */
    @PostMapping("pay/notify")
    public Result payNotify(@RequestBody PayNotifyDTO dto) {
        return paymentService.handlePayNotify(dto);
    }

    @PostMapping("{id}/refund")
    public Result applyRefund(@PathVariable("id") Long id) {
        return paymentService.applyRefund(id);
    }

    /**
     * 模拟渠道退款回调，免登录，靠签名鉴权。
     */
    @PostMapping("refund/notify")
    public Result refundNotify(@RequestBody PayNotifyDTO dto) {
        return paymentService.handleRefundNotify(dto);
    }
}

package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result createVoucherOrder(Long voucherId);

    /**
     * 查询订单详情，只能查看自己的订单。
     */
    Result queryOrderById(Long id);

    /**
     * 分页查询我的订单。
     *
     * @param status  订单状态，为空则查全部
     * @param current 页码，从 1 开始
     */
    Result queryMyOrders(Integer status, Integer current);

    /**
     * 用户取消待支付订单，并回补数据库库存。
     */
    Result cancelOrder(Long id);

    /**
     * 系统取消订单（超时关单、兜底扫描），幂等。
     * 回补 DB 库存并写 ORDER_CANCELLED outbox，Redis 库存由 outbox 投递回补。
     *
     * @return true 表示订单已处于取消状态（本次取消成功或此前已取消）
     */
    boolean cancelBySystem(Long orderId, String operator);

    /**
     * 核销订单：2 已支付 -> 3 已核销，只能操作自己的订单。
     */
    Result useVoucher(Long id);
}

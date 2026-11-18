package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderStateService;
import com.hmdp.service.IOutboxService;
import com.hmdp.service.ISeckillStockService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IOrderStateService orderStateService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillStockService seckillStockService;

    @Resource
    private IOutboxService outboxService;

    @Value("${seckill.async:true}")
    private boolean seckillAsync;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        Long userId = UserHolder.getUser().getId();

        // 4. 异步秒杀：Lua 原子完成库存判断、一人一单、扣减、写 Stream
        if (seckillAsync) {
            long orderId = redisIdWorker.nextId("order");
            long code = seckillStockService.trySeckill(voucherId, userId, orderId);
            if (code == 1L) {
                return Result.fail("库存不足");
            }
            if (code == 2L) {
                return Result.fail("不允许重复下单");
            }
            // 语义为“受理成功”，订单由消费者异步落库
            return Result.ok(orderId);
        }

        // 5. 同步回退路径：判断 DB 库存 + Redis 分布式锁
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(1200);
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            // 6. 通过代理对象调用，保证 createVoucherOrder 上的事务生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 7. 只有锁的 value 与当前线程标识一致时才会真正删除
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. 一人一单：查询该用户是否已经购买过该优惠券
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次");
        }

        // 2. 扣减库存：乐观锁，只有 stock > 0 时才扣减
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        // 3. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 4. 返回订单 id
        return Result.ok(orderId);
    }

    @Override
    public Result queryOrderById(Long id) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        return Result.ok(toVO(order, voucher));
    }

    @Override
    public Result queryMyOrders(Integer status, Integer current) {
        Long userId = UserHolder.getUser().getId();
        int pageNo = (current == null || current < 1) ? 1 : current;

        LambdaQueryWrapper<VoucherOrder> wrapper = Wrappers.<VoucherOrder>lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(status != null, VoucherOrder::getStatus, status)
                .orderByDesc(VoucherOrder::getCreateTime);
        Page<VoucherOrder> page = page(
                new Page<>(pageNo, SystemConstants.DEFAULT_PAGE_SIZE), wrapper);

        List<VoucherOrder> records = page.getRecords();
        Map<Long, Voucher> voucherMap = Collections.emptyMap();
        if (!records.isEmpty()) {
            Set<Long> voucherIds = records.stream()
                    .map(VoucherOrder::getVoucherId)
                    .collect(Collectors.toSet());
            voucherMap = voucherService.listByIds(voucherIds).stream()
                    .collect(Collectors.toMap(Voucher::getId, Function.identity()));
        }

        // 批量查询券信息，避免逐条查询造成 N+1
        Map<Long, Voucher> finalVoucherMap = voucherMap;
        List<OrderVO> orders = records.stream()
                .map(order -> toVO(order, finalVoucherMap.get(order.getVoucherId())))
                .collect(Collectors.toList());
        return Result.ok(orders, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result cancelOrder(Long id) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            return Result.ok();
        }
        // 条件更新：只有仍处于待支付状态才能取消，天然防止与支付并发
        if (!cancelBySystem(id, "USER")) {
            return Result.fail("订单状态不允许取消");
        }
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelBySystem(Long orderId, String operator) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return true;
        }
        if (order.getStatus() != null && order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            return true;
        }

        boolean changed = orderStateService.change(
                orderId, OrderStatus.UNPAID.getCode(), OrderStatus.CANCELLED.getCode(), operator);
        if (!changed) {
            VoucherOrder latest = getById(orderId);
            return latest != null && latest.getStatus() != null
                    && latest.getStatus() == OrderStatus.CANCELLED.getCode();
        }

        // 1. 回补 DB 库存
        boolean restored = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!restored) {
            throw new IllegalStateException("回补库存失败，orderId=" + orderId);
        }

        // 2. 写 outbox，由 OutboxDispatcher 幂等回补 Redis 库存
        SeckillMessage message = new SeckillMessage(
                orderId, order.getUserId(), order.getVoucherId());
        outboxService.saveEvent("ORDER_CANCELLED",
                "order-cancelled:" + orderId, JSONUtil.toJsonStr(message));
        return true;
    }

    @Override
    public Result useVoucher(Long id) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == OrderStatus.USED.getCode()) {
            return Result.ok();
        }
        boolean changed = orderStateService.change(
                id, OrderStatus.PAID.getCode(), OrderStatus.USED.getCode(), "USER");
        if (!changed) {
            return Result.fail("订单状态不允许核销");
        }
        return Result.ok();
    }

    private OrderVO toVO(VoucherOrder order, Voucher voucher) {
        OrderVO vo = new OrderVO()
                .setId(order.getId())
                .setVoucherId(order.getVoucherId())
                .setStatus(order.getStatus())
                .setStatusDesc(OrderStatus.descOf(order.getStatus()))
                .setCreateTime(order.getCreateTime())
                .setPayTime(order.getPayTime());
        if (voucher != null) {
            vo.setVoucherTitle(voucher.getTitle())
                    .setAmount(voucher.getPayValue())
                    .setShopId(voucher.getShopId());
        }
        return vo;
    }
}

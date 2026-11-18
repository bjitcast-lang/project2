package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IReconcileService;
import com.hmdp.service.ISeckillStockService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 管理端接口，由 AdminInterceptor 校验 X-Admin-Token。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Resource
    private ISeckillStockService seckillStockService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IReconcileService reconcileService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 手动预热秒杀库存。
     * force=true 会强制覆盖库存并清空一人一单集合，仅测试环境使用。
     */
    @PostMapping("/seckill/preheat/{voucherId}")
    public Result preheat(@PathVariable("voucherId") Long voucherId,
                          @RequestParam(value = "force", defaultValue = "false") boolean force) {
        seckillStockService.preheat(voucherId, force);
        return Result.ok();
    }

    /**
     * 秒杀观测：Redis/DB 库存、一人一单集合、Stream、pending、DLQ、订单数。
     * 用于压测后验证「DB 库存 + 订单数 = 初始库存」以及 pending/DLQ 清零。
     */
    @GetMapping("/seckill/stats")
    public Result seckillStats(@RequestParam("voucherId") Long voucherId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voucherId", voucherId);
        data.put("redisStock", stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId));
        data.put("oneUserCount", stringRedisTemplate.opsForSet()
                .size(RedisConstants.SECKILL_ORDER_KEY + voucherId));
        data.put("streamLength", stringRedisTemplate.opsForStream()
                .size(RedisConstants.SECKILL_STREAM_KEY));
        data.put("pendingCount", pendingCount());
        data.put("dlqLength", stringRedisTemplate.opsForStream()
                .size(RedisConstants.SECKILL_DLQ_KEY));

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        data.put("dbStock", voucher == null ? null : voucher.getStock());
        data.put("orderCount", voucherOrderMapper.selectCount(
                Wrappers.<VoucherOrder>lambdaQuery()
                        .eq(VoucherOrder::getVoucherId, voucherId)));
        return Result.ok(data);
    }

    @GetMapping("/reconcile/diff")
    public Result reconcileDiff(@RequestParam("date") String date) {
        return Result.ok(reconcileService.listDiff(date));
    }

    @PostMapping("/reconcile/run")
    public Result reconcileRun(@RequestParam(value = "date", required = false) String date) {
        String bizDate = StrUtil.isBlank(date) ? LocalDate.now().toString() : date;
        int count = reconcileService.reconcile(bizDate);
        if (count < 0) {
            return Result.fail("对账任务正在执行，请稍后重试");
        }
        return Result.ok(count);
    }

    /**
     * 压测辅助：为指定 userId 直接签发一个登录 token，走与正常登录一致的 Redis 结构。
     * 仅本地演示使用，生产环境必须关闭。
     */
    @PostMapping("/test/token")
    public Result testToken(@RequestParam("userId") Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> user = new HashMap<>(4);
        user.put("id", String.valueOf(userId));
        user.put("nickName", "loadtest-" + userId);
        user.put("icon", "");
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, user);
        stringRedisTemplate.expire(tokenKey, 1, TimeUnit.DAYS);
        Map<String, Object> data = new HashMap<>(2);
        data.put("token", token);
        data.put("userId", userId);
        return Result.ok(data);
    }

    private int pendingCount() {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_GROUP,
                    Range.unbounded(),
                    1000L);
            return pending == null ? 0 : pending.size();
        } catch (Exception e) {
            // 消费组尚未创建时 XPENDING 会报错
            log.debug("查询 pending 失败: {}", e.getMessage());
            return 0;
        }
    }
}

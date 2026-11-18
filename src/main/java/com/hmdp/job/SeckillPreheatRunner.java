package com.hmdp.job;

import com.hmdp.service.ISeckillStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 应用启动时预热进行中的秒杀券库存。
 * Redis 不可用时只记录日志，不阻塞应用启动。
 */
@Slf4j
@Component
public class SeckillPreheatRunner implements ApplicationRunner {

    @Resource
    private ISeckillStockService seckillStockService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seckillStockService.preheatActive();
        } catch (Exception e) {
            log.warn("启动秒杀库存预热失败，可稍后调用 /admin/seckill/preheat/{id} 手动预热", e);
        }
    }
}

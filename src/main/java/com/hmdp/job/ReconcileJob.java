package com.hmdp.job;

import com.hmdp.service.IReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;

/**
 * 每日 00:10 对前一天的订单做对账，也可通过 Admin 接口手动触发。
 */
@Slf4j
@Component
public class ReconcileJob {

    @Resource
    private IReconcileService reconcileService;

    @Scheduled(cron = "0 10 0 * * ?")
    public void dailyReconcile() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        try {
            int count = reconcileService.reconcile(yesterday);
            log.info("每日对账完成，date={}, diffCount={}", yesterday, count);
        } catch (Exception e) {
            log.error("每日对账失败，date={}", yesterday, e);
        }
    }
}

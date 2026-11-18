package com.hmdp.service;

import com.hmdp.entity.ReconcileDiff;

import java.util.List;

/**
 * 支付对账：扫描订单与支付/退款流水，把差异写入差错表。
 */
public interface IReconcileService {

    /**
     * 对指定日期（yyyy-MM-dd）的订单执行对账。
     *
     * @return 该日期的差异记录总数；-1 表示已有实例在跑
     */
    int reconcile(String bizDate);

    /**
     * 查询指定日期的差异记录。
     */
    List<ReconcileDiff> listDiff(String bizDate);
}

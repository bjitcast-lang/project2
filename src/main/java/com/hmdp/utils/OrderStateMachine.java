package com.hmdp.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：只负责校验状态流转是否合法，不做任何数据库操作。
 * <p>
 * 合法流转：
 * <pre>
 * 1 待支付  -> 2 已支付 / 4 已取消
 * 2 已支付  -> 3 已核销 / 5 退款中
 * 3 已核销  -> 无
 * 4 已取消  -> 无
 * 5 退款中  -> 2 已支付（退款失败回滚）/ 6 已退款
 * 6 已退款  -> 无
 * </pre>
 */
public final class OrderStateMachine {

    private static final Map<Integer, Set<Integer>> ALLOWED;

    static {
        Map<Integer, Set<Integer>> allowed = new HashMap<>();
        allowed.put(OrderStatus.UNPAID.getCode(),
                unmodifiable(OrderStatus.PAID.getCode(), OrderStatus.CANCELLED.getCode()));
        allowed.put(OrderStatus.PAID.getCode(),
                unmodifiable(OrderStatus.USED.getCode(), OrderStatus.REFUNDING.getCode()));
        allowed.put(OrderStatus.REFUNDING.getCode(),
                unmodifiable(OrderStatus.PAID.getCode(), OrderStatus.REFUNDED.getCode()));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    private OrderStateMachine() {
    }

    public static boolean canTransit(int fromStatus, int toStatus) {
        Set<Integer> targets = ALLOWED.get(fromStatus);
        return targets != null && targets.contains(toStatus);
    }

    private static Set<Integer> unmodifiable(Integer... statuses) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(statuses)));
    }
}

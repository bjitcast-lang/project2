package com.hmdp.utils;

/**
 * 订单状态枚举。
 * <p>
 * 与 {@code tb_voucher_order.status} 字段对应：
 * 1待支付、2已支付、3已核销、4已取消、5退款中、6已退款。
 */
public enum OrderStatus {

    UNPAID(1, "待支付"),
    PAID(2, "已支付"),
    USED(3, "已核销"),
    CANCELLED(4, "已取消"),
    REFUNDING(5, "退款中"),
    REFUNDED(6, "已退款");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public static String descOf(Integer code) {
        OrderStatus status = of(code);
        return status == null ? "未知状态" : status.desc;
    }
}

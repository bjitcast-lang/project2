package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 支付/退款回调里锁订单行，保证「状态 CAS + 流水更新」的顺序。
     */
    @Select("SELECT * FROM tb_voucher_order WHERE id = #{id} FOR UPDATE")
    VoucherOrder selectForUpdate(@Param("id") Long id);
}

package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀券抢购
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherId);
}

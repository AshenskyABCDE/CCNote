package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("抢购时间还没开始");
        }
        // 判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("已经错过了抢购时间");
        }
        // 判断库存是够不足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
//        // 在分布式集群下可能遇到问题
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) { // 应该把这一个函数都上锁
//            // 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        // 获取用户信息
        Long userId = UserHolder.getUser().getId();
//        // 将其主动上分布式锁 (自行实现的)
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // redisson 上锁
        // 似乎这里有bug，如果不加优惠券的id，那么一个人只能抢一个优惠券
         RLock lock = redissonClient.getLock("lock:order:" + voucherId + userId);
        boolean islock = lock.tryLock();
        if(!islock) {
           return Result.fail("一个人只允许下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            if (lock.isLocked()) {
                lock.unlock();
            }
        }
    }
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        // 进行一人一单的判断
        Long userId = UserHolder.getUser().getId();

            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("该用户已经买过一个了");
            }
            boolean state = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!state) {
                return Result.fail("库存不足");
            }
            // 创建订单 优惠券Id 用户Id 购物Id
            VoucherOrder voucherOrder = new VoucherOrder();
            long Id = redisIdWorker.nextId("order");
            voucherOrder.setId(Id);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            // 返回订单id
        return Result.ok(voucherId);
    }
}

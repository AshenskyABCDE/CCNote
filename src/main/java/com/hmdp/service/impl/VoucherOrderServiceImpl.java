package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>( 1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private  void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private  class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true) {
                // 获取队列里的订到信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
//        // 将其主动上分布式锁 (自行实现的)
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // redisson 上锁
        // 似乎这里有bug，如果不加优惠券的id，那么一个人只能抢一个优惠券
         RLock lock = redissonClient.getLock("lock:order:" + voucherId + userId);
        boolean islock = lock.tryLock();
        if(!islock) {
            log.error("不允许重复下单");
            return ;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            if (lock.isLocked()) {
                lock.unlock();
            }
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 创建订单之后 首先调用，通过lua 脚本 判断库存是否不足 用户是否下过单
        // 如果有购买资格，把订单创建写到阻塞队列里
        // 执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        System.out.println(voucherId);
        int now = result.intValue();
        if(now == 1) {
            return Result.fail("库存不足");
        }
        if(now == 2) {
            return Result.fail("该用户已经下过单");
        }
        // 有购买资格 放进阻塞队列里
        long orderId = redisIdWorker.nextId("order");
        // 创建订单 优惠券Id 用户Id 购物Id
        VoucherOrder voucherOrder = new VoucherOrder();
        long Id = redisIdWorker.nextId("order");
        voucherOrder.setId(Id);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);

        // 创建阻塞队列
        orderTasks.add(voucherOrder);

        // 获取代理对象 执行异步下单
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("抢购时间还没开始");
//        }
//        // 判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("已经错过了抢购时间");
//        }
//        // 判断库存是够不足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
////        // 在分布式集群下可能遇到问题
////        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) { // 应该把这一个函数都上锁
////            // 获取代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        // 获取用户信息
//        Long userId = UserHolder.getUser().getId();
////        // 将其主动上分布式锁 (自行实现的)
//        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        // redisson 上锁
//        // 似乎这里有bug，如果不加优惠券的id，那么一个人只能抢一个优惠券
//         RLock lock = redissonClient.getLock("lock:order:" + voucherId + userId);
//        boolean islock = lock.tryLock();
//        if(!islock) {
//           return Result.fail("一个人只允许下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            if (lock.isLocked()) {
//                lock.unlock();
//            }
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 进行一人一单的判断
        Long userId = voucherOrder.getUserId();

            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("该用户已经买过一个");
                return;
            }
            System.out.println(voucherOrder.getVoucherId());
            boolean state = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!state) {
                log.error("库存不足！");
                return ;
            }
            save(voucherOrder);
    }
}

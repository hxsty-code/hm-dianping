package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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

    /**
     * 秒杀优惠券（入口方法：前置校验 + 加锁 + 调代理）
     * 注意：此方法不加 @Transactional，事务在 createVoucherOrder 里
     * @param voucherId
     * @return
     */

    // 创建脚本对象
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();   // 创建脚本对象
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));   // 设置脚本路径
        SECKILL_SCRIPT.setResultType(Long.class);   // 设置脚本返回值类型
    }

    // 创建异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 初始化异步处理线程池，用于处理阻塞队列中的订单
    @PostConstruct      // 在当前类初始化后执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 创建线程任务
    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1、获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2、判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续循环
                        continue;
                    }
                    // 3、解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5、ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1、获取pending-list中的订单信息 xreadgroup group g1 c1 count 1 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2、判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    // 3、解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5、ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    // 失败的消息因未ACK会立即被再次读出，休眠片刻避免空转占用CPU
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    // 处理订单（一人一单，通过Redisson实现分布式锁）
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 加锁保证一人一单
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取成功
        if (!isLock) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 创建事务代理对象
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 获取代理对象（事务）：必须在执行lua之前完成——lua中会将订单入队，消费线程可能立即取走并读取proxy
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 1、执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2、判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            // 2.1 不为0，表示没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "请勿重复下单");
        }
        // 3、返回订单id
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//
//        // 2、判断优惠券是否可用
//        LocalDateTime now = LocalDateTime.now();
//        if (now.isBefore(seckillVoucher.getBeginTime()) || now.isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("优惠券不可用！");
//        }
//
//        // 3、判断优惠券库存是否充足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("优惠券库存不足！");
//        }
//
//        // 4、加锁保证一人一单
//        Long userId = UserHolder.getUser().getId();
//        // 4.1 创建锁对象
//        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 4.2 尝试获取锁
//        boolean isLock = lock.tryLock();
//        // 4.3 判断是否获取成功
//        if(!isLock){
//            return Result.fail("请勿重复下单！");
//        }
//        try {
//            // 5、获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 6、释放锁
//            lock.unlock();
//        }
//    }

    /**
     * 创建订单（一人一单 + 扣库存 + 下单，事务范围）
     * 必须通过代理对象调用（AopContext.currentProxy()）
     *
     * @param voucherOrder
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 一人一单：查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否已存在
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }

        // 扣减库存（乐观锁解决超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }

        // 创建订单
        save(voucherOrder);
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 秒杀 秒杀券
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SecKill_LUA;

    // 静态代码块用来配置 初始化lua
    static {
        SecKill_LUA = new DefaultRedisScript<>();
        SecKill_LUA.setLocation(new ClassPathResource("SecKill.lua"));
        SecKill_LUA.setResultType(Long.class);
    }

    //线程池异步处理
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendding订单异常", e);
                    try{
                        Thread.sleep(20);
                    }catch(Exception e1){
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    /**
     * 执行lua脚本，将消息放进队列中，并根据lua脚本是否成功放进队列
     * 并根据lua脚本的返回值来判断是否成功下单，下单的业务通过额从线程池拿一个线程来执行
     * @param voucherId
     * @return
     */
    public Result secKill(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本，将三个参数传入，根据返回值做相应业务
        // 执行完lua脚本，即向消息队列中发消息，由于开了一个定时任务，隔一段时间去查看队列中是否有消息接收
        Long result = stringRedisTemplate.execute(
                SecKill_LUA,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        // 以下操作均放到lua脚本执行
//        //保存阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.3.订单id
//        long orderId2 = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId2);
//        // 2.4.用户id
//        voucherOrder.setUserId(userId);
//        // 2.5.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);
        //3.获取代理对象
        // 这里是将proxy作为一个静态变量，在这里赋值以后，子线程也能用到proxy，因为proxy底层是随着线程变化而改变
        // 这里在主线程创好之后，放到静态变量区，子线程也能拿到
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        // 该方法只是判断是否可以下单，下单成功返回单号，对数据库的操作由别人做（线程监听）
        return Result.ok(orderId);
    }

    /**
     * 创建秒杀订单
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }
//    @Override
//    public Result secKill(Long voucherId) {
//        // 提交优惠券id
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 查询优惠券信息
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        int stock = voucher.getStock();
//        // 判断秒杀是否开始,没开始则返回异常
//        if(LocalDateTime.now().isBefore(beginTime)) {
//            return Result.fail("秒杀尚未开始");
//        }
//            if(LocalDateTime.now().isAfter(endTime)){
//                return Result.fail("秒杀已经结束~~");
//            }
//        // 开始的话则判断库存是否充足
//            if(stock<1){
//                return Result.fail("库存无了");
//            }
//        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()) {
//            // 通过AopContext 获得当前类的代理对象
//            // 进而通过代理对象实现事务的功能，否则事务会失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(userId,voucher);
//        }
//    }
//    @Transactional
//    public Result createSesKillOrder(SeckillVoucher voucher) {
//        // 根据优惠券ID和用户ID查询订单是否存在
//        Long userId = UserHolder.getUser().getId();
//        Long voucherId = voucher.getVoucherId();
//        int count = query().eq("voucher_id", voucher).eq("user_id",userId).count();
//        if(count>0){
//       //存在的话返回异常，一个用户不能下多个单
//            return Result.fail("已经下过订单了。");
//        }
//        // 不存在的话则进行扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1") // 对库存量减一
//                .eq("voucher_id", voucherId).update(); // where 查询
//        if(!success){
//            return Result.fail("库存不足");
//        }
//        // 创建订单
//        VoucherOrder voucher1 = new VoucherOrder();
//        Long orderId = redisIdWorker.nextId(SECKILL_STOCK_KEY+ voucherId);
//        voucher1.setId(orderId);
//        voucher1.setUserId(userId);
//        voucher1.setVoucherId(voucherId);
//        save(voucher1);
//        return Result.ok(orderId);
//    }
}

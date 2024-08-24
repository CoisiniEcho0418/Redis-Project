package com.hmdp.service.impl;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisGlobalIdGenerator;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisGlobalIdGenerator redisGlobalIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 异步处理的线程池 (TODO 修改线程池的创建方法)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // bean 初始化之后就开始执行异步任务
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*// 阻塞队列
    private BlockingQueue<VoucherOrder> SECKILL_ORDERS_BLOCKING_QUEUE = new ArrayBlockingQueue<>(1024 * 1024);
    
    // 异步处理秒杀任务的线程（做数据库相关的操作）
    private class VoucherOrderHandler implements Runnable {
    
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = SECKILL_ORDERS_BLOCKING_QUEUE.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    // 异步处理秒杀任务的线程（做数据库相关的操作）
    private class VoucherOrderHandler implements Runnable {
        // 消息队列名称
        public static final String QUEUE_NAME = "stream.orders";

        @Override
        public void run() {
            while (true) {
                // 消费组名称为 g1
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed()));
                    if (CollectionUtil.isEmpty(messageList)) {
                        continue;
                    }
                    // 2.解析消息内容
                    MapRecord<String, Object, Object> message = messageList.get(0);
                    Map<Object, Object> messageValue = message.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageValue, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", message.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> messageList =
                        stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1), StreamOffset.create(QUEUE_NAME, ReadOffset.from("0")));
                    if (CollectionUtil.isEmpty(messageList)) {
                        break;
                    }
                    // 2.解析消息内容
                    MapRecord<String, Object, Object> message = messageList.get(0);
                    Map<Object, Object> messageValue = message.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageValue, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", message.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    // 休眠一段时间之后 continue（继续尝试处理）
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if (!lock.tryLock()) {
            log.error("用户不能重复下单！");
            return;
        }
        try {
            // 获取代理对象（事务），因为Spring的事务管理是基于代理对象的，这样做可以防止直接调用this.createVoucherOrder
            // 而导致的事务控制失效（因为该方法没加@Transactional注解，只有createVoucherOrder方法加了）
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.从 Redis查询用户购买资格
        // 获取用户ID 和 订单 ID
        Long userId = UserHolder.getUser().getId();
        long orderId = redisGlobalIdGenerator.nextId("order");
        // 调用 lua 脚本判断秒杀库存和校验一人一单（用 REDIS STREAM 版本的消息队列来代替阻塞队列）
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),
            userId.toString(), String.valueOf(orderId));
        // 根据返回结果进行判断
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "用户不能重复下单！");
        }

        // 获取代理对象并存储到全局变量中
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        // 返回订单 id
        return Result.ok(orderId);
    }

    // @Override
    // public Result seckillVoucher(Long voucherId) {
    // // 1.从 Redis查询用户购买资格
    // // 获取用户ID
    // Long userId = UserHolder.getUser().getId();
    // // 调用 lua 脚本判断秒杀库存和校验一人一单
    // Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),
    // userId.toString());
    // // 根据返回结果进行判断
    // int r = result.intValue();
    // if (r != 0) {
    // return Result.fail(r == 1 ? "库存不足！" : "用户不能重复下单！");
    // }
    //
    // // 2.将优惠券id、用户id、订单id 存入阻塞队列
    // long orderId = redisGlobalIdGenerator.nextId("order");
    // VoucherOrder voucherOrder = new VoucherOrder();
    // voucherOrder.setId(orderId);
    // voucherOrder.setUserId(userId);
    // voucherOrder.setVoucherId(voucherId);
    // SECKILL_ORDERS_BLOCKING_QUEUE.add(voucherOrder);
    //
    // // 获取代理对象并存储到全局变量中
    // proxy = (IVoucherOrderService)AopContext.currentProxy();
    //
    // // 返回订单 id
    // return Result.ok(orderId);
    // }

    // @Override
    // public Result seckillVoucher(Long voucherId) {
    // // TODO Jmeter并发测试（超卖，一人一单）
    // // 1.查询优惠券信息
    // SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //
    // // 2.判断秒杀是否开始
    // if (ObjectUtil.isNull(seckillVoucher)) {
    // return Result.fail("优惠券不存在！");
    // }
    // // 判断开始时间
    // if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
    // return Result.fail("优惠券活动未开始！");
    // }
    // // 判断结束时间
    // if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
    // return Result.fail("优惠券活动已结束！");
    // }
    //
    // // 3.判断库存是否充足
    // if (seckillVoucher.getStock() < 1) {
    // return Result.fail("库存不足！");
    // }
    //
    // // 4.一人一单判断
    // Long userId = UserHolder.getUser().getId();
    //
    // /*// userId.toString().intern() 保证只要用户id的值一样就是同样的对象，得到的锁就一样，
    // // 这样不会导致同一用户多次请求得到不同的锁，导致锁失效。同时锁加在这里是为了防止加在
    // // createVoucherOrder方法内部而导致的先释放锁后提交事务而产生的可能的并发问题（若线
    // // 程在释放锁且未提交事务之际进入，便会使原本想要锁住的并发逻辑失效）
    // synchronized (userId.toString().intern()) {
    // // 获取代理对象（事务），因为Spring的事务管理是基于代理对象的，这样做可以防止直接调用this.createVoucherOrder
    // // 而导致的事务控制失效（因为该方法没加@Transactional注解，只有createVoucherOrder方法加了）
    // IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    // return proxy.createVoucherOrder(voucherId, userId);
    // }*/
    //
    // /*// 获取分布式锁
    // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    // if (!simpleRedisLock.tryLock(60)) {
    // return Result.fail("用户不能重复下单！");
    // }*/
    // RLock lock = redissonClient.getLock("lock:order:" + userId);
    // if (!lock.tryLock()) {
    // return Result.fail("用户不能重复下单！");
    // }
    //
    // try {
    // // 获取代理对象（事务），因为Spring的事务管理是基于代理对象的，这样做可以防止直接调用this.createVoucherOrder
    // // 而导致的事务控制失效（因为该方法没加@Transactional注解，只有createVoucherOrder方法加了）
    // IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    // return proxy.createVoucherOrder(voucherId, userId);
    // } catch (IllegalStateException e) {
    // throw new RuntimeException(e);
    // } finally {
    // // simpleRedisLock.unlock();
    // lock.unlock();
    // }
    // }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("一个用户只能下一次单！");
        }

        // 5.扣减库存 (利用乐观锁 优化版CAS 法来解决并发安全问题——超卖：在扣减时判断库存是否大于0）
        boolean success = seckillVoucherService.update().setSql("stock = stock -1"). // set stock = stock -1
            eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
            .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisGlobalIdGenerator.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 保存到数据库
        save(voucherOrder);
        // 7.返回订单id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("一个用户只能下一次单！");
            return;
        }

        // 扣减库存 (利用乐观锁 优化版CAS 法来解决并发安全问题——超卖：在扣减时判断库存是否大于0）
        boolean success = seckillVoucherService.update().setSql("stock = stock -1"). // set stock = stock -1
            eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
            .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }

        // 保存到数据库
        save(voucherOrder);
    }
}

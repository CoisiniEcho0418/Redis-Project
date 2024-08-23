package com.hmdp.service.impl;

import java.util.Collections;

import javax.annotation.Resource;

import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
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

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.从 Redis查询用户购买资格
        // 获取用户ID
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),
            userId.toString());
        // 根据返回结果进行判断
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "用户不能重复下单！");
        }

        // 2.将优惠券id、用户id、订单id 存入阻塞队列
        long orderId = redisGlobalIdGenerator.nextId("order");
        // TODO

        return Result.ok(orderId);
    }

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
}

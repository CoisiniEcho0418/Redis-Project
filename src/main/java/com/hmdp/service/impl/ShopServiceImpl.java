package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.*;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RandomExpireTimeUtil;
import com.hmdp.utils.RedisData;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        /*// 缓存穿透
        // Shop shop = queryByIdWithPassThrough(id);
        Shop shop = cacheClient.queryByIdWithPassThrough(id, Shop.class, this::getById, CACHE_SHOP_KEY, CACHE_SHOP_TTL,
                TimeUnit.MINUTES);*/

        /*// 互斥锁解决缓存击穿
        Shop shop = queryByIdWithMutex(id);*/

        // 逻辑过期时间解决缓存击穿
        // Shop shop = queryByIdWithLogicalExpire(id);
        Shop shop = cacheClient.queryByIdWithLogicalExpire(id, Shop.class, this::getById, CACHE_SHOP_KEY,
            CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺信息不存在！");
        }
        return Result.ok(shop);
    }

    // 缓存穿透版本（缓存空值）
    public Shop queryByIdWithPassThrough(Long id) {
        // 1.从Redis里查询
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotEmpty(shopCache)) {
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }
        // 判断是否是空值（缓存穿透）
        if (shopCache != null) {
            return null;
        }

        // 2.从数据库里查
        Shop shop = getById(id);
        if (ObjectUtil.isEmpty(shop)) {
            // 缓存空值，应对缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",
                RandomExpireTimeUtil.getRandomExpire(CACHE_NULL_TTL), TimeUnit.MINUTES);
            return null;
        }
        // 3.如果从数据库查到则写回Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
            RandomExpireTimeUtil.getRandomExpire(CACHE_SHOP_TTL), TimeUnit.MINUTES);
        return shop;
    }

    // 缓存击穿（互斥锁）版本
    public Shop queryByIdWithMutex(Long id) {
        // 1.从Redis里查询
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotEmpty(shopCache)) {
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }
        // 判断是否是空值（缓存穿透）
        if (shopCache != null) {
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;

        try {
            // 尝试获取互斥锁
            if (!tryLock(lockKey)) {
                // 获取互斥锁失败，休眠一段时间
                Thread.sleep(50);
                // 递归调用（获取互斥锁成功后还要再查询缓存，double check，如果存在则无需重建缓存）
                return queryByIdWithMutex(id);
            }

            // 获取到互斥锁之后查询数据库
            // 2.从数据库里查
            shop = getById(id);
            // 模拟查询数据库延时
            Thread.sleep(200);
            if (ObjectUtil.isEmpty(shop)) {
                // 缓存空值，应对缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",
                    RandomExpireTimeUtil.getRandomExpire(CACHE_NULL_TTL), TimeUnit.MINUTES);
                return null;
            }
            // 3.如果从数据库查到则写回Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                RandomExpireTimeUtil.getRandomExpire(CACHE_SHOP_TTL), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    // 缓存击穿（逻辑过期时间）版本
    public Shop queryByIdWithLogicalExpire(Long id) {
        // 1.从Redis里查询
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 未命中则直接返回空
        if (StrUtil.isEmpty(shopCache)) {
            return null;
        }

        // 命中则需要先反序列化
        RedisData redisData = JSONUtil.toBean(shopCache, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 判断逻辑缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return shop;
        }
        // 如果过期，则需要重构缓存
        // 先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 开启独立线程去重构缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    savaRedisData(id, 3600L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("Shop id could not be empty!");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return null;
    }

    private boolean tryLock(String key) {
        // 用Redis的setnx来作为互斥锁，并设置过期时间作为兜底（防止获取锁的线程崩溃导致锁一直不被释放）
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
            RandomExpireTimeUtil.getRandomExpire(LOCK_SHOP_TTL), TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 给要缓存的对象封装成带逻辑过期时间的RedisData，并存到Redis中（针对热点key，所以不考虑查不到数据的情况）
    private void savaRedisData(Long id, Long seconds) {
        Shop shop = getById(id);
        String key = CACHE_SHOP_KEY + id;
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
}

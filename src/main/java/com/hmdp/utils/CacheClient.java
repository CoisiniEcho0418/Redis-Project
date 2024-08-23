package com.hmdp.utils;

import static com.hmdp.utils.RedisConstants.*;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author hwj
 * @create: 2024-08-19 1:06
 * @Description:
 */
@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // TODO 修改线程池用法
    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(Object data, String key, Long time, TimeUnit unit) {
        String jsonStr = JSONUtil.toJsonStr(data);
        stringRedisTemplate.opsForValue().set(key, jsonStr, RandomExpireTimeUtil.getRandomExpire(time), unit);
    }

    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(Object data, String key, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(data);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
    }

    // TODO 补上JMeter测试
    // 缓存穿透版本（缓存空值）
    public <R, ID> R queryByIdWithPassThrough(ID id, Class<R> type, Function<ID, R> dbGetByIdFunc, String cachePrefix,
        Long time, TimeUnit unit) {
        String key = cachePrefix + id;
        // 1.从Redis里查询
        String shopCache = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotEmpty(shopCache)) {
            return JSONUtil.toBean(shopCache, type);
        }
        // 判断是否是空值（缓存穿透）
        if (shopCache != null) {
            return null;
        }

        // 2.从数据库里查
        R r = dbGetByIdFunc.apply(id);
        if (ObjectUtil.isEmpty(r)) {
            // 缓存空值，应对缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RandomExpireTimeUtil.getRandomExpire(CACHE_NULL_TTL),
                TimeUnit.MINUTES);
            return null;
        }
        // 3.如果从数据库查到则写回Redis
        /*stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r),
            RandomExpireTimeUtil.getRandomExpire(valueTtl), valueUnit);*/
        set(r, key, time, unit);
        return r;
    }

    // 缓存击穿（逻辑过期时间）版本
    public <R, ID> R queryByIdWithLogicalExpire(ID id, Class<R> type, Function<ID, R> dbGetByIdFunc, String cachePrefix,
        Long time, TimeUnit unit) {
        String key = cachePrefix + id;
        // 1.从Redis里查询
        String cache = stringRedisTemplate.opsForValue().get(key);
        // 未命中则直接返回空
        if (StrUtil.isEmpty(cache)) {
            return null;
        }

        // 命中则需要先反序列化
        RedisData redisData = JSONUtil.toBean(cache, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 判断逻辑缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }
        // 如果过期，则需要重构缓存
        // 先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 开启独立线程去重构缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 1.查询数据库
                    R result = dbGetByIdFunc.apply(id);
                    // 2.写回Redis
                    setWithLogicalExpire(result, key, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(key);
                }
            });
        }
        return r;
    }

    // 获取互斥锁
    private boolean tryLock(String key) {
        // 用Redis的setnx来作为互斥锁，并设置过期时间作为兜底（防止获取锁的线程崩溃导致锁一直不被释放）
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
            RandomExpireTimeUtil.getRandomExpire(LOCK_SHOP_TTL), TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}

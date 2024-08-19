package com.hmdp.utils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    public void set(Object data, String key, Long time, TimeUnit unit) {
        String jsonStr = JSONUtil.toJsonStr(data);
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, unit);
    }

    public void setWithLogicalExpire(Object data, String key, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(data);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
    }

    // TODO 两个查询方法有待实现，（实现完之后还要替换掉原来impl中的代码块，并且补上JMeter测试）
}

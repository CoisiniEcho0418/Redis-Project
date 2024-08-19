package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * @author hwj
 * @create: 2024-08-19 22:01
 * @Description:
 */
@Component
public class RedisGlobalIdGenerator {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1704067200L;
    // 序列号位数
    private static final int COUNT_BITS = 32;

    /**
     * 
     * @param keyPrefix 业务key前缀
     * @return 返回全局ID，格式（long，8字节，64位，第一位符号位为0，接着31位为时间戳，后32位为序列号）
     */
    public long nextId(String keyPrefix) {
        // 1.获取时间戳（）
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSeconds - BEGIN_TIMESTAMP;

        // 2.获取序列号
        // 获取当前日期
        String nowFormat = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 组装redis key并获取redis自增value的值
        String key = "icr:" + keyPrefix + ":" + nowFormat;
        long increment = stringRedisTemplate.opsForValue().increment(key);
        return timeStamp << COUNT_BITS | increment;
    }

}

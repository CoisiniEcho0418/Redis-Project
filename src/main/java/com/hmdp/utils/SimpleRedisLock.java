package com.hmdp.utils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import cn.hutool.core.lang.UUID;

/**
 * @author hwj
 * @create: 2024-08-23 17:25
 * @Description:
 */
public class SimpleRedisLock implements ILock {

    private String keyName;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String keyName, StringRedisTemplate stringRedisTemplate) {
        this.keyName = keyName;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";

    private static final String VALUE_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 拼接 KEY 前缀形成 REDIS key
        String key = KEY_PREFIX + keyName;
        // 拼接 UUID 和线程 ID 作为 REDIS value
        long threadId = Thread.currentThread().getId();
        String value = VALUE_PREFIX + threadId;
        // SETNX 并设置 expire time 作为兜底
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用 lua 脚本 （通过 lua 脚本实现判断锁标识和删除锁这两个操作的原子性）
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + keyName),
            VALUE_PREFIX + Thread.currentThread().getId());

    }

    /*@Override
    public void unlock() {
        // 获取 REDIS 互斥锁中存的值
        String key = KEY_PREFIX + keyName;
        String value = stringRedisTemplate.opsForValue().get(key);
        // 拼接 UUID 和当前线程 ID 得到期望值
        long id = Thread.currentThread().getId();
        String expectValue = VALUE_PREFIX + id;
        if (expectValue.equals(value)) {
            stringRedisTemplate.delete(key);
        }
    }*/
}

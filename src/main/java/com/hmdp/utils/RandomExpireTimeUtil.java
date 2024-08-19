package com.hmdp.utils;

import cn.hutool.core.util.RandomUtil;

/**
 * @author hwj
 * @create: 2024-08-18 22:49
 * @Description:
 */
public class RandomExpireTimeUtil {
    // 将缓存过期的TTL增加一个随机值以应对缓存雪崩问题
    public static Long getRandomExpire(Long ttl) {
        return ttl + RandomUtil.randomLong(10);
    }
}

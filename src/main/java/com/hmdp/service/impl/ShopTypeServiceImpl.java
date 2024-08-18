package com.hmdp.service.impl;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1.查询缓存
        List<String> shopTypeCacheJsonList =
            stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY, 0, -1);
        if (CollectionUtils.isNotEmpty(shopTypeCacheJsonList)) {
            // 如果存在则进行类型转换并返回
            List<ShopType> shopTypeList = shopTypeCacheJsonList.stream()
                .map(json -> JSON.parseObject(json, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }

        // 2.查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (CollectionUtils.isEmpty(shopTypeList)) {
            return Result.fail("Shop type is not found!");
        }
        // 3.写回Redis并设置过期时间
        shopTypeList.forEach(shopType -> stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOPTYPE_KEY,
            JSONUtil.toJsonStr(shopType)));
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOPTYPE_KEY, RedisConstants.CACHE_SHOPTYPE_TTL,
            TimeUnit.HOURS);
        return Result.ok(shopTypeList);
    }
}

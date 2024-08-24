package com.hmdp.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /*// 关注/取关操作（直接存数据库版）
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前用户信息
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();
    
        if (isFollow) {
            // 关注操作
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            save(follow);
        } else {
            // 取关操作
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.ok();
    }*/

    // 关注/取关操作（存Redis-实现共同关注版）
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前用户信息
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();

        if (isFollow) {
            // 关注操作
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean success = save(follow);
            if (success) {
                String key = "follow:" + userId;
                // 放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关操作 删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean success =
                remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (success) {
                String key = "follow:" + userId;
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 获取当前用户信息
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();

        // 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result findCommonFollows(Long id) {
        // 1.获取当前用户信息
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();

        // 2.求交集
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> commonSet = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (CollectionUtil.isEmpty(commonSet)) {
            return Result.ok(Collections.emptyList());
        }

        // 3.根据 id 解析用户信息
        List<Long> ids = commonSet.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.query().in("id", ids).list().stream()
            .map(user1 -> BeanUtil.copyProperties(user1, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}

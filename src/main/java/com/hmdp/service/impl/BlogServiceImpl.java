package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (ObjectUtil.isEmpty(userDTO)) {
            // 用户未登录，不做处理
            return Result.fail("用户未登录！");
        }
        Long userId = userDTO.getId();
        String key = BLOG_LIKED_KEY + id;
        // 判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) { // 未点赞
            // 修改点赞数量（先改数据库，再存 redis）
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                // 把时间戳当做 score 存入
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            } else {
                return Result.fail("点赞失败！");
            }

        } else { // 已点赞
            // 修改点赞数量（先改数据库，再存 redis）
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            } else {
                return Result.fail("取消点赞失败！");
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 从 redis 中获取点赞 top 5 用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (CollectionUtil.isEmpty(top5)) {
            return Result.ok(Collections.emptyList());
        }

        // 解析用户id，并获取用户信息
        List<Long> userIdList = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        /* 之所以要这样查是因为如果直接使用 listByIds() 来查询的话，Mybatis-Plus 底层调用的是 MySQL 的 in() 查询，此时
        返回的结果不会按照传入的 idList 的顺序返回，因而需要手动在后面指定 order by
        */
        String sql = StrUtil.join(",", userIdList);
        List<UserDTO> userDTOS = userService.query().in("id", userIdList).last("ORDER BY FIELD(id," + sql + ")").list()
            .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            // 用户未登录，不做处理
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();
        blog.setUserId(userId);

        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("保存笔记失败！");
        }

        // 获取作者粉丝列表 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        // 遍历粉丝列表进行推送
        for (Follow follow : follows) {
            Long followUserId = follow.getUserId();
            String key = FEED_KEY + followUserId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogsOfFollow(Long max, Integer offset) {
        // 获取当前用户信息
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            // 用户未登录，不做处理
            return Result.fail("用户未登录！");
        }

        // 查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + user.getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
            stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (CollectionUtil.isEmpty(typedTuples)) {
            return Result.ok();
        }

        // 解析数据：blogId、minTime（时间戳）、offset
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        int os = 1;
        long minTime = 0;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取id
            blogIds.add(Long.valueOf(typedTuple.getValue()));
            // 获取分数(时间戳）
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        /* -- 根据 id查询 blog --
        之所以要这样查是因为如果直接使用 listByIds() 来查询的话，Mybatis-Plus 底层调用的是 MySQL 的 in() 查询，此时
        返回的结果不会按照传入的 idList 的顺序返回，因而需要手动在后面指定 order by
        */
        String sql = StrUtil.join(",", blogIds);
        List<Blog> blogList = query().in("id", blogIds).last("ORDER BY FIELD(id," + sql + ")").list();
        // 补全 blog 相关信息
        for (Blog blog : blogList) {
            // 查询 blog 相关用户数据
            queryBlogUser(blog);
            // 查询当前用户是否点赞
            checkIsLiked(blog);
        }

        // 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        long begin = System.currentTimeMillis();
        // 根据点赞数排序查询
        Page<Blog> page = query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            checkIsLiked(blog);
        });
        long end = System.currentTimeMillis();
        System.out.println("time:" + (end - begin));
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // TODO 优化：把blog数据存到redis里，提升查询速度
        // 查询 blog 数据
        Blog blog = getById(id);
        if (ObjectUtil.isEmpty(blog)) {
            return Result.fail("笔记不存在！");
        }
        // 查询 blog 相关用户数据
        queryBlogUser(blog);
        // 查询当前用户是否点赞
        checkIsLiked(blog);
        return Result.ok(blog);
    }

    private void checkIsLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if (ObjectUtil.isEmpty(userDTO)) {
            // 用户未登录，不做处理
            return;
        }
        Long userId = userDTO.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}

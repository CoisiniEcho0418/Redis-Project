package com.hmdp.controller;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.*;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    // 关注/取关接口
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    // 查询当前用户是否关注
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    // 查询当前用户是否关注
    @GetMapping("/common/{id}")
    public Result findCommonFollows(@PathVariable("id") Long id) {
        return followService.findCommonFollows(id);
    }

}

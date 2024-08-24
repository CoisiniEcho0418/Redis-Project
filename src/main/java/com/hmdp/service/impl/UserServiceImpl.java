package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RandomExpireTimeUtil;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number.");
        }
        // 2.生成验证码
        String code = RandomUtil.randomNumbers(6);

        /*        // 3.保存到session
        session.setAttribute("code",code);*/

        // 3.保存到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,
            RandomExpireTimeUtil.getRandomExpire(LOGIN_CODE_TTL), TimeUnit.MINUTES);
        // 4.发送验证码
        log.debug("验证码发送成功:{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number.");
        }
        // 判断是手机号登录还是密码登录
        if (StrUtil.isNotEmpty(loginForm.getCode())) {
            // 2.校验手机号和验证码
            // String code = (String)session.getAttribute("code");
            String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (!code.equals(loginForm.getCode())) {
                return Result.fail("验证码错误！");
            }
            // 3.根据手机号查询用户是否存在
            User user = query().eq("phone", phone).one();
            if (ObjectUtil.isEmpty(user)) {
                // 用户不存在则创建新用户
                System.out.println("创建新用户");
                user = createWithPhone(phone);
            }

            /*// 4.保存用户信息到session
            session.setAttribute("user",user);*/

            // 4.保存用户信息到Redis（要生成token作为key）
            String token = UUID.randomUUID().toString();
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            String loginToken = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(loginToken, userMap);
            stringRedisTemplate.expire(loginToken, LOGIN_USER_TTL, TimeUnit.MINUTES);
            // 返回token
            return Result.ok(token);
        } else if (StrUtil.isNotEmpty(loginForm.getPassword())) {
            // 根据手机号查询用户是否存在
            User user = query().eq("phone", phone).one();
            if (ObjectUtil.isEmpty(user)) {
                return Result.fail("用户不存在！");
            }
            // 获取密码
            String password = user.getPassword();
            if (!loginForm.getPassword().equals(password)) {
                return Result.fail("密码错误！");
            }

            // 保存用户信息到Redis（要生成token作为key）
            String token = UUID.randomUUID().toString();
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            String loginToken = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(loginToken, userMap);
            stringRedisTemplate.expire(loginToken, LOGIN_USER_TTL, TimeUnit.MINUTES);
            // 返回token
            return Result.ok(token);
        } else {
            return Result.fail("验证码或密码未填！");
        }
    }

    private User createWithPhone(String phone) {
        // 1.创建新用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        // 2.保存用户到数据库
        save(user);
        return user;
    }
}

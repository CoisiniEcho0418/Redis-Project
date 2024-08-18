package com.hmdp.Interceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.util.ObjectUtil;

/**
 * @author hwj
 * @create: 2024-08-17 21:26
 * @Description:
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {
        // 从ThreadLocal中获取token（上一个拦截器会存token到ThreadLocal中）
        UserDTO user = UserHolder.getUser();
        if (ObjectUtil.isEmpty(user)) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}

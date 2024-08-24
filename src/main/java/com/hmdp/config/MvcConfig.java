package com.hmdp.config;

import javax.annotation.Resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.hmdp.Interceptor.LoginInterceptor;
import com.hmdp.Interceptor.RefreshTokenInterceptor;

/**
 * @author hwj
 * @create: 2024-08-17 21:35
 * @Description:
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 默认按照添加顺序执行拦截，也可以设置order（值越大优先级越低，越晚执行拦截）
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        registry.addInterceptor(new LoginInterceptor())
            .excludePathPatterns("/shop/**", "/voucher/**", "/shop-type/**", "/blog/hot", "/user/code", "/user/login")
            .order(1);
    }
}

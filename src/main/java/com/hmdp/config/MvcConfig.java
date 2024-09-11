package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * 配置拦截器，拦截器是MVC中的一种实现，通过registry去注册拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                    "/user/code",
                    "/user/login",
                    "/shop/**",
                    "/shop-type/**",
                    "/blog/hot",
                    "/voucher-order",
                    "/upload/**",
                        "/voucher/list/**"
                ).order(1);
        // RefreshToken拦截器会将所有请求都进行拦截，我们在这个拦截器中去判断是否有token，【有的话】同时延迟token的有效期
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns()
                .order(0);

    }
}

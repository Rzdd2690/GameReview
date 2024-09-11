package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override//前置，开始之前，进行校验
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if(UserHolder.getUser()==null){
        // 2.不存在则进行拦截，拦截就是返回一个false，同时我也可以返回一些信息，如状态码
          response.setStatus(401);
          return false;
      }
        // 有用户，放行
      return true;
    }
}

package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 检验是否登录（ThreadLocal中有没有用户信息）
        if(UserHolder.getUser() == null) {
            // 没有用户信息则返回未登录结果
            response.setStatus(401);
            return false;
        }
        return true;
    }
}

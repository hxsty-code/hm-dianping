package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、获取session（false表示没有session时不创建，避免给匿名请求生成无用的会话）
        HttpSession session = request.getSession(false);
        // 2、从session中获取用户
        Object user = session == null ? null : session.getAttribute("user");
        // 3、判断用户是否存在
        if (user == null) {
            // 4、如果用户不存在，则返回未登录结果
            response.setStatus(401);
            return false;
        }
        // 5、如果用户存在，保存用户到ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        // 6、返回true，表示当前请求可以放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 1、移除用户
        UserHolder.removeUser();
    }
}

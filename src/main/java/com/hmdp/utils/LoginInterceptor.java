package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    // 前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取session 判断是否存在用户
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if(user == null) {
            // 拦截 返回401状态码
            response.setStatus(401);
            return false;
        }
        // 存在 保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        return false;
    }
    //  session 之后拦截
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}

package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器。
 * <p>
 * 只负责判断「是否已登录」：用户信息已由 {@link RefreshTokenInterceptor} 放入 ThreadLocal，
 * 这里取不到就说明没登录，直接返回 401（前端会跳转登录页）。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}

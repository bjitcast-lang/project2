package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 简易管理端鉴权，仅用于本地演示。
 * 生产环境应接入统一的权限系统。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Value("${admin.token:hmdp-admin-token}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String token = request.getHeader("X-Admin-Token");
        if (adminToken.equals(token)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"errorMsg\":\"无管理员权限\"}");
        return false;
    }
}

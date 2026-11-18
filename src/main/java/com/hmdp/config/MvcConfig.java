package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order(0)：拦截所有请求，负责刷新 token 有效期并把用户放入 ThreadLocal
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // order(1)：只负责判断是否需要登录拦截
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**",
                        // 支付/退款回调由第三方渠道调用，不能要求用户登录
                        "/voucher-order/pay/notify",
                        "/voucher-order/refund/notify",
                        // 管理端接口由 AdminInterceptor 单独鉴权
                        "/admin/**"
                )
                .order(1);

        // order(2)：管理端接口校验 X-Admin-Token
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .order(2);
    }
}

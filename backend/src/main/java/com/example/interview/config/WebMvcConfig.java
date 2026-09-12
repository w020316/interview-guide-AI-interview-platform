package com.example.interview.config;

import com.example.interview.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器注册
 * - 限流拦截器作用于所有 /api/** 路径
 * - 排除认证接口本身，避免注册/登录被限流
 * - v1.32.0：排除健康/信息探活端点，避免监控与压测高频探活被误限流（429 导致监控误报）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/health",
                        "/api/info");
    }
}

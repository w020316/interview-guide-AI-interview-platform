package com.example.interview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JSON 请求体大小限制过滤器（P2-11）
 *
 * <p>应用层此前仅对 multipart 上传有 10MB 限制，对 application/json 请求体无任何上限——
 * 匿名可达的 /api/auth/login、/api/auth/register 接受任意大小 JSON，超大请求体在
 * Jackson 反序列化阶段即整体进入堆内存，可被用于内存耗尽攻击。
 *
 * <p>实现：基于 Content-Length 预检（超过上限直接 413，不读取 body）。
 * 已知局限：chunked 传输（无 Content-Length）无法在读取前判断，依赖外层代理
 * （Render Ingress / Nginx client_max_body_size）兜底；本过滤器覆盖常规直连场景。
 * multipart 请求不受本过滤器影响（由 multipart 配置约束）。
 */
@Component
public class JsonBodySizeLimitFilter extends OncePerRequestFilter {

    /** JSON 请求体上限（字节），默认 1MB；0 或负数表示禁用 */
    @Value("${app.security.max-json-body-bytes:1048576}")
    private long maxJsonBodyBytes;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (maxJsonBodyBytes > 0) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                long contentLength = request.getContentLengthLong();
                if (contentLength > maxJsonBodyBytes) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                            "{\"code\":413,\"message\":\"请求体过大，超过 "
                                    + (maxJsonBodyBytes / 1024) + "KB 限制\",\"data\":null}");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}

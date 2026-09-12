package com.example.interview.controller;

import com.example.interview.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthController} MockMvc 集成测试
 *
 * <p>/api/info 是保活 ping 的目标端点，也是公开访问端点，必须稳定。
 * /api/health 为深度体检端点（v1.32.0），供监控与告警使用。
 *
 * <p>说明：{@code @WebMvcTest} 默认会拾取 {@code OncePerRequestFilter} 子类（JwtAuthFilter），
 * 但不会拾取普通 {@code @Component}（JwtUtil），导致依赖注入失败。
 * 这里 mock JwtUtil 即可让 JwtAuthFilter 注入成功，SecurityConfig 也能正常构建。
 * 同时通过 {@code spring.autoconfigure.exclude} 关闭 SecurityAutoConfiguration，
 * 避免触发 Spring Security 默认过滤器链（HealthController 是 permitAll 端点）。
 * JdbcTemplate/RedisTemplate 由 WebMvc 切片不装配，同样以 @MockBean 提供。
 */
@WebMvcTest(controllers = HealthController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("GET /api/info 返回 200 + 系统信息")
    void info_returnsSystemInfo() throws Exception {
        mockMvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.name").value("AI 智能面试辅助平台"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("GET /api/info 响应包含 description 与 docs 字段")
    void info_containsDescriptionAndDocs() throws Exception {
        mockMvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").exists())
                .andExpect(jsonPath("$.data.docs").value("/api/docs"));
    }

    @Test
    @DisplayName("GET /api/health 返回 200 + 各组件状态（数据库/Redis/JVM 均 UP）")
    void health_returnsAllComponentsUp() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.redis.status").value("UP"))
                .andExpect(jsonPath("$.data.jvm.heapUsedMb").exists())
                .andExpect(jsonPath("$.data.uptimeSec").isNumber());
    }

    @Test
    @DisplayName("GET /api/health 数据库异常时该字段降级为 DOWN，接口仍 200")
    void health_databaseDown_stillReturns200() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("DOWN"))
                .andExpect(jsonPath("$.data.redis.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/health Redis 异常时该字段降级为 DOWN，接口仍 200")
    void health_redisDown_stillReturns200() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.redis.status").value("DOWN"));
    }
}

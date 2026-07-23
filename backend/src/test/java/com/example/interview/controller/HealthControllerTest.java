package com.example.interview.controller;

import com.example.interview.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthController} MockMvc 集成测试
 *
 * <p>/api/info 是保活 ping 的目标端点，也是公开访问端点，必须稳定。
 *
 * <p>说明：{@code @WebMvcTest} 默认会拾取 {@code OncePerRequestFilter} 子类（JwtAuthFilter），
 * 但不会拾取普通 {@code @Component}（JwtUtil），导致依赖注入失败。
 * 这里 mock JwtUtil 即可让 JwtAuthFilter 注入成功，SecurityConfig 也能正常构建。
 * 同时通过 {@code spring.autoconfigure.exclude} 关闭 SecurityAutoConfiguration，
 * 避免触发 Spring Security 默认过滤器链（HealthController 是 permitAll 端点）。
 */
@WebMvcTest(controllers = HealthController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

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
}

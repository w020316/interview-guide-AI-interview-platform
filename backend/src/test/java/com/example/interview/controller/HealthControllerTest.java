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

    /** v1.34.1：深度体检新增 RAG 区块，构造依赖这两个 Bean */
    @MockBean
    private com.example.interview.service.RagSearchService ragSearchService;

    @MockBean
    private com.example.interview.service.RagHealthTracker ragHealthTracker;

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
    @DisplayName("GET /api/health 轻量探活：匿名仅返回 status=UP，不探测 DB/Redis、不暴露 JVM（P2-08）")
    void health_lightweightOnlyStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").doesNotExist())
                .andExpect(jsonPath("$.data.redis").doesNotExist())
                .andExpect(jsonPath("$.data.jvm").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/health/detail 返回 200 + 各组件状态（数据库/Redis/JVM 均 UP）")
    void health_returnsAllComponentsUp() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.redis.status").value("UP"))
                .andExpect(jsonPath("$.data.jvm.heapUsedMb").exists())
                .andExpect(jsonPath("$.data.uptimeSec").isNumber());
    }

    @Test
    @DisplayName("GET /api/health/detail 数据库异常时该字段降级为 DOWN，接口仍 200")
    void health_databaseDown_stillReturns200() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("DOWN"))
                .andExpect(jsonPath("$.data.redis.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/health/detail Redis 异常时该字段降级为 DOWN，接口仍 200")
    void health_redisDown_stillReturns200() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));
        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.redis.status").value("DOWN"));
    }

    // ───────── RAG 子系统状态暴露（v1.34.1）─────────
    // 背景：2026-09-21 生产实测——Embedding 失效导致知识库完全不可用（导入返回 503、向量库 0 条），
    // 但 /api/health/detail 恒报 status UP，属「监控全绿、功能全废」的静默降级。
    // 现把播种结果与最近一次向量化失败暴露出来，使该故障无需登录平台即可自诊断。

    @Test
    @DisplayName("GET /api/health/detail 知识库正常时 rag.status=UP 且不计入 degraded")
    void healthDetail_ragHealthy() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(ragSearchService.storedCount()).thenReturn(120);
        when(ragSearchService.maxDocuments()).thenReturn(500);
        when(ragHealthTracker.snapshot(120, 500))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "status", "UP", "seedStatus", "SUCCESS", "documents", 120, "maxDocuments", 500)));

        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rag.status").value("UP"))
                .andExpect(jsonPath("$.data.rag.seedStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rag.documents").value(120))
                .andExpect(jsonPath("$.data.degraded").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/health/detail 知识库不可用时暴露 DEGRADED 与排查提示，且标出 degraded 子系统")
    void healthDetail_ragDegraded() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(ragSearchService.storedCount()).thenReturn(0);
        when(ragSearchService.maxDocuments()).thenReturn(500);
        when(ragHealthTracker.snapshot(0, 500))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "status", "DEGRADED",
                        "seedStatus", "FAILED",
                        "seedDetail", "播种失败：401 Invalid token",
                        "hint", "知识库不可用：请检查 Embedding 配置")));

        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                // 核心依赖仍 UP —— 知识库是可选子系统，不应把整体判为不可用
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.rag.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.rag.seedStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.rag.seedDetail").value("播种失败：401 Invalid token"))
                .andExpect(jsonPath("$.data.rag.hint").exists())
                .andExpect(jsonPath("$.data.degraded[0]").value("rag"));
    }

    @Test
    @DisplayName("GET /api/health/detail 跟踪器未提供快照时 rag.status=UNKNOWN，接口不 500")
    void healthDetail_trackerMissing_reportsUnknown() throws Exception {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        // 不 stub snapshot：Mockito 对 Map 返回类型默认给「空 Map」（而非 null），
        // 正好覆盖「不完整快照」这一边界——控制器必须兜成 UNKNOWN，而不是产出无 status 的区块
        // 并据此误判为 degraded

        mockMvc.perform(get("/api/health/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rag.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.degraded").doesNotExist());
    }
}

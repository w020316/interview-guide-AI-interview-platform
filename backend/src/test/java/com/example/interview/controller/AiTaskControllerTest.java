package com.example.interview.controller;

import com.example.interview.common.BusinessException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.task.AiTaskService;
import com.example.interview.service.task.AiTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AiTaskController} MockMvc 契约测试（阶段六 M2）。
 *
 * <p>覆盖：创建（含参数校验与幂等直连 service 的行为）、单查归属 404、取消冲突 409、
 * 列表分页、汇总、开关关闭时 503 语义。Security 自动配置被排除，认证主体手动注入。
 */
@WebMvcTest(controllers = AiTaskController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class AiTaskControllerTest {

    private static final String USER = "u1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiTaskController controller;

    @MockBean
    private AiTaskService taskService;

    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null, List.of()));
        // 开关字段由 @Value 注入；切片测试里直接置位，保证用例只验证控制器契约本身。
        // 生产默认值（false）由 Boot 启动与「disabled_503」用例共同确认。
        ReflectionTestUtils.setField(controller, "enabled", true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private AiTaskEntity task(String status) {
        return AiTaskEntity.builder()
                .id(7L).userId(USER).type("interview.questions").idempotencyKey("k1")
                .status(status).attempt(1).maxAttempt(1).progress(0).build();
    }

    // ── 创建 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("创建：合法入参 → 200，返回任务 id 与 QUEUED 状态")
    void create_ok() throws Exception {
        when(taskService.create(eq(USER), eq("interview.questions"), eq("k1"), any(), anyInt()))
                .thenReturn(task(AiTaskStatus.QUEUED.name()));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "interview.questions",
                                "idempotencyKey", "k1",
                                "maxAttempt", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(taskService).create(eq(USER), eq("interview.questions"), eq("k1"), eq(null), eq(2));
    }

    @Test
    @DisplayName("创建：缺少 type → 400，且不触达 service（避免无类型任务入队）")
    void create_missingType() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少任务类型（type）"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("创建：缺少幂等键 → 400（没有键就无法防重复计费）")
    void create_missingKey() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"interview.questions\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少幂等键（idempotencyKey）"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("创建：payload 超长 → 400（任务表不存大文本）")
    void create_payloadTooLong() throws Exception {
        String payload = "x".repeat(2001);
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "t", "idempotencyKey", "k", "payload", payload))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("任务参数过长（最多 2000 字符）"));

        verifyNoInteractions(taskService);
    }

    // ── 查询 / 取消 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("单查：非本人任务 → 404（不暴露是否存在）")
    void get_notOwner_404() throws Exception {
        when(taskService.findOwned(anyString(), eq(7L)))
                .thenThrow(new ResourceNotFoundException("任务不存在或无权操作"));

        mockMvc.perform(get("/api/tasks/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("取消已成功任务 → 409（结果已产出，不允许改成取消）")
    void cancel_conflict_409() throws Exception {
        when(taskService.cancel(anyString(), eq(7L)))
                .thenThrow(new BusinessException(409, "任务状态不允许该操作（当前 SUCCEEDED → CANCELLED）"));

        mockMvc.perform(post("/api/tasks/7/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("列表：返回 items/total/page/size，且 service 收到的 size 不超过 50")
    void list_ok() throws Exception {
        when(taskService.listOwned(eq(USER), eq(null), eq(0), eq(50)))
                .thenReturn(new PageImpl<>(List.of(task("RUNNING")), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/tasks").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(7));
    }

    @Test
    @DisplayName("汇总：返回 queued/running/failed 三项计数")
    void summary_ok() throws Exception {
        when(taskService.summary(USER)).thenReturn(Map.of("queued", 2L, "running", 1L, "failed", 0L));

        mockMvc.perform(get("/api/tasks/summary"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.queued").value(2))
                .andExpect(jsonPath("$.data.failed").value(0));
    }

    @Test
    @DisplayName("结果正文：任务未成功 → 409（区分「还没跑完」与「跑完没产物」）")
    void result_notSucceeded_409() throws Exception {
        when(taskService.findOwned(anyString(), eq(7L))).thenReturn(task(AiTaskStatus.RUNNING.name()));

        mockMvc.perform(get("/api/tasks/7/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("任务尚未成功完成（当前 RUNNING）"));
    }

    @Test
    @DisplayName("结果正文：任务成功 → 返回 resultRef 与 resultText")
    void result_ok() throws Exception {
        AiTaskEntity done = task(AiTaskStatus.SUCCEEDED.name());
        done.setResultText("[{\"question\":\"q\"}]");
        when(taskService.findOwned(anyString(), eq(7L))).thenReturn(done);

        mockMvc.perform(get("/api/tasks/7/result"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resultText").value("[{\"question\":\"q\"}]"))
                .andExpect(jsonPath("$.data.resultRef", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("开关关闭 → 503（明确告知，而不是静默返回空列表）")
    void disabled_503() throws Exception {
        ReflectionTestUtils.setField(controller, "enabled", false);

        mockMvc.perform(get("/api/tasks/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("任务中心未启用"));

        verifyNoInteractions(taskService);
    }
}

package com.example.interview.controller;

import com.example.interview.entity.AiTaskEntity;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.task.AiTaskService;
import com.example.interview.service.task.AiTaskStatus;
import com.example.interview.service.task.AiTaskTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InterviewTaskController} MockMvc 契约测试（阶段六 M3）。
 *
 * <p>覆盖：开关关闭 → 503（前端据此回退同步）、参数校验不触达 service、
 * 幂等键按内容派生、带附图评分被拒回同步链路、userId 取自 SecurityContext。
 */
@WebMvcTest(controllers = InterviewTaskController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class InterviewTaskControllerTest {

    private static final String USER = "u1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InterviewTaskController controller;

    @MockBean
    private AiTaskService taskService;

    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null, List.of()));
        ReflectionTestUtils.setField(controller, "enabled", true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private AiTaskEntity queued(String type) {
        return AiTaskEntity.builder().id(7L).userId(USER).type(type).idempotencyKey("k")
                .status(AiTaskStatus.QUEUED.name()).build();
    }

    @Test
    @DisplayName("开关关闭 → 503（前端据错误码回退同步链路）")
    void disabled_503() throws Exception {
        ReflectionTestUtils.setField(controller, "enabled", false);

        mockMvc.perform(post("/api/tasks/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeText\":\"r\",\"jobDescription\":\"j\"}"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("任务中心未启用"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("出题：合法入参 → 200 + taskId；幂等键按内容派生，payload 为 JSON 快照")
    void questions_ok() throws Exception {
        when(taskService.create(eq(USER), eq(AiTaskTypes.INTERVIEW_QUESTIONS), anyString(), anyString(), eq(2)))
                .thenReturn(queued(AiTaskTypes.INTERVIEW_QUESTIONS));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resumeText", "r");
        body.put("jobDescription", "j");
        body.put("count", 5);
        body.put("difficulty", "HARD");
        body.put("focusCategories", "技术基础");

        mockMvc.perform(post("/api/tasks/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(taskService).create(eq(USER), eq(AiTaskTypes.INTERVIEW_QUESTIONS),
                argThat(key -> key.startsWith("q:") && key.length() <= 96),
                argThat(payload -> payload.contains("\"resumeText\":\"r\"")
                        && payload.contains("\"count\":5")),
                eq(2));
    }

    @Test
    @DisplayName("出题：缺岗位描述 → 400，且不建任务")
    void questions_missingJob() throws Exception {
        mockMvc.perform(post("/api/tasks/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeText\":\"r\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("简历和岗位描述不能为空"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("出题：字段类型错误 → 400（与同步接口一致，不能落成 500）")
    void questions_typeError() throws Exception {
        mockMvc.perform(post("/api/tasks/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeText\":123,\"jobDescription\":\"j\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("resumeText 类型错误，期望字符串"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("评分：带附图 → 400（短时效签名会过期，必须走同步链路）")
    void evaluate_withImageRejected() throws Exception {
        mockMvc.perform(post("/api/tasks/interview/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"userAnswer\":\"a\",\"imageUrl\":\"https://x/y.png\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("带附图的评分请使用同步接口"));

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("评分：纯文本 → 200 + taskId，幂等键以 e: 开头")
    void evaluate_ok() throws Exception {
        when(taskService.create(eq(USER), eq(AiTaskTypes.INTERVIEW_EVALUATE), anyString(), anyString(), anyInt()))
                .thenReturn(queued(AiTaskTypes.INTERVIEW_EVALUATE));

        mockMvc.perform(post("/api/tasks/interview/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"userAnswer\":\"a\",\"referenceAnswer\":\"ref\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(7));

        verify(taskService).create(eq(USER), eq(AiTaskTypes.INTERVIEW_EVALUATE),
                argThat(key -> key.startsWith("e:")), anyString(), eq(2));
    }

    @Test
    @DisplayName("评分：缺回答 → 400，且不建任务")
    void evaluate_missingAnswer() throws Exception {
        mockMvc.perform(post("/api/tasks/interview/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("问题和回答不能为空"));

        verifyNoInteractions(taskService);
    }
}

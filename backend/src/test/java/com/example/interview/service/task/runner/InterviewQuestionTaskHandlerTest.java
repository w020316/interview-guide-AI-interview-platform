package com.example.interview.service.task.runner;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.service.InterviewService;
import com.example.interview.service.task.AiTaskOutcome;
import com.example.interview.service.task.AiTaskTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewQuestionTaskHandler} 单元测试（阶段六 M3）。
 *
 * <p>重点：入参从数据库里的 payload 解析（不能假设接口层已校验）、数量夹取、
 * 空响应判失败、以及确实把任务归属用户传给了 AI 链路（缓存与 RAG 隔离依赖它）。
 */
@ExtendWith(MockitoExtension.class)
class InterviewQuestionTaskHandlerTest {

    @Mock
    private InterviewService interviewService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterviewQuestionTaskHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 必须在 mock 注入之后构造：字段初始化器在 MockitoExtension 注入前执行，拿到的是 null
        handler = new InterviewQuestionTaskHandler(interviewService, objectMapper);
    }

    private AiTaskEntity task(String payload) {
        return AiTaskEntity.builder().id(1L).userId("u1")
                .type(AiTaskTypes.INTERVIEW_QUESTIONS).idempotencyKey("k").payload(payload).build();
    }

    private String payload(String resume, String jd, int count, String difficulty, String focus) {
        return String.format("{\"resumeText\":\"%s\",\"jobDescription\":\"%s\",\"count\":%d,"
                + "\"difficulty\":\"%s\",\"focusCategories\":\"%s\"}", resume, jd, count, difficulty, focus);
    }

    @Test
    @DisplayName("合法 payload → 透传全部参数给 AI 链路，并返回结果正文")
    void execute_ok() {
        when(interviewService.generateQuestions(eq("u1"), eq("resume"), eq("jd"), eq(5), eq("HARD"), eq("技术基础"), eq(false)))
                .thenReturn("[{\"question\":\"q\"}]");

        AiTaskOutcome outcome = handler.execute(task(payload("resume", "jd", 5, "HARD", "技术基础")));

        assertThat(outcome.resultText()).isEqualTo("[{\"question\":\"q\"}]");
        assertThat(outcome.resultRef()).isNull();
    }

    @Test
    @DisplayName("P1-3：payload 里 regenerate=true → 透传给 AI 链路，不能命中旧缓存")
    void execute_regenerate_forwarded() {
        when(interviewService.generateQuestions(eq("u1"), eq("r"), eq("j"), eq(5), eq(""), eq(""), eq(true)))
                .thenReturn("[]");

        handler.execute(task("{\"resumeText\":\"r\",\"jobDescription\":\"j\",\"count\":5,\"regenerate\":true}"));

        verify(interviewService).generateQuestions(eq("u1"), eq("r"), eq("j"), eq(5), eq(""), eq(""), eq(true));
    }

    @Test
    @DisplayName("数量越界 → 夹到 1–20（与同步接口一致，而不是直接拒绝）")
    void execute_clampsCount() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyBoolean())).thenReturn("[]");

        handler.execute(task(payload("r", "j", 0, "", "")));
        verify(interviewService).generateQuestions(eq("u1"), eq("r"), eq("j"), eq(1), eq(""), eq(""), eq(false));

        handler.execute(task(payload("r", "j", 99, "", "")));
        verify(interviewService).generateQuestions(eq("u1"), eq("r"), eq("j"), eq(20), eq(""), eq(""), eq(false));
    }

    @Test
    @DisplayName("payload 为空或非法 JSON → 400（不可重试，避免白烧配额）")
    void execute_badPayload() {
        assertThatThrownBy(() -> handler.execute(task(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务参数为空");
        assertThatThrownBy(() -> handler.execute(task("not-json")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式错误");
        verify(interviewService, never()).generateQuestions(anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("缺简历或岗位 → 400")
    void execute_missingFields() {
        assertThatThrownBy(() -> handler.execute(task("{\"jobDescription\":\"jd\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        verify(interviewService, never()).generateQuestions(anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("入参超长 → 400（payload 可能来自通用创建接口，必须在这里再校验）")
    void execute_tooLong() {
        String longText = "x".repeat(InterviewQuestionTaskHandler.MAX_RESUME_LEN + 1);
        assertThatThrownBy(() -> handler.execute(task(payload(longText, "jd", 5, "", ""))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过长");
    }

    @Test
    @DisplayName("AI 返回空白 → 503（可重试；空串不能算成功）")
    void execute_blankResponse() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyBoolean())).thenReturn("   ");

        assertThatThrownBy(() -> handler.execute(task(payload("r", "j", 5, "", ""))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 返回内容为空");
    }
}

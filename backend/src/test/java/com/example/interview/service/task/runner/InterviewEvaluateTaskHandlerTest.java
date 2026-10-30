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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewEvaluateTaskHandler} 单元测试（阶段六 M3）。
 *
 * <p>重点：纯文本评分（imageUrl 必须传 null，附图请求在接口层就被挡回同步链路）、
 * 入参二次校验、空响应判失败。
 */
@ExtendWith(MockitoExtension.class)
class InterviewEvaluateTaskHandlerTest {

    @Mock
    private InterviewService interviewService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterviewEvaluateTaskHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 必须在 mock 注入之后构造，见 QuestionTaskHandlerTest 同名说明
        handler = new InterviewEvaluateTaskHandler(interviewService, objectMapper);
    }

    private AiTaskEntity task(String payload) {
        return AiTaskEntity.builder().id(1L).userId("u1")
                .type(AiTaskTypes.INTERVIEW_EVALUATE).idempotencyKey("k").payload(payload).build();
    }

    @Test
    @DisplayName("合法 payload → 纯文本评分（imageUrl 固定为 null），返回结果正文")
    void execute_ok() {
        when(interviewService.evaluateAnswerWithImage(eq("q"), eq("a"), eq("ref"), isNull()))
                .thenReturn("{\"overallScore\":80}");

        AiTaskOutcome outcome = handler.execute(
                task("{\"question\":\"q\",\"userAnswer\":\"a\",\"referenceAnswer\":\"ref\"}"));

        assertThat(outcome.resultText()).isEqualTo("{\"overallScore\":80}");
        verify(interviewService).evaluateAnswerWithImage(eq("q"), eq("a"), eq("ref"), isNull());
    }

    @Test
    @DisplayName("缺少参考答案 → 按空串处理，不影响评分")
    void execute_missingReference() {
        when(interviewService.evaluateAnswerWithImage(anyString(), anyString(), eq(""), isNull()))
                .thenReturn("{}");

        handler.execute(task("{\"question\":\"q\",\"userAnswer\":\"a\"}"));

        verify(interviewService).evaluateAnswerWithImage(eq("q"), eq("a"), eq(""), isNull());
    }

    @Test
    @DisplayName("payload 为空 / 非法 JSON / 缺字段 → 400")
    void execute_badPayload() {
        assertThatThrownBy(() -> handler.execute(task(null)))
                .hasMessageContaining("任务参数为空");
        assertThatThrownBy(() -> handler.execute(task("not-json")))
                .hasMessageContaining("格式错误");
        assertThatThrownBy(() -> handler.execute(task("{\"question\":\"q\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        verify(interviewService, never()).evaluateAnswerWithImage(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("回答超长 → 400")
    void execute_tooLong() {
        String answer = "x".repeat(InterviewEvaluateTaskHandler.MAX_ANSWER_LEN + 1);
        assertThatThrownBy(() -> handler.execute(
                task("{\"question\":\"q\",\"userAnswer\":\"" + answer + "\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过长");
    }

    @Test
    @DisplayName("AI 返回空白 → 503（可重试）")
    void execute_blankResponse() {
        when(interviewService.evaluateAnswerWithImage(anyString(), anyString(), anyString(), isNull()))
                .thenReturn("");

        assertThatThrownBy(() -> handler.execute(
                task("{\"question\":\"q\",\"userAnswer\":\"a\",\"referenceAnswer\":\"r\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 返回内容为空");
    }
}

package com.example.interview.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link JobAnalysisService} 单元测试
 *
 * <p>覆盖三大公开方法 + 共享内部模板 callAi/callAiRaw：
 * <ul>
 *   <li>{@code analyzeJobDescription}：JD 分析（走 callAi，含 JSON 修复 + 兜底）</li>
 *   <li>{@code diagnoseGap}：差距诊断（走 callAi）</li>
 *   <li>{@code generateLetter}：求职信生成（走 callAiRaw，含 Markdown 剥离 + type 分支）</li>
 * </ul>
 *
 * <p>关键测试点：
 * <ul>
 *   <li>callAi：AI 返回非 JSON 时 JsonRepairUtil 修复失败，返回 {"error":...} 兜底</li>
 *   <li>callAiRaw：AI 空响应抛 IllegalStateException + finally 埋点</li>
 *   <li>generateLetter：type=email/referral/coverLetter 三分支 + Markdown 代码块剥离</li>
 *   <li>文本截断：JD/简历超 1200 字截断</li>
 *   <li>埋点：jobAnalysisCounter.increment + aiCallTimer.record 覆盖三方法</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobAnalysisService 单元测试")
class JobAnalysisServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec chatClientRequestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private Counter jobAnalysisCounter;
    @Mock private Timer aiCallTimer;

    @InjectMocks
    private JobAnalysisService service;

    private static final String VALID_JSON = "{\"jobTitle\":\"Java 后端\",\"summary\":\"岗位概述\"}";
    private static final String JD = "负责 Java 后端开发，熟悉 Spring Boot 和微服务";
    private static final String RESUME = "三年 Java 后端经验，参与过电商微服务项目";

    @BeforeEach
    void setUp() {
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(VALID_JSON);
    }

    @Nested
    @DisplayName("analyzeJobDescription: JD 岗位分析")
    class AnalyzeJobDescription {

        @Test
        @DisplayName("正常调用返回修复后 JSON")
        void analyze_validInput_returnsRepairedJson() {
            String result = service.analyzeJobDescription(JD);

            assertThat(result).isEqualTo(VALID_JSON);
            verify(chatClient).prompt();
            verify(jobAnalysisCounter).increment();
            verify(aiCallTimer).record(anyLong(), any());
        }

        @Test
        @DisplayName("AI 返回空内容时抛 IllegalStateException")
        void analyze_aiEmptyResponse_throwsException() {
            when(callResponseSpec.content()).thenReturn("");

            assertThatThrownBy(() -> service.analyzeJobDescription(JD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AI 返回内容为空");
            verify(jobAnalysisCounter).increment();
        }

        @Test
        @DisplayName("AI 返回非 JSON 时 callAi 修复失败，返回 error 兜底 JSON")
        void analyze_aiReturnsNonJson_returnsErrorFallback() {
            when(callResponseSpec.content()).thenReturn("这不是一段 JSON 内容");

            String result = service.analyzeJobDescription(JD);

            assertThat(result).contains("\"error\"");
            assertThat(result).contains("AI 返回内容无法解析");
        }

        @Test
        @DisplayName("JD 超长时截断到 1200 字")
        void analyze_longJd_truncatedInPrompt() {
            String longJd = "X".repeat(1500);

            service.analyzeJobDescription(longJd);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatClientRequestSpec).user(promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("...");
        }
    }

    @Nested
    @DisplayName("diagnoseGap: 差距诊断")
    class DiagnoseGap {

        @Test
        @DisplayName("正常调用返回修复后 JSON")
        void gap_validInput_returnsRepairedJson() {
            String gapJson = "{\"overallMatchScore\":80,\"items\":[]}";
            when(callResponseSpec.content()).thenReturn(gapJson);

            String result = service.diagnoseGap(RESUME, JD);

            assertThat(result).isEqualTo(gapJson);
            verify(chatClient).prompt();
            verify(jobAnalysisCounter).increment();
        }

        @Test
        @DisplayName("简历和 JD 均超长时截断")
        void gap_longInputs_truncatedInPrompt() {
            String longResume = "R".repeat(1500);
            String longJd = "J".repeat(1500);

            service.diagnoseGap(longResume, longJd);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatClientRequestSpec).user(promptCaptor.capture());
            // 截断标记应出现至少两次（简历 + JD）
            String prompt = promptCaptor.getValue();
            long truncationCount = prompt.chars().filter(c -> c == '.').count();
            assertThat(truncationCount).isGreaterThanOrEqualTo(6); // "..." 出现两次 = 6 个点
        }
    }

    @Nested
    @DisplayName("generateLetter: 求职信生成")
    class GenerateLetter {

        @Test
        @DisplayName("type=email 生成申请邮件")
        void letter_emailType_returnsContent() {
            String letter = "主题：应聘 Java 后端\n正文：您好...";
            when(callResponseSpec.content()).thenReturn(letter);

            String result = service.generateLetter(RESUME, JD, "email");

            assertThat(result).isEqualTo(letter);
            verify(chatClient).prompt();
            verify(jobAnalysisCounter).increment();
        }

        @Test
        @DisplayName("type=referral 生成内推私信")
        void letter_referralType_returnsContent() {
            String letter = "嗨，看到你们在招 Java 后端...";
            when(callResponseSpec.content()).thenReturn(letter);

            String result = service.generateLetter(RESUME, JD, "referral");

            assertThat(result).isEqualTo(letter);
        }

        @Test
        @DisplayName("type=coverLetter（默认）生成求职信")
        void letter_coverLetterType_returnsContent() {
            String letter = "尊敬的招聘经理：\n我非常感兴趣...";
            when(callResponseSpec.content()).thenReturn(letter);

            String result = service.generateLetter(RESUME, JD, "coverLetter");

            assertThat(result).isEqualTo(letter);
        }

        @Test
        @DisplayName("type 未知时走默认分支生成 Cover Letter")
        void letter_unknownType_defaultsToCoverLetter() {
            String letter = "尊敬的招聘经理：";
            when(callResponseSpec.content()).thenReturn(letter);

            String result = service.generateLetter(RESUME, JD, "unknown-type");

            assertThat(result).isEqualTo(letter);
        }

        @Test
        @DisplayName("AI 返回 Markdown 代码块包裹时剥离后返回")
        void letter_markdownWrapped_strippedToContent() {
            String raw = "```markdown\n这是求职信内容\n```";
            when(callResponseSpec.content()).thenReturn(raw);

            String result = service.generateLetter(RESUME, JD, "email");

            assertThat(result).isEqualTo("这是求职信内容");
        }

        @Test
        @DisplayName("AI 返回空内容时抛 IllegalStateException")
        void letter_aiEmptyResponse_throwsException() {
            when(callResponseSpec.content()).thenReturn("");

            assertThatThrownBy(() -> service.generateLetter(RESUME, JD, "email"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AI 返回内容为空");
            verify(jobAnalysisCounter).increment();
        }
    }
}

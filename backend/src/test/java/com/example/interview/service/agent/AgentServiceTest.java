package com.example.interview.service.agent;

import com.example.interview.entity.AgentConversationEntity;
import com.example.interview.entity.AgentMessageEntity;
import com.example.interview.repository.AgentConversationRepository;
import com.example.interview.repository.AgentMessageRepository;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.util.SseConcurrencyGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能体编排服务单测（Career Copilot）
 *
 * <p>用 Mockito 桩 ChatClient 同步调用链（prompt() → system()/messages()/user()/options() → call() → content()）
 * 驱动真实 ReAct 循环：工具调用协议、终止条件、8 轮上限收尾、取消路径（P2-15）、异常兜底、
 * 消息落库与会话标题异步生成；仅 mock 外部依赖（ChatClient/Repository/各业务 Service）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("智能体编排服务测试")
class AgentServiceTest {

    private static final String USER_ID = "u1";
    private static final String ACTION_JSON = "{\"action\":\"searchJobs\",\"params\":{\"keyword\":\"Java\"}}";

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private AgentConversationRepository conversationRepository;
    @Mock private AgentMessageRepository messageRepository;
    @Mock private JobAgentService jobAgentService;
    @Mock private InterviewSessionService interviewSessionService;
    @Mock private InterviewEventService interviewEventService;
    @Mock private RagSearchService ragSearchService;
    @Mock private com.example.interview.service.job.WebJobSearcherService webJobSearcherService;
    @Mock private com.example.interview.service.job.JobMatchService jobMatchService;
    @Mock private com.example.interview.service.InterviewService interviewService;

    @InjectMocks
    private AgentService service;

    @BeforeEach
    void setUp() {
        // ChatClient 同步调用链：prompt() → system()/messages()/user()/options() → call() → content()
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        doReturn(requestSpec).when(requestSpec).options(any());
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    /** 等待异步 ReAct 循环结束（Disposable 在 Mono 完成后置为 disposed） */
    private void awaitDone(Disposable disposable) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!disposable.isDisposed() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(disposable.isDisposed()).as("ReAct 循环应在 5s 内完成").isTrue();
    }

    private AgentConversationEntity conv(long id) {
        return AgentConversationEntity.builder().id(id).userId(USER_ID).title("会话").build();
    }

    private AgentMessageEntity msg(long id, String role, String content) {
        return AgentMessageEntity.builder().id(id).conversation(conv(5L)).role(role).content(content).build();
    }

    // ─────────────────────────── streamChat：会话与画像 ───────────────────────────

    @Test
    @DisplayName("streamChat: 新建会话并注入用户画像（含分类掌握度与均分格式化）")
    void streamChat_newConversation_buildsProfile() {
        when(conversationRepository.save(any())).thenAnswer(inv -> {
            AgentConversationEntity c = inv.getArgument(0);
            c.setId(77L);
            return c;
        });
        when(interviewSessionService.questionProfile(eq(USER_ID), anyInt())).thenReturn(Map.of(
                "totalQuestions", 10L, "wrongQuestions", 2L, "averageScore", 72.5,
                "byCategory", List.of(Map.of("category", "Java基础", "avgScore", 70.0))));

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, null, "帮我找岗位", new AtomicBoolean(false));

        assertThat(session.conversation().getId()).isEqualTo(77L);
        assertThat(session.conversation().getTitle()).isEqualTo("帮我找岗位");
        assertThat(session.systemPrompt())
                .contains("Career Copilot").contains("已练习题目").contains("Java基础").contains("70.0分");
        assertThat(session.history()).isEmpty();
        assertThat(session.userMessage()).isEqualTo("帮我找岗位");
        assertThat(session.cancelled()).isFalse();
    }

    @Test
    @DisplayName("streamChat: 超长首条消息标题截断为 30 字")
    void streamChat_longMessage_titleTruncated() {
        when(conversationRepository.save(any())).thenAnswer(inv -> {
            AgentConversationEntity c = inv.getArgument(0);
            c.setId(78L);
            return c;
        });
        String longMessage = "这是一个非常长的求职咨询消息用于验证标题截断逻辑是否生效于三十个字符处。";

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, null, longMessage, new AtomicBoolean(false));

        assertThat(session.conversation().getTitle()).hasSize(30).isEqualTo(longMessage.substring(0, 30));
    }

    @Test
    @DisplayName("streamChat: 既有会话装配最近 12 条历史窗口（倒序还原）")
    void streamChat_existingConversation_loadsHistoryWindow() {
        AgentConversationEntity existing = conv(5L);
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(existing));
        // 模拟 repository 返回的“倒序最新 3 条”： reversal 后恢复时间正序
        List<AgentMessageEntity> latest = new ArrayList<>(List.of(
                msg(3, "ASSISTANT", "  "),
                msg(2, "ASSISTANT", "旧回答"),
                msg(1, "USER", "旧问题")));
        when(messageRepository.findLatestByConversationId(eq(5L), any(Pageable.class))).thenReturn(latest);

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, 5L, "新消息", new AtomicBoolean(false));

        assertThat(session.conversation()).isSameAs(existing);
        assertThat(session.history()).hasSize(3);
        // repository 返回“最新在前”，reverse 后恢复时间正序
        assertThat(session.history().get(0).getId()).isEqualTo(1L);
        assertThat(session.history().get(2).getId()).isEqualTo(3L);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName("streamChat: 他人会话/不存在会话一律新建（防 IDOR）")
    void streamChat_idorOrMissing_createsNewConversation() {
        when(conversationRepository.findById(9L)).thenReturn(Optional.of(
                AgentConversationEntity.builder().id(9L).userId("someoneElse").title("他人的").build()));
        when(conversationRepository.save(any())).thenAnswer(inv -> {
            AgentConversationEntity c = inv.getArgument(0);
            c.setId(80L);
            return c;
        });

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, 9L, "继续对话", new AtomicBoolean(false));

        assertThat(session.conversation().getId()).isEqualTo(80L);
        assertThat(session.conversation().getUserId()).isEqualTo(USER_ID);
        assertThat(session.conversation().getTitle()).isEqualTo("继续对话");
    }

    @Test
    @DisplayName("streamChat: 画像查询异常时降级为“暂无练习数据”")
    void streamChat_profileError_fallsBack() {
        when(conversationRepository.save(any())).thenAnswer(inv -> {
            AgentConversationEntity c = inv.getArgument(0);
            c.setId(81L);
            return c;
        });
        when(interviewSessionService.questionProfile(eq(USER_ID), anyInt())).thenThrow(new RuntimeException("db down"));

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, null, "你好", new AtomicBoolean(false));

        assertThat(session.systemPrompt()).contains("暂无练习数据");
    }

    // ─────────────────────────── runStream：ReAct 循环 ───────────────────────────

    @Test
    @DisplayName("runStream: 直接回答→分块推送→onComplete→USER/ASSISTANT 落库→bump 会话→异步改名")
    void runStream_directAnswer_savesAndRenames() {
        AgentConversationEntity conversation = conv(77L);
        when(conversationRepository.findById(77L)).thenReturn(Optional.of(conversation));
        when(callResponseSpec.content())
                .thenReturn("这是最终回答。\n第二行内容较长一些用来触发分块逻辑产生多个推送块。", "求职规划助手");
        var cancelled = new AtomicBoolean(false);
        var session = new AgentService.AgentStreamSession(conversation, "SYS", List.of(), "你好", cancelled);
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> done.set(true), errors::add);
        awaitDone(d);

        assertThat(done.get()).isTrue();
        assertThat(errors).isEmpty();
        assertThat(String.join("", tokens)).contains("这是最终回答。").contains("第二行");
        // USER 与 ASSISTANT 消息均落库
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "USER".equals(m.getRole()) && "你好".equals(m.getContent())));
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().startsWith("这是最终回答。")));
        // P2-16：会话 updated_at 同步 bump（bump 与异步 rename 共享同一实体，至少落库一次即可）
        verify(conversationRepository, timeout(5000).atLeastOnce()).save(argThat(c ->
                c.getId().equals(77L) && c.getUpdatedAt() != null));
        // 新会话（无历史）→ 异步生成精炼标题并保存（共享实体，两次 save 均可能携带新标题）
        verify(conversationRepository, timeout(5000)).findById(77L);
        verify(conversationRepository, timeout(5000).atLeastOnce())
                .save(argThat(c -> "求职规划助手".equals(c.getTitle())));
    }

    @Test
    @DisplayName("runStream: 工具调用→观察回填→下一轮给最终回答；历史消息进入提示词且不触发改名")
    void runStream_toolCall_thenFinalAnswer() {
        AgentConversationEntity conversation = conv(5L);
        List<AgentMessageEntity> history = List.of(
                msg(1, "USER", "之前的问题"),
                msg(2, "ASSISTANT", "之前的回答"));
        var session = new AgentService.AgentStreamSession(conversation, "SYS", history, "帮我找 Java 岗位", new AtomicBoolean(false));
        when(callResponseSpec.content())
                .thenReturn(ACTION_JSON, "根据检索结果为你推荐以下岗位。");
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(
                        com.example.interview.entity.JobPostingEntity.builder()
                                .title("Java 后端").companyName("腾讯").location("深圳")
                                .salary("25k").degree("本科").build())));
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, tokens::add, () -> done.set(true), err -> {});
        awaitDone(d);

        // 工具真实执行（经 AgentTools.dispatch → jobAgentService.search）
        verify(jobAgentService).search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt());
        // 第 2 轮提示词包含观察结果与历史消息
        org.mockito.ArgumentCaptor<String> userCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec, timeout(3000).times(2)).user(userCap.capture());
        assertThat(userCap.getAllValues().get(1))
                .contains("已执行的工具调用与观察结果").contains("searchJobs").contains("Java 后端");
        // 历史消息装配进 messages()
        org.mockito.ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> msgCap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(requestSpec, timeout(3000).times(2)).messages(msgCap.capture());
        assertThat(msgCap.getValue()).hasSize(2);
        // 最终回答推送并落库；有历史 → 不触发异步改名
        assertThat(done.get()).isTrue();
        assertThat(String.join("", tokens)).contains("根据检索结果为你推荐以下岗位。");
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().contains("根据检索结果")));
        verify(conversationRepository, timeout(3000).times(1)).save(any());
    }

    @Test
    @DisplayName("runStream: 8 轮工具调用耗尽后强制收尾（allowAction=false 携带 options）")
    void runStream_maxRounds_forceFinalCall() {
        List<AgentMessageEntity> history = List.of(msg(1, "USER", "q"));
        var session = new AgentService.AgentStreamSession(conv(5L), "SYS", history, "复杂问题", new AtomicBoolean(false));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(callResponseSpec.content()).thenAnswer(inv ->
                calls.incrementAndGet() <= 8 ? ACTION_JSON : "基于观察结果的收尾回答。");
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        Disposable d = service.runStream(session, t -> {}, () -> {}, err -> {});
        awaitDone(d);

        // 8 轮循环 + 1 次收尾调用
        verify(callResponseSpec, timeout(3000).times(9)).content();
        // 收尾调用温度/长度收窄走 options()
        verify(requestSpec, timeout(3000)).options(any());
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().contains("收尾回答")));
    }

    @Test
    @DisplayName("runStream: 开始前已取消→跳过全部 AI 调用与推送，不落库不回调")
    void runStream_cancelledBeforeLoop_skipsEverything() {
        var cancelled = new AtomicBoolean(true);
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", cancelled);
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> done.set(true), errors::add);
        awaitDone(d);

        assertThat(tokens).isEmpty();
        assertThat(done.get()).isFalse();
        assertThat(errors).isEmpty();
        verify(chatClient, never()).prompt();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("runStream: 推送中途取消→停止推送，已生成部分照常落库，不回调 onComplete")
    void runStream_cancelledMidStream_savesPartial() {
        when(callResponseSpec.content()).thenReturn("很长的回答内容将被切分为多个分块依次推送。".repeat(10));
        var cancelled = new AtomicBoolean(false);
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", cancelled);
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, token -> {
            tokens.add(token);
            cancelled.set(true); // 收到第一个分块后立即取消
        }, () -> done.set(true), err -> {});
        awaitDone(d);

        assertThat(tokens).hasSize(1);
        assertThat(done.get()).isFalse();
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().equals(tokens.get(0))));
    }

    @Test
    @DisplayName("runStream: 模型返回空白→使用兜底文案回复")
    void runStream_blankModelAnswer_fallbackMessage() {
        when(callResponseSpec.content()).thenReturn("   ");
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(msg(1, "USER", "q")), "你好", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, tokens::add, () -> done.set(true), err -> {});
        awaitDone(d);

        assertThat(done.get()).isTrue();
        assertThat(String.join("", tokens)).contains("抱歉，这次没能生成回答");
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().contains("抱歉")));
    }

    @Test
    @DisplayName("runStream: 未知动作 JSON 视为最终回答原样推送")
    void runStream_unknownAction_treatedAsAnswer() {
        when(callResponseSpec.content()).thenReturn("{\"action\":\"noSuchTool\",\"params\":{}}");
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(msg(1, "USER", "q")), "问", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> {}, err -> {});
        awaitDone(d);

        assertThat(String.join("", tokens)).contains("noSuchTool");
        verify(jobAgentService, never()).search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("runStream: 非法 JSON（花括号开头但解析失败）视为最终回答")
    void runStream_malformedJson_treatedAsAnswer() {
        when(callResponseSpec.content()).thenReturn("{not json at all");
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(msg(1, "USER", "q")), "问", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> {}, err -> {});
        awaitDone(d);

        assertThat(String.join("", tokens)).contains("{not json at all");
    }

    @Test
    @DisplayName("runStream: 模型调用异常→onError 兜底文案，不落库")
    void runStream_modelError_onError() {
        when(callResponseSpec.content()).thenThrow(new RuntimeException("gateway 502"));
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        List<String> errors = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, t -> {}, () -> done.set(true), errors::add);
        awaitDone(d);

        assertThat(errors).containsExactly("AI 服务异常，请重试");
        assertThat(done.get()).isFalse();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("runStream: 模型偶发失败自动重试一次后成功")
    void runStream_retryThenSuccess() {
        when(callResponseSpec.content())
                .thenThrow(new RuntimeException("临时抖动"))
                .thenReturn("重试后的回答。");
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, tokens::add, () -> done.set(true), err -> {});
        awaitDone(d);

        assertThat(done.get()).isTrue();
        assertThat(String.join("", tokens)).contains("重试后的回答。");
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().contains("重试后的回答")));
    }

    @Test
    @DisplayName("runStream: 推送回调抛异常→onError 兜底，已推送部分照常落库")
    void runStream_onTokenThrows_savesPartialAndErrors() {
        when(callResponseSpec.content()).thenReturn("很长的回答内容将被切分为多个分块依次推送。".repeat(10));
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, token -> {
            tokens.add(token);
            if (tokens.size() == 1) {
                throw new RuntimeException("客户端断连");
            }
        }, () -> {}, errors::add);
        awaitDone(d);

        assertThat(errors).containsExactly("AI 服务异常，请重试");
        assertThat(tokens).hasSize(1);
        // 已生成的部分照常落库供历史回看
        verify(messageRepository, timeout(3000)).save(argThat(m ->
                "ASSISTANT".equals(m.getRole()) && m.getContent().equals(tokens.get(0))));
    }

    @Test
    @DisplayName("runStream: 闸门类 IllegalStateException 不重试，直接走兜底")
    void runStream_gateIllegalState_noRetry() {
        when(callResponseSpec.content()).thenThrow(new IllegalStateException("AI 并发闸门排队超时"));
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, t -> {}, () -> {}, errors::add);
        awaitDone(d);

        assertThat(errors).containsExactly("AI 服务异常，请重试");
        // 无重试：模型仅被调用一次
        verify(callResponseSpec, times(1)).content();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("runStream: 重试等待期间线程被中断→中断标志复位并抛出首次异常")
    void runStream_interruptDuringRetry() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(callResponseSpec.content()).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            throw new RuntimeException("第一次失败");
        });
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, t -> {}, () -> {}, errors::add);

        // 等模型首次调用发生后中断 worker，使其落进重试 sleep 窗口
        long deadline = System.currentTimeMillis() + 5000;
        while (worker.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100); // 让其进入 Thread.sleep(300) 重试等待
        worker.get().interrupt();
        awaitDone(d);

        assertThat(errors).containsExactly("AI 服务异常，请重试");
        // 被中断后不再重试
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("runStream: action 字段非文本（缺失/数字）视为最终回答")
    void runStream_actionNodeNotTextual_treatedAsAnswer() {
        when(callResponseSpec.content()).thenReturn("{\"foo\":1}");
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(msg(1, "USER", "q")), "问", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> {}, err -> {});
        awaitDone(d);

        assertThat(String.join("", tokens)).contains("{\"foo\":1}");
        verify(jobAgentService, never()).search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("splitChunks: 短行累计冲刷、长行按 60 字切分、缓冲先冲刷再切长行")
    void runStream_splitChunks_mixedLines() {
        // 短行(缓冲4) → 长行(缓冲非空冲刷 + 按60切) → 三个 30 字短行(累计 ≥60 冲刷)
        String answer = "短行。\n" + "长".repeat(130) + "\n"
                + "三".repeat(30) + "\n" + "三".repeat(30) + "\n" + "三".repeat(30);
        when(callResponseSpec.content()).thenReturn(answer);
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(msg(1, "USER", "q")), "问", new AtomicBoolean(false));
        List<String> tokens = new ArrayList<>();

        Disposable d = service.runStream(session, tokens::add, () -> {}, err -> {});
        awaitDone(d);

        // 生产行为：长行按 60 字切分后行尾换行符不保留（切片与后续短行直接相连），内容无丢失
        String expected = "短行。\n" + "长".repeat(130) + "三".repeat(30) + "\n"
                + "三".repeat(30) + "\n" + "三".repeat(30) + "\n";
        assertThat(String.join("", tokens)).isEqualTo(expected);
        assertThat(tokens.size()).isGreaterThan(3);
    }

    @Test
    @DisplayName("runStream: 消息落库异常被吞掉，不影响完成回调")
    void runStream_saveMessagesThrows_stillDone() {
        when(callResponseSpec.content()).thenReturn("正常回答。");
        when(messageRepository.save(any())).thenThrow(new RuntimeException("db down"));
        var session = new AgentService.AgentStreamSession(conv(1L), "SYS", List.of(), "你好", new AtomicBoolean(false));
        AtomicBoolean done = new AtomicBoolean(false);
        List<String> errors = new ArrayList<>();

        Disposable d = service.runStream(session, t -> {}, () -> done.set(true), errors::add);
        awaitDone(d);

        assertThat(done.get()).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("runStream: 会话无 id 时跳过异步改名")
    void runStream_renameSkippedWhenConversationIdNull() throws Exception {
        when(callResponseSpec.content()).thenReturn("回答内容。");
        // 会话未持久化（无 id）→ rename 首行即返回
        var unsaved = AgentConversationEntity.builder().userId(USER_ID).title("会话").build();
        var session = new AgentService.AgentStreamSession(unsaved, "SYS", List.of(), "你好", new AtomicBoolean(false));
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, t -> {}, () -> done.set(true), err -> {});
        awaitDone(d);
        // 给异步 rename 足够时间证明它未执行（无模型调用、无会话查询）
        Thread.sleep(300);

        assertThat(done.get()).isTrue();
        verify(chatClient, times(1)).prompt(); // 仅主循环 1 次，无 rename 调用
        verify(conversationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("rename: 超 20 字标题截断保存")
    void runStream_renameTruncatesLongTitle() {
        AgentConversationEntity conversation = conv(88L);
        when(conversationRepository.findById(88L)).thenReturn(Optional.of(conversation));
        when(callResponseSpec.content()).thenReturn("回答。", "这是一个超过二十个字符的会话标题需要被截断处理");
        var session = new AgentService.AgentStreamSession(conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));

        Disposable d = service.runStream(session, t -> {}, () -> {}, err -> {});
        awaitDone(d);

        verify(conversationRepository, timeout(5000).atLeastOnce()).save(argThat(c ->
                c.getTitle() != null && c.getTitle().length() == 20));
    }

    @Test
    @DisplayName("rename: 模型返回 null/“null”标题时放弃保存")
    void runStream_renameNullTitle_skipsSave() throws Exception {
        AgentConversationEntity conversation = conv(89L);
        when(conversationRepository.findById(89L)).thenReturn(Optional.of(conversation));
        when(callResponseSpec.content()).thenReturn("回答。", "null");
        var session = new AgentService.AgentStreamSession(conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));

        Disposable d = service.runStream(session, t -> {}, () -> {}, err -> {});
        awaitDone(d);
        // 等待 rename 完整执行（第 2 次模型调用已发生）再验证未保存无效标题
        verify(chatClient, timeout(5000).times(2)).prompt();
        Thread.sleep(200);

        verify(conversationRepository, never()).save(argThat(c -> "null".equals(c.getTitle())));
    }

    @Test
    @DisplayName("rename: 标题生成失败静默吞掉（保持默认标题）")
    void runStream_renameFailure_swallowed() throws Exception {
        AgentConversationEntity conversation = conv(90L);
        when(conversationRepository.findById(90L)).thenReturn(Optional.of(conversation));
        when(callResponseSpec.content())
                .thenReturn("回答。")
                .thenThrow(new RuntimeException("rename 调用失败"));
        var session = new AgentService.AgentStreamSession(conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));
        AtomicBoolean done = new AtomicBoolean(false);

        Disposable d = service.runStream(session, t -> {}, () -> done.set(true), err -> {});
        awaitDone(d);
        verify(chatClient, timeout(5000).times(2)).prompt(); // 主循环 + rename 各一次
        Thread.sleep(200);

        assertThat(done.get()).isTrue(); // rename 失败不影响主流程完成
        verify(messageRepository, timeout(3000)).save(argThat(m -> "ASSISTANT".equals(m.getRole())));
    }

    // ─────────────────────────── 会话管理 ───────────────────────────

    @Test
    @DisplayName("listConversations: 按用户查询会话列表")
    void listConversations_returnsList() {
        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).thenReturn(List.of(conv(1L)));
        assertThat(service.listConversations(USER_ID)).hasSize(1);
    }

    @Test
    @DisplayName("listMessages: 本人会话返回消息，他人/不存在会话返回空（防越权）")
    void listMessages_enforcesOwnership() {
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conv(5L)));
        when(messageRepository.findByConversationIdOrderByIdAsc(5L))
                .thenReturn(List.of(msg(1, "USER", "q"), msg(2, "ASSISTANT", "a")));
        assertThat(service.listMessages(USER_ID, 5L)).hasSize(2);

        when(conversationRepository.findById(6L)).thenReturn(Optional.of(
                AgentConversationEntity.builder().id(6L).userId("someoneElse").title("x").build()));
        assertThat(service.listMessages(USER_ID, 6L)).isEmpty();

        when(conversationRepository.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.listMessages(USER_ID, 7L)).isEmpty();
    }

    @Test
    @DisplayName("deleteConversation: 本人会话级联删除消息与会话，他人会话不动")
    void deleteConversation_enforcesOwnership() {
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conv(5L)));
        when(messageRepository.findByConversationIdOrderByIdAsc(5L))
                .thenReturn(List.of(msg(1, "USER", "q"), msg(2, "ASSISTANT", "a")));

        service.deleteConversation(USER_ID, 5L);

        verify(messageRepository).deleteAll(anyList());
        verify(conversationRepository).delete(conv(5L));

        // 他人会话：不做任何删除
        when(conversationRepository.findById(6L)).thenReturn(Optional.of(
                AgentConversationEntity.builder().id(6L).userId("someoneElse").title("x").build()));
        service.deleteConversation(USER_ID, 6L);
        verify(conversationRepository, never()).delete(argThat(c -> c != null && Long.valueOf(6L).equals(c.getId())));
    }

    // ─────────────────────────── SSE 并发与心跳 ───────────────────────────

    @Test
    @DisplayName("tryAcquire/release: 每用户上限 1，释放后可再次获取")
    void sseGuard_acquireAndRelease() {
        assertThat(service.tryAcquire(USER_ID)).isEqualTo(SseConcurrencyGuard.Result.ACQUIRED);
        assertThat(service.tryAcquire(USER_ID)).isEqualTo(SseConcurrencyGuard.Result.USER_LIMIT);
        assertThat(service.tryAcquire("another")).isEqualTo(SseConcurrencyGuard.Result.ACQUIRED);
        service.release(USER_ID);
        assertThat(service.tryAcquire(USER_ID)).isEqualTo(SseConcurrencyGuard.Result.ACQUIRED);
        service.release(USER_ID);
        service.release("another");
    }

    @Test
    @DisplayName("heartbeatExecutor: 返回可用调度器")
    void heartbeatExecutor_available() {
        assertThat(service.heartbeatExecutor()).isNotNull();
        service.heartbeatExecutor().shutdown();
    }

    @Test
    @DisplayName("streamChat: 会话保存返回 null id 时仍正常返回会话（历史为空）")
    @SuppressWarnings("unused")
    void streamChat_unsavedConversation_historyEmpty() {
        // 新建会话但未赋 id（模拟异常场景）→ history 为空分支
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(interviewSessionService.questionProfile(eq(USER_ID), anyInt())).thenReturn(Map.of());

        AgentService.AgentStreamSession session =
                service.streamChat(USER_ID, null, "消息", new AtomicBoolean(false));

        assertThat(session.history()).isEmpty();
        assertThat(session.conversation().getId()).isNull();
    }
}

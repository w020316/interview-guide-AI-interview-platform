package com.example.interview.service.agent;

import com.example.interview.entity.AgentConversationEntity;
import com.example.interview.entity.AgentMessageEntity;
import com.example.interview.repository.AgentConversationRepository;
import com.example.interview.repository.AgentMessageRepository;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.util.PromptSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体编排服务（Career Copilot 核心）
 *
 * 职责：
 * 1. 会话与记忆管理：创建会话、装配最近 12 条历史消息窗口
 * 2. System Prompt 构建：智能体人设 + 工具使用指引 + 用户画像（薄弱分类/成绩预注入，减少工具调用轮次）
 * 3. 工具注册：每请求构造 AgentTools 实例（绑定 userId），交由 Spring AI 内部 ReAct 循环执行
 * 4. 流式输出：SSE 逐 token 推送（start/token/done/error 事件 + 15s 心跳保活）
 * 5. 消息落库：对话完成后保存 USER/ASSISTANT 消息（失败仅记日志，不影响响应）
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 对话记忆窗口：最近 12 条消息（约 6 轮对话） */
    private static final int HISTORY_WINDOW = 12;

    /** 标题截断长度 */
    private static final int TITLE_MAX_LEN = 30;

    private final ChatClient chatClient;
    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final JobAgentService jobAgentService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewEventService interviewEventService;
    private final RagSearchService ragSearchService;

    /** SSE 并发控制（与面试问答相同的令牌机制，信号量保护虚拟线程） */
    private final Semaphore sseSemaphore = new Semaphore(20, true);
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeat = new ScheduledThreadPoolExecutor(1);

    public AgentService(ChatClient chatClient,
                        AgentConversationRepository conversationRepository,
                        AgentMessageRepository messageRepository,
                        JobAgentService jobAgentService,
                        InterviewSessionService interviewSessionService,
                        InterviewEventService interviewEventService,
                        RagSearchService ragSearchService) {
        this.chatClient = chatClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.jobAgentService = jobAgentService;
        this.interviewSessionService = interviewSessionService;
        this.interviewEventService = interviewEventService;
        this.ragSearchService = ragSearchService;
    }

    /**
     * 流式对话
     *
     * @param conversationId 会话 ID，null 则新建会话
     * @param message        用户消息（经 PromptSanitizer 消毒）
     */
    public AgentStreamSession streamChat(String userId, Long conversationId, String message) {
        // 1. 会话归属校验/创建（防 IDOR：会话必须属于当前用户）
        AgentConversationEntity conversation = resolveConversation(userId, conversationId, message);

        // 2. 装配记忆窗口 + 用户画像
        List<AgentMessageEntity> history = conversation.getId() == null ? List.of()
                : loadHistoryWindow(conversation.getId());
        String systemPrompt = buildSystemPrompt(userId);

        return new AgentStreamSession(conversation, systemPrompt, history, message);
    }

    /**
     * 执行流式生成（由 Controller 调用，回调通过 listener 推送 SSE）
     */
    public Disposable runStream(AgentStreamSession session,
                                java.util.function.Consumer<String> onToken,
                                Runnable onComplete,
                                java.util.function.Consumer<String> onError) {
        AgentConversationEntity conversation = session.conversation();
        String safeMessage = PromptSanitizer.sanitize(session.userMessage());

        // 注册当前用户绑定的工具实例
        AgentTools tools = new AgentTools(conversation.getUserId(), jobAgentService,
                interviewSessionService, interviewEventService, ragSearchService);

        try {
            var spec = chatClient.prompt()
                    .system(session.systemPrompt())
                    .messages(toMessages(session.history()))
                    .user(safeMessage)
                    .tools(tools);

            StringBuilder reply = new StringBuilder();
            return spec.stream()
                    .content()
                    .doOnNext(token -> {
                        reply.append(token);
                        onToken.accept(token);
                    })
                    .doOnComplete(() -> {
                        onComplete.run();
                        saveMessages(conversation, session.userMessage(), reply.toString());
                    })
                    .doOnError(e -> {
                        log.warn("智能体流式生成失败：{}", e.getMessage());
                        onError.accept("AI 服务异常，请重试");
                        // 部分内容也落库，保证上下文连续
                        if (!reply.isEmpty()) {
                            saveMessages(conversation, session.userMessage(), reply.toString());
                        }
                    })
                    .subscribe();
        } catch (Exception e) {
            log.warn("智能体启动失败：{}", e.getMessage());
            onError.accept("智能体启动失败：" + e.getMessage());
            return null;
        }
    }

    public boolean tryAcquire() {
        return sseSemaphore.tryAcquire();
    }

    public void release() {
        sseSemaphore.release();
    }

    public ScheduledExecutorService heartbeatExecutor() {
        return heartbeat;
    }

    public ExecutorService sseExecutor() {
        return sseExecutor;
    }

    // ---------- 会话管理 ----------

    public List<AgentConversationEntity> listConversations(String userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public List<AgentMessageEntity> listMessages(String userId, Long conversationId) {
        AgentConversationEntity conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return List.of(); // 防越权：他人会话视为空
        }
        return messageRepository.findByConversationIdOrderByIdAsc(conversationId);
    }

    @Transactional
    public void deleteConversation(String userId, Long conversationId) {
        AgentConversationEntity conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv != null && conv.getUserId().equals(userId)) {
            messageRepository.deleteAll(messageRepository.findByConversationIdOrderByIdAsc(conversationId));
            conversationRepository.delete(conv);
        }
    }

    // ---------- 私有方法 ----------

    /** 校验/创建会话（防 IDOR：非本人会话一律新建，不复用他人 conversationId） */
    private AgentConversationEntity resolveConversation(String userId, Long conversationId, String firstMessage) {
        if (conversationId != null) {
            AgentConversationEntity existing = conversationRepository.findById(conversationId).orElse(null);
            if (existing != null && existing.getUserId().equals(userId)) {
                return existing;
            }
        }
        String title = firstMessage == null ? "新对话" : shorten(firstMessage, TITLE_MAX_LEN);
        return conversationRepository.save(AgentConversationEntity.builder()
                .userId(userId)
                .title(title)
                .build());
    }

    private List<AgentMessageEntity> loadHistoryWindow(Long conversationId) {
        List<AgentMessageEntity> latest = messageRepository.findLatestByConversationId(
                conversationId, org.springframework.data.domain.PageRequest.of(0, HISTORY_WINDOW));
        java.util.Collections.reverse(latest);
        return latest;
    }

    private List<Message> toMessages(List<AgentMessageEntity> history) {
        List<Message> messages = new ArrayList<>();
        for (AgentMessageEntity m : history) {
            if ("USER".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            } else if ("ASSISTANT".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }
        return messages;
    }

    /**
     * System Prompt：人设 + 工具指引 + 用户画像（预注入薄弱分类与成绩，减少工具调用轮次）
     */
    private String buildSystemPrompt(String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「Career Copilot」，AI 智能面试辅助平台的专属求职智能体。你可以：\n");
        sb.append("1. 搜索与推荐岗位（秋招/社招/实习），并给出申请建议与截止日期提醒\n");
        sb.append("2. 检索知识库解答技术面试题\n");
        sb.append("3. 分析用户的面试表现、找出薄弱点并制定复习计划\n");
        sb.append("4. 查看用户的面试日程\n\n");
        sb.append("【工作准则】\n");
        sb.append("- 需要用户数据（岗位/统计/错题/日程/知识点）时主动调用对应工具，不要凭空编造\n");
        sb.append("- 工具返回空结果时如实告知，并给出可操作的建议\n");
        sb.append("- 回答简洁、结构化，岗位推荐用列表并附申请链接；复习计划给出具体行动项\n");
        sb.append("- 使用中文回答\n\n");
        sb.append("【当前用户画像（真实数据，可直接引用）】\n");
        try {
            Map<String, Object> summary = interviewSessionService.questionSummary(userId);
            Object total = summary.getOrDefault("totalQuestions", 0);
            Object wrong = summary.getOrDefault("wrongQuestions", 0);
            Object avg = summary.get("averageScore");
            sb.append("- 已练习题目：").append(total).append(" 道，错题 ").append(wrong)
                    .append(" 道，平均分 ").append(avg instanceof Double ? String.format("%.1f", (Double) avg) : avg).append("\n");
            if (summary.get("byCategory") instanceof List<?> cats && !cats.isEmpty()) {
                sb.append("- 各分类掌握度：");
                int i = 0;
                for (Object o : cats) {
                    if (o instanceof Map<?, ?> m && i < 6) {
                        sb.append(m.get("category")).append(" ").append(m.get("avgScore")).append("分；");
                        i++;
                    }
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("- 暂无练习数据\n");
        }
        return sb.toString();
    }

    private void saveMessages(AgentConversationEntity conversation, String userMessage, String assistantReply) {
        try {
            if (assistantReply == null || assistantReply.isBlank()) {
                return;
            }
            messageRepository.save(AgentMessageEntity.builder()
                    .conversation(conversation).role("USER")
                    .content(userMessage).build());
            messageRepository.save(AgentMessageEntity.builder()
                    .conversation(conversation).role("ASSISTANT")
                    .content(assistantReply).build());
        } catch (Exception e) {
            log.warn("智能体对话落库失败（不影响响应）：{}", e.getMessage());
        }
    }

    private static String shorten(String s, int max) {
        String clean = s.replace("\n", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    /**
     * 一次流式对话的载体（会话 + 系统提示 + 历史 + 用户消息）
     */
    public record AgentStreamSession(AgentConversationEntity conversation,
                                     String systemPrompt,
                                     List<AgentMessageEntity> history,
                                     String userMessage) {
    }
}

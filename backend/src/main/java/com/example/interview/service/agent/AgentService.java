package com.example.interview.service.agent;

import com.example.interview.entity.AgentConversationEntity;
import com.example.interview.entity.AgentMessageEntity;
import com.example.interview.repository.AgentConversationRepository;
import com.example.interview.repository.AgentMessageRepository;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.util.JsonRepairUtil;
import com.example.interview.util.PromptSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体编排服务（Career Copilot 核心）
 *
 * 自研 ReAct 循环（提示词层工具协议）：
 * 因 B.AI 网关不支持 API 级 function calling（带 tools 参数返回 400），
 * 工具以文本协议写入提示词，模型输出 {"action":...,"params":{...}} 动作 JSON
 * （经 JsonRepairUtil 修复解析），本地执行工具后将观察结果回填，最多 6 轮。
 *
 * 职责：
 * 1. 会话与记忆管理：创建会话、装配最近 12 条历史消息窗口
 * 2. System Prompt 构建：智能体人设 + 工具协议 + 用户画像（预注入，减少工具调用轮次）
 * 3. 决策循环：模型决策 → 工具执行 → 观察回填 → 直到给出最终回答
 * 4. 流式输出：最终回答分块经 SSE 推送（start/token/done/error 事件 + 心跳由 Controller 管理）
 * 5. 消息落库：对话完成后保存 USER/ASSISTANT 消息（失败仅记日志，不影响响应）
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 对话记忆窗口：最近 12 条消息（约 6 轮对话） */
    private static final int HISTORY_WINDOW = 12;

    /** ReAct 最大工具调用轮次 */
    private static final int MAX_TOOL_ROUNDS = 6;

    /** 标题截断长度 */
    private static final int TITLE_MAX_LEN = 30;

    private final ChatClient chatClient;
    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final JobAgentService jobAgentService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewEventService interviewEventService;
    private final RagSearchService ragSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** SSE 并发控制（与面试问答相同的令牌机制，信号量保护线程资源） */
    private final Semaphore sseSemaphore = new Semaphore(20, true);
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
     * 流式对话会话准备
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
     * 执行 ReAct 决策循环并分块推送最终回答（异步执行，回调线程安全由 Controller 保证）
     */
    public Disposable runStream(AgentStreamSession session,
                                java.util.function.Consumer<String> onToken,
                                Runnable onComplete,
                                java.util.function.Consumer<String> onError) {
        return Mono.fromRunnable(() -> executeLoop(session, onToken, onComplete, onError))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /** ReAct 主循环 */
    private void executeLoop(AgentStreamSession session,
                             java.util.function.Consumer<String> onToken,
                             Runnable onComplete,
                             java.util.function.Consumer<String> onError) {
        AgentConversationEntity conversation = session.conversation();
        String safeMessage = PromptSanitizer.sanitize(session.userMessage());
        AgentTools tools = new AgentTools(conversation.getUserId(), jobAgentService,
                interviewSessionService, interviewEventService, ragSearchService);

        StringBuilder emitted = new StringBuilder();
        List<String> steps = new ArrayList<>();
        try {
            String finalAnswer = null;
            for (int round = 0; round < MAX_TOOL_ROUNDS && finalAnswer == null; round++) {
                String prompt = buildIterationPrompt(safeMessage, tools, steps);
                String content = callModel(session, prompt, true);
                Action action = parseAction(content, tools);
                if (action != null) {
                    log.info("智能体工具调用 round={}: action={}", round + 1, action.action());
                    String observation = tools.dispatch(action.action(), action.paramsJson());
                    steps.add("调用工具 " + action.action() + "(" + action.paramsJson() + ")\n观察结果：" + observation);
                } else {
                    finalAnswer = content;
                }
            }
            // 轮次耗尽仍未得到最终回答：强制收尾
            if (finalAnswer == null) {
                finalAnswer = callModel(session, buildFinalPrompt(safeMessage, steps), false);
            }
            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = "抱歉，这次没能生成回答，请重试或换个问法。";
            }

            // 分块推送，模拟流式体验
            for (String chunk : splitChunks(finalAnswer)) {
                emitted.append(chunk);
                onToken.accept(chunk);
            }
            onComplete.run();
            saveMessages(conversation, session.userMessage(), finalAnswer);
        } catch (Exception e) {
            log.warn("智能体流式生成失败：{}", e.getMessage());
            onError.accept("AI 服务异常，请重试");
            if (!emitted.isEmpty()) {
                saveMessages(conversation, session.userMessage(), emitted.toString());
            }
        }
    }

    /** 单轮决策调用：allowAction=false 时禁止输出动作 JSON */
    private String callModel(AgentStreamSession session, String prompt, boolean allowAction) {
        var spec = chatClient.prompt()
                .system(session.systemPrompt())
                .messages(toMessages(session.history()))
                .user(prompt);
        if (!allowAction) {
            // 收尾调用：温度更低、限制长度，确保输出面向用户的最终回答
            spec = spec.options(OpenAiChatOptions.builder().temperature(0.4).maxTokens(1500).build());
        }
        String content = spec.call().content();
        return content == null ? "" : content.trim();
    }

    /** 解析模型输出是否为工具动作 JSON（严格：仅当整段内容是 JSON 且 action 匹配注册表） */
    private Action parseAction(String content, AgentTools tools) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return null; // 直接回答
        }
        try {
            String repaired = JsonRepairUtil.repair(trimmed);
            JsonNode node = objectMapper.readTree(repaired);
            JsonNode actionNode = node.get("action");
            if (actionNode == null || !actionNode.isTextual()) {
                return null;
            }
            String action = actionNode.asText();
            if (!tools.all().containsKey(action)) {
                return null; // 未知动作视为回答失败，交给下一轮
            }
            JsonNode paramsNode = node.get("params");
            String paramsJson = paramsNode == null ? "{}" : paramsNode.toString();
            return new Action(action, paramsJson);
        } catch (Exception e) {
            log.warn("动作 JSON 解析失败，视为最终回答：{}", e.getMessage());
            return null;
        }
    }

    private String buildIterationPrompt(String userMessage, AgentTools tools, List<String> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户消息】\n").append(userMessage).append("\n\n");
        if (!steps.isEmpty()) {
            sb.append("【已执行的工具调用与观察结果】\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append("第 ").append(i + 1).append(" 步：\n").append(steps.get(i)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("【可用工具】\n");
        int i = 1;
        for (var spec : tools.all().values()) {
            sb.append(i++).append(". ").append(spec.name()).append(" - ").append(spec.description())
                    .append("\n   参数：").append(spec.paramsDoc()).append("\n");
        }
        sb.append("\n【输出规则（严格遵守）】\n");
        sb.append("- 若需要调用工具：仅输出一行 JSON（标准双引号），格式：{\"action\":\"工具名\",\"params\":{\"参数名\":\"值\"}}\n");
        sb.append("- 若已有足够信息：直接输出面向用户的最终回答（中文、结构化，不要输出 JSON，不要解释你的决策过程）\n");
        if (!steps.isEmpty()) {
            sb.append("- 已有观察结果时优先基于观察结果回答，不要重复调用相同工具\n");
        }
        return sb.toString();
    }

    /** 轮次耗尽后的收尾提示词（禁止再调工具） */
    private String buildFinalPrompt(String userMessage, List<String> steps) {
        StringBuilder sb = new StringBuilder("【用户消息】\n").append(userMessage).append("\n\n");
        sb.append("【已收集的观察结果】\n");
        for (String s : steps) {
            sb.append(s).append("\n");
        }
        sb.append("\n请基于以上信息直接给出最终回答（中文、结构化），不要再调用任何工具。");
        return sb.toString();
    }

    private List<String> splitChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int size = 40;
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return chunks;
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

    /** 取消当前流（Controller 断连时调用）；ReAct 循环本身在 boundedElastic 上原子执行 */
    public void cancel(AtomicBoolean cancelled) {
        cancelled.set(true);
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
     * System Prompt：人设 + 用户画像（预注入薄弱分类与成绩，减少工具调用轮次）
     */
    private String buildSystemPrompt(String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「Career Copilot」，AI 智能面试辅助平台的专属求职智能体。你可以：\n");
        sb.append("1. 搜索与推荐岗位（秋招/社招/实习），并给出申请建议与截止日期提醒\n");
        sb.append("2. 检索知识库解答技术面试题\n");
        sb.append("3. 分析用户的面试表现、找出薄弱点并制定复习计划\n");
        sb.append("4. 查看用户的面试日程\n\n");
        sb.append("【工作准则】\n");
        sb.append("- 需要用户数据（岗位/统计/错题/日程/知识点）时按协议调用工具，不要凭空编造数据\n");
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

    /** 一次流式对话的载体（会话 + 系统提示 + 历史 + 用户消息） */
    public record AgentStreamSession(AgentConversationEntity conversation,
                                     String systemPrompt,
                                     List<AgentMessageEntity> history,
                                     String userMessage) {
    }

    /** 模型决策出的工具动作 */
    record Action(String action, String paramsJson) {
    }
}

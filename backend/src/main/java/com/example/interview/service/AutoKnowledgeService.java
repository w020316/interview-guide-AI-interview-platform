package com.example.interview.service;

import com.example.interview.ai.AiConcurrencyGuard;
import com.example.interview.util.HashUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识自动补充服务（2026-09-20）
 *
 * ── 要解决的问题 ────────────────────────────────────────────────────
 * 本平台是「AI 智能面试辅助平台」，服务**全行业**。但预置知识不可能穷举所有行业：
 * 用户问「化工 HAZOP 怎么分析」「航空配载平衡怎么算」时，向量库检索落空，
 * 回答退化为「无参考资料」。知识库必须能**随着使用自动生长**。
 *
 * ── 工作机制 ────────────────────────────────────────────────────────
 * <pre>
 *   用户提问 → RAG 检索
 *      ├─ 命中（相似度达标）→ 正常基于参考资料作答，不做任何额外动作
 *      └─ 落空（无结果 或 最相似结果距离过大）
 *            → 异步：让 LLM 就该主题生成一条结构化知识
 *            → 写入共享库（shared=true，所有用户可检索）
 *            → 下次任何人问到同类问题即可命中
 * </pre>
 *
 * ── 设计取舍 ────────────────────────────────────────────────────────
 * 1. **异步**：补充知识会额外调用一次 LLM，绝不能阻塞用户当前这次回答，
 *    因此投递到独立线程池，失败仅记日志。
 * 2. **限流**：异常流量（爬虫、压测、恶意构造）会让「每次落空都触发 LLM」变成
 *    成本黑洞，故设置每小时上限，超出后静默跳过。
 * 3. **确定性 ID**：ID 由「分类+标题」派生，同一主题重复补充只覆盖不新增，
 *    避免知识库被同义条目灌满。
 * 4. **不写入用户隐私**：生成的是**该主题的通用知识**，而非用户的原始问答，
 *    因此入库内容不含简历/个人信息，可安全共享给全部用户。
 * 5. **队列有界**：线程池队列满时直接拒绝（不阻塞调用方），保证主流程稳定。
 */
@Service
public class AutoKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(AutoKnowledgeService.class);

    private static final String META_SHARED = "shared";
    private static final String SOURCE_AUTO = "auto-supplement";

    /** 提问过短（如「你好」）没有补充价值；过长多为误粘贴简历 */
    private static final int MIN_QUESTION_LEN = 4;
    private static final int MAX_QUESTION_LEN = 120;

    private final RagSearchService ragSearchService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.auto-supplement-enabled:true}")
    private boolean enabled;

    @Value("${app.rag.auto-supplement-per-hour:30}")
    private int perHourLimit;

    /** 单线程 + 容量 200 的有界队列：串行化 LLM 调用，避免并发放大成本 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "auto-knowledge");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** 本小时已补充条数 */
    private final AtomicInteger hourCount = new AtomicInteger(0);
    /** 当前小时窗口起点（epoch ms），用于跨小时归零 */
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    /** 累计补充成功条数（供管理接口展示） */
    private final AtomicInteger totalSupplemented = new AtomicInteger(0);

    @Autowired
    public AutoKnowledgeService(RagSearchService ragSearchService,
                                ChatClient chatClient,
                                ObjectMapper objectMapper) {
        this.ragSearchService = ragSearchService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试为该问题自动补充知识（异步、尽力而为）。
     *
     * <p>调用方在「检索落空」时触发；本方法立即返回，不抛出任何异常。
     *
     * @param question 用户原始提问（仅用于生成通用知识，不会原样入库）
     */
    public void supplementAsync(String question) {
        if (!enabled) {
            return;
        }
        if (question == null) {
            return;
        }
        String q = question.trim();
        if (q.length() < MIN_QUESTION_LEN || q.length() > MAX_QUESTION_LEN) {
            return;
        }
        if (!acquireQuota()) {
            log.debug("自动补充已达每小时上限 {}，本次跳过", perHourLimit);
            return;
        }
        try {
            executor.execute(() -> doSupplement(q));
        } catch (Exception e) {
            // 队列满（DiscardPolicy 一般不抛，此处兜底）或线程池关闭
            log.debug("自动补充任务投递失败：{}", e.toString());
        }
    }

    /** 限流：跨小时自动重置窗口 */
    private boolean acquireQuota() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= TimeUnit.HOURS.toMillis(1)) {
            if (windowStart.compareAndSet(start, now)) {
                hourCount.set(0);
            }
        }
        return hourCount.incrementAndGet() <= perHourLimit;
    }

    private void doSupplement(String question) {
        try {
            // v1.34.1（P3-6）：改走后台专用许可池。本方法由检索落空时异步触发、无用户在等，
            // 此前与用户前台请求共用 5 个许可，批量补充会占满许可让用户请求排队超时。
            String raw = AiConcurrencyGuard.callBackground(() -> chatClient.prompt()
                    .user(buildPrompt(question))
                    .call()
                    .content());
            if (raw == null || raw.isBlank()) {
                log.debug("自动补充：LLM 返回空内容，跳过。questionFp='{}'", questionFingerprint(question));
                return;
            }
            JsonNode node = parseJson(raw);
            if (node == null) {
                log.debug("自动补充：响应非合法 JSON，跳过。questionFp='{}'", questionFingerprint(question));
                return;
            }
            String category = text(node, "category");
            String title = text(node, "title");
            String content = text(node, "content");
            if (category.isBlank() || title.isBlank() || content.length() < 50) {
                log.debug("自动补充：字段不完整或内容过短，跳过。questionFp='{}'", questionFingerprint(question));
                return;
            }

            Document doc = Document.builder()
                    .id(deterministicId(category, title))
                    .text("【" + category + "】" + title + "\n" + content)
                    .metadata(Map.of(
                            "category", category,
                            "title", title,
                            META_SHARED, "true",
                            "type", "knowledge",
                            "source", SOURCE_AUTO))
                    .build();
            int stored = ragSearchService.addToVectorStore(List.of(doc));
            if (stored > 0) {
                totalSupplemented.incrementAndGet();
                // P3/A-08（2026-09-20）：日志不落用户提问明文——提问可能粘贴含隐私的简历内容，
                // 只记短哈希+长度，既能跨日志关联同一次触发，又不泄漏内容
                log.info("知识库自动补充：新增 [{}] {}（触发提问指纹：{}）",
                        category, title, questionFingerprint(question));
            } else {
                log.debug("自动补充：向量库容量已满，未入库。topic='{}'", title);
            }
        } catch (Exception e) {
            // 自动补充是增强能力，任何失败都不应影响主流程
            log.debug("自动补充失败（已忽略）：{}", e.toString());
        }
    }

    /**
     * 提问指纹（P3/A-08）：日志不落用户原始提问明文——提问可能粘贴含隐私的简历内容。
     * 只保留短哈希 + 长度，既能跨日志关联同一次触发，又不泄漏内容。
     */
    private String questionFingerprint(String question) {
        int len = question == null ? 0 : question.length();
        return HashUtil.sha256Short(question == null ? "" : question) + "/" + len + "字";
    }

    /**
     * 生成「通用知识」而非「本次问答记录」——这决定入库内容可安全共享给所有用户。
     * 明确要求输出纯 JSON，便于程序化解析与去重。
     */
    private String buildPrompt(String question) {
        return "你是知识库编辑，负责为「AI 面试辅助平台」沉淀跨行业面试知识。\n"
                + "用户在知识问答中提出了一个当前知识库没有覆盖的主题。请就该主题整理一条"
                + "**通用、可复用**的知识条目（不要复述提问、不要包含任何个人信息）。\n\n"
                + "【用户提问】\n" + question + "\n\n"
                + "【要求】\n"
                + "1. content 必须自包含且含该行业专业术语，便于后续被向量检索命中；\n"
                + "2. content 长度 200~600 字，聚焦面试高频考点而非百科式泛谈；\n"
                + "3. category 用简洁的行业名（如：化工、航空服务、财务会计、医疗器械）；\n"
                + "4. 只输出 JSON，不要 markdown 代码块，不要任何解释文字。\n\n"
                + "【输出格式】\n"
                + "{\"category\":\"行业分类\",\"title\":\"知识点标题\",\"content\":\"正文内容\"}";
    }

    /** 容错解析：LLM 常在 JSON 外包一层 ```json 代码块 */
    private JsonNode parseJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText().trim();
    }

    /** 由「分类 + 标题」派生稳定 ID：同一主题重复补充只覆盖，不新增 */
    private static String deterministicId(String category, String title) {
        String key = "auto|" + category + "|" + title;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ── 观测（供管理接口）────────────────────────────────────────────
    public boolean isEnabled() {
        return enabled;
    }

    public int totalSupplemented() {
        return totalSupplemented.get();
    }

    public int currentHourCount() {
        return hourCount.get();
    }

    public int perHourLimit() {
        return perHourLimit;
    }
}

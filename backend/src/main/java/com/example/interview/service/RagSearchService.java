package com.example.interview.service;

import com.example.interview.util.LocalPromptCache;
import com.example.interview.util.PromptSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RAG 知识库服务
 * - 向量检索面试八股文（按用户隔离）
 * - 构建带上下文的回答
 * - 通过 ObjectMapper 输出 JSON，避免手工拼接导致的安全/转义问题
 *
 * v1.8 起：所有 search/ask/import 均携带 userId，写入 metadata 并在检索时过滤
 * v1.10 起：去重相似度阈值可配置（app.rag.dedup-similarity-threshold）
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    /** metadata 中用户隔离字段名 */
    private static final String META_USER_ID = "userId";
    /** metadata 中共享知识标记（系统预置数据无 userId，标记为 shared） */
    private static final String META_SHARED = "shared";

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ObjectMapper objectMapper;

    /** v1.14：AI 调用计数器（type=rag），埋点在 answerWithRag */
    @Autowired
    @Qualifier("aiCallRagCounter")
    private Counter ragCounter;

    /** v1.14：AI 响应耗时分布 */
    @Autowired
    private Timer aiCallTimer;

    /**
     * 去重相似度阈值：相似度 >= 此值视为重复文档，跳过导入
     * 通过 app.rag.dedup-similarity-threshold 配置，默认 0.90
     */
    @Value("${app.rag.dedup-similarity-threshold:0.90}")
    private double dedupSimilarityThreshold;

    /**
     * 触发知识自动补充的距离阈值（v1.34.0）。
     *
     * <p>SimpleVectorStore 返回的 {@code distance} 越小越相似（cosine distance，0 为完全相同）。
     * 当检索到的最相似文档距离仍大于此值时，视为「知识库实质未覆盖该主题」，
     * 触发 {@link AutoKnowledgeService} 异步沉淀一条新知识。
     *
     * <p><b>阈值取值依据（2026-09-20 真机实测）</b>：SimpleVectorStore 无论相关与否
     * 都会返回 topK 条结果，因此「有结果」不等于「覆盖」。实测距离分布：
     * <pre>
     *   在库命中（护理三查七对 → 命中同名条目）   bestDistance = 0.32
     *   不在库（医疗器械注册 → 仅命中「门诊诊疗流程」）bestDistance = 0.52
     *   不在库（航空配载 → 仅命中弱相关条目）        bestDistance = 0.52 左右
     * </pre>
     * 取 0.45 作为分界：命中场景（约 0.32）明显低于它，未命中场景（约 0.52）明显高于它，
     * 两侧各留约 0.07~0.13 的余量。
     *
     * <p>⚠️ 此值初始误设为 0.75，导致所有「检索到无关近邻」都被判为「已覆盖」，
     * 自动补充从未触发。**更换 embedding 模型后必须重新标定本阈值** ——
     * 不同模型的余弦距离尺度不同。
     */
    @Value("${app.rag.auto-supplement-max-distance:0.45}")
    private double autoSupplementMaxDistance;

    /**
     * 自动补充服务（延迟解析）。
     *
     * <p>用 {@code ObjectProvider} 而非直接 {@code @Autowired}：本服务被
     * {@code AutoKnowledgeService} 反向依赖（后者要调 {@code addToVectorStore} 入库），
     * 直接注入会形成构造期循环依赖。{@code ObjectProvider} 把解析推迟到首次使用，
     * 既打破环又不引入 {@code @Lazy} 代理的额外语义。
     */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<AutoKnowledgeService> autoKnowledgeProvider;

    /**
     * 向量库最大文档数（生产为内存 SimpleVectorStore，无上限会累积导致 512MB 容器 OOM）
     * 计数器与向量库同生命周期（重启同清零），超限后拒绝新增并提示
     */
    @Value("${app.rag.max-documents:500}")
    private int maxDocuments;

    /**
     * RAG 健康跟踪（v1.34.1）：旁路记录向量化失败原因，供 {@code /api/health/detail} 暴露。
     *
     * <p>声明为 {@code required = false} 并处处空值保护——本类在切片单测中以
     * {@code @InjectMocks} 构造，未声明该 mock 时为 null，不应因此 NPE。
     */
    @Autowired(required = false)
    private RagHealthTracker ragHealthTracker;

    /** 已入库文档计数（与内存向量库同生命周期） */
    private final java.util.concurrent.atomic.AtomicInteger storedDocs = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 检索结果短 TTL 缓存（P2-09）。
     *
     * <p>背景：embedding 跑在**独立部署的 Render 免费实例**上，空闲即被挂起，
     * 首次请求要等它冷启动 —— 实测线上单次语义检索 25.66s（同期其他接口 0.9~2.4s）。
     * 检索结果是确定性的（同一 query + topK + 同一用户可见集合 → 同一结果），
     * 因此对 (userId, topK, query) 做 10 分钟缓存：重复检索直接命中，不再唤醒 embedding。
     *
     * <p>权衡：知识库写入后最长存在 10 分钟陈旧窗口，故导入/删除路径会主动
     * {@link #clearSearchCache()}。{@link #answerWithRag} 含 AI 生成、非确定性，不参与缓存。
     */
    private final LocalPromptCache searchCache = new LocalPromptCache(200);

    /** 检索结果缓存有效期（毫秒） */
    private static final long SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 单批去重检查次数上限：每次检查 = 1 次 embedding 调用，批量大时限流保护（超出部分直接导入） */
    private static final int DEDUP_CHECK_LIMIT = 50;

    /**
     * 同步「已入库文档数」计数（v1.34.0）。
     *
     * <p>背景：{@link #storedDocs} 与内存向量库同生命周期。local profile 改用
     * 文件快照持久化后，重启会从磁盘恢复 N 条文档，但计数器仍是 0 ——
     * 这会让 {@link #maxDocuments} 上限失效，知识库被无界追加直至 OOM。
     * 因此持久化层在 {@code restore()} 之后必须回填真实条数。
     */
    public void syncStoredCount(int actualCount) {
        int safe = Math.max(0, actualCount);
        storedDocs.set(safe);
        log.info("向量库容量计数已同步为 {} 条（上限 {}）", safe, maxDocuments);
    }

    /** 当前已入库文档数（供健康检查/管理接口展示） */
    public int storedCount() {
        return storedDocs.get();
    }

    /** 容量上限（供管理接口展示） */
    public int maxDocuments() {
        return maxDocuments;
    }

    /**
     * 检索相关知识点（返回 JSON 数组字符串）
     * v1.8：按 userId 隔离，仅返回该用户导入的 + 系统预置共享文档
     *
     * @param query  查询文本
     * @param topK   返回条数（1-50）
     * @param userId 当前用户 ID（用于隔离）
     */
    public String search(String query, int topK, String userId) {
        if (query == null || query.isBlank()) {
            return "[]";
        }
        int safeK = Math.max(1, Math.min(topK, 50));

        // P2-09：命中缓存直接返回，避免为一次重复检索唤醒休眠中的 embedding 实例
        String cacheKey = userId + "|" + safeK + "|" + query.trim();
        String cached = searchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            // 过滤条件：userId = 当前用户 OR shared = true
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            SearchRequest.Builder reqBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(safeK)
                    .filterExpression(b.or(
                            b.eq(META_USER_ID, userId),
                            b.eq(META_SHARED, "true")
                    ).build());

            List<Document> docs = vectorStore.similaritySearch(reqBuilder.build());

            if (docs == null || docs.isEmpty()) {
                // 空结果同样是确定性结论，必须缓存：否则查一个知识库未覆盖的词，
                // 每次都要把休眠中的 embedding 实例重新唤醒（P2-09 的代价场景）
                searchCache.put(cacheKey, "[]", SEARCH_CACHE_TTL_MS);
                return "[]";
            }

            List<Map<String, Object>> result = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", doc.getId());
                item.put("content", doc.getText() == null ? "" : doc.getText());
                // v1.34.1 修复（P2-10）：原字段名为 score 但取值实为向量库的 distance
                // （余弦距离，**越小越相似**），前端若按「分越高越好」渲染/排序会得到相反语义。
                // 现补充语义明确的 distance 与 similarity，并保留 score 作为历史契约字段
                //（语义同 distance，不改变既有前端行为）。
                Object rawDistance = doc.getMetadata().get("distance");
                item.put("score", rawDistance == null ? 0.0 : rawDistance);
                if (rawDistance instanceof Number n) {
                    double d = n.doubleValue();
                    item.put("distance", d);                       // 余弦距离：越小越相似
                    item.put("similarity", Math.max(0.0, 1.0 - d)); // 归一化相似度：越大越相似
                } else {
                    item.put("distance", null);
                    item.put("similarity", null);
                }
                result.add(item);
            }
            String json = objectMapper.writeValueAsString(result);
            searchCache.put(cacheKey, json, SEARCH_CACHE_TTL_MS);
            return json;
        } catch (Exception e) {
            // 检索失败不写缓存：异常态（如 embedding 尚未就绪）不应被固化 10 分钟
            log.warn("RAG 检索失败 query='{}'：{}", query, e.getMessage());
            return "[]";
        }
    }

    /**
     * 清空检索缓存（P2-09）：知识库内容变更后调用，消除 10 分钟陈旧窗口。
     */
    public void clearSearchCache() {
        searchCache.clear();
    }

    /**
     * 构建带上下文的回答（按 userId 隔离检索）
     * - 失败时返回兜底提示，避免返回 null 让前端崩
     *
     * @param question 用户问题
     * @param userId   当前用户 ID
     */
    public String answerWithRag(String question, String userId) {
        if (question == null || question.isBlank()) {
            return "问题不能为空";
        }

        long start = System.nanoTime();
        try {
            // 1. 检索相关知识（按 userId 隔离）
            String relatedKnowledge = "";
            // 默认认为「知识库未覆盖」；只有检索到足够相似的文档才置为 false
            boolean topicMissing = true;
            try {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                SearchRequest req = SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .filterExpression(b.or(
                                b.eq(META_USER_ID, userId),
                                b.eq(META_SHARED, "true")
                        ).build())
                        .build();
                List<Document> docs = vectorStore.similaritySearch(req);
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    double bestDistance = Double.MAX_VALUE;
                    for (Document doc : docs) {
                        sb.append("【参考】").append(doc.getText()).append("\n\n");
                        Double d = extractDistance(doc);
                        if (d != null) {
                            bestDistance = Math.min(bestDistance, d);
                        }
                    }
                    relatedKnowledge = sb.toString();
                    // 命中足够相似的文档才算「已覆盖」；否则只是检索到的近邻噪声
                    topicMissing = bestDistance > autoSupplementMaxDistance;
                    if (topicMissing) {
                        log.debug("RAG 命中但相似度不足（bestDistance={} > {}），视为未覆盖该主题",
                                bestDistance, autoSupplementMaxDistance);
                    }
                }
            } catch (Exception e) {
                log.warn("RAG 检索失败 query='{}'：{}", question, e.getMessage());
            }

            // 1.1 知识库未覆盖该主题 → 异步沉淀一条通用知识，下次同类问题即可命中。
            // 全行业覆盖无法靠预置知识穷举，这是知识库随使用自动生长的关键机制。
            // 注意：异步投递，绝不影响本次回答的时延。
            if (topicMissing) {
                try {
                    AutoKnowledgeService svc = autoKnowledgeProvider.getIfAvailable();
                    if (svc != null) {
                        svc.supplementAsync(question);
                    }
                } catch (Exception e) {
                    log.debug("触发知识自动补充失败（已忽略）：{}", e.toString());
                }
            }

            // 2. 构建 RAG Prompt（使用 StringBuilder 避免 String.format 注入风险）
            // v1.34.0：此前写死「你是一个 Java 后端面试助手」，与平台定位冲突 ——
            // 本平台是全行业 AI 面试辅助（财会/法律/医疗/教育/销售/制造等），
            // 写死 IT 语境会让非技术岗问答被强行往编程方向带偏。
            StringBuilder promptBuilder = new StringBuilder()
                    .append("你是一位资深的面试辅导专家，服务范围覆盖**全行业**：")
                    .append("技术研发、产品与设计、金融与财会、法律、医疗与护理、教育与科研、")
                    .append("人力资源、销售与市场、运营与电商、制造与工程、行政与公共服务等。\n")
                    .append("请根据以下参考资料回答问题。\n\n")
                    .append("【参考资料】\n")
                    .append(relatedKnowledge.isEmpty() ? "无" : relatedKnowledge)
                    .append("\n【问题】\n")
                    .append(PromptSanitizer.sanitize(question))
                    .append("\n\n要求：\n")
                    .append("1. 回答要准确、有条理\n")
                    .append("2. 尽量引用参考资料\n")
                    .append("3. 如果资料不足，明确说明\n")
                    .append("4. 先判断提问所属行业与岗位，用该行业的专业术语作答；")
                    .append("不要假设提问者一定来自互联网/IT 行业\n");

            // 3. 调用 AI 并做空值校验（v1.23.1：纳入全局 AI 并发闸门）
            String response = com.example.interview.ai.AiConcurrencyGuard.call(() ->
                    chatClient.prompt()
                            .user(promptBuilder.toString())
                            .call()
                            .content());

            if (response == null || response.isBlank()) {
                log.warn("AI 返回内容为空，question='{}'", question);
                return "AI 暂时无法生成回答，请稍后重试。";
            }

            return response;
        } finally {
            // v1.14：统一埋点
            ragCounter.increment();
            aiCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 统一的向量入库入口（P1-04）：全部写入路径（知识导入/批量导入/简历向量化）必须经此方法，
     * 受 maxDocuments 容量计数保护。生产为全内存 SimpleVectorStore（-Xmx220m），
     * 任何绕过计数的 add 都会造成无界内存增长直至 OOM。
     *
     * @return 实际入库条数（容量不足时部分入库；容量耗尽返回 0）
     */
    public int addToVectorStore(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int remaining = maxDocuments - storedDocs.get();
        if (remaining <= 0) {
            log.warn("向量入库被拒绝：已达容量上限 {} 条", maxDocuments);
            return 0;
        }
        List<Document> accepted = docs.size() <= remaining ? docs : new ArrayList<>(docs.subList(0, remaining));
        // v1.34.1：记录向量化故障供 /api/health/detail 暴露。
        // 入库失败最常见的原因是 Embedding 服务不可用（Key 缺失/失效/欠费/维度不匹配），
        // 而调用方（如批量导入）会把它转成 503，日志在平台上不易翻查；
        // 旁路记录最近一次失败原因后，监控侧无需登录平台即可定位。
        try {
            vectorStore.add(accepted);
        } catch (RuntimeException e) {
            if (ragHealthTracker != null) {
                ragHealthTracker.markFailure("向量化入库失败：" + e.getMessage());
            }
            throw e;
        }
        if (ragHealthTracker != null) {
            // 一次成功入库即清除历史失败，避免旧故障长期驻留造成误判
            ragHealthTracker.clearFailure();
        }
        storedDocs.addAndGet(accepted.size());
        if (accepted.size() < docs.size()) {
            log.warn("向量库容量不足：请求 {} 条，仅入库 {} 条（上限 {}）", docs.size(), accepted.size(), maxDocuments);
        }
        return accepted.size();
    }

    /**
     * 统一的向量删除入口（v1.34.1，P2-6）：与 {@link #addToVectorStore} 配对，
     * 删除时同步递减容量计数。
     *
     * <p><b>背景</b>：简历向量化走「先删后加」的 upsert 覆盖，此前直接调用
     * {@code vectorStore.delete}，绕过了 {@link #storedDocs} 计数；而计数只有 {@code set}
     * （启动恢复）与 {@code addAndGet}（新增）两处写入，**没有任何递减路径** ——
     * 反复分析同一份简历会让计数单调虚高，容量检查 {@code maxDocuments - storedDocs}
     * 越来越保守，最终出现「向量库实际未满却拒绝导入知识」。
     *
     * @return 实际请求删除的条数（删除失败返回 0 且不改计数）
     */
    public int removeFromVectorStore(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            // 删除失败则计数不动，保持与真实库一致（宁可偏保守，不可偏乐观）
            log.warn("向量删除失败（容量计数保持不变）：{}", e.getMessage());
            return 0;
        }
        // 递减并钳到 0：重复删除或与启动恢复竞争时，计数可能被压到真实值以下
        storedDocs.updateAndGet(cur -> Math.max(0, cur - ids.size()));
        return ids.size();
    }

    /**
     * 导入知识文档到向量库（绑定 userId）
     * - 空列表直接返回，避免无意义调用
     * - 去重预检：对每个文档做相似度搜索，相似度 >= {@link #DEDUP_SIMILARITY_THRESHOLD} 视为重复，跳过
     * - 异常时仅记录日志，不抛出，避免影响批量导入主流程
     *
     * @param documents 文档文本列表
     * @param userId    当前用户 ID（写入 metadata 实现隔离）
     * @return 实际导入的文档数量（重复跳过的不计入）
     */
    public int importKnowledge(List<String> documents, String userId) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        // P2-09：知识库内容即将变化，清掉检索缓存，避免新导入的知识 10 分钟内检索不到
        clearSearchCache();
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<Document> docs = new ArrayList<>();
            int skipped = 0;
            int skippedExact = 0;
            int dedupChecks = 0;
            // v1.34.1 改进（P2-11）：先用哈希集合剔除**批次内精确重复**，再做相似度预检。
            // 此前 DEDUP_CHECK_LIMIT=50 的语义是「每批只对前 50 条做相似度检查，其余直接入库」，
            // 若前 50 条里有大量完全相同的文本，会白白消耗 50 次 embedding 配额
            //（每次 isDuplicate = 1 次 embedding），而真正的重复条目却因超出上限被放行入库。
            // 现精确重复零成本拦截，有限的相似度预算全部用于互不相同的新文本。
            java.util.Set<String> seenInBatch = new java.util.HashSet<>();
            for (String text : documents) {
                if (text == null || text.isBlank()) continue;
                if (!seenInBatch.add(text.trim())) {
                    skippedExact++;
                    continue;
                }
                // 去重预检：搜索该用户已有文档中是否存在高度相似的（每批最多 50 次，超出直接导入）。
                // P2-12：计数移入条件内——重复路径也消耗一次 embedding 调用，必须占用检查配额，
                // 否则重复率高的批次完全失去限流保护
                if (dedupChecks < DEDUP_CHECK_LIMIT) {
                    dedupChecks++;
                    if (isDuplicate(text, userId, b)) {
                        skipped++;
                        continue;
                    }
                }
                docs.add(Document.builder()
                        .text(text)
                        .metadata(Map.of("type", "knowledge", META_USER_ID, userId))
                        .build());
            }
            if (docs.isEmpty()) {
                log.info("知识库导入：{} 条全部重复或为空，跳过（批次内精确重复 {} 条）",
                        documents.size(), skippedExact);
                return 0;
            }
            // 统一入库入口：容量上限保护 + 计数（P1-04）
            int stored = addToVectorStore(docs);
            if (stored > 0 && (skipped > 0 || skippedExact > 0)) {
                log.info("知识库导入：{} 条新增，{} 条库内重复跳过，{} 条批次内精确重复跳过",
                        stored, skipped, skippedExact);
            }
            return stored;
        } catch (Exception e) {
            log.error("知识库导入失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 检查该用户已有文档中是否存在与 text 高度相似的（去重预检）
     * - 仅搜索当前用户的文档，不影响其他用户
     * - 去重检查失败不阻断导入（返回 false，按非重复处理）
     */
    private boolean isDuplicate(String text, String userId, FilterExpressionBuilder b) {
        try {
            SearchRequest dedupReq = SearchRequest.builder()
                    .query(text)
                    .topK(1)
                    .similarityThreshold(dedupSimilarityThreshold)
                    .filterExpression(b.eq(META_USER_ID, userId).build())
                    .build();
            List<Document> existing = vectorStore.similaritySearch(dedupReq);
            return existing != null && !existing.isEmpty();
        } catch (Exception e) {
            log.debug("去重检查失败，按非重复处理：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 从检索结果中取出距离值。
     *
     * <p>不同 VectorStore 实现写入的兼容字段名不一致：SimpleVectorStore 写 {@code distance}，
     * 部分实现（含 pgvector）在 metadata 中落 {@code score} / {@code similarity}。
     * 这里按优先级兼容读取，取不到返回 {@code null}（调用方按「无法判断」处理，
     * 不会误判为「已覆盖」而漏掉自动补充）。
     */
    private static Double extractDistance(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        for (String key : new String[]{"distance", "score", "similarity"}) {
            Object v = doc.getMetadata().get(key);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }

}

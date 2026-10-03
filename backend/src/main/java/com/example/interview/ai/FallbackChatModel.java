package com.example.interview.ai;

import com.example.interview.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多模型降级链 ChatModel
 *
 * 按 chain 顺序调用；主模型失败（超时/限流/5xx/网络异常）时自动降级到下一个模型，
 * 保证 AI 功能在单厂商故障时仍可用。
 *
 * 同时实现 call()（同步，面试题生成/评估/岗位分析等）与 stream()（SSE 流式问答）。
 *
 * <h2>v1.48.0（第六轮 P1）：按厂商 RPM 令牌桶 + 区分错误码语义</h2>
 *
 * <p>此前本类对所有失败一律「换下一节点」，且不限制 RPM。实测暴露两个问题：
 * <ol>
 *   <li>无 RPM 闸门 → 突发并发把 Agnes 免费档（20 RPM）打到 429（响应体为空，无法诊断）；</li>
 *   <li>把「账户级速率限制」（智谱 1302 / HTTP 429）与「平台全局过载」（智谱 1305）
 *       同等对待——前者等待后重试本节点可能成功，后者重试本节点毫无意义（与本账户无关）。</li>
 * </ol>
 *
 * <p>现在每个节点在调用前先过本地 {@link AiRateLimiter} 令牌桶（预防性限流，触顶即降级）；
 * 调用失败时按 {@link FailureKind} 分流：
 * <ul>
 *   <li>{@code ACCOUNT_RATE_LIMIT}（1302 / 429）：指数退避（1s / 2s，最多 2 次）+ 抖动后
 *       <b>重试本节点</b>；退避重试耗尽或退避期间本地桶触顶才降级。</li>
 *   <li>{@code PLATFORM_OVERLOAD}（1305）：<b>立即换下一节点</b>（全局过载，重试本节点无意义）。</li>
 *   <li>其它（超时 / 5xx / 网络 / 空正文）：换下一节点（保持既有行为）。</li>
 * </ul>
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    /** 退避基数：1s，第 n 次重试等待约 {@code base << n}（1s / 2s） */
    private static final long BACKOFF_BASE_MILLIS = 1000L;

    /** 抖动幅度：在基础等待上叠加 0~30% 的随机量，避免多实例/多请求同步重试形成尖峰 */
    private static final double BACKOFF_JITTER_RATIO = 0.3;

    private final List<ChatModel> delegates;
    private final List<String> names;

    /** 与 {@link #delegates} 平行：每个节点的厂商限流 key（可为 null 表示该节点不限流） */
    private final List<String> rateLimitKeys;

    /** 按厂商 RPM 令牌桶；null 或 disabled 表示不限流 */
    private final AiRateLimiter rateLimiter;

    /** 退避等待实现（可注入，便于单测免真实等待） */
    private final Sleeper sleeper;

    /** 账户级限流（1302/429）时对同一节点的最大重试次数 */
    private final int maxRateLimitRetries;

    /** 退避等待的可注入抽象（默认 {@link Thread#sleep}） */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 失败语义分类（决定「重试本节点」还是「换下一节点」） */
    enum FailureKind {
        /** 账户级速率限制（智谱 1302 / HTTP 429）——等待后重试本节点可能成功 */
        ACCOUNT_RATE_LIMIT,
        /** 平台全局过载（智谱 1305）——与本账户无关，重试本节点无意义 */
        PLATFORM_OVERLOAD,
        /** 其它失败（超时 / 5xx / 网络 / 密钥失效等） */
        OTHER
    }

    /**
     * 便捷构造（无 RPM 限流、无退避重试）——等价于本类改造前的行为。
     *
     * <p>仅用于测试与「不装配策略」的场景；生产由 {@code AiConfig} 走带策略的重载。
     */
    public FallbackChatModel(List<ChatModel> delegates, List<String> names) {
        this(delegates, names, null, null, Thread::sleep, 0);
    }

    /**
     * 带限流与退避策略的构造。
     *
     * @param rateLimitKeys     与 {@code delegates} 平行的厂商限流 key（可 null）
     * @param rateLimiter       按厂商 RPM 令牌桶（可 null）
     * @param sleeper           退避等待实现（null 时用 {@link Thread#sleep}）
     * @param maxRateLimitRetries 账户级限流时对同一节点的最大重试次数（≤0 表示不重试）
     */
    public FallbackChatModel(List<ChatModel> delegates, List<String> names,
                             List<String> rateLimitKeys, AiRateLimiter rateLimiter,
                             Sleeper sleeper, int maxRateLimitRetries) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("AI 模型降级链不能为空");
        }
        this.delegates = delegates;
        this.names = names;
        this.rateLimitKeys = rateLimitKeys;
        this.rateLimiter = rateLimiter;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.maxRateLimitRetries = Math.max(0, maxRateLimitRetries);
    }

    /** 第 {@code i} 个节点的厂商限流 key（越界或未配置返回 null） */
    private String rateLimitKey(int i) {
        if (rateLimitKeys == null || i < 0 || i >= rateLimitKeys.size()) {
            return null;
        }
        return rateLimitKeys.get(i);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Exception last = null;
        String lastDetail = null;
        for (int i = 0; i < delegates.size(); i++) {
            String name = names.get(i);
            String key = rateLimitKey(i);

            // ① 本地 RPM 令牌桶（预防性限流）：触顶说明该厂商这一分钟已饱和，
            //    直接降级到下一节点（不同厂商、独立配额），而不是原地阻塞等待——
            //    RPM=15 时补 1 个令牌需 4s，等待收益极低，反而叠加延迟。
            if (rateLimiter != null && key != null && !rateLimiter.tryAcquire(key)) {
                lastDetail = name + " → 本地 RPM 令牌桶触顶（rpm=" + rateLimiter.rpmOf(key) + "），跳过";
                log.warn("AI 模型 {} 本地 RPM 令牌桶触顶（rpm={}），跳过并降级",
                        name, rateLimiter.rpmOf(key));
                continue;
            }

            int attempt = 0;
            while (true) {
                try {
                    ChatResponse resp = delegates.get(i).call(prompt);
                    // P1（2026-09-20 免费模型实测发现）：把「HTTP 200 但正文为空」判定为失败并继续降级。
                    //
                    // 背景：智谱 glm-4.7-flash / glm-4.5-flash 默认开启思考模式，reasoning_content
                    // 会吃光 max_tokens，导致 content 返回空字符串。此时 HTTP 200、usage 正常，
                    // Spring AI 不抛异常 —— 若照原样返回，用户会拿到一个**成功状态下的空白答案**，
                    // 比直接报错更糟（前端无法区分"没答"与"答了但空"）。
                    //
                    // 这里主动拦截：正文为空即视为该节点不可用，降级到下一顺位。
                    // 若全部节点都空，则走下方统一失败路径（503 + 可读文案），而不是返回空答案。
                    if (!hasUsableContent(resp)) {
                        String detail = "返回内容为空（疑似思考模式耗尽 max_tokens）";
                        log.warn("AI 模型 {} 判定不可用：{}", name, detail);
                        lastDetail = name + " → " + detail;
                        break;
                    }
                    if (i > 0) {
                        log.warn("已降级到第 {} 顺位模型 {}", i + 1, name);
                    }
                    return resp;
                } catch (Exception e) {
                    last = e;
                    String detail = describeFailure(e);
                    lastDetail = name + " → " + detail;
                    FailureKind kind = classifyFailure(e);

                    // ② 账户级限流（1302 / 429）：退避后重试**本节点**
                    if (kind == FailureKind.ACCOUNT_RATE_LIMIT && attempt < maxRateLimitRetries) {
                        long delay = backoffMillis(attempt);
                        log.warn("AI 模型 {} 触发账户级限流（1302/429），第 {}/{} 次退避重试，等待约 {}ms：{}",
                                name, attempt + 1, maxRateLimitRetries, delay, detail);
                        sleepQuietly(delay);
                        attempt++;
                        // 退避期间本地桶可能已被其它请求吃空：此时再重试本节点已无意义，直接降级
                        if (rateLimiter != null && key != null && !rateLimiter.tryAcquire(key)) {
                            log.warn("AI 模型 {} 退避期间本地 RPM 令牌桶触顶，放弃重试并降级", name);
                            break;
                        }
                        continue;
                    }

                    // ③ 平台全局过载（1305）：立即换下一节点（重试本节点无意义）
                    if (kind == FailureKind.PLATFORM_OVERLOAD) {
                        log.warn("AI 模型 {} 平台服务过载（1305），立即降级：{}", name, detail);
                    } else {
                        log.warn("AI 模型 {} 调用失败，尝试降级：{}", name, detail);
                    }
                    break;
                }
            }
        }
        // U1：降级链全失败属于"AI 服务暂不可用"的可重试业务故障，以 BusinessException 承载
        // 明确文案（全局处理器映射 503），替代此前语义混淆的 IllegalStateException→500"服务器内部错误"
        //
        // P2（2026-09-19 真机验证发现）：此前无论失败原因是什么，用户都只看到「AI 服务暂时不可用」，
        // 运维在日志里也只能看到「Error while extracting response ...」这种被 Spring 包装后的
        // 无信息量文案，完全无法区分「密钥失效」「模型名写错」「额度耗尽」这三类需要人工介入的
        // 配置故障与真正的「上游临时抖动」。
        // 典型来源：上游以 HTTP 200 返回 {"error":{"message":"Invalid token"}}（而非 401），
        // Spring AI 拿 200 去反序列化 ChatCompletion 结构失败，抛出的异常消息里不含上游原文。
        // 现把每个节点的失败摘要拼进日志，并让业务异常携带可诊断的一手信息。
        log.error("AI 降级链全部失败（共 {} 个节点）。失败摘要：{}", delegates.size(), lastDetail);
        throw new BusinessException("AI 服务暂时不可用，请稍后重试", last);
    }

    /**
     * 退避等待（指数 + 抖动）。第 {@code attempt} 次重试的基础等待为
     * {@code BACKOFF_BASE_MILLIS << attempt}（1s / 2s），再叠加 0~30% 随机抖动，
     * 避免多个请求同步重试形成尖峰。
     */
    private static long backoffMillis(int attempt) {
        long base = BACKOFF_BASE_MILLIS << Math.min(attempt, 3);
        long jitter = (long) (base * BACKOFF_JITTER_RATIO * ThreadLocalRandom.current().nextDouble());
        return base + jitter;
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("退避等待被中断，提前结束");
        }
    }

    /**
     * 按上游错误码区分失败语义（沿 cause 链查找）。
     *
     * <ul>
     *   <li>智谱 {@code 1305} = 平台服务过载（全局，与本账户无关）→ {@link FailureKind#PLATFORM_OVERLOAD}</li>
     *   <li>智谱 {@code 1302} = 账户速率限制；HTTP {@code 429} = 免费档 RPM 超限 →
     *       {@link FailureKind#ACCOUNT_RATE_LIMIT}</li>
     *   <li>其余 → {@link FailureKind#OTHER}</li>
     * </ul>
     *
     * <p>1305 优先于 429：智谱的「访问量过大」可能同时以 429 返回，但语义是全局过载，
     * 必须立即换节点而非退避重试。
     */
    static FailureKind classifyFailure(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        boolean rateLimit = false;
        while (cur != null && depth < 8) {
            String msg = cur.getMessage();
            if (msg != null) {
                if (msg.contains("1305")) {
                    return FailureKind.PLATFORM_OVERLOAD;
                }
                if (msg.contains("1302")
                        || msg.contains("429")
                        || msg.contains("Too Many Requests")
                        || msg.toLowerCase().contains("rate limit")) {
                    rateLimit = true;
                }
            }
            if (cur instanceof org.springframework.web.client.RestClientResponseException rce
                    && rce.getStatusCode().value() == 429) {
                rateLimit = true;
            }
            cur = cur.getCause();
            depth++;
        }
        return rateLimit ? FailureKind.ACCOUNT_RATE_LIMIT : FailureKind.OTHER;
    }

    /**
     * 判断一次响应是否含可用正文。
     *
     * <p>用于拦截「HTTP 200 + 空 content」这种**假成功**：模型返回了响应对象、usage 也正常，
     * 但真正给用户看的正文是空的（典型成因是思考模式的 reasoning_content 占满输出预算）。
     * 这种情况必须继续降级，绝不能把空答案当成功返回给业务层。
     *
     * <p>空值处理：响应结构任一层为 null（无 result / 无 output / 无 message）一律判为不可用。
     */
    static boolean hasUsableContent(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return false;
        }
        String text = resp.getResult().getOutput().getText();
        return text != null && !text.isBlank();
    }

    /**
     * 提取可诊断的失败原因。
     *
     * <p>Spring AI 对「HTTP 200 + {"error":{...}}」这类非标准错误响应，会抛出
     * {@code RestClientException: Error while extracting response ...}，异常消息本身不含上游原文；
     * 真实原因藏在 {@code HttpMessageNotReadableException} 的 cause 里（Jackson 解析错误会带上
     * 原始 JSON 片段）。这里沿 cause 链向下找，把最有信息量的一层提取出来，
     * 使「Invalid token」「model not found」「insufficient quota」等配置类故障直接可见。
     */
    static String describeFailure(Throwable e) {
        String best = null;
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                // 优先选择包含上游错误关键字的层——这些才是有诊断价值的
                if (msg.contains("Invalid token") || msg.contains("invalid_api_key")
                        || msg.contains("model") || msg.contains("quota") || msg.contains("balance")
                        || msg.contains("Unauthorized") || msg.contains("rate limit")
                        || msg.contains("error\":")) {
                    best = msg;
                    break;
                }
                if (best == null) {
                    best = msg;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        if (best == null) {
            return e.getClass().getSimpleName();
        }
        // 截断超长消息，避免日志被大段 JSON 淹没
        return best.length() > 500 ? best.substring(0, 500) + "..." : best;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> streamFrom(0, prompt,
                new AtomicBoolean(false), new java.util.concurrent.atomic.AtomicReference<>()));
    }

    /**
     * 从第 {@code idx} 个节点起尝试流式输出，支持两级降级。
     *
     * <p><b>降级条件（两者都要求「尚未向下游发出任何可用 token」）</b>：
     * <ol>
     *   <li>订阅时抛错（网络/限流/5xx）→ 换下一节点；</li>
     *   <li><b>流正常结束但没有任何可用正文</b>（HTTP 200 + 空 content 的假成功）→ 换下一节点。</li>
     * </ol>
     *
     * <p>第 2 条是 v1.34.1 修复：此前只有 {@link #call} 路径做了空正文判定，
     * {@code stream} 路径会把空正文当成功返回，用户拿到「成功状态下的空白回答」且无任何错误提示，
     * 比直接报错更糟。两条路径的判据现统一为 {@link #hasUsableContent}。
     *
     * <p><b>不降级的场景</b>：已向下游发出可用 token 后中途失败——此时切换模型会导致
     * 新旧模型内容拼接错乱（v1.23.1 修复的既有行为，此处保持）。
     *
     * @param tokenSent 是否已向下游发出可用正文（跨节点共享，用于判断能否安全降级）
     * @param lastError 最近一次真实异常，用于在降级链耗尽时保留原始错误语义
     */
    private Flux<ChatResponse> streamFrom(int idx, Prompt prompt, AtomicBoolean tokenSent,
                                          java.util.concurrent.atomic.AtomicReference<Throwable> lastError) {
        if (idx >= delegates.size()) {
            // 降级链耗尽：优先抛出最后一个节点的真实异常（保持既有测试与诊断语义），
            // 若为「全部节点都返回空正文」则抛出可重试的业务异常
            Throwable err = lastError.get();
            return err != null ? Flux.error(err)
                    : Flux.error(new BusinessException("AI 服务暂时不可用，请稍后重试"));
        }

        // 本地 RPM 令牌桶：触顶则流式跳过本节点（预防性限流；流式路径不做退避重试，
        // 避免首字节延迟被放大——退避重试只在同步 call 路径生效）。
        String key = rateLimitKey(idx);
        if (rateLimiter != null && key != null && !rateLimiter.tryAcquire(key)) {
            log.warn("AI 模型 {} 本地 RPM 令牌桶触顶（rpm={}），流式跳过并降级",
                    names.get(idx), rateLimiter.rpmOf(key));
            return streamFrom(idx + 1, prompt, tokenSent, lastError);
        }

        // 过滤空正文 chunk：既避免下游把空白 token 推给用户，也让「全空正文流」自然退化为
        // empty，从而被 switchIfEmpty 捕获并触发降级
        Flux<ChatResponse> current = Flux.defer(() -> delegates.get(idx).stream(prompt))
                .filter(FallbackChatModel::hasUsableContent)
                .doOnNext(resp -> tokenSent.set(true));

        return current
                .switchIfEmpty(Flux.defer(() -> {
                    if (tokenSent.get()) {
                        // 已发出过正文，后续为空 → 正常结束，不降级
                        return Flux.empty();
                    }
                    log.warn("AI 模型 {} 流式返回空正文（HTTP 200 假成功，疑似思考模式耗尽 max_tokens），尝试降级",
                            names.get(idx));
                    return streamFrom(idx + 1, prompt, tokenSent, lastError);
                }))
                .onErrorResume(e -> {
                    if (tokenSent.get()) {
                        log.warn("AI 模型 {} 流式输出中途失败（已发出 token，不降级）：{}",
                                names.get(idx), describeFailure(e));
                        return Flux.error(e);
                    }
                    log.warn("AI 模型 {} 流式调用失败（未发出 token），尝试降级：{}",
                            names.get(idx), describeFailure(e));
                    lastError.set(e);
                    return streamFrom(idx + 1, prompt, tokenSent, lastError);
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegates.get(0).getDefaultOptions();
    }

    @Override
    public String toString() {
        return "FallbackChatModel" + names;
    }
}

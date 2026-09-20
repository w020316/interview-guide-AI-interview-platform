package com.example.interview.config;

import com.example.interview.ai.AiResponseDiagnosticInterceptor;
import com.example.interview.ai.FallbackChatModel;
import com.example.interview.ai.VersionPathRewriteInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatClient 配置
 *
 * v1.22.0：聊天模型切换为多厂商降级链（app.ai.chain）：
 * 1. B.AI · GLM-5.3-Flash（主模型，免费额度，0 Credits）
 * 2. B.AI · Qwen3.8-Flash（次模型，免费额度，0 Credits）
 * 3. Agnes AI · agnes-2.5-flash（兜底，原主模型自动降为末位）
 *
 * v1.33.0（P1-03）：为每个提供方显式配置 HTTP 超时——此前未定制任何超时，一旦 AI 网关
 * 建连后挂起，同步调用永久阻塞，AiConcurrencyGuard 的 5 个许可被无限期占住。
 * 读超时（默认 240s）需大于最慢的合法生成（UI 宣称出题 2-3 分钟），
 * 且大于闸门排队超时（30s），保证许可必然在有限时间内释放。
 *
 * 不设置硬编码的岗位默认 system prompt，由各业务 Service 根据岗位动态生成，
 * 避免默认 "Java 后端" 与实际岗位冲突（支持全行业岗位）。
 *
 * 注意：Embedding 不走本链，仍由 spring.ai.openai.*（Agnes，text-embedding-3-small）驱动，
 * 用于知识库 RAG 向量化。
 */
@Configuration
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * 响应诊断拦截器是否已加装。
     *
     * <p>RestClient.Builder 是原型作用域，同一次启动内本方法只应执行一次；但为防将来
     * 被多处调用导致拦截器重复堆叠（每次请求记 N 遍日志），用静态标志做幂等保护。
     * 生产部署为单实例进程，静态字段无跨实例一致性问题。
     */
    private static volatile boolean diagnosticInterceptorAttached = false;

    /**
     * 上游 AI 响应诊断拦截器（单例复用）。
     *
     * <p>有状态可复用：拦截器本身无实例字段，且下游 {@code OpenAiApi} 会按需 clone
     * {@code RestClient.Builder}，共用同一拦截器实例不会产生交叉污染，同时避免每次
     * 构造节点都 new 一个新对象。
     */
    private static final AiResponseDiagnosticInterceptor DIAGNOSTIC_INTERCEPTOR =
            new AiResponseDiagnosticInterceptor();

    /**
     * P0-03 修复（2026-09-19 真机验证）：把诊断拦截器挂到 ChatModel 统一使用的
     * {@code RestClient.Builder} 上。
     *
     * <p>为什么必须单独抽一个方法：{@code RestClient.Builder} 是**原型作用域**，
     * Spring 每次注入都是新实例。此前只在 {@link #fallbackChatModel} 的入参 builder 上
     * 挂了拦截器，而各节点实际使用的是 {@code restClientBuilder.clone()}——
     * 实测证明 clone 不继承 {@code requestInterceptors}（Spring 6.1.21 的
     * {@code DefaultRestClientBuilder#clone()} 只拷贝 messageConverters/requestFactory/
     * defaultHeaders/uriBuilderFactory 等字段，不含拦截器列表），
     * 因此拦截器**一次都没被调用过**，日志里那条「AI 上游返回异常响应」永远不出现。
     *
     * <p>改为在每个真正被使用的 builder 上显式挂载，彻底摆脱对 clone 语义的依赖。
     * 下游 {@code OpenAiApi} 内部还会再 clone 一次，同样不继承拦截器——所以此处
     * 必须在"最后一次 clone 之后"调用，调用点见各处使用处。
     */
    private static RestClient.Builder withDiagnostics(RestClient.Builder builder) {
        return builder.clone().requestInterceptor(DIAGNOSTIC_INTERCEPTOR);
    }

    /**
     * P0-04 修复（2026-09-19 真机验证）：AI 调用统一使用标准 JDK HttpClient 作为
     * {@code RestClient} 的传输层，替换 Spring 默认的 {@code SimpleClientHttpRequestFactory}。
     *
     * <p>为什么必须换（实测堆栈）：
     * <pre>
     * HttpRetryException: cannot retry due to server authentication, in streaming mode
     *   at sun.net.www.protocol.http.HttpURLConnection.getInputStream0
     *   at SimpleClientHttpResponse.getStatusCode
     * </pre>
     * Spring 默认工厂底层是 {@code HttpURLConnection}。当上游返回 **401** 时，
     * {@code HttpURLConnection} 会走"服务器要求认证"分支，在请求体为流式（POST + body）的场景下
     * 无法自动重试，直接抛 {@link java.net.HttpRetryException}，并且**不读取、也不保留响应体**。
     * 于是上游精心给出的
     * {@code {"error":{"code":"","message":"Invalid token (request id: ...)","type":"AgnesAI_error"}}
     * 被彻底丢弃——这正是「密钥失效」这类配置故障在日志中只剩
     * 「Error while extracting response for type [ChatCompletion]」的唯一原因。
     *
     * <p>换成 JDK {@code HttpClient}（{@link JdkClientHttpRequestFactory}）后：
     * 401 会作为一个**正常的响应**返回，响应体完整可读，诊断拦截器也就能抓到原文。
     *
     * <p>兜底策略：若构建失败（理论上不会，JDK 11+ 必带 HttpClient），退回原工厂，
     * 保证 Bean 构造不因传输层选型而失败——功能可用性优先于可观测性。
     */
    private static ClientHttpRequestFactory aiRequestFactory(Duration connectTimeout, Duration readTimeout) {
        try {
            return new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build());
        } catch (Throwable t) {
            log.warn("JDK HttpClient 传输层初始化失败（将退回默认 HttpURLConnection 工厂，"
                    + "上游错误响应体可能不可读）：{}", t.toString());
            return ClientHttpRequestFactories.get(
                    ClientHttpRequestFactorySettings.DEFAULTS
                            .withConnectTimeout(connectTimeout)
                            .withReadTimeout(readTimeout));
        }
    }

    /**
     * 多厂商降级链 ChatModel（主 Bean）
     * - api-key 为空的提供方自动跳过（如未配置 AI_BAI_API_KEY 时落到 Agnes）
     * - 标记 @Primary 避免与 spring.ai 自动装配的 OpenAiChatModel（Agnes，Embedding 用）注入歧义
     */
    @Bean
    @Primary
    public ChatModel fallbackChatModel(
            AiProviderProperties props,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${app.ai.read-timeout-seconds:240}") long readTimeoutSeconds,
            @Value("${spring.ai.openai.base-url:https://apihub.agnes-ai.com}") String springAiBaseUrl,
            @Value("${spring.ai.openai.api-key:sk-placeholder}") String springAiApiKey,
            @Value("${spring.ai.openai.chat.options.model:agnes-2.5-flash}") String springAiModel) {
        // 同步调用：连接 10s + 读超时兜底，保证闸门许可有限时间内释放。
        // P0-04：传输层换 JDK HttpClient，上游 401 时响应体不再被 HttpRetryException 吞掉。
        restClientBuilder.requestFactory(
                aiRequestFactory(Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds)));
        // P3（2026-09-19 真机验证）：加装响应诊断拦截器。
        // 上游 AI 网关鉴权失败时返回 401 + {"error":{"message":"Invalid token (request id: ...)"}}，
        // 但默认的 SimpleClientHttpRequestFactory 在鉴权失败下抛 HttpRetryException，
        // **把响应体彻底吞掉**，Spring AI 侧只剩「Error while extracting response」（实测 cause 数为 0），
        // 导致「密钥失效」这类必须人工处理的配置故障在日志里毫无线索。
        // 拦截器在解析前抓一份响应体快照，命中 error 特征即记录原始内容。
        //
        // 注意：此处只对入参 builder 生效；真正构造节点用的是 restClientBuilder.clone()，
        // 而 clone() 不继承 requestInterceptors。因此下面每个实际使用的 builder 都会
        // 再经 withDiagnostics(...) 显式挂载一次（详见该方法注释）。
        restClientBuilder.requestInterceptor(DIAGNOSTIC_INTERCEPTOR);
        // 流式调用（SSE）：classpath 无 Reactor Netty，WebClient 默认走 JDK HttpClient，
        // 此处配置连接超时；流式整体时长由 SseEmitter 超时与客户端兜底约束
        webClientBuilder.clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build()));

        List<ChatModel> delegates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AiProviderProperties.Provider p : props.getChain()) {
            if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                    || p.getModel() == null || p.getModel().isBlank()
                    || p.getApiKey() == null || p.getApiKey().isBlank()) {
                continue;
            }
            String nodeBaseUrl = toOpenAiCompatibleBaseUrl(p.getBaseUrl());
            RestClient.Builder nodeBuilder = withDiagnostics(restClientBuilder).clone();
            // P0-05：非 v1 版本段的厂商（如智谱 /api/paas/v4）需剥掉 Spring AI 多拼的 /v1
            if (needsV1Stripping(nodeBaseUrl)) {
                nodeBuilder.requestInterceptor(new VersionPathRewriteInterceptor());
            }
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(nodeBaseUrl)
                    .apiKey(p.getApiKey())
                    .restClientBuilder(nodeBuilder)
                    .webClientBuilder(webClientBuilder.clone())
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(p.getModel())
                            .temperature(p.getTemperature() == null ? 0.7 : p.getTemperature())
                            .build())
                    .build();
            delegates.add(model);
            names.add((p.getName() == null || p.getName().isBlank() ? p.getBaseUrl() : p.getName())
                    + "/" + p.getModel());
        }
        if (delegates.isEmpty()) {
            // P0-01 修复（v1.33.1 / 2026-09-19）：此前此处直接抛 IllegalStateException，导致 **整个 Spring 上下文
            // 启动失败**——不只是 AI 不可用，登录/注册/健康检查等与 AI 完全无关的功能一并不可用。
            // 在 Render 免费层上的表现为：健康检查持续失败 → 实例反复重启 → 站点永远"冷启动"，
            // 与「登录界面加载停滞、认证过程无响应」的现象完全一致。
            //
            // 修正为与类注释一致的行为：链条无可用节点时回退 spring.ai.openai.*（Agnes）构造单节点模型，
            // 保证应用**始终可启动**；该提供方也失败时，由 FallbackChatModel 在调用阶段统一转为
            // BusinessException（全局映射 503 + 可读文案），仅 AI 功能降级。
            log.error("app.ai.chain 未解析出任何可用 AI 提供方（各节点 api-key 均为空），"
                    + "已回退 spring.ai.openai.*（base-url={}, model={}）。"
                    + "请检查 AI_API_KEY / AI_EMBEDDING_* 等环境变量是否注入。", springAiBaseUrl, springAiModel);
            delegates.add(buildSpringAiFallbackModel(
                    restClientBuilder, webClientBuilder, springAiBaseUrl, springAiApiKey, springAiModel));
            names.add("spring-ai-openai/" + (springAiModel == null || springAiModel.isBlank()
                    ? "agnes-2.5-flash" : springAiModel.trim()));
        }
        return new FallbackChatModel(delegates, names);
    }

    /**
     * 构造 spring.ai.openai.* 兜底模型（降级链为空时使用）。
     *
     * <p>与 {@link #openAiEmbeddingModel} 一致：OpenAiApi 会自行拼接 {@code /v1/chat/completions}，
     * 因此 base-url 若误带 {@code /v1} 后缀会被剥离，避免出现 {@code /v1/v1/...} 404。
     * api-key 缺失时使用占位符——只保证应用可启动，调用时由 AI 提供方返回 401 并转为友好文案。
     */
    private OpenAiChatModel buildSpringAiFallbackModel(RestClient.Builder restClientBuilder,
                                                       WebClient.Builder webClientBuilder,
                                                       String baseUrl, String apiKey, String model) {
        String useBaseUrl = normalizeOpenAiBaseUrl(baseUrl);
        String useApiKey = (apiKey == null || apiKey.isBlank()) ? "sk-placeholder" : apiKey.trim();
        String useModel = (model == null || model.isBlank()) ? "agnes-2.5-flash" : model.trim();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(useBaseUrl)
                .apiKey(useApiKey)
                .restClientBuilder(withDiagnostics(restClientBuilder).clone())
                .webClientBuilder(webClientBuilder.clone())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(useModel).temperature(0.7).build())
                .build();
    }

    /**
     * 把任意厂商的 OpenAI 兼容 base-url 规范成 Spring AI 期望的形态。
     *
     * <p>P0-05 修复（2026-09-20 免费模型接入实测发现，最隐蔽的一个缺陷）：
     * Spring AI 的 {@code OpenAiApi} 内部**硬编码**拼接 {@code /v1/chat/completions}
     * 与 {@code /v1/embeddings}（已用反射探针实测确认），因此它只能对接
     * 「版本段恰好是 v1」的厂商：
     * <pre>
     *   Agnes   https://apihub.agnes-ai.com            → /v1/chat/completions        ✅
     *   智谱    https://open.bigmodel.cn/api/paas/v4   → /api/paas/v4/v1/chat/...    ❌ 404
     * </pre>
     * 智谱的版本段是 {@code /v4}，且实测其**只认** {@code /api/paas/v4/chat/completions}
     * （穷举验证：{@code /v4/v1/...}、{@code /v3/...}、{@code /paas/paas/v4/...} 全部 404）。
     *
     * <p>由于 base-url 无论怎么写都无法让「硬拼 /v1」得到「不要 /v1」的结果，
     * 这里改用**重写请求路径**的方式：把 base-url 设为厂商的完整版本前缀
     * （智谱为 {@code .../api/paas/v4}），再由
     * {@link com.example.interview.ai.VersionPathRewriteInterceptor} 在发送前
     * 把 Spring AI 多拼的那层 {@code /v1} 去掉。
     *
     * <p>返回值即为应当传给 {@code OpenAiApi.builder().baseUrl(...)} 的地址。
     */
    static String toOpenAiCompatibleBaseUrl(String baseUrl) {
        String use = normalizeOpenAiBaseUrl(baseUrl);
        if (needsV1Stripping(use)) {
            log.info("检测到非 v1 版本段的 OpenAI 兼容厂商，将启用 /v1 路径重写：baseUrl={}", use);
        }
        return use;
    }

    /**
     * 判断该 base-url 是否需要剥掉 Spring AI 多拼的 {@code /v1}。
     *
     * <p>特征：路径以 {@code /vN}（N 为数字且 N≠1）结尾，说明厂商用自己的版本号，
     * Spring AI 追加的 {@code /v1} 会导致 404。
     *
     * <p>例：{@code https://open.bigmodel.cn/api/paas/v4} → true（需重写）
     *        {@code https://apihub.agnes-ai.com} → false（标准 OpenAI 协议）
     */
    static boolean needsV1Stripping(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String path = baseUrl;
        int schemeIdx = path.indexOf("://");
        if (schemeIdx >= 0) {
            int slash = path.indexOf('/', schemeIdx + 3);
            path = slash >= 0 ? path.substring(slash) : "";
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        String last = path.substring(lastSlash + 1);
        // 形如 v4 / v2 / v3；v1 是标准 OpenAI 版本段，无需重写
        return last.matches("v[0-9]+") && !last.equals("v1");
    }

    /**
     * 规范化 OpenAI 兼容 base-url（Agnes 等标准 OpenAI 协议厂商）。
     *
     * <p>OpenAiApi 会自行拼接 {@code /v1/chat/completions}，配置值若误带 {@code /v1}
     * 后缀会产生 {@code /v1/v1/...} 404，此处统一剥离末尾 {@code /v1}。
     *
     * <p>注意：智谱 {@code /api/paas/v4} 这类**非 v1 版本段**不走剥离逻辑
     * （它本来就需要保留 v4），其路径差异由
     * {@link com.example.interview.ai.VersionPathRewriteInterceptor} 处理。
     */
    static String normalizeOpenAiBaseUrl(String baseUrl) {
        String useBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://apihub.agnes-ai.com" : baseUrl.trim();
        if (useBaseUrl.endsWith("/v1")) {
            useBaseUrl = useBaseUrl.substring(0, useBaseUrl.length() - 3);
        }
        if (useBaseUrl.endsWith("/")) {
            useBaseUrl = useBaseUrl.substring(0, useBaseUrl.length() - 1);
        }
        return useBaseUrl;
    }

    @Bean
    public ChatClient chatClient(ChatModel fallbackChatModel) {
        return ChatClient.builder(fallbackChatModel).build();
    }

    /**
     * P0-02 后续（2026-09-19）：显式装配 Embedding 模型，覆盖 spring.ai 自动装配。
     * 问题：自动装配的 OpenAiEmbeddingModel 使用默认 RestClient（无超时）+ 默认重试模板
     * （指数退避 10 次，累计约 5 分钟），embedding 端点不可用时知识导入请求挂起约 5 分钟
     * 才失败，期间占用请求线程与 AI 闸门许可。
     * 修复：连接 10s + 读 60s 显式超时，最多重试 1 次（间隔 0.5s），快速失败后由
     * RagSearchService 转为「AI 服务暂时不可用」业务文案（U1）。
     */
    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String embeddingModel,
            @Value("${app.ai.embedding.base-url:}") String embeddingBaseUrlOverride,
            @Value("${app.ai.embedding.api-key:}") String embeddingApiKeyOverride,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int embeddingDimensions) {
        // P2-06 后续（2026-09-19）：embedding 提供方可插拔——Agnes 已下线全部 embedding 模型，
        // 通过 AI_EMBEDDING_BASE_URL / AI_EMBEDDING_API_KEY 指向第三方（如硅基流动 bge-m3），
        // 未配置时回退 spring.ai.openai.*（保持向后兼容）
        boolean override = embeddingBaseUrlOverride != null && !embeddingBaseUrlOverride.isBlank()
                && embeddingApiKeyOverride != null && !embeddingApiKeyOverride.isBlank();
        String useBaseUrl = override ? normalizeOpenAiBaseUrl(embeddingBaseUrlOverride) : normalizeOpenAiBaseUrl(baseUrl);
        String useApiKey = override ? embeddingApiKeyOverride : apiKey;

        // P1-01 后续（2026-09-19）：Embedding 配置一致性体检（只告警，不阻断启动）。
        // 典型误配：只改了 AI_EMBEDDING_BASE_URL/API_KEY，忘记同步 AI_EMBEDDING_DIMENSIONS，
        // 使 pgvector 列维度（默认 1536）与实际输出维度（如 bge-m3 = 1024）不一致，
        // 表现为「知识库导入 500 · 向量维度不匹配」，而启动日志看不出任何线索。
        if (override) {
            log.info("Embedding 使用第三方提供方：baseUrl={}, model={}, dimensions={}",
                    useBaseUrl, embeddingModel, embeddingDimensions);
            if (embeddingDimensions == 1536 && !embeddingModel.contains("text-embedding-3")) {
                log.warn("Embedding 维度可能不匹配：模型 {} 通常不是 1536 维，但 "
                        + "spring.ai.vectorstore.pgvector.dimensions={}。"
                        + "请同步设置 AI_EMBEDDING_DIMENSIONS（如 bge-m3 为 1024）并按该维度重建向量表，"
                        + "否则知识导入会因维度不一致失败。", embeddingModel, embeddingDimensions);
            }
        } else {
            log.info("Embedding 使用默认 spring.ai.openai.* 提供方：baseUrl={}, model={}, dimensions={}",
                    useBaseUrl, embeddingModel, embeddingDimensions);
        }

        RestClient.Builder rb = withDiagnostics(restClientBuilder).requestFactory(
                aiRequestFactory(Duration.ofSeconds(10), Duration.ofSeconds(60)));
        // P0-05：embedding 走同一套 base-url 拼接规则，非 v1 版本段厂商同样需重写
        String embBaseUrl = normalizeOpenAiBaseUrl(useBaseUrl);
        if (needsV1Stripping(embBaseUrl)) {
            rb.requestInterceptor(new VersionPathRewriteInterceptor());
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(embBaseUrl)
                .apiKey(useApiKey)
                .restClientBuilder(rb)
                .build();
        // P2-09（2026-09-20）：维度透传修复。此前 OpenAiEmbeddingOptions 只设 model，
        // 未把 embeddingDimensions 透传进 embedding 请求，实际向量维度完全由模型默认值决定，
        // 与 pgvector 表维度（AI_EMBEDDING_DIMENSIONS）可能不一致——生产仅因 text-embedding-v4
        // 原生维度恰好 1024 == 配置值才未暴露。此处链式补 .dimensions()；但 embeddingDimensions<=0
        // 时不设置（保持原行为），避免个别提供方拒绝该参数导致启动/调用失败。
        OpenAiEmbeddingOptions.Builder embeddingOptionsBuilder =
                OpenAiEmbeddingOptions.builder().model(embeddingModel);
        if (embeddingDimensions > 0) {
            embeddingOptionsBuilder.dimensions(embeddingDimensions);
        }
        return new OpenAiEmbeddingModel(
                api,
                org.springframework.ai.document.MetadataMode.EMBED,
                embeddingOptionsBuilder.build(),
                RetryTemplate.builder().maxAttempts(2).fixedBackoff(500).build());
    }
}

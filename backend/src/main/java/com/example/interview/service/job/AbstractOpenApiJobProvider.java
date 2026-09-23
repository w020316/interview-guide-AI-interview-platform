package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 免费公开招聘数据 API 适配器基类（v1.37.0）
 *
 * <p><b>引入背景</b>：招聘广场此前只有三类内置种子数据（秋招精选 / 多频道精选 / 行业精选），
 * 总量约 85 条且更新依赖发版；主流招聘平台（BOSS 直聘 / 智联 / 前程无忧）官方未开放公开
 * 岗位查询 API，而 WebJobSearcherService 的 jsoup 抓取又受「搜索页强 JS 渲染」限制。
 * 因此引入「免费公开、无需 API Key、返回结构化 JSON、持续更新」的招聘数据 API 作为增量来源。
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li><b>失败隔离</b>：网络/解析异常会抛出带原因的异常，但调度层（{@code JobAgentService}）
 *       对每个数据源单独 try/catch，因此一个源失败绝不影响其它源与整体刷新流程
 *       （Render 免费层网络抖动是常态）。抛出而非吞掉是为了让管理后台能告警——
 *       「这个源挂了」和「这个源正常但没有岗位」必须能被区分开。</li>
 *   <li><b>列长保护</b>：岗位的 platform/external_id/title/company/location/salary/tags/apply_url
 *       在库中都有长度上限（见 {@code JobPostingEntity}），入库前统一裁剪，
 *       避免单条超长文本触发 DataIntegrityViolation 让整批 upsert 回滚。</li>
 *   <li><b>description 清洗</b>：上游 description 为 HTML 片段，清洗为纯文本并截断，
 *       避免 TEXT 列被数万字 HTML 撑爆、前端也无法阅读。</li>
 *   <li><b>快速失败</b>：连接/读取超时收紧到 4s/12s，外部 API 不可达时不能拖住
 *       刷新互斥锁（否则整点刷新会被一个挂死的源卡住）。</li>
 * </ul>
 */
public abstract class AbstractOpenApiJobProvider implements JobPlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenApiJobProvider.class);

    /** 连接/读取超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 12000;

    /** 部分站点对默认 UA 返回 403，统一伪装为桌面浏览器 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    /** description 入库长度上限（纯文本字符数） */
    private static final int DESC_MAX_LEN = 1200;

    /**
     * 公开 API 数据源的最小刷新间隔：6 小时。
     *
     * <p>内置种子数据是幂等的本地数据，每小时全量刷新开销极低；
     * 但外部公开 API 有礼貌性调用要求（且不会小时级更新），6 小时足够。
     */
    protected static final long OPEN_API_REFRESH_INTERVAL_MS = 6 * 3600 * 1000L;

    /** 列长度上限（与 JobPostingEntity 保持一致） */
    protected static final int LEN_PLATFORM = 50;
    protected static final int LEN_EXTERNAL_ID = 128;
    protected static final int LEN_TITLE = 200;
    protected static final int LEN_COMPANY = 200;
    protected static final int LEN_LOCATION = 100;
    protected static final int LEN_SALARY = 100;
    protected static final int LEN_TAGS = 500;
    protected static final int LEN_URL = 500;

    protected final ObjectMapper objectMapper;
    private final RestClient restClient;

    protected AbstractOpenApiJobProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 公开 API 数据源统一归类为「海外 / 远程」。
     *
     * <p>这三家接口的岗位以欧美远程为主，与国内校招/社招场景差异明显，
     * 因此在招聘广场独立成栏，不混进国内列表。
     */
    @Override
    public boolean overseas() {
        return true;
    }

    /** 统一 6 小时刷新一次，避免高频打扰上游 */
    @Override
    public long minRefreshIntervalMs() {
        return OPEN_API_REFRESH_INTERVAL_MS;
    }

    /**
     * 单个海外源每轮最多入库的岗位数（v1.39.0）。
     *
     * <p><b>为什么需要上限</b>：这些公开 API 单次返回 50~100+ 条，五个源合计
     * 一轮可灌入 400 余条英文海外岗位，而国内种子数据总量约 130 条。
     * 结果是招聘广场被海外远程岗位淹没，与「国内校招 / 社招」的产品定位相反。
     *
     * <p><b>取值 25 的取舍</b>：五个源 × 25 = 最多 125 条，足以让「海外远程」分栏
     * 保持可用（不是空栏，也不至于只有零星几条）；同时国内岗位在任何默认视图里
     * 都占多数。低于 15 会让该分栏看起来像坏了，高于 40 则失去配额的意义。
     */
    protected static final int MAX_ITEMS_PER_REFRESH = 25;

    /** 该数据源的 JSON 接口地址 */
    protected abstract String endpoint();

    /** 解析上游 JSON 为统一 JobDto 列表（同源内无需去重，跨源去重由 JobAgentService 负责） */
    protected abstract List<JobDto> parse(JsonNode root);

    /**
     * 拉取并解析。
     *
     * <p><b>失败时抛异常而不是返回空列表</b>（v1.38.0 调整）：早先的实现把异常吞掉、
     * 返回空列表，导致「这个源挂了」与「这个源正常但没有岗位」在调用方看来完全一样，
     * 管理后台也就无从告警。现在抛出带原因的异常，由 {@code JobAgentService} 统一捕获，
     * 在那里能同时拿到平台名与耗时并登记健康状态；失败隔离仍然成立——
     * 调度层对每个源单独 try/catch，一个源失败不影响其他源。
     *
     * <p><b>v1.39.0 增加入库配额</b>：解析结果按 {@link #MAX_ITEMS_PER_REFRESH} 截断，
     * 见该常量注释。截断发生在入库之前，因此不会把上游全量数据写进库。
     */
    @Override
    public List<JobDto> fetch() {
        String body;
        try {
            body = restClient.get()
                    .uri(endpoint())
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("公开数据源 {} 拉取失败：{}", platform(), e.getMessage());
            throw new IllegalStateException("拉取失败：" + e.getMessage(), e);
        }
        if (body == null || body.isBlank()) {
            log.warn("公开数据源 {} 返回空响应体", platform());
            throw new IllegalStateException("拉取失败：上游返回空响应体");
        }
        try {
            return applyQuota(parse(objectMapper.readTree(body)));
        } catch (Exception e) {
            log.warn("公开数据源 {} 数据解析失败：{}", platform(), e.getMessage());
            throw new IllegalStateException("数据解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按单源配额截断解析结果（纯函数，便于单测）。
     *
     * <p>截断发生在入库之前，因此上游的全量数据不会被写进库。
     * 保留上游返回顺序（通常是按发布时间/相关度），不额外排序——
     * 排序会引入「为什么这批是这个」的隐性规则，不如原样截断可解释。
     */
    static List<JobDto> applyQuota(List<JobDto> parsed) {
        if (parsed == null || parsed.isEmpty()) return List.of();
        if (parsed.size() <= MAX_ITEMS_PER_REFRESH) return parsed;
        return new ArrayList<>(parsed.subList(0, MAX_ITEMS_PER_REFRESH));
    }

    // ────────────────────────── 字段工具 ──────────────────────────

    /** 取首个非空文本字段（trim 后返回，长度由调用方按列上限裁剪） */
    protected static String text(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    /** 取数组字段并拼为逗号分隔的标签串 */
    protected static String joinArray(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v == null || !v.isArray() || v.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            for (JsonNode item : v) {
                String s = item.isValueNode() ? item.asText("").trim() : "";
                if (!s.isBlank()) parts.add(s);
            }
            if (!parts.isEmpty()) return String.join(",", parts);
        }
        return null;
    }

    /** HTML 片段 → 纯文本（去脚本/样式/标签、还原常见实体、压缩空白），并按上限截断 */
    protected static String plainText(String html, int maxLen) {
        if (html == null || html.isBlank()) return null;
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return s.isBlank() ? null : clip(s, maxLen);
    }

    /** 上游 description 字段 → 可直接入库的纯文本 */
    protected static String desc(JsonNode node) {
        return plainText(text(node, "description", "jobDescription"), DESC_MAX_LEN);
    }

    /** 按字符数截断（列长度上限保护）；null 安全 */
    protected static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 秒级时间戳 → 日期 */
    protected static LocalDate fromEpochSecond(long epochSecond) {
        if (epochSecond <= 0) return null;
        return Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 由发帖日期推导截止日期。
     *
     * <p>上游多数源不提供 deadline，而截止日期同时承担两个职责：
     * 「临期优先」排序，以及 {@code JobAgentService} 的过期自动下架。
     * 因此按发帖日 + 有效期推算，保证海外岗位也会自然新陈代谢。
     */
    protected static LocalDate deadlineFrom(LocalDate posted, int validDays) {
        return posted == null ? null : posted.plusDays(validDays);
    }

    /** 解析 yyyy-MM-dd / yyyy-MM-dd'T'HH:mm:ss 前缀日期 */
    protected static LocalDate parseDatePrefix(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.length() > 10) s = s.substring(0, 10);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}

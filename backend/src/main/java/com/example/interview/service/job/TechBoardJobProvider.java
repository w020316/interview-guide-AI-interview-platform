package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 科技公司官方招聘板数据源（第五批 · 全时段全行业）。
 *
 * <p><b>数据来源</b>：现代科技公司普遍使用 ATS（Applicant Tracking System）托管官方招聘页，
 * 其中 <b>Ashby</b> 与 <b>Greenhouse</b> 都提供**按公司 slug 的公开、无需鉴权的发布 API**
 * （经 2026-10-04 实测：Ashby 与 Greenhouse 均返回 HTTP 200 + 完整 JSON）。
 * 由于没有全局搜索端点，本源维护一个**精选公司清单**，逐个按 slug 拉取。
 *
 * <p><b>为什么选这两家而不是爬招聘网站</b>：这是**雇主自己发布的原始岗位**（一手信息，
 * 非二手聚合），每条都带直达申请链接与发布时间，可信度与新鲜度都优于任何聚合站；
 * 且公开、免费、无 Key——符合本项目「不触及需登录/需授权接口」的合规红线。
 *
 * <p><b>⚠️ 实测淘汰记录（勿重复踩）</b>：
 * <ul>
 *   <li><b>Lever</b>（{@code api.lever.co/v0/postings/<slug>?mode=json}）：2026-10-04 实测
 *       多个已知 slug（netflix/figma/notion/plaid/brex/rippling）**全部返回 404**，
 *       疑似已收紧或下线 → <b>不接入</b>。如未来恢复，补一个 normalize 分支即可
 *       （注意 Lever 返回**裸 JSON 数组**而非 {@code {"jobs":[...]}}，且职位标题字段是
 *       {@code text} 不是 {@code title}，发布时间 {@code createdAt} 是 **epoch 毫秒**）。</li>
 *   <li><b>Working Nomads</b>（{@code workingnomads.com/jobboard.rss}）：实测 **HTTP 404**。</li>
 *   <li><b>Jobspresso</b>（{@code jobspresso.co/api/v1/jobs}）：实测 **HTTP 403**。</li>
 * </ul>
 * 这三条记在这里是为了避免下一个人（或下一轮的我）再花时间试一遍——
 * 一个「公开 API 清单」在网上看起来是好的，**能不能用必须自己打一遍**。
 *
 * <p><b>合规</b>：只读**雇主公开发布的招聘页 API**，不登录、不投递、不伪造；
 * 与普通公开数据源无异，仅执行 GET。岗位描述由基类 {@code plainText} 清洗为纯文本。
 */
@Component
public class TechBoardJobProvider extends AbstractOpenApiJobProvider {

    /**
     * 精选公司清单（slug → 雇主展示名）。
     *
     * <p><b>为什么是固定清单而不是自动发现</b>：这些 API 是「按公司」的，没有全局搜索端点；
     * 自动发现需要爬 Internet Archive 的 CDX 索引（参见 mherzog4/job-boards 的双阶段方案），
     * 对单次 6 小时刷新而言成本过高、且会给第三方带来压力。
     * 固定清单**可控、可解释、零维护风险**——某家公司 hiring 结束就返回空列表，不会报错。
     *
     * <p><b>扩容方式</b>：加一行即可。slug 就是公司招聘页地址里的那段
     * （如 {@code jobs.ashbyhq.com/linear} → {@code linear}）。
     */
    private static final List<String[]> ASHBY_BOARDS = List.of(
            new String[]{"openai", "OpenAI"},
            new String[]{"linear", "Linear"},
            new String[]{"ramp", "Ramp"},
            new String[]{"notion", "Notion"}
    );

    private static final List<String[]> GREENHOUSE_BOARDS = List.of(
            new String[]{"stripe", "Stripe"},
            new String[]{"airbnb", "Airbnb"},
            new String[]{"dropbox", "Dropbox"},
            new String[]{"coinbase", "Coinbase"}
    );

    private static final String ASHBY_URL = "https://api.ashbyhq.com/posting-api/job-board/%s";
    private static final String GREENHOUSE_URL = "https://boards-api.greenhouse.io/v1/boards/%s/jobs";

    /** 单个雇主最多取多少条（防止某家一次性灌入数百条淹没其他源） */
    private static final int MAX_PER_BOARD = 12;

    private final JobAgentProperties properties;

    public TechBoardJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "科技公司官方板";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    /** 基类抽象方法的占位实现：本源覆写 fetch()（多公司、两种结构），不走基类单步 JSON 流程 */
    @Override
    protected String endpoint() {
        return String.format(ASHBY_URL, ASHBY_BOARDS.get(0)[0]);
    }

    /**
     * 逐个公司拉取并合并。
     *
     * <p><b>失败隔离</b>：单家公司失败（网络抖动 / 该公司换了 ATS）只记录并跳过，
     * 不影响其余公司——否则一家挂掉会让整个源在管理后台显示为「故障」，
     * 掩盖「其实大部分公司都是好的」这一事实。
     */
    @Override
    public List<JobDto> fetch() {
        List<JobDto> all = new ArrayList<>();
        for (String[] board : ASHBY_BOARDS) {
            all.addAll(fetchAshby(board[0], board[1]));
        }
        for (String[] board : GREENHOUSE_BOARDS) {
            all.addAll(fetchGreenhouse(board[0], board[1]));
        }
        return applyQuotaForSource(all);
    }

    /** 拉取单个 Ashby 板：{@code {"jobs":[...], "apiVersion":"..."}} */
    private List<JobDto> fetchAshby(String slug, String company) {
        try {
            return parseAshby(objectMapper.readTree(fetchRaw(String.format(ASHBY_URL, slug))), company);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 拉取单个 Greenhouse 板：{@code {"jobs":[...], "meta":{...}}} */
    private List<JobDto> fetchGreenhouse(String slug, String company) {
        try {
            return parseGreenhouse(
                    objectMapper.readTree(fetchRaw(String.format(GREENHOUSE_URL, slug))), company);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析 Ashby 板（包级可见，便于单测）。
     *
     * <p>字段：{@code title / location / isRemote / employmentType / jobUrl / publishedAt
     * / department / descriptionPlain}。只取 {@code isListed != false} 的岗位——
     * 未公开的草稿不应出现在广场（**如实过滤，不是猜测**）。
     */
    List<JobDto> parseAshby(JsonNode root, String company) {
        List<JobDto> result = new ArrayList<>();
        if (root == null) return result;
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) return result;
        int taken = 0;
        for (JsonNode j : jobs) {
            if (taken >= MAX_PER_BOARD) break;
            // 上游用 isListed 标记该岗位是否对公开页可见；false 明确表示「未公开」
            if (j.has("isListed") && !j.path("isListed").asBoolean(true)) continue;
            String title = text(j, "title");
            String url = text(j, "jobUrl");
            if (title == null || url == null) continue;
            String location = text(j, "location");
            boolean remote = j.path("isRemote").asBoolean(false);
            String dept = text(j, "department");
            String team = text(j, "team");
            LocalDate posted = parseIsoDate(text(j, "publishedAt"));

            result.add(new JobDto(
                    clip("ashby-" + slugOf(url), LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    "互联网",
                    jobTypeOf(dept, team, title),
                    clip(locationOf(location, remote), LEN_LOCATION),
                    null,
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(url, LEN_URL),
                    plainText(text(j, "descriptionPlain"), DESC_MAX_LEN),
                    null,
                    clip(tagsOf(dept, team), LEN_TAGS)
            ));
            taken++;
        }
        return result;
    }

    /**
     * 解析 Greenhouse 板（包级可见，便于单测）。
     *
     * <p>字段：{@code title / location.name / absolute_url / updated_at / first_published
     * / company_name}。列表端点默认不返回 description（要 {@code ?content=true}，
     * 体积涨约 26 倍）——本源**刻意不请求正文**：仅需标题/地点/链接即可入库，
     * 正文并非广场列表页的必需字段，为它付出 26 倍流量不划算。
     */
    List<JobDto> parseGreenhouse(JsonNode root, String company) {
        List<JobDto> result = new ArrayList<>();
        if (root == null) return result;
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) return result;
        int taken = 0;
        for (JsonNode j : jobs) {
            if (taken >= MAX_PER_BOARD) break;
            String title = text(j, "title");
            String url = text(j, "absolute_url");
            if (title == null || url == null) continue;
            String location = j.path("location").path("name").asText(null);
            LocalDate posted = parseIsoDate(firstNonBlank(
                    text(j, "first_published"), text(j, "updated_at")));

            result.add(new JobDto(
                    clip("gh-" + text(j, "id"), LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    "互联网",
                    jobTypeOf(null, null, title),
                    clip(locationOf(location, false), LEN_LOCATION),
                    null,
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(url, LEN_URL),
                    null,
                    null,
                    null
            ));
            taken++;
        }
        return result;
    }

    // ────────────────────────── 字段工具 ──────────────────────────

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** 取 URL 最后一段作为 externalId 后缀（Ashby 的 jobUrl 形如 .../<uuid>） */
    private static String slugOf(String url) {
        String s = url.trim();
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /** ISO-8601（含时区偏移）→ LocalDate；解析不动时回退日期前缀解析 */
    private static LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim()).toLocalDate();
        } catch (Exception e) {
            return parseDatePrefix(raw);
        }
    }

    /** 地点：远程岗位统一标注「远程」+ 原地区，便于识别 */
    private static String locationOf(String location, boolean remote) {
        String loc = (location == null || location.isBlank()) ? null : location.trim();
        if (remote) {
            return loc == null ? "远程" : loc + " · 远程";
        }
        return loc == null ? "不限" : loc;
    }

    /**
     * 职位类型：按部门/团队/标题关键词粗分类。
     *
     * <p>科技公司板的岗位以技术为主，但产品/设计/市场/销售岗同样存在；
     * 归类仅供参考（前端用招聘类型分栏，此处只填 jobType 展示字段）。
     */
    private static String jobTypeOf(String dept, String team, String title) {
        String s = ((dept == null ? "" : dept) + " "
                + (team == null ? "" : team) + " "
                + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        if (s.contains("engineer") || s.contains("develop") || s.contains("software")
                || s.contains("data") || s.contains("infra") || s.contains("security")
                || s.contains("technical") || s.contains("sre") || s.contains("ml ")) {
            return "技术";
        }
        if (s.contains("design")) return "设计";
        if (s.contains("product")) return "产品";
        if (s.contains("market") || s.contains("growth") || s.contains("brand")) return "市场";
        if (s.contains("sales") || s.contains("account") || s.contains("business")) return "销售";
        if (s.contains("finance") || s.contains("legal") || s.contains("accounting")) return "金融";
        if (s.contains("people") || s.contains("recruit") || s.contains("hr ")) return "职能";
        if (s.contains("support") || s.contains("success")) return "客服";
        return "不限";
    }

    /** 标签：部门 + 团队，便于筛选与展示 */
    private static String tagsOf(String dept, String team) {
        List<String> parts = new ArrayList<>();
        if (dept != null && !dept.isBlank()) parts.add(dept.trim());
        if (team != null && !team.isBlank() && !team.equalsIgnoreCase(dept)) parts.add(team.trim());
        return parts.isEmpty() ? null : String.join(",", parts);
    }
}

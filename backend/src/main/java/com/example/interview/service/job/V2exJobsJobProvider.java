package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V2EX 酷工作（国内中文活源，v1.45.0）
 *
 * <p><b>为什么加这个源：</b>线上实测（2026-10-01）招聘广场国内岗位 327 条
 * **100% 来自 8 个硬编码种子**（{@code Seed*.java}），更新依赖发版；
 * 而海外 3251 条来自 5 个实时 API。国内**实时源为零**，这是「国内岗位少」的根因。
 *
 * <p>主流国内平台（BOSS 直聘 / 智联 / 前程无忧 / 拉勾 / 实习僧 / 牛客）均无公开 JSON 接口，
 * 实测不是 403 就是 SPA 无数据；电鸭社区的 {@code /api/*} 全部 404。
 * **V2EX 酷工作是唯一验证通过的中文活源**（HTTP 200、结构化 JSON、每日滚动）。
 *
 * <p><b>接口</b>：{@code https://www.v2ex.com/api/topics/show.json?node_name=jobs}
 * —— 公开、无需 Key、无需登录。返回一个数组，每条是一个帖子：
 * {@code id / title / url / content / content_rendered / created / member{username} / node{...}}。
 *
 * <p><b>已知取舍（如实记录）</b>：
 * <ul>
 *   <li>该接口单次只返回**最新一页（约 8 条）**，没有分页参数；靠 3 小时一轮 + 按
 *       {@code externalId} 幂等去重持续累积。</li>
 *   <li>酷工作节点里混有「求职帖」和闲聊帖，按关键词过滤掉明显不是招聘的那些
 *       （见 {@link #looksLikeHiring}）。过滤后估计保留 60~70%。</li>
 *   <li>帖子**没有结构化的公司名与薪资**，只能从标题里尽力抽取；
 *       抽不到时公司名回退为「见岗位详情」，不编造。</li>
 *   <li>⚠️ **从 Render 出口的可达性尚未验证**（本机出口被代理拦截，只在 WebFetch 下成功）。
 *       上线后需用 {@code /api/admin/sources} 看该源的健康状态；失败时调度层会隔离，
 *       不影响其它源。</li>
 * </ul>
 */
@Component
public class V2exJobsJobProvider extends AbstractOpenApiJobProvider {

    /** 国内中文活源，不计入「海外远程」分栏 */
    private static final String PLATFORM = "V2EX 酷工作";

    /** 3 小时一轮：V2EX 限速约 120 次/时，3h 远低于该阈值 */
    private static final long REFRESH_INTERVAL_MS = 3 * 3600 * 1000L;

    /** 单轮配额：国内岗位本来就稀缺，不该再被 25 卡住 */
    private static final int QUOTA = 60;

    /** 标题里可能出现的城市/地点，按「越具体越靠前」排列 */
    private static final String[] LOCATIONS = {
            "北京", "上海", "深圳", "广州", "杭州", "成都", "南京", "武汉", "西安", "苏州",
            "长沙", "重庆", "天津", "合肥", "郑州", "青岛", "厦门", "福州", "济南", "大连",
            "宁波", "无锡", "珠海", "东莞", "佛山", "香港", "台北", "远程",
    };

    /** 出现这些词说明是「求职帖」而不是「招聘帖」，直接丢弃 */
    private static final String[] SEEKER_MARKERS = {
            "求职", "求内推", "找工作", "求一份", "个人简历", "求职者", "求推荐",
            "简历求", "找实习", "求实习", "求职中",
    };

    /** 出现这些词说明是招聘帖 */
    private static final String[] HIRING_MARKERS = {
            "招聘", "招人", "诚招", "急招", "招募", "内推", "岗位", "职位",
            "hiring", "Hiring", "招 ", "招个",
    };

    private final JobAgentProperties properties;

    public V2exJobsJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    /** 国内源：进「国内」分栏，不进「海外远程」 */
    @Override
    public boolean overseas() {
        return false;
    }

    @Override
    public long minRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }

    @Override
    protected int maxItemsPerRefresh() {
        return QUOTA;
    }

    @Override
    protected String endpoint() {
        return "https://www.v2ex.com/api/topics/show.json?node_name=jobs";
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return result;
        }
        for (JsonNode node : root) {
            String id = text(node, "id");
            String title = text(node, "title");
            if (id == null || title == null) {
                continue;
            }
            if (!looksLikeHiring(title)) {
                continue;
            }
            String url = text(node, "url");
            if (url == null) {
                url = "https://www.v2ex.com/t/" + id;
            }
            // 帖子正文优先用渲染后的 HTML（内容更完整），清洗成纯文本
            String body = text(node, "content_rendered", "content");

            result.add(new JobDto(
                    clip("v2ex-" + id, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(companyOf(title), LEN_COMPANY),
                    "互联网",
                    jobTypeOf(title),
                    clip(locationOf(title), LEN_LOCATION),
                    clip(salaryOf(title), LEN_SALARY),
                    "不限",
                    "不限",
                    recruitTypeOf(title),
                    null,   // 上游无截止信息 → 留空
                    clip(url, LEN_URL),
                    body == null ? null : plainText(body, DESC_MAX_LEN),
                    null,
                    clip("V2EX,酷工作", LEN_TAGS)
            ));
        }
        return result;
    }

    // ────────────────────────── 标题解析工具 ──────────────────────────

    /**
     * 判断是否像一条招聘帖。
     *
     * <p>先看「求职」类标记（一票否决），再看招聘类关键词。
     * 酷工作节点里求职帖占比不低，混进来会污染「岗位」语义。
     */
    private static boolean looksLikeHiring(String title) {
        for (String s : SEEKER_MARKERS) {
            if (title.contains(s)) return false;
        }
        for (String s : HIRING_MARKERS) {
            if (title.contains(s)) return true;
        }
        return false;
    }

    /**
     * 从标题里尽力抽取公司名。
     *
     * <p>抽不到就返回「见岗位详情」——**不编造公司名**。
     * 常见形态：{@code [北京] 某某科技 招聘 后端}、{@code 某某科技 - 招聘 Java}。
     */
    private static String companyOf(String title) {
        // 去掉开头的地区标记：[北京] 【上海】（深圳）(杭州)
        String t = title.replaceFirst("^\\s*[\\[【(（][^\\]】)）]{1,12}[\\]】)）]\\s*", "");
        for (String kw : new String[]{"招聘", "招人", "诚招", "急招", "招募", "内推", "hiring", "Hiring"}) {
            int i = t.indexOf(kw);
            if (i > 1) {
                String c = t.substring(0, i).replaceAll("[\\s\\-—|,，、:：/]+$", "").trim();
                if (!c.isBlank() && c.length() <= 40) {
                    return c;
                }
            }
        }
        return "见岗位详情";
    }

    /** 从标题里抽取城市；抽不到返回「不限」 */
    private static String locationOf(String title) {
        for (String city : LOCATIONS) {
            if (title.contains(city)) return city;
        }
        String lower = title.toLowerCase();
        if (lower.contains("remote")) return "远程";
        return "不限";
    }

    /** 从标题里抽取薪资（如 20k-40k、25-45K）；抽不到返回 null */
    private static String salaryOf(String title) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3}\\s*[kK]\\s*[-~至]\\s*\\d{1,3}\\s*[kK])")
                .matcher(title);
        return m.find() ? m.group(1).replaceAll("\\s+", "") : null;
    }

    /**
     * 招聘类型：标题含「实习」判实习、含「校招/秋招/春招/应届」判秋招，其余按社招。
     *
     * <p>取值必须落在平台既有枚举内，否则前端筛选与统计会对不上。
     */
    private static String recruitTypeOf(String title) {
        if (title.contains("实习")) return "INTERN";
        if (title.contains("校招") || title.contains("秋招") || title.contains("春招")
                || title.contains("应届")) return "AUTUMN";
        return "SOCIAL";
    }

    /** 职位类型：与其它源的枚举保持一致（技术/产品/设计/运营/市场/销售/职能…） */
    private static String jobTypeOf(String title) {
        String t = title.toLowerCase();
        if (matchAny(t, "前端", "后端", "java", "python", "golang", "go ", "rust", "c++",
                "运维", "测试", "算法", "架构", "开发", "工程师", "devops", "sre", "数据",
                "android", "ios", "全栈", "安全", "嵌入式")) return "技术";
        if (matchAny(t, "产品经理", "产品", "product")) return "产品";
        if (matchAny(t, "设计", "ui", "ux", "视觉")) return "设计";
        if (matchAny(t, "运营", "operation")) return "运营";
        if (matchAny(t, "市场", "marketing", "增长", "品牌")) return "市场";
        if (matchAny(t, "销售", "sales", "商务", "bd")) return "销售";
        if (matchAny(t, "人事", "hr", "财务", "法务", "行政")) return "职能";
        if (matchAny(t, "客服", "支持", "support")) return "客服";
        return "技术";
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}

package com.example.interview.service.agent;

import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 智能体工具层（Career Copilot 的能力集）
 *
 * 非单例 Bean：每次请求由 AgentService 构造新实例并绑定当前 userId，
 * 避免 ThreadLocal 在 reactor 线程切换时失效导致的数据串扰。
 *
 * 设计约束：
 * - 只读安全：全部为查询操作，无写接口，接受用户输入仅作检索参数
 * - 结果裁剪：限制条数与字符长度，防止单次工具调用撑爆上下文
 * - 提示词层工具协议：因 B.AI 网关不支持 API 级 function calling（tools 参数返回 400），
 *   工具通过 ToolSpec 注册表以文本协议暴露给模型，dispatch() 统一执行
 */
public class AgentTools {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentTools.class);

    private static final int MAX_TEXT_LEN = 1500;

    /** 工具规格：名称、用途描述、参数说明、执行器（paramsJson 原文 + 解析后的键值对 → 结果文本） */
    public record ToolSpec(String name, String description, String paramsDoc,
                           BiFunction<String, Map<String, Object>, String> executor) {
    }

    private final String userId;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ToolSpec> registry = new LinkedHashMap<>();

    private final JobAgentService jobAgentService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewEventService interviewEventService;
    private final RagSearchService ragSearchService;
    private final com.example.interview.service.job.WebJobSearcherService webJobSearcherService;
    private final com.example.interview.service.job.JobMatchService jobMatchService;
    private final com.example.interview.service.InterviewService interviewService;
    /**
     * 简历服务（v1.34.1 P3-11）：出题工具需要用户简历做个性化。
     * 允许为 null（切片测试/未注入场景），使用时做空值保护并降级为通用出题。
     */
    private final com.example.interview.service.ResumeService resumeService;

    /**
     * 求职 Skill 服务（v1.35.0）：职业资产四层挖掘 + 岗位节奏计划。
     * 允许为 null（切片测试未注入），使用时做空值保护并给出引导文案。
     */
    private final com.example.interview.service.career.CareerProfileService careerProfileService;

    /**
     * 投递台账服务（v1.35.0）：让智能体能读到用户的投递进度与待跟进项。
     * 允许为 null（切片测试未注入），使用时做空值保护。
     */
    private final com.example.interview.service.job.JobApplicationService jobApplicationService;

    /**
     * 面试故事库服务（v1.36.0）：STAR 故事提炼（interview-story-bank 思路）。
     * 允许为 null（切片测试未注入），使用时做空值保护。
     */
    private final com.example.interview.service.career.StoryBankService storyBankService;

    public AgentTools(String userId,
                      JobAgentService jobAgentService,
                      InterviewSessionService interviewSessionService,
                      InterviewEventService interviewEventService,
                      RagSearchService ragSearchService,
                      com.example.interview.service.job.WebJobSearcherService webJobSearcherService,
                      com.example.interview.service.job.JobMatchService jobMatchService,
                      com.example.interview.service.InterviewService interviewService) {
        this(userId, jobAgentService, interviewSessionService, interviewEventService, ragSearchService,
                webJobSearcherService, jobMatchService, interviewService, null, null, null, null);
    }

    public AgentTools(String userId,
                      JobAgentService jobAgentService,
                      InterviewSessionService interviewSessionService,
                      InterviewEventService interviewEventService,
                      RagSearchService ragSearchService,
                      com.example.interview.service.job.WebJobSearcherService webJobSearcherService,
                      com.example.interview.service.job.JobMatchService jobMatchService,
                      com.example.interview.service.InterviewService interviewService,
                      com.example.interview.service.ResumeService resumeService) {
        this(userId, jobAgentService, interviewSessionService, interviewEventService, ragSearchService,
                webJobSearcherService, jobMatchService, interviewService, resumeService, null, null, null);
    }

    public AgentTools(String userId,
                      JobAgentService jobAgentService,
                      InterviewSessionService interviewSessionService,
                      InterviewEventService interviewEventService,
                      RagSearchService ragSearchService,
                      com.example.interview.service.job.WebJobSearcherService webJobSearcherService,
                      com.example.interview.service.job.JobMatchService jobMatchService,
                      com.example.interview.service.InterviewService interviewService,
                      com.example.interview.service.ResumeService resumeService,
                      com.example.interview.service.career.CareerProfileService careerProfileService,
                      com.example.interview.service.job.JobApplicationService jobApplicationService) {
        this(userId, jobAgentService, interviewSessionService, interviewEventService, ragSearchService,
                webJobSearcherService, jobMatchService, interviewService, resumeService,
                careerProfileService, jobApplicationService, null);
    }

    public AgentTools(String userId,
                      JobAgentService jobAgentService,
                      InterviewSessionService interviewSessionService,
                      InterviewEventService interviewEventService,
                      RagSearchService ragSearchService,
                      com.example.interview.service.job.WebJobSearcherService webJobSearcherService,
                      com.example.interview.service.job.JobMatchService jobMatchService,
                      com.example.interview.service.InterviewService interviewService,
                      com.example.interview.service.ResumeService resumeService,
                      com.example.interview.service.career.CareerProfileService careerProfileService,
                      com.example.interview.service.job.JobApplicationService jobApplicationService,
                      com.example.interview.service.career.StoryBankService storyBankService) {
        this.userId = userId;
        this.jobAgentService = jobAgentService;
        this.interviewSessionService = interviewSessionService;
        this.interviewEventService = interviewEventService;
        this.resumeService = resumeService;
        this.ragSearchService = ragSearchService;
        this.webJobSearcherService = webJobSearcherService;
        this.jobMatchService = jobMatchService;
        this.interviewService = interviewService;
        this.careerProfileService = careerProfileService;
        this.jobApplicationService = jobApplicationService;
        this.storyBankService = storyBankService;
        registerTools();
    }

    private void registerTools() {
        registry.put("searchJobs", new ToolSpec("searchJobs",
                "搜索本平台的岗位库（含秋招/春招/社招/实习/兼职/定向专项，覆盖互联网、制造、能源、医药、"
                        + "金融、建筑、物流、教育、政务、法律等行业，也包含海外远程岗位），"
                        + "返回岗位列表：标题、企业、地点、薪资、学历、招聘类型、数据来源、截止日期、申请链接。"
                        + "默认只检索国内岗位；用户明确想看海外/远程机会时才传 overseas=true。"
                        + "回答用户「有什么岗位/帮我找岗位」时必须以本工具的真实返回为准，"
                        + "并把筛选范围与命中数量一并说明，不得凭记忆编造岗位",
                "keyword(可选,岗位名/企业名/标签), industry(可选,如:互联网/金融/制造/能源/医疗/建筑), "
                        + "jobType(可选,如:技术/产品/运营/销售/职能/金融/教研), location(可选,如:深圳/杭州), "
                        + "recruitType(可选,AUTUMN秋招/SPRING春招/SOCIAL社招/INTERN实习/PART_TIME兼职/"
                        + "TARGETED定向专项；用户未指定或不确定时留空表示不限，不要臆断为秋招), "
                        + "overseas(可选,true=只看海外/远程岗位；缺省或 false=只看国内岗位)",
                (raw, params) -> searchJobs(str(params, "keyword"), str(params, "industry"),
                        str(params, "jobType"), str(params, "location"), str(params, "recruitType"),
                        asBool(params, "overseas"))));
        registry.put("searchWebJobs", new ToolSpec("searchWebJobs",
                "【联网实时搜索】通过联网搜索各大招聘平台（BOSS直聘/智联/拉勾/前程无忧等）的全国实时岗位信息，返回真实岗位：标题、企业、地点、薪资。用于用户要求最新/全网岗位，或本地岗位不足时",
                "keyword(必填,岗位关键词如:Java/产品/算法), location(可选,城市如:深圳,空或全国表示全国范围)",
                (raw, params) -> searchWebJobs(str(params, "keyword"), str(params, "location"))));
        registry.put("matchResumeJobs", new ToolSpec("matchResumeJobs",
                "根据用户粘贴的简历要点（技能/项目/学历）为岗位自动匹配打分，推荐最吻合的岗位列表（含匹配分）。用于用户给出/粘贴了简历内容时推荐适合他的岗位",
                "resumeText(必填,简历核心内容或技术要点,如:熟悉Java/Spring/Redis,本科)",
                (raw, params) -> matchResumeJobs(str(params, "resumeText"))));
        registry.put("generateInterviewQuestions", new ToolSpec("generateInterviewQuestions",
                "为求职者生成模拟面试练习题（含分类与难度），用于用户要求练习/出题/模拟面试时。会自动结合用户画像与目标岗位方向",
                "count(可选,题目数量,默认5,1-10), direction(可选,岗位/技术方向如:Java后端/算法/前端), difficulty(可选,EASY/MEDIUM/HARD)",
                (raw, params) -> generateInterviewQuestions(
                        intVal(params, "count"), str(params, "direction"), str(params, "difficulty"))));
        registry.put("deepFollowUp", new ToolSpec("deepFollowUp",
                "基于用户对某道面试题的作答，生成一道深挖细节/定位盲区的针对性追问（下钻回答中最薄弱或最有价值的一点）。用于用户在面试练习中已作答、想进一步深挖时",
                "question(必填,原始面试题), userAnswer(必填,用户的作答内容), resumeText(可选,简历要点用于结合背景)",
                (raw, params) -> deepFollowUp(str(params, "question"),
                        str(params, "userAnswer"), str(params, "resumeText"))));
        registry.put("searchKnowledge", new ToolSpec("searchKnowledge",
                "检索平台知识库中的面试知识点（Java/Spring/数据库/中间件等），用于回答技术面试题",
                "query(必填,要检索的知识主题)",
                (raw, params) -> searchKnowledge(str(params, "query"))));
        registry.put("getMyInterviewStats", new ToolSpec("getMyInterviewStats",
                "获取当前用户的模拟面试统计：总题数、错题数、平均分、各分类掌握度，用于薄弱点分析",
                "无参数",
                (raw, params) -> getMyInterviewStats()));
        registry.put("listWrongQuestions", new ToolSpec("listWrongQuestions",
                "获取当前用户最近答错/低分的面试题列表（含题目、分类、得分），用于针对性复习",
                "limit(可选,最多返回条数,默认8)",
                (raw, params) -> listWrongQuestions(intVal(params, "limit"))));
        registry.put("getUpcomingInterviews", new ToolSpec("getUpcomingInterviews",
                "获取当前用户面试日历中的面试安排（企业、时间、状态）",
                "无参数",
                (raw, params) -> getUpcomingInterviews()));
        registry.put("mineCareerAssets", new ToolSpec("mineCareerAssets",
                "【求职Skill】用「证据→行为→能力→可投岗位信号」四层结构，从用户口语化的真实经历中挖掘可迁移能力与可投岗位方向。"
                        + "用于用户说「不知道能投什么岗」「帮我看看我的优势」「我该找什么工作」时",
                "narrative(必填,用户对自身经历的口语化描述,越具体越好), targetTrack(可选,期望赛道/岗位方向如:Java后端/数据分析)",
                (raw, params) -> mineCareerAssets(str(params, "narrative"), str(params, "targetTrack"))));
        registry.put("getMyApplications", new ToolSpec("getMyApplications",
                "获取当前用户的投递台账概况：各状态数量、转化漏斗、以及需要跟进的投递（超过7天无回复或已到跟进时间）。"
                        + "用于用户问「我投了哪些」「有没有回复」「谁该催一下」时",
                "无参数",
                (raw, params) -> getMyApplications()));
        registry.put("prepareInterviewStories", new ToolSpec("prepareInterviewStories",
                "【求职Skill·面试故事库】把用户的真实经历整理成 STAR 结构的面试故事候选（情境/任务/行动/结果/证据/能力标签），"
                        + "并给出六项质检要点（结构/证据/贴合岗位/废话/风险表达/经得起追问）。"
                        + "用于用户说「帮我准备面试自我介绍」「把我的经历整理成面试话术」「面试怎么讲这个项目」时",
                "narrative(必填,用户对自身经历的口语化描述,越具体越好), targetTrack(可选,目标岗位/赛道)",
                (raw, params) -> prepareInterviewStories(str(params, "narrative"), str(params, "targetTrack"))));
    }

    /** 全部工具规格（按注册顺序，用于生成提示词协议） */
    public Map<String, ToolSpec> all() {
        return registry;
    }

    /** 按动作名执行工具；未知动作返回错误提示 */
    public String dispatch(String action, String paramsJson) {
        ToolSpec spec = registry.get(action);
        if (spec == null) {
            return "错误：未知工具 " + action + "，可用工具：" + String.join("/", registry.keySet());
        }
        Map<String, Object> params = parseParams(paramsJson);
        try {
            return spec.executor().apply(paramsJson, params);
        } catch (Exception e) {
            return "工具执行失败：" + e.getMessage();
        }
    }

    // ---------- 具体工具实现 ----------

    /**
     * 岗位检索（兼容旧签名：不传海外标识，等价于只检索国内岗位）。
     *
     * <p>保留重载是为了让既有调用点与单测不必为一个新增筛选维度全体改签名。
     * 注意参数表已到 6 个，可读性接近上限——将来若还要加维度，应像
     * {@code JobQuery} 那样收敛成条件对象，而不是继续堆参数。
     */
    public String searchJobs(String keyword, String industry, String jobType, String location, String recruitType) {
        return searchJobs(keyword, industry, jobType, location, recruitType, null);
    }

    /**
     * 1. 岗位检索
     *
     * <p><b>v1.38.0 修正「智能体给的岗位不准」的根因</b>：此前 {@code recruitType} 缺省时被
     * 硬编码为 {@code AUTUMN}，于是当用户问「有社招的 Java 岗位吗」而模型没显式传类型时，
     * 智能体只在秋招里检索，再把「秋招里没有」当成「平台没有这类岗位」答复用户。
     *
     * <p>现在：① 缺省即不限类型；② 返回内容带上「筛选范围 + 命中总数」，让模型能如实转述
     * 「在什么范围内、找到多少个」，而不是含糊其辞；③ 每条岗位补上招聘类型与数据来源，
     * 用户追问「这岗位哪来的/什么时候的」时有据可依。
     */
    public String searchJobs(String keyword, String industry, String jobType, String location,
                             String recruitType, Boolean overseas) {
        try {
            String type = normalizeRecruitType(recruitType);
            // v1.38.0：默认只检索国内岗位。海外/远程源（RemoteOK、Remotive、Jobicy、
            // Himalayas、Arbeitnow）的岗位是英文的，用户问「社招岗位」时混进来会明显降低
            // 回答可用性；只有用户明确想看海外/远程机会时才由模型传 overseas=true。
            boolean onlyOverseas = Boolean.TRUE.equals(overseas);
            var page = jobAgentService.search(
                    blankToNull(keyword), blankToNull(industry), blankToNull(jobType), blankToNull(location),
                    type, null, null, null, onlyOverseas, 0, 8);

            String scope = describeJobFilters(keyword, industry, jobType, location, type, onlyOverseas);
            List<com.example.interview.entity.JobPostingEntity> items = page.getContent();
            if (items.isEmpty()) {
                return "未找到匹配岗位。本次筛选范围：" + scope
                        + "｜当前岗位库共有 " + jobAgentService.activeJobCount() + " 个有效岗位。"
                        + "请如实告知用户「在你给的条件范围内没有找到」，并引导其放宽关键词/城市、"
                        + "明确想要的招聘类型（秋招/春招/社招/实习/兼职），"
                        + "或建议其在「招聘广场」手动刷新数据后再查。不要编造岗位，也不要声称平台没有岗位。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("筛选范围：").append(scope)
                    .append("｜命中 ").append(page.getTotalElements()).append(" 个，")
                    .append("展示前 ").append(items.size()).append(" 个（均为本平台岗位库的真实数据）：\n");
            for (var j : items) {
                sb.append("- ").append(j.getTitle())
                        .append(" | ").append(j.getCompanyName())
                        .append(" | ").append(j.getLocation() == null ? "地点未标注" : j.getLocation())
                        .append(" | ").append(j.getSalary() == null ? "面议" : j.getSalary())
                        .append(" | 学历:").append(j.getDegree() == null ? "不限" : j.getDegree())
                        .append(" | 类型:").append(recruitTypeLabel(j.getRecruitType()));
                if (j.getPlatform() != null && !j.getPlatform().isBlank()) {
                    sb.append(" | 来源:").append(j.getPlatform());
                }
                if (j.getDeadline() != null) {
                    sb.append(" | 截止:").append(j.getDeadline());
                }
                if (j.getApplyUrl() != null && !j.getApplyUrl().isBlank()) {
                    sb.append(" | 申请:").append(j.getApplyUrl());
                }
                sb.append("\n");
            }
            return truncate(sb);
        } catch (Exception e) {
            return "岗位检索暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 招聘类型归一。
     *
     * <p>把「空值 / 模型爱写的字符串 "null" / 中文类型名 / 非法值」统一处理成
     * 平台使用的枚举或 {@code null}（表示不限）。非法值一律归为不限而不是原样下传——
     * 否则模型偶尔吐出的自由文本会让查询条件变成「类型必须等于 JAVA」这类永远为假的过滤，
     * 用户看到的就是「平台没岗位」。
     */
    private static String normalizeRecruitType(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)
                || "不限".equals(trimmed) || "全部".equals(trimmed) || "any".equalsIgnoreCase(trimmed)) {
            return null;
        }
        String v = trimmed.toUpperCase();
        return switch (v) {
            case "秋招", "校招", "校园招聘" -> "AUTUMN";
            case "春招" -> "SPRING";
            case "社招", "社会招聘" -> "SOCIAL";
            case "实习", "实习生" -> "INTERN";
            case "兼职", "临时" -> "PART_TIME";
            case "定向", "专项", "选调" -> "TARGETED";
            case "AUTUMN", "SPRING", "SOCIAL", "INTERN", "PART_TIME", "TARGETED" -> v;
            default -> null;
        };
    }

    /** 招聘类型中文名：让模型转述人话，而不是把 AUTUMN 这样的枚举念给用户 */
    private static String recruitTypeLabel(String type) {
        if (type == null || type.isBlank()) return "未标注";
        return switch (type.toUpperCase()) {
            case "AUTUMN" -> "秋招";
            case "SPRING" -> "春招";
            case "SOCIAL" -> "社招";
            case "INTERN" -> "实习";
            case "PART_TIME" -> "兼职";
            case "TARGETED" -> "定向专项";
            default -> type;
        };
    }

    /** 把本次筛选条件转成人类可读的一句话，供模型如实说明检索范围 */
    private static String describeJobFilters(String keyword, String industry, String jobType,
                                             String location, String recruitType, boolean onlyOverseas) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        // 地域范围始终显式写出：海外岗位是英文的，用户需要知道结果切的是哪个池子
        parts.add(onlyOverseas ? "范围=海外远程岗位" : "范围=国内岗位");
        if (blankToNull(keyword) != null) parts.add("关键词=" + keyword.trim());
        if (blankToNull(industry) != null) parts.add("行业=" + industry.trim());
        if (blankToNull(jobType) != null) parts.add("职位类型=" + jobType.trim());
        if (blankToNull(location) != null) parts.add("地点=" + location.trim());
        if (blankToNull(recruitType) != null) parts.add("招聘类型=" + recruitTypeLabel(recruitType));
        return String.join("、", parts);
    }

    /** 从工具参数里取布尔值：兼容模型输出 true/"true"/"null"/空 等形态 */
    private static Boolean asBool(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return Boolean.parseBoolean(s);
    }

    /** 2. 联网实时岗位搜索（v1.31.0：全国范围，真实抓取为主，失败降级本地库） */
    public String searchWebJobs(String keyword, String location) {
        String kw = blankToNull(keyword);
        String loc = blankToNull(location);
        // v1.38.0：此前关键词缺省时硬编码为 "Java"。用户说「帮我找产品经理岗位」而模型漏传
        // 关键词时，智能体会拿回一堆 Java 岗位并当成用户想要的结果回复——这是「岗位不准」
        // 的另一处根因。现在缺关键词直接走本地岗位库，按用户给出的其他条件检索，
        // 不再凭空假设技术栈。
        if (kw == null || "null".equalsIgnoreCase(kw)) {
            return fallbackToLocalJobs(null, loc);
        }
        List<com.example.interview.service.job.WebJobSearcherService.WebJob> jobs;
        try {
            jobs = webJobSearcherService.searchWeb(kw, loc);
        } catch (Exception e) {
            jobs = List.of();
        }
        // 联网实时抓取无可用源时，降级到本地聚合库，保证智能体"回复正常"
        if (jobs.isEmpty()) {
            return fallbackToLocalJobs(kw, loc);
        }
        StringBuilder sb = new StringBuilder("联网实时搜索到 ").append(jobs.size()).append(" 个全国岗位（数据来自公开招聘平台）：\n");
        for (var j : jobs) {
            sb.append("- ").append(j.title())
                    .append(" | ").append(j.company() == null || j.company().isBlank() ? "企业待确认" : j.company())
                    .append(" | ").append(j.location() == null || j.location().isBlank() ? "地点待确认" : j.location())
                    .append(" | ").append(j.salary() == null || j.salary().isBlank() ? "面议" : j.salary());
            if (j.degree() != null && !j.degree().isBlank()) {
                sb.append(" | 学历:").append(j.degree());
            }
            if (j.applyUrl() != null && !j.applyUrl().isBlank()) {
                sb.append(" | 申请:").append(j.applyUrl());
            }
            sb.append("\n");
        }
        sb.append("\n提示：以上岗位为联网实时抓取，投递前请以招聘平台页面为准。");
        return truncate(sb);
    }

    /** 联网搜索无结果时回退本地聚合岗位（两种数据合流，保证回复可用） */
    private String fallbackToLocalJobs(String kw, String loc) {
        try {
            // 兜底同样只查国内岗位（overseas=false）：与 searchJobs 的口径一致，
            // 否则「联网搜不到」时会拿一堆英文海外岗位来充数
            var page = jobAgentService.search(kw, null, null, loc, null, null, null, null, false, 0, 8);
            List<com.example.interview.entity.JobPostingEntity> items = page.getContent();
            if (items.isEmpty()) {
                return "当前联网搜索与本地岗位库均未找到匹配岗位。可提示用户：换关键词（如 Java/产品/算法）、换城市，或确认网络可访问公开招聘站点；也可在「招聘广场」刷新数据。";
            }
            StringBuilder sb = new StringBuilder("联网实时搜索暂未抓取到新岗位，已为你从本地岗位库推荐 ").append(items.size()).append(" 个相近岗位：\n");
            for (var j : items) {
                sb.append("- ").append(j.getTitle())
                        .append(" | ").append(j.getCompanyName())
                        .append(" | ").append(j.getLocation() == null ? "地点未标注" : j.getLocation())
                        .append(" | ").append(j.getSalary() == null ? "面议" : j.getSalary());
                if (j.getApplyUrl() != null && !j.getApplyUrl().isBlank()) {
                    sb.append(" | 申请:").append(j.getApplyUrl());
                }
                sb.append("\n");
            }
            return truncate(sb);
        } catch (Exception e) {
            return "联网搜索与本地岗位库暂时都不可用，请稍后再试或换一种问法。";
        }
    }

    /** 简历 × 岗位 匹配推荐（v1.31.2）：用户粘贴简历要点 → 推荐最吻合岗位 */
    public String matchResumeJobs(String resumeText) {
        if (resumeText == null || resumeText.isBlank() || "null".equalsIgnoreCase(resumeText)) {
            return "请提供简历核心内容（如：熟悉Java/Spring/Redis，本科）以便为你匹配岗位";
        }
        try {
            List<com.example.interview.service.job.JobMatchService.MatchResult> matched =
                    jobMatchService.match(resumeText.trim(), jobAgentService.activeJobs(), 8);
            if (matched.isEmpty()) {
                return "暂未找到与这份简历吻合的岗位。可补充更多技术栈/项目关键词（如：并发/微服务/前端/Vue），或让我联网搜索更多岗位。";
            }
            StringBuilder sb = new StringBuilder("根据你的简历为你推荐 ").append(matched.size()).append(" 个高吻合岗位：\n");
            for (var r : matched) {
                var j = r.job();
                sb.append("- [匹配 ").append(r.matchScore()).append(" 分] ").append(j.getTitle())
                        .append(" | ").append(j.getCompanyName())
                        .append(" | ").append(j.getLocation() == null ? "地点未标注" : j.getLocation())
                        .append(" | ").append(j.getSalary() == null ? "面议" : j.getSalary())
                        .append(" | 命中技能:").append(String.join("/", r.matchedSkills()));
                if (j.getApplyUrl() != null && !j.getApplyUrl().isBlank()) {
                    sb.append(" | 申请:").append(j.getApplyUrl());
                }
                sb.append("\n");
            }
            return truncate(sb);
        } catch (Exception e) {
            return "简历匹配暂时不可用：" + e.getMessage();
        }
    }

    /** 生成模拟面试练习题（v1.31.2）：调 InterviewService.generateQuestions，结合目标方向与难度 */
    public String generateInterviewQuestions(Integer count, String direction, String difficulty) {
        try {
            int n = (count == null || count < 1) ? 5 : Math.min(count, 10);
            String jobDesc = blankToNull(direction) == null ? "通用技术岗" : direction.trim();
            String diff = (difficulty == null || difficulty.isBlank()) ? "" : difficulty.trim().toUpperCase();
            if (!List.of("", "EASY", "MEDIUM", "HARD").contains(diff)) {
                diff = "";
            }
            // v1.34.1 修复（P3-11）：此前 resumeText 传空串，出题**丢失用户简历上下文**，
            // 生成的是通用题而非针对该用户经历/技术栈的题。现取用户最近一次简历文本，
            // 取不到时仍降级为空串（与旧行为一致，不影响可用性）。
            String resumeContext = resolveLatestResumeText();
            String json = interviewService.generateQuestions(userId, resumeContext, jobDesc, n, diff, "");
            if (json == null || json.isBlank()) {
                return "出题失败，请稍后重试或换个岗位方向。";
            }
            // 解析题目 JSON，整理为可读列表
            var node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder("已为你生成 ").append(n).append(" 道模拟面试题");
            if (!jobDesc.equals("通用技术岗")) {
                sb.append("（方向：").append(jobDesc).append("）");
            }
            sb.append("：\n");
            if (node.isArray()) {
                int idx = 1;
                for (var q : node) {
                    if (idx > n) break;
                    String qtext = q.path("question").asText("");
                    String cat = q.path("category").asText("");
                    String d = q.path("difficulty").asText("");
                    if (qtext.isBlank()) continue;
                    sb.append(idx++).append(". [").append(cat.isBlank() ? "未分类" : cat)
                            .append("/").append(d.isBlank() ? "MEDIUM" : d).append("] ").append(qtext).append("\n");
                }
                if (idx == 1) {
                    sb.append("（未解析到题目，请尝试减少数量或换方向）\n");
                }
            } else {
                sb.append(json.length() > 300 ? json.substring(0, 300) : json).append("\n");
            }
            sb.append("\n提示：需要的话我可以在后续对话中帮你逐题作答并点评，或给出参考答案。");
            return truncate(sb);
        } catch (Exception e) {
            return "出题暂时不可用：" + e.getMessage();
        }
    }

    /** 基于作答生成深挖追问（v1.31.2）：复用 InterviewService.generateFollowUp */
    public String deepFollowUp(String question, String userAnswer, String resumeText) {
        if (question == null || question.isBlank() || "null".equalsIgnoreCase(question)
                || userAnswer == null || userAnswer.isBlank() || "null".equalsIgnoreCase(userAnswer)) {
            return "请提供面试题与你的作答内容，以便我基于此生成针对性追问。";
        }
        try {
            String followUp = interviewService.generateFollowUp(
                    question.trim(), userAnswer.trim(),
                    (resumeText == null || "null".equalsIgnoreCase(resumeText)) ? "" : resumeText.trim());
            if (followUp == null || followUp.isBlank()) {
                return "追问生成失败，请稍后重试。";
            }
            StringBuilder sb = new StringBuilder("基于你的回答，为你生成一道深挖追问：\n");
            sb.append(followUp.trim());
            sb.append("\n\n提示：你可以继续作答这道追问，我可以在后续对话中给出参考答案与点评。");
            return truncate(sb);
        } catch (Exception e) {
            return "追问生成暂时不可用：" + e.getMessage();
        }
    }

    /** 3. 知识库检索（RAG） */
    public String searchKnowledge(String query) {
        if (query == null || query.isBlank() || "null".equalsIgnoreCase(query)) {
            return "检索主题不能为空";
        }
        try {
            String result = ragSearchService.search(query.trim(), 3, userId);
            return truncate(new StringBuilder(result == null || result.isBlank() ? "知识库中未找到相关内容" : result));
        } catch (Exception e) {
            return "知识库检索暂时不可用：" + e.getMessage();
        }
    }

    /** 3. 面试表现统计 */
    public String getMyInterviewStats() {
        try {
            Map<String, Object> summary = interviewSessionService.questionSummary(userId);
            StringBuilder sb = new StringBuilder("面试练习统计：\n");
            sb.append("- 总题数:").append(summary.getOrDefault("totalQuestions", 0)).append("\n");
            sb.append("- 已答题:").append(summary.getOrDefault("answeredQuestions", 0)).append("\n");
            sb.append("- 错题数:").append(summary.getOrDefault("wrongQuestions", 0)).append("\n");
            Object avg = summary.get("averageScore");
            sb.append("- 平均分:").append(avg instanceof Double ? String.format("%.1f", (Double) avg) : avg).append("\n");
            appendStats(sb, "分类掌握度", summary.get("byCategory"));
            appendStats(sb, "难度掌握度", summary.get("byDifficulty"));
            return truncate(sb);
        } catch (Exception e) {
            return "面试统计暂时不可用：" + e.getMessage();
        }
    }

    /** 4. 错题列表 */
    public String listWrongQuestions(Integer limit) {
        try {
            int n = (limit == null || limit < 1 || limit > 15) ? 8 : limit;
            List<InterviewQuestionEntity> wrong = interviewSessionService.listWrongQuestionsByUser(userId, 60);
            if (wrong.isEmpty()) {
                return "暂无错题记录，用户表现不错！可以建议用户开始一场新的模拟面试。";
            }
            StringBuilder sb = new StringBuilder("共 ").append(wrong.size())
                    .append(" 道错题，最近 ").append(Math.min(n, wrong.size())).append(" 道：\n");
            wrong.stream().limit(n).forEach(q -> sb.append("- [").append(q.getCategory() == null ? "未分类" : q.getCategory())
                    .append("/").append(q.getDifficulty() == null ? "MEDIUM" : q.getDifficulty())
                    .append(" 得分").append(q.getEvaluationScore()).append("] ")
                    .append(shorten(q.getQuestion(), 60)).append("\n"));
            return truncate(sb);
        } catch (Exception e) {
            return "错题查询暂时不可用：" + e.getMessage();
        }
    }

    /** 5. 面试日历 */
    public String getUpcomingInterviews() {
        try {
            List<InterviewEventEntity> events = interviewEventService.listByUser(userId);
            if (events.isEmpty()) {
                return "面试日历为空。可以建议用户在「学习中心-面试日历」中添加面试安排。";
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            StringBuilder sb = new StringBuilder("面试安排共 ").append(events.size()).append(" 条：\n");
            events.stream().limit(10).forEach(e -> sb.append("- ").append(e.getTitle())
                    .append(" | ").append(e.getInterviewAt() == null ? "时间待定" : e.getInterviewAt().format(fmt))
                    .append(" | ").append(e.getStatus() == null ? "UPCOMING" : e.getStatus()).append("\n"));
            return truncate(sb);
        } catch (Exception e) {
            return "面试日历查询暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 求职 Skill：职业资产四层挖掘（v1.35.0）
     *
     * <p>把「证据→行为→能力→可投岗位信号」框架的产出整理为可读文本，
     * 让智能体在对话里直接给出结构化诊断，而不是甩一段 JSON。
     */
    public String mineCareerAssets(String narrative, String targetTrack) {
        if (narrative == null || narrative.isBlank() || "null".equalsIgnoreCase(narrative)) {
            return "请先用几句口语把你自己真实做过的事讲出来（比如「我在学校做过一个XX项目，负责……结果……」），"
                    + "我再帮你按「证据→行为→能力→可投岗位信号」四层挖出可迁移能力。";
        }
        if (careerProfileService == null) {
            return "职业资产挖掘服务暂不可用，请稍后重试。";
        }
        try {
            String json = careerProfileService.mine(userId, narrative.trim(),
                    (targetTrack == null || "null".equalsIgnoreCase(targetTrack)) ? "" : targetTrack.trim());
            var node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            String positioning = node.path("positioning").asText("");
            if (!positioning.isBlank()) {
                sb.append("【职业定位】").append(positioning).append("\n\n");
            }
            var assets = node.path("assets");
            if (assets.isArray() && !assets.isEmpty()) {
                sb.append("【职业资产（证据→行为→能力→岗位信号）】\n");
                int i = 1;
                for (var a : assets) {
                    if (i > 6) break;
                    sb.append(i++).append(". 证据：").append(oneLine(a.path("evidence").asText(""))).append("\n");
                    sb.append("   行为：").append(oneLine(a.path("behavior").asText(""))).append("\n");
                    sb.append("   能力：").append(oneLine(a.path("capability").asText(""))).append("\n");
                    var signals = a.path("jobSignals");
                    if (signals.isArray() && !signals.isEmpty()) {
                        List<String> sig = new ArrayList<>();
                        signals.forEach(s -> sig.add(s.asText("")));
                        sb.append("   可投岗位信号：").append(String.join(" / ", sig)).append("\n");
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("（未能从这段描述中提取出足够具体的证据，建议补充：你具体做了什么、结果是什么、有没有数字）\n\n");
            }
            appendStringArray(sb, "优势标签", node.path("strengthTags"));
            appendStringArray(sb, "信息缺口（需补充）", node.path("blindSpots"));
            appendStringArray(sb, "下一步行动", node.path("nextSteps"));
            sb.append("提示：想进一步定位到具体岗位，可以让我用 planCareerTrack 做一份「为什么适合/差距/30天补什么/适合什么赛道」的节奏计划。");
            return truncate(sb);
        } catch (Exception e) {
            return "职业资产挖掘暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 投递台账概况（v1.35.0）：各状态计数 + 转化漏斗 + 待跟进清单
     */
    public String getMyApplications() {
        if (jobApplicationService == null) {
            return "投递台账服务暂不可用，请稍后重试。";
        }
        try {
            Map<String, Object> board = jobApplicationService.board(userId);
            Object total = board.getOrDefault("total", 0);
            if (total instanceof Number n && n.intValue() == 0) {
                return "你的投递台账还是空的。可以在「招聘广场」或岗位收藏里点「加入投递计划」，"
                        + "我会帮你跟踪每家的投递状态与回复情况。";
            }
            StringBuilder sb = new StringBuilder("投递台账概况（共 ").append(total).append(" 条）：\n");
            if (board.get("counts") instanceof Map<?, ?> counts) {
                for (var entry : counts.entrySet()) {
                    int c = entry.getValue() instanceof Number cn ? cn.intValue() : 0;
                    if (c > 0) {
                        sb.append("- ").append(
                                com.example.interview.service.job.JobApplicationService.label(String.valueOf(entry.getKey())))
                                .append("：").append(c).append(" 条\n");
                    }
                }
            }
            if (board.get("funnel") instanceof Map<?, ?> funnel) {
                sb.append("转化漏斗：已投 ").append(numOf(funnel, "submitted"))
                        .append(" → 有回复 ").append(numOf(funnel, "repliedOrBeyond"))
                        .append(" → 面试 ").append(numOf(funnel, "interviewOrBeyond"))
                        .append(" → Offer ").append(numOf(funnel, "offer")).append("\n");
            }
            Object followUps = board.get("followUps");
            if (followUps instanceof List<?> list && !list.isEmpty()) {
                sb.append("\n【需要跟进】\n");
                for (Object o : list) {
                    if (o instanceof com.example.interview.entity.JobApplicationEntity a) {
                        sb.append("- ").append(a.getTitle()).append(" | ").append(a.getCompanyName())
                                .append(" | 当前状态：")
                                .append(com.example.interview.service.job.JobApplicationService.label(a.getStatus()))
                                .append(a.getApplyUrl() == null || a.getApplyUrl().isBlank()
                                        ? "" : " | " + a.getApplyUrl())
                                .append("\n");
                    }
                }
                sb.append("建议：超过 7 天无回复的可以礼貌催一下 HR，或把精力转向新的机会。\n");
            }
            return truncate(sb);
        } catch (Exception e) {
            return "投递台账查询暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 面试故事库（v1.36.0）：把真实经历整理成 STAR 故事候选 + 六项质检要点
     *
     * <p>落地「interview-story-bank」思路：面试不是背答案，是经得起追问。
     * 提炼结果为候选，用户可在「求职诊断」页确认后存入故事库并反复练习。
     */
    public String prepareInterviewStories(String narrative, String targetTrack) {
        if (narrative == null || narrative.isBlank() || "null".equalsIgnoreCase(narrative)) {
            return "请先用几句口语把你自己真实做过的事讲出来（做了什么、结果怎样、有没有数字），"
                    + "我再帮你整理成 STAR 结构的面试故事：情境-任务-行动-结果，确保经得起追问。";
        }
        if (storyBankService == null) {
            return "面试故事库服务暂不可用，请稍后重试。";
        }
        try {
            String json = storyBankService.extract(userId, narrative.trim(),
                    (targetTrack == null || "null".equalsIgnoreCase(targetTrack)) ? "" : targetTrack.trim());
            var node = objectMapper.readTree(json);
            var stories = node.path("stories");
            if (!stories.isArray() || stories.isEmpty()) {
                return "这段描述还不足以提炼出完整的故事，建议补充：你具体做了什么、遇到了什么问题、"
                        + "结果是什么、有没有数字。也可以到「求职诊断」页重新提炼。";
            }
            StringBuilder sb = new StringBuilder("已把你的经历整理成 ").append(stories.size())
                    .append(" 条 STAR 面试故事候选：\n\n");
            int i = 1;
            for (var s : stories) {
                if (i > 4) break;
                sb.append("故事 ").append(i++).append("：").append(oneLine(s.path("title").asText(""))).append("\n");
                sb.append("  情境：").append(oneLine(s.path("situation").asText(""))).append("\n");
                sb.append("  任务：").append(oneLine(s.path("task").asText(""))).append("\n");
                sb.append("  行动：").append(oneLine(s.path("action").asText(""))).append("\n");
                sb.append("  结果：").append(oneLine(s.path("result").asText(""))).append("\n");
                String evidence = oneLine(s.path("evidence").asText(""));
                if (!evidence.isBlank()) {
                    sb.append("  证据：").append(evidence).append("\n");
                }
                var caps = s.path("capabilities");
                if (caps.isArray() && !caps.isEmpty()) {
                    List<String> cap = new ArrayList<>();
                    caps.forEach(c -> cap.add(c.asText("")));
                    sb.append("  能力标签：").append(String.join(" / ", cap)).append("\n");
                }
                sb.append("\n");
            }
            sb.append("六项质检提醒（讲之前自查）：结构是否清楚 / 证据是否具体 / 是否贴合岗位 / ")
              .append("有没有废话 / 有没有风险表达 / 能否被追问住。\n")
              .append("提示：到「求职诊断」页可把这些故事存入你的面试故事库，反复练习「先回答，再追问，再复盘」。");
            return truncate(sb);
        } catch (Exception e) {
            return "面试故事整理暂时不可用：" + e.getMessage();
        }
    }

    // ---------- 私有辅助 ----------

    /** 追加字符串数组为列表行（用于 strengthTags / blindSpots / nextSteps） */
    private static void appendStringArray(StringBuilder sb, String label, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return;
        }
        sb.append("【").append(label).append("】\n");
        node.forEach(n -> {
            String v = n.asText("");
            if (!v.isBlank()) {
                sb.append("- ").append(oneLine(v)).append("\n");
            }
        });
        sb.append("\n");
    }

    /** 把多行文本压成单行，避免破坏列表结构 */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\r", " ").trim();
    }

    /** 从通配 Map 中读取数值（用于看板 funnel 等无固定泛型的结构） */
    private static int numOf(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private Map<String, Object> parseParams(String paramsJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (paramsJson == null || paramsJson.isBlank()) {
            return params;
        }
        try {
            var node = objectMapper.readTree(paramsJson);
            if (node.isObject()) {
                node.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().isValueNode()
                        ? e.getValue().asText() : e.getValue().toString()));
            }
        } catch (Exception ignored) {
            // 参数解析失败按空参数处理
        }
        return params;
    }

    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s.trim();
    }

    @SuppressWarnings("unchecked")
    private static void appendStats(StringBuilder sb, String label, Object stats) {
        if (stats instanceof List<?> list && !list.isEmpty()) {
            sb.append("- ").append(label).append(": ");
            int i = 0;
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw && i < 6) {
                    Map<String, Object> m = (Map<String, Object>) raw;
                    sb.append(m.getOrDefault("category", m.getOrDefault("difficulty", "?")))
                            .append("(").append(m.getOrDefault("avgScore", 0)).append("分) ");
                    i++;
                }
            }
            sb.append("\n");
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s.replace("\n", " ") : s.substring(0, max).replace("\n", " ") + "...";
    }

    /**
     * 取当前用户最近一次简历文本，供出题工具做个性化（v1.34.1，P3-11）。
     *
     * <p>取不到（未上传简历 / 服务不可用 / 切片测试未注入）时返回空串，
     * 出题自动降级为「按岗位方向通用出题」，与修复前行为一致，不影响可用性。
     */
    private String resolveLatestResumeText() {
        if (resumeService == null) {
            return "";
        }
        try {
            var history = resumeService.listByUser(userId);
            if (history == null || history.isEmpty()) {
                return "";
            }
            String content = history.get(0).getContent();
            return content == null ? "" : content;
        } catch (Exception e) {
            log.warn("智能体出题取用户简历失败，降级为通用出题：{}", e.getMessage());
            return "";
        }
    }

    private static String truncate(StringBuilder sb) {
        return sb.length() <= MAX_TEXT_LEN ? sb.toString() : sb.substring(0, MAX_TEXT_LEN) + "\n...(结果已截断)";
    }
}

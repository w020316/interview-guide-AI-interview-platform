package com.example.interview.service.agent;

import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
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

    public AgentTools(String userId,
                      JobAgentService jobAgentService,
                      InterviewSessionService interviewSessionService,
                      InterviewEventService interviewEventService,
                      RagSearchService ragSearchService) {
        this.userId = userId;
        this.jobAgentService = jobAgentService;
        this.interviewSessionService = interviewSessionService;
        this.interviewEventService = interviewEventService;
        this.ragSearchService = ragSearchService;
        registerTools();
    }

    private void registerTools() {
        registry.put("searchJobs", new ToolSpec("searchJobs",
                "搜索招聘平台聚合的岗位信息（秋招/社招/实习），返回岗位列表：标题、企业、地点、薪资、截止日期、申请链接",
                "keyword(可选,岗位名/企业名/标签), industry(可选,如:互联网/金融/制造/能源), jobType(可选,如:技术/产品/运营), location(可选,如:深圳), recruitType(可选,AUTUMN/SPRING/INTERN/SOCIAL,默认AUTUMN)",
                (raw, params) -> searchJobs(str(params, "keyword"), str(params, "industry"),
                        str(params, "jobType"), str(params, "location"), str(params, "recruitType"))));
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

    /** 1. 岗位检索 */
    public String searchJobs(String keyword, String industry, String jobType, String location, String recruitType) {
        try {
            var page = jobAgentService.search(
                    blankToNull(keyword), blankToNull(industry), blankToNull(jobType), blankToNull(location),
                    (recruitType == null || recruitType.isBlank() || "null".equalsIgnoreCase(recruitType))
                            ? "AUTUMN" : recruitType.toUpperCase(),
                    null, null, null, 0, 8);
            List<com.example.interview.entity.JobPostingEntity> items = page.getContent();
            if (items.isEmpty()) {
                return "未找到匹配岗位。建议：放宽筛选条件，或提示用户在「招聘广场」页手动刷新数据。";
            }
            StringBuilder sb = new StringBuilder("共 ").append(page.getTotalElements())
                    .append(" 个匹配岗位，前 ").append(items.size()).append(" 个：\n");
            for (var j : items) {
                sb.append("- ").append(j.getTitle())
                        .append(" | ").append(j.getCompanyName())
                        .append(" | ").append(j.getLocation() == null ? "地点未标注" : j.getLocation())
                        .append(" | ").append(j.getSalary() == null ? "面议" : j.getSalary())
                        .append(" | 学历:").append(j.getDegree() == null ? "不限" : j.getDegree());
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

    /** 2. 知识库检索（RAG） */
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

    // ---------- 私有辅助 ----------

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

    private static String truncate(StringBuilder sb) {
        return sb.length() <= MAX_TEXT_LEN ? sb.toString() : sb.substring(0, MAX_TEXT_LEN) + "\n...(结果已截断)";
    }
}

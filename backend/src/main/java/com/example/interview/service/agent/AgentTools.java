package com.example.interview.service.agent;

import com.example.interview.repository.JobPostingRepository;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 智能体工具层（Career Copilot 的能力集）
 *
 * 非单例 Bean：每次请求由 AgentService 构造新实例并绑定当前 userId，
 * 避免 ThreadLocal 在 reactor 线程切换时失效导致的数据串扰。
 *
 * 设计约束：
 * - 只读安全：全部为查询操作，无写接口，接受用户输入仅作检索参数
 * - 结果裁剪：限制条数与字符长度，防止单次工具调用撑爆上下文
 */
public class AgentTools {

    private static final int MAX_TEXT_LEN = 1500;

    private final String userId;
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
    }

    /** 1. 岗位检索 */
    @Tool(description = "搜索招聘平台聚合的岗位信息（秋招/社招/实习）。当用户想找工作、看岗位、查企业招聘信息时调用。" +
            "返回岗位列表：标题、企业、地点、薪资、学历要求、截止日期、申请链接。")
    public String searchJobs(
            @ToolParam(description = "关键词：岗位名/企业名/标签，可为空", required = false) String keyword,
            @ToolParam(description = "行业：互联网/金融/制造/能源/教育/医疗/快消/通信/硬件等，可为空", required = false) String industry,
            @ToolParam(description = "职位类型：技术/产品/运营/设计/市场/职能/金融等，可为空", required = false) String jobType,
            @ToolParam(description = "工作地点，如：深圳/杭州/北京，可为空", required = false) String location,
            @ToolParam(description = "招聘类型：AUTUMN秋招/SPRING春招/INTERN实习/SOCIAL社招，默认 AUTUMN", required = false) String recruitType) {
        try {
            Page<com.example.interview.entity.JobPostingEntity> page = jobAgentService.search(
                    keyword, industry, jobType, location,
                    (recruitType == null || recruitType.isBlank()) ? "AUTUMN" : recruitType.toUpperCase(),
                    null, 0, 8);
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
    @Tool(description = "检索平台知识库中的面试知识点（Java/Spring/数据库/中间件/八股文等）。" +
            "当用户问技术知识点、面试题解法、概念解释时调用，返回最相关的知识片段。")
    public String searchKnowledge(
            @ToolParam(description = "要检索的知识主题或问题，如：Redis 持久化") String query) {
        if (query == null || query.isBlank()) {
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
    @Tool(description = "获取当前用户的模拟面试表现统计：总题数、已答题数、错题数、平均分、按分类/难度的掌握度。" +
            "当用户问自己的面试表现、薄弱点、成绩时调用。")
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
    @Tool(description = "获取当前用户最近答错/低分的面试题列表（含题目、分类、得分）。" +
            "当用户想回顾错题、针对性复习时调用。")
    public String listWrongQuestions(
            @ToolParam(description = "最多返回条数，默认 8", required = false) Integer limit) {
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
    @Tool(description = "获取当前用户面试日历中的面试安排（企业、时间、状态）。" +
            "当用户问面试安排、日程规划时调用。")
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

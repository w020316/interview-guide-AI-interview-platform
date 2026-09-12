package com.example.interview.service.job;

import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import com.example.interview.util.JsonRepairUtil;
import org.springframework.ai.chat.model.ChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位智能分类服务（招聘信息智能体的 AI 能力层）
 *
 * 用主模型对缺失行业/职位类型的岗位做智能归类与打标；
 * AI 不可用时自动降级为规则分类，保证数据入库不受影响。
 */
@Service
public class JobClassifyService {

    private static final Logger log = LoggerFactory.getLogger(JobClassifyService.class);

    private static final List<String> INDUSTRIES = List.of(
            "互联网", "金融", "制造", "能源", "教育", "医疗", "快消", "通信", "硬件", "房地产", "物流", "其他");
    private static final List<String> JOB_TYPES = List.of(
            "技术", "产品", "运营", "设计", "市场", "职能", "金融", "综合");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobClassifyService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 单条分类结果 */
    public record Classification(String industry, String jobType, String tags) {
    }

    /**
     * 批量分类（每批最多 10 条，控制 token 消耗）
     *
     * @param items 待分类岗位（取 标题/企业/描述摘要 作为输入）
     * @return 与入参顺序一一对应的分类结果；AI 失败时逐条降级为规则分类
     */
    public List<Classification> classifyBatch(List<JobDto> items) {
        List<Classification> result = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i += 10) {
            List<JobDto> batch = items.subList(i, Math.min(i + 10, items.size()));
            try {
                result.addAll(classifyByAi(batch));
            } catch (Exception e) {
                log.warn("AI 岗位分类失败，降级规则分类：{}", e.getMessage());
                for (JobDto job : batch) {
                    result.add(classifyByRule(job));
                }
            }
        }
        return result;
    }

    /** AI 分类：要求严格 JSON 数组输出（双引号） */
    private List<Classification> classifyByAi(List<JobDto> batch) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            JobDto j = batch.get(i);
            String desc = j.description() == null ? "" : j.description();
            if (desc.length() > 150) {
                desc = desc.substring(0, 150);
            }
            sb.append(i + 1).append(". 岗位：").append(j.title())
                    .append(" | 企业：").append(j.companyName())
                    .append(" | 描述：").append(desc).append("\n");
        }
        String prompt = """
                你是招聘数据分析专家。请对以下岗位逐条分类，仅输出 JSON 数组，使用标准双引号，不要输出任何其他文字。
                每个元素格式：{"index":1,"industry":"行业","jobType":"职位类型","tags":["标签1","标签2"]}
                行业限定：%s；职位类型限定：%s；标签 2-4 个（如 校招/AI/高薪/国企 等）。

                岗位列表：
                %s""".formatted(String.join("/", INDUSTRIES), String.join("/", JOB_TYPES), sb);

        String content = chatModel.call(
                new org.springframework.ai.chat.prompt.Prompt(prompt)
        ).getResult().getOutput().getText();

        // 模型 JSON 容错修复（单引号/控制字符等），失败则抛出走规则降级
        String repaired = JsonRepairUtil.repair(content);

        List<Classification> list = new ArrayList<>();
        JsonNode arr = objectMapper.readTree(repaired);
        if (!arr.isArray()) {
            throw new IllegalStateException("AI 分类返回非 JSON 数组");
        }
        for (JsonNode node : arr) {
            int idx = node.path("index").asInt(1) - 1;
            String industry = node.path("industry").asText(null);
            String jobType = node.path("jobType").asText(null);
            StringBuilder tags = new StringBuilder();
            if (node.has("tags") && node.get("tags").isArray()) {
                for (JsonNode t : node.get("tags")) {
                    if (tags.length() > 0) tags.append(",");
                    tags.append(t.asText());
                }
            }
            if (idx >= 0 && idx < batch.size()) {
                list.add(new Classification(
                        normalize(industry, INDUSTRIES),
                        normalize(jobType, JOB_TYPES),
                        tags.length() == 0 ? null : tags.toString()));
            }
        }
        // 条数不足时对缺失部分走规则兜底
        while (list.size() < batch.size()) {
            list.add(classifyByRule(batch.get(list.size())));
        }
        return list;
    }

    /** 规则分类兜底 */
    public Classification classifyByRule(JobDto job) {
        String text = (job.title() + " " + job.companyName() + " " + (job.description() == null ? "" : job.description()))
                .toLowerCase();
        String industry = matchFirst(text, java.util.Map.ofEntries(
                java.util.Map.entry("金融|银行|证券|基金|保险|投行", "金融"),
                java.util.Map.entry("互联网|科技|软件|电商|游戏|在线", "互联网"),
                java.util.Map.entry("电池|汽车|制造|工业|半导体|芯片", "制造"),
                java.util.Map.entry("电网|能源|电力|石油|光伏", "能源"),
                java.util.Map.entry("教育|学校|培训", "教育"),
                java.util.Map.entry("医院|医药|生物|健康", "医疗"),
                java.util.Map.entry("快消|零售|食品|饮料", "快消"),
                java.util.Map.entry("通信|电信|5g", "通信"),
                java.util.Map.entry("硬件|电子|无人机|家电", "硬件"),
                java.util.Map.entry("地产|房地产|置业", "房地产"),
                java.util.Map.entry("物流|快递|供应链", "物流")
        ));
        String jobType = matchFirst(text, java.util.Map.ofEntries(
                java.util.Map.entry("开发|工程师|算法|架构|测试|运维|研发|程序员|软件", "技术"),
                java.util.Map.entry("产品经理|产品", "产品"),
                java.util.Map.entry("运营|用户增长|活动策划", "运营"),
                java.util.Map.entry("设计|ui|ux|视觉", "设计"),
                java.util.Map.entry("市场|营销|品牌|销售|商务", "市场"),
                java.util.Map.entry("人力|行政|财务|法务|人事", "职能"),
                java.util.Map.entry("量化|风控|投行|分析师|金融", "金融")
        ));
        return new Classification(
                industry == null ? "其他" : industry,
                jobType == null ? (industry != null ? "综合" : "综合") : jobType,
                "校招");
    }

    private static String matchFirst(String text, java.util.Map<String, String> rules) {
        for (var e : rules.entrySet()) {
            for (String kw : e.getKey().split("\\|")) {
                if (text.contains(kw)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /** 限定枚举值，超出范围归为空（由调用方兜底） */
    private static String normalize(String v, List<String> allowed) {
        if (v == null || v.isBlank()) return null;
        for (String a : allowed) {
            if (v.contains(a)) return a;
        }
        return null;
    }
}

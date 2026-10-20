package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键投递助手（v1.67.0）
 *
 * ── 这个类到底做什么 ────────────────────────────────────────────────
 * 把「投这一份简历」需要的东西一次凑齐：岗位信息、已定制的简历要点、
 * 打招呼话术、投递前检查清单、以及<b>明确的合规提示</b>。
 * 用户拿到这一屏，复制话术 → 打开招聘平台 → 自己点提交 → 回来推进状态。
 *
 * ── 为什么刻意不调 AI ───────────────────────────────────────────────
 * 定制简历（{@code TailoredResumeService}）已经是一次 AI 调用，产出里就带着
 * {@code matchedKeywords} / {@code rewrittenBullets}。话术完全可以用这些**已有结果**
 * 拼装出来，再调一次模型只会：
 * <ul>
 *   <li>多一个失败点（限流、空正文、JSON 不合规），且失败时用户什么都拿不到；</li>
 *   <li>多等十几秒，而这一步本该是「点一下就出来」的即时操作。</li>
 * </ul>
 * 产品评审对投递侧的结论是「做薄的一键」——不加新能力，只做串联。
 * 因此本类<b>纯规则组装、零网络、零模型调用</b>，响应稳定在毫秒级。
 *
 * ── 合规边界（与项目红线一致，改动前先读 JobApplicationService 类注释）──
 * 本类<b>不</b>代理招聘平台接口、<b>不</b>读写平台登录态、<b>不</b>自动填表或提交。
 * 产出的 {@code applyUrl} 只供前端在新标签页打开，实际投递动作必须由用户本人完成。
 */
@Service
public class ApplyKitService {

    private static final Logger log = LoggerFactory.getLogger(ApplyKitService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 话术里引用的匹配关键词最多列几个（多了变成堆砌，反而不可信） */
    private static final int MAX_GREETING_KEYWORDS = 3;

    /** 话术里引用的一条成果最多取多少字（微信/BOSS 打招呼有长度限制） */
    private static final int MAX_EVIDENCE_LEN = 60;

    /** 合规提示：固定文案，前端直接展示，不允许被省略 */
    public static final String COMPLIANCE_NOTE =
            "本平台不会代替你登录招聘平台，也不会自动提交简历。"
                    + "点击「打开投递页」会在新标签页打开招聘网站，请由你本人完成投递并确认。";

    /**
     * 组装投递助手数据。
     *
     * @param app 已经过归属校验的投递记录（调用方负责校验 userId）
     */
    public Map<String, Object> build(JobApplicationEntity app) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId());

        // ── 岗位信息 ──
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("title", app.getTitle());
        job.put("companyName", app.getCompanyName());
        job.put("platform", app.getPlatform());
        job.put("location", app.getLocation());
        job.put("salary", app.getSalary());
        job.put("applyUrl", app.getApplyUrl());
        job.put("deadline", app.getDeadline() == null ? null : app.getDeadline().toString());
        job.put("status", app.getStatus());
        result.put("job", job);

        // ── 定制简历要点（解析失败不抛错：历史数据可能不是合法 JSON）──
        TailoredParts parts = parseTailored(app.getTailoredResume());
        result.put("hasTailored", !parts.rewritten.isEmpty());
        result.put("matchedKeywords", parts.matched);
        result.put("missingKeywords", parts.missing);

        // ── 打招呼话术（基于已有结果拼装，不编造）──
        result.put("greeting", buildGreeting(app, parts));

        // ── 投递前检查清单 ──
        result.put("checklist", buildChecklist(app, parts));

        result.put("compliance", COMPLIANCE_NOTE);
        return result;
    }

    /** 从定制简历 JSON 中提取三个可用片段 */
    private TailoredParts parseTailored(String raw) {
        TailoredParts parts = new TailoredParts();
        if (raw == null || raw.isBlank()) {
            return parts;
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                return parts;
            }
            parts.matched = readStringArray(root.get("matchedKeywords"));
            parts.missing = readStringArray(root.get("missingKeywords"));
            JsonNode bullets = root.get("rewrittenBullets");
            if (bullets != null && bullets.isArray()) {
                for (JsonNode b : bullets) {
                    if (b != null && b.isObject()) {
                        String rewritten = b.path("rewritten").asText("");
                        if (!rewritten.isBlank()) {
                            parts.rewritten.add(rewritten.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 定制简历是历史快照，格式可能来自旧版本；解析不了就当没有，
            // 不影响投递助手其余部分（检查清单会提示「尚未定制」）
            log.debug("定制简历 JSON 解析失败，按无定制处理：{}", e.getMessage());
        }
        return parts;
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().trim());
            }
        }
        return out;
    }

    /**
     * 打招呼话术。
     *
     * <p><b>不编造原则</b>：只有在真的拿到匹配关键词时才写「我在 X 方面有经验」，
     * 拿不到就只写意图表达。宁可话术朴素，也不能让用户发出去一句自己兑现不了的话。
     */
    private String buildGreeting(JobApplicationEntity app, TailoredParts parts) {
        StringBuilder sb = new StringBuilder();
        String title = app.getTitle() == null || app.getTitle().isBlank() ? "该" : app.getTitle();
        String company = app.getCompanyName() == null ? "" : app.getCompanyName().trim();

        sb.append("您好");
        if (!company.isEmpty()) {
            sb.append("，我是看到").append(company).append("「").append(title).append("」岗位的候选人");
        } else {
            sb.append("，我看到贵司「").append(title).append("」岗位的招聘信息");
        }
        sb.append("。");

        List<String> kws = parts.matched.stream().limit(MAX_GREETING_KEYWORDS).toList();
        if (!kws.isEmpty()) {
            sb.append("我在").append(String.join("、", kws)).append("方面有直接的项目经验");
            if (!parts.rewritten.isEmpty()) {
                String evidence = parts.rewritten.get(0);
                if (evidence.length() > MAX_EVIDENCE_LEN) {
                    evidence = evidence.substring(0, MAX_EVIDENCE_LEN) + "…";
                }
                sb.append("，例如：").append(evidence);
            }
            sb.append("。");
        } else {
            // 没有匹配关键词时明确建议用户自己补一句，而不是让模板假装有
            sb.append("（建议在此补一句你与该岗位最相关的一段经历。）");
        }

        sb.append("简历已附上，期待有机会进一步沟通。");
        return sb.toString();
    }

    /**
     * 投递前检查清单。
     *
     * <p>每条都是<b>可判定的事实</b>，不是鼓励性文案：
     * done 由数据推导，hint 说明不满足时该做什么。
     */
    private List<Map<String, Object>> buildChecklist(JobApplicationEntity app, TailoredParts parts) {
        List<Map<String, Object>> list = new ArrayList<>();

        boolean tailored = !parts.rewritten.isEmpty();
        list.add(item("tailored", "简历已按该岗位定制",
                tailored,
                tailored ? null : "先在「定制简历」里生成要点，再投递命中率更高"));

        boolean hasUrl = app.getApplyUrl() != null && !app.getApplyUrl().isBlank();
        list.add(item("applyUrl", "有可直接打开的投递入口",
                hasUrl,
                hasUrl ? null : "该岗位没有投递链接，请到原平台搜索岗位名后手动投递"));

        boolean deadlineOk = app.getDeadline() == null || !app.getDeadline().isBefore(LocalDate.now());
        String deadlineHint = null;
        if (!deadlineOk) {
            deadlineHint = "截止日期已过（" + app.getDeadline() + "），建议确认岗位是否仍在招";
        }
        list.add(item("deadline", "截止日期未过", deadlineOk, deadlineHint));

        boolean planned = "PLANNED".equals(app.getStatus());
        list.add(item("status", "状态为「待投递」", planned,
                planned ? null : "该记录已推进过状态，如需重新投递请先确认是否重复投递"));

        boolean missingWarn = !parts.missing.isEmpty();
        list.add(item("missing", "待补关键词已知悉", !missingWarn,
                missingWarn ? "JD 要求但你的材料里未体现：" + String.join("、", parts.missing)
                        + "。这些不要写进简历，可在沟通中如实说明" : null));

        return list;
    }

    private static Map<String, Object> item(String key, String label, boolean done, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("done", done);
        m.put("hint", hint);
        return m;
    }

    /** 定制简历 JSON 里对本类有用的三个片段 */
    private static final class TailoredParts {
        private List<String> matched = new ArrayList<>();
        private List<String> missing = new ArrayList<>();
        private final List<String> rewritten = new ArrayList<>();
    }
}

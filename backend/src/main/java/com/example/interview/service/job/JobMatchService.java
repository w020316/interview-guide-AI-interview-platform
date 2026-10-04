package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 简历 × 岗位 匹配度打分（v1.29.0）
 *
 * 用简历中的技术画像与岗位标题/标签/描述做关键词匹配，输出吻合的技能与综合得分，据此为求职者推荐岗位。
 * 纯规则、确定性、可单测；不调用模型，稳定且零成本。
 */
@Service
public class JobMatchService {

    /** 常见技术画像词表：命中简历即视为候选技能点 */
    private static final String[] SKILL_KEYWORDS = {
            "java", "spring", "springboot", "mybatis", "hibernate", "redis", "mysql", "postgresql",
            "postgres", "mongo", "mongodb", "kafka", "rabbitmq", "rocketmq", "elasticsearch", "es",
            "git", "docker", "kubernetes", "k8s", "linux", "jvm", "netty", "dubbo", "grpc", "websocket",
            "并发", "多线程", "分布式", "微服务", "缓存", "消息队列", "大数据", "算法", "机器学习", "深度学习",
            "大模型", "nlp", "python", "go", "golang", "c++", "c语言", "javascript", "ts", "typescript",
            "react", "vue", "前端", "flutter", "android", "ios", "测试", "运维", "数据分析", "sql", "hive",
            "spark", "flink"
    };

    /**
     * 匹配结果
     *
     * @param missingSkills JD 中明确出现、但简历画像里没有的技能（短板）。按词表顺序输出（确定）。
     *                      <b>规则推导、零 AI</b> —— 与简历优化「未具备只能进 missingKeywords 待补」的
     *                      不编造原则同源：缺什么就说缺什么，不猜、不编。
     */
    public record MatchResult(JobPostingEntity job, int matchScore, List<String> matchedSkills,
                              List<String> missingSkills) {
    }

    /**
     * 技能命中器：ASCII 词用「词边界」正则（根治 java ⊂ javascript、es ⊂ redis 这类子串误报）；
     * 含非 ASCII 的词（并发/分布式/c语言）没有词边界概念，保持子串匹配。
     * 边界用前后 lookaround 而非 \b —— \b 对 c++ 结尾、k8s 这类拼写不可靠。
     */
    private record SkillMatcher(String keyword, java.util.regex.Pattern boundary) {
        /** 严格命中：词边界匹配（ASCII）或子串匹配（中文） */
        boolean hitStrict(String lowerText) {
            if (boundary != null) {
                return boundary.matcher(lowerText).find();
            }
            return lowerText.contains(keyword);
        }

        /** 宽松命中：子串匹配 —— 仅用于「短板豁免」（简历以更具体的形式覆盖了该技能） */
        boolean hitLenient(String lowerText) {
            return lowerText.contains(keyword);
        }
    }

    private static final List<SkillMatcher> SKILL_MATCHERS = buildSkillMatchers();

    private static List<SkillMatcher> buildSkillMatchers() {
        List<SkillMatcher> out = new ArrayList<>(SKILL_KEYWORDS.length);
        for (String kw : SKILL_KEYWORDS) {
            String k = kw.toLowerCase(Locale.ROOT);
            boolean ascii = true;
            for (int i = 0; i < k.length() && ascii; i++) {
                ascii = k.charAt(i) < 128;
            }
            if (ascii) {
                out.add(new SkillMatcher(k, java.util.regex.Pattern.compile(
                        "(?<![A-Za-z0-9])" + java.util.regex.Pattern.quote(k) + "(?![A-Za-z0-9])")));
            } else {
                out.add(new SkillMatcher(k, null));
            }
        }
        return out;
    }

    /** 提取文本中的技能画像（小写，归一化；词边界匹配，无子串误报） */
    public Set<String> extractSkills(String resumeText) {
        Set<String> result = new HashSet<>();
        if (resumeText == null) {
            return result;
        }
        String lower = resumeText.toLowerCase(Locale.ROOT);
        for (SkillMatcher m : SKILL_MATCHERS) {
            if (m.hitStrict(lower)) {
                result.add(m.keyword());
            }
        }
        return result;
    }

    /**
     * 对岗位列表按简历匹配度评分并按分数倒序返回。
     * 分数 = 命中技能数 × 20 + 学历精确匹配 +10（本科及以上等取关键词 '本科'）；
     * 未命中任何技能不计分。
     */
    public List<MatchResult> match(String resumeText, List<JobPostingEntity> jobs, int limit) {
        String resumeLower = resumeText == null ? "" : resumeText.toLowerCase(Locale.ROOT);
        List<MatchResult> result = new ArrayList<>();
        if (jobs == null) {
            return result;
        }
        for (JobPostingEntity job : jobs) {
            // 必须先小写：词边界正则区分大小写，而 JD 文本是原始大小写（Java/Spring/Redis…）
            String jdText = concat(job).toLowerCase(Locale.ROOT);
            List<String> hit = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (SkillMatcher m : SKILL_MATCHERS) {
                // 严格命中：JD 明确出现该技能（词边界，避免 es ⊂ redis / java ⊂ javascript 误报）
                if (!m.hitStrict(jdText)) {
                    continue;
                }
                if (m.hitStrict(resumeLower)) {
                    hit.add(m.keyword());               // 优势：双方词边界命中
                } else if (!m.hitLenient(resumeLower)) {
                    missing.add(m.keyword());           // 简历文本完全没提 → 短板
                }
                // 简历以更具体形式覆盖（如 postgres ⊂ postgresql）→ 既不算优势也不列短板
            }
            if (hit.isEmpty()) {
                continue; // 完全无关的岗位不推荐
            }
            // 相近词去重：postgres/postgresql、go/golang、es/elasticsearch 同指一项技术，
            // 不去重会出现「同一技术两个名字占两条短板」的噪声（词表天然成对，此问题必然发生）。
            missing = dedupeSkills(missing);
            int score = hit.size() * 20;
            String degree = job.getDegree() == null ? "" : job.getDegree();
            if (degree.contains("本科") && resumeText != null
                    && resumeText.toLowerCase(Locale.ROOT).contains("本科")) {
                score += 10;
            }
            if (degree.contains("硕士") && resumeText != null
                    && resumeText.toLowerCase(Locale.ROOT).contains("硕士")) {
                score += 10;
            }
            result.add(new MatchResult(job, score, hit, missing));
        }
        result.sort((a, b) -> Integer.compare(b.matchScore(), a.matchScore()));
        return limit > 0 ? result.stream().limit(limit).toList() : result;
    }

    private String concat(JobPostingEntity job) {
        StringBuilder sb = new StringBuilder();
        if (job == null) return sb.toString();
        append(sb, job.getTitle());
        append(sb, job.getTags());
        append(sb, job.getDescription());
        append(sb, job.getRequirements());
        append(sb, job.getJobType());
        return sb.toString();
    }

    private void append(StringBuilder sb, String s) {
        if (s != null) {
            sb.append(' ').append(s);
        }
    }

    /**
     * 相近技能去重：若某技能是列表中另一技能的**子串**（postgres ⊂ postgresql、go ⊂ golang、
     * es ⊂ elasticsearch），保留更具体（更长）的一个，丢弃短名 —— 否则同一技术会以两个名字
     * 重复出现在短板列表里。顺带消除「es 命中自 redis」这类子串误报。
     *
     * <p>顺序保持不变（按词表顺序），只做过滤。仅用于短板展示，不影响打分。
     */
    private static List<String> dedupeSkills(List<String> ordered) {
        List<String> out = new ArrayList<>();
        for (String s : ordered) {
            boolean covered = false;
            for (String t : ordered) {
                if (!t.equals(s) && t.contains(s)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                out.add(s);
            }
        }
        return out;
    }
}
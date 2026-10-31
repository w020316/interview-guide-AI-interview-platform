package com.example.interview.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 限流路径配置的「结构与漂移」双重锁（P1 死规则的结构性防线）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>P1 死规则（{@code /autofill}）的根因不是写错一个字符串，而是
 * <b>「配置里的规则」与「代码里真实存在的端点」之间没有任何自动校验</b>：
 * 前端用子串匹配、后端用段边界匹配，一份清单跨处搬移后就可能有一条永远匹配不到的规则，
 * 而所有测试仍然全绿（因为测试也是照着配置写的）。
 *
 * <p>本测试补两道锁：
 *
 * <ol>
 *   <li><b>规则 ↔ 真实端点全表</b>：{@code DEFAULT_AI_PATH_PREFIXES} 里的<b>每一条</b>
 *       都必须在这张表里出现，并且能真正命中一个真实端点（连续两次请求，第 2 次被限流）。
 *       规则增删而表未同步 → 直接变红；表里写了但真实路径不匹配（死规则）→ 直接变红。</li>
 *   <li><b>配置与代码默认值一致</b>：{@code application.yml} 里
 *       {@code app.ai.user-rate-limit.paths} 的默认值必须与 Java 常量逐项一致，
 *       防止「只改 yml 或只改常量」导致两处漂移（yml 生效、常量变成过期文档）。</li>
 * </ol>
 *
 * <p>表里的「真实端点」全部来自后端 {@code @RequestMapping} 的实际映射，
 * 并标注了 controller 行号；<b>不允许用前端 AI_PATH_FRAGMENTS 填这张表</b>
 * —— 那正是 P1 的错误来源。
 */
@DisplayName("AI 限流路径配置审计（规则必须命中真实端点）")
class AiPathConfigAuditTest {

    private static final String IP = "203.0.113.20";
    private static final String USER = "audit-user";

    /**
     * 配置规则 → 后端真实端点（controller:line 为映射声明位置）。
     *
     * <p>顺序与 {@link AiRateLimitFilter#DEFAULT_AI_PATH_PREFIXES} 保持一致，
     * 便于人工比对；断言使用 containsExactly 以保证两边同步。
     */
    private static Map<String, String> ruleToRealEndpoint() {
        Map<String, String> table = new LinkedHashMap<>();
        // AgentController:44 /api/agent + :81 POST /chat/stream（ReAct 循环，走 expensive 配额）
        table.put("/api/agent/", "/api/agent/chat/stream");
        // InterviewController:39 + :102/:188/:235/:301/:327
        table.put("/api/interview/", "/api/interview/questions");
        // JobAnalysisController:29 + :150
        table.put("/api/job/", "/api/job/analyze");
        // JobAgentController:44 + :252 POST /refresh（复数 jobs，注意与单数 job 区分）
        table.put("/api/jobs/refresh", "/api/jobs/refresh");
        // KnowledgeController:62 POST /ask
        table.put("/api/knowledge/ask", "/api/knowledge/ask");
        // KnowledgeController:70 POST /import（另有 :107 /import/batch 由同一条前缀覆盖）
        table.put("/api/knowledge/import", "/api/knowledge/import");
        // KnowledgeController:55 GET /search
        table.put("/api/knowledge/search", "/api/knowledge/search");
        // ResumeController:88 / :112 / :252 / :297 / :447
        table.put("/api/resume/analyze", "/api/resume/analyze");
        table.put("/api/resume/upload", "/api/resume/upload");
        table.put("/api/resume/optimize", "/api/resume/optimize");
        table.put("/api/resume/import-url", "/api/resume/import-url");
        // ResumeController:447 POST /import-pdf-url（v1.90.0：抓取第三方分享的 PDF + AI 分析）
        table.put("/api/resume/import-pdf-url", "/api/resume/import-pdf-url");
        // ResumeDocumentController:165 POST /documents/{id}/autofill
        table.put("/api/resume/documents/*/autofill", "/api/resume/documents/5/autofill");
        // CareerController:81 / :103 / :128 POST /salary（v1.85.0）
        table.put("/api/career/mine", "/api/career/mine");
        table.put("/api/career/plan", "/api/career/plan");
        table.put("/api/career/salary", "/api/career/salary");
        // StoryBankController:78 / :107 / :134
        table.put("/api/story-bank/extract", "/api/story-bank/extract");
        table.put("/api/story-bank/*/check", "/api/story-bank/42/check");
        table.put("/api/story-bank/*/followup", "/api/story-bank/42/followup");
        // JobApplicationController:214 POST /{id}/tailor
        table.put("/api/application/*/tailor", "/api/application/9/tailor");
        return table;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────── 锁一：每条规则都能命中真实端点 ───────────────────────

    @Test
    @DisplayName("DEFAULT_AI_PATH_PREFIXES 的每一条都必须命中真实端点（死规则会直接变红）")
    void everyConfiguredRuleMatchesARealEndpoint() throws Exception {
        List<String> rules = split(AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES);
        Map<String, String> table = ruleToRealEndpoint();

        // ① 覆盖性：常量的每一条都在表里，表里也没有多余条目
        assertThat(table.keySet())
                .as("配置规则与真实端点表必须严格一一对应：新增规则请同步补表并核对 controller 映射")
                .containsExactlyElementsOf(rules);

        // ② 有效性：每条规则对它声称的真实端点确实生效（配额 1，第 2 次必被拦）
        for (Map.Entry<String, String> entry : table.entrySet()) {
            AiRateLimitFilter filter = new AiRateLimitFilter(true,
                    AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES, 1,
                    AiRateLimitFilter.DEFAULT_EXPENSIVE_PATH_PREFIXES, 1,
                    60L, new ObjectMapper());
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(USER, null, List.of()));

            assertThat(passThrough(filter, entry.getValue()))
                    .as("规则 %s 应命中真实端点 %s（第 1 次放行）", entry.getKey(), entry.getValue())
                    .isTrue();
            assertThat(passThrough(filter, entry.getValue()))
                    .as("规则 %s 对真实端点 %s 未生效 —— 这是一条死规则", entry.getKey(), entry.getValue())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("配置文件里的 paths 默认值与 Java 常量逐项一致（防两处漂移）")
    void yamlDefaultPathsMatchJavaConstant() throws Exception {
        String yamlDefault = yamlPathsDefault();

        assertThat(split(yamlDefault))
                .as("application.yml 的 app.ai.user-rate-limit.paths 默认值与 "
                        + "AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES 不一致："
                        + "只改一处的改动会让另一处变成过期文档")
                .containsExactlyElementsOf(split(AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES));
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private static boolean passThrough(AiRateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(IP);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 取 {@code application.yml} 中 {@code app.ai.user-rate-limit.paths} 的<b>默认值部分</b>。
     *
     * <p>原始值形如 {@code ${AI_USER_RATE_LIMIT_PATHS:/api/agent/,...}}：冒号后是默认值，
     * 部署时可用环境变量整体覆盖。本测试校验的正是这个默认值。
     */
    @SuppressWarnings("unchecked")
    private static String yamlPathsDefault() throws Exception {
        Path yml = Path.of("src", "main", "resources", "application.yml");
        assertThat(Files.exists(yml))
                .as("找不到 %s（surefire 工作目录应为 backend/）", yml.toAbsolutePath())
                .isTrue();
        Object loaded;
        try (InputStream in = Files.newInputStream(yml)) {
            loaded = new Yaml().load(in);
        }
        assertThat(loaded).isInstanceOf(Map.class);
        Object current = loaded;
        for (String key : new String[] {"app", "ai", "user-rate-limit", "paths"}) {
            assertThat(current).as("application.yml 缺少 app.ai.user-rate-limit.paths").isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
        }
        assertThat(current).as("app.ai.user-rate-limit.paths 未配置").isNotNull();

        String raw = String.valueOf(current);
        String prefix = "${AI_USER_RATE_LIMIT_PATHS:";
        if (raw.startsWith(prefix) && raw.endsWith("}")) {
            return raw.substring(prefix.length(), raw.length() - 1);
        }
        return raw;
    }
}

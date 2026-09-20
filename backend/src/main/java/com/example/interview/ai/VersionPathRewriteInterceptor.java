package com.example.interview.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;
import java.net.URI;

/**
 * 非 v1 版本段的 OpenAI 兼容厂商路径重写拦截器。
 *
 * ── 为什么需要它（2026-09-20 免费模型接入实测发现）────────────────────
 * Spring AI 1.0.0 的 {@code OpenAiApi} **硬编码**把 base-url 与
 * {@code /v1/chat/completions}、{@code /v1/embeddings} 拼接（已用反射探针确认）。
 * 这导致它只能对接版本段恰好为 {@code v1} 的厂商：
 *
 * <pre>
 *   Agnes   https://apihub.agnes-ai.com            → /v1/chat/completions       ✅
 *   智谱    https://open.bigmodel.cn/api/paas/v4   → /api/paas/v4/v1/chat/...   ❌ 404
 * </pre>
 *
 * 智谱的真实端点是 {@code /api/paas/v4/chat/completions}（已穷举验证：
 * {@code /v4/v1/...}、{@code /v3/...}、{@code /paas/paas/v4/...} 全部 404）。
 * 由于无论 base-url 怎么填，Spring AI 都会再拼一层 {@code /v1}，
 * 无法通过配置表达「不要这层 v1」，只能**在请求发出前把路径改回去**。
 *
 * ── 行为 ──────────────────────────────────────────────────────────
 * 仅当路径同时满足以下条件时才重写（避免误伤正常厂商）：
 * 1. 路径匹配 {@code /v{n}/v1/(chat/completions|embeddings)}（n≠1）——
 *    即厂商自有版本段被多拼了一层 {@code v1}；
 * 2. 重写方式：删掉紧跟在 {@code /vN/} 之后的那个 {@code v1/} 段。
 *
 * 例：{@code /api/paas/v4/v1/chat/completions} → {@code /api/paas/v4/chat/completions}
 *
 * 其他路径（如标准 OpenAI 的 {@code /v1/chat/completions}）原样放行，不做任何改动。
 */
public class VersionPathRewriteInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VersionPathRewriteInterceptor.class);

    /** 匹配「/vN/v1/...」，其中 N≠1（v1/v1 属异常配置，也一并规范化） */
    private static final java.util.regex.Pattern REDUNDANT_V1 =
            java.util.regex.Pattern.compile("(/v[0-9]+)/v1(/.*)$");

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        URI uri = request.getURI();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return execution.execute(request, body);
        }

        var m = REDUNDANT_V1.matcher(path);
        if (!m.find()) {
            // 标准 v1 厂商：原样放行，零开销
            return execution.execute(request, body);
        }

        String versionSeg = m.group(1);           // 如 /v4
        if ("/v1".equals(versionSeg)) {
            // /v1/v1 —— 这是 base-url 误带 /v1 造成的配置问题，同样修掉
            log.warn("检测到重复的 v1 版本段（疑似 base-url 误带 /v1）：原路径={}，已自动修正", path);
        }

        String newPath = path.substring(0, m.start()) + versionSeg + m.group(2);
        URI rewritten = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + newPath
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""));

        log.debug("重写 AI 请求路径：{} → {}", path, newPath);
        return execution.execute(new HttpRequestWrapper(request) {
            @Override
            public URI getURI() {
                return rewritten;
            }
        }, body);
    }
}

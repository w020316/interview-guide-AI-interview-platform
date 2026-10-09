package com.example.interview.service;

import com.example.interview.ai.AiConcurrencyGuard;
import com.example.interview.util.ImageTypeValidator;
import com.example.interview.util.TextUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.Map;

/**
 * 简历图片识别（截图 / 拍照 → 文本），v1.45.0
 *
 * <p><b>为什么需要它：</b>手机上「从别的 App 取简历」没有可靠的深链方案——
 * 微信/钉钉内置浏览器会拦 scheme，微信「文件传输助手」网页版只在电脑可用，
 * {@code wx.chooseMessageFile} 又需要公众号 JS-SDK。实测下来，
 * **手机上唯一在任何 App 里都做得到的动作是「截图」**。
 * 于是把「截图 → 识别成文字 → 走既有分析链路」做成一条通用出路。
 *
 * <p><b>模型选型（2026-10-01 实测）</b>：智谱 {@code glm-4v-flash} 免费、无需额外密钥
 * （复用 {@code AI_ZHIPU_API_KEY}）、单张 420×900 简历截图 4.14s、12/12 关键字段全部命中。
 * ⚠️ 该模型的 {@code max_tokens} 上限是 **1024**（传 2048 直接 400），因此**长简历会被截断**——
 * 这里如实把 {@code finish_reason=length} 暴露给调用方，由前端提示用户分两张截图或改用原文件，
 * **不做静默截断**。
 *
 * <p><b>约定</b>：与项目其它 AI 调用一致 —— 走 {@link AiConcurrencyGuard} 并发闸门；
 * 失败抛带原因的异常（不返回空串假装成功）。
 */
@Service
public class ResumeImageOcrService {

    private static final Logger log = LoggerFactory.getLogger(ResumeImageOcrService.class);

    /** 单张图片大小上限（base64 后约 1.37 倍，留足上游请求体余量） */
    private static final long MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    /** glm-4v-flash 的输出上限就是 1024，传更大会被上游 400 拒绝 */
    private static final int MAX_OUTPUT_TOKENS = 1024;

    /** 识别结果长度上限（约 3 页简历，超出部分对分析无增益） */
    private static final int MAX_TEXT_LEN = 8000;

    private static final String PROMPT =
            "把这张简历图片里的文字逐字提取出来，保留原有分节结构（如 教育背景 / 工作经历 / 项目经历 / 技能）。"
                    + "只输出提取到的正文，不要任何解释、不要用 markdown 代码块、不要臆造图片里没有的内容。"
                    + "如果某个字看不清，按最接近的字输出，不要留空。";

    @Value("${app.ai.vision.enabled:true}")
    private boolean enabled;

    @Value("${app.ai.vision.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${app.ai.vision.api-key:}")
    private String apiKey;

    @Value("${app.ai.vision.model:glm-4v-flash}")
    private String model;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper;

    public ResumeImageOcrService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 带连接/读超时的 RestTemplate。
     *
     * <p><b>为什么必须设超时</b>：本调用在 {@link AiConcurrencyGuard} 闸门内执行（全局仅 5 个许可），
     * 默认的 {@code new RestTemplate()} 无任何超时——上游建连后挂起会让该请求**无限期占用一个许可**，
     * 数个挂起即可拖垮全站 AI 功能。90s 读超时远大于实测的 ~4s 识别耗时，只兜底真正的挂起。
     */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000);
        return new RestTemplate(factory);
    }

    /** 识别结果：文本 + 是否被上游输出上限截断 */
    public record OcrResult(String text, boolean truncated) {
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 把简历图片识别成纯文本。
     *
     * @throws IllegalStateException 未配置 / 不是合法图片 / 上游失败 / 识别结果为空
     */
    public OcrResult extractText(MultipartFile file) {
        if (!isEnabled()) {
            throw new IllegalStateException("图片识别未配置（app.ai.vision.api-key 为空）");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("请上传简历图片");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("图片过大（超过 6MB），请压缩后重试或改用原始文件上传");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalStateException("图片读取失败：" + e.getMessage(), e);
        }

        // 按**文件头魔数**判定真实类型；客户端声明的 Content-Type 只用于交叉校验
        String contentType = file.getContentType();
        String detected = detectImageMime(bytes);
        if (detected == null) {
            throw new IllegalStateException("不是受支持的图片格式（支持 PNG / JPG / WEBP / GIF）");
        }
        // 声明了类型就必须与文件头一致，防止「伪装成图片」的载荷（沿用 ImageTypeValidator 的安全属性）
        if (ImageTypeValidator.isAllowed(contentType)
                && ImageTypeValidator.resolveExtension(contentType, bytes) == null) {
            throw new IllegalStateException("图片内容与声明的类型不一致，请重新导出后再试");
        }

        String dataUrl = "data:" + detected + ";base64," + Base64.getEncoder().encodeToString(bytes);

        return AiConcurrencyGuard.call(() -> callVision(dataUrl));
    }

    /**
     * 按文件头魔数识别图片 MIME 类型。
     *
     * <p>为什么不用 {@link ImageTypeValidator#resolveExtension} 单独判定：那个方法要求
     * Content-Type 必须非空且在白名单内，而**部分手机浏览器上传相册图片时不带
     * Content-Type**——直接用会把合法图片挡在门外。这里改为以文件头为准，
     * 再把「声明类型与文件头是否一致」作为额外校验。
     *
     * @return image/jpeg、image/png、image/gif、image/webp；无法识别返回 null
     */
    private static String detectImageMime(byte[] b) {
        if (b == null) return null;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** 真正发请求；单独抽出便于单测与失败定位 */
    private OcrResult callVision(String dataUrl) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", MAX_OUTPUT_TOKENS,
                    "temperature", 0.1,
                    "messages", new Object[]{Map.of("role", "user", "content", new Object[]{
                            Map.of("type", "text", "text", PROMPT),
                            Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                    })}
            ));
        } catch (Exception e) {
            throw new IllegalStateException("构造识别请求失败：" + e.getMessage(), e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        ResponseEntity<String> resp;
        try {
            resp = restTemplate.exchange(
                    baseUrl.replaceAll("/+$", "") + "/chat/completions",
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        } catch (RestClientResponseException e) {
            // 上游 4xx/5xx：把真实状态码与响应体带出去，否则用户只看到「识别失败」
            String detail = e.getResponseBodyAsString();
            log.warn("简历图片识别失败：上游 {} {}", e.getStatusCode(), detail);
            throw new IllegalStateException("图片识别服务返回 " + e.getStatusCode() + "：" + brief(detail), e);
        } catch (Exception e) {
            log.warn("简历图片识别异常：{}", e.getMessage());
            throw new IllegalStateException("无法连接图片识别服务：" + e.getMessage(), e);
        }

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new IllegalStateException("图片识别失败：上游返回 " + resp.getStatusCode());
        }

        try {
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            String finish = choice.path("finish_reason").asText("");
            if (content.isBlank()) {
                // 空正文必须可见：否则前端会拿到空字符串却以为成功
                throw new IllegalStateException("图片识别返回空内容（finish_reason=" + finish + "），请换一张更清晰的图片重试");
            }
            boolean truncated = "length".equals(finish);
            if (truncated) {
                log.info("简历图片识别被输出上限截断（模型 {} 上限 {} tokens）", model, MAX_OUTPUT_TOKENS);
            }
            return new OcrResult(TextUtil.truncate(content.trim(), MAX_TEXT_LEN), truncated);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("图片识别结果解析失败：" + e.getMessage(), e);
        }
    }

    private static String brief(String body) {
        if (body == null) return "(空响应体)";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

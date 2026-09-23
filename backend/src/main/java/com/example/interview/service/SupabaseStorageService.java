package com.example.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Supabase Storage 文件上传服务
 * - 将简历文件上传至 Supabase Storage Bucket
 * - 返回公开访问 URL
 * - 使用 PUT 方法（Supabase Storage REST API 要求）
 * - 文件名清洗防止路径穿越
 */
@Service
public class SupabaseStorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

    @Value("${app.supabase.url}")
    private String supabaseUrl;

    @Value("${app.supabase.service-key}")
    private String serviceKey;

    @Value("${app.supabase.bucket:resumes}")
    private String bucket;

    private final RestTemplate restTemplate;

    public SupabaseStorageService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * P0-02（2026-09-19）：Storage 配置为占位值时不阻断启动（仅记录 WARN）。
     *
     * <p>此前 application-prod.yml 中 {@code app.supabase.url/service-key} 无默认值，
     * SUPABASE_URL 未注入会导致占位符解析失败、整个应用无法启动。补齐默认后，
     * 这里显式告警，避免"上传功能静默失效、没人知道为什么"。
     *
     * <p><b>2026-09-23 修正</b>：原检测字符串只覆盖了 {@code placeholder.supabase.co} / {@code placeholder} 前缀，
     * 而 {@code application.yml}（base profile）与 {@code application-local.yml} 的默认值是
     * {@code your-project.supabase.co} / {@code your-service-key} —— 只要运行在非 prod profile 下，
     * 这条告警就<b>永远不会触发</b>，「上传静默失效」的初衷落空。
     * 现统一收敛到 {@link #isConfigured()}，把 placeholder / your-project / your- 前缀与空值一并覆盖。
     */
    @jakarta.annotation.PostConstruct
    void warnIfPlaceholder() {
        if (!isConfigured()) {
            log.warn("Supabase Storage 未正确配置（url={}）：简历文件与作答附图上传、签名 URL 将不可用，"
                    + "但登录与其余功能不受影响。请在部署环境注入 SUPABASE_URL / SUPABASE_SERVICE_KEY。"
                    + "可通过 GET /api/health/detail 的 storage 区块随时确认配置状态。", supabaseUrl);
        }
    }

    /**
     * 存储配置是否可用（非空且非占位值）。
     *
     * <p>供启动告警、上传前置校验与 {@code /api/health/detail} 共用同一判定，
     * 避免「告警说没问题、上传却失败」这类判定口径分裂。
     */
    public boolean isConfigured() {
        String url = supabaseUrl == null ? "" : supabaseUrl.trim();
        String key = serviceKey == null ? "" : serviceKey.trim();
        if (url.isEmpty() || key.isEmpty()) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        String lowerKey = key.toLowerCase();
        return !(lowerUrl.contains("placeholder")
                || lowerUrl.contains("your-project")
                || lowerKey.startsWith("placeholder")
                || lowerKey.startsWith("your-"));
    }

    /**
     * 最近一次上传失败的原始原因（含上游状态码与响应体摘要），供健康检查自诊断。
     *
     * <p>此前上传失败的真实原因（上游 4xx/5xx 的响应体、DNS 解析失败等）只落在
     * {@code log.error("作答附图上传失败", e)} 里，而接口只回一句「请稍后重试」——
     * 从外部完全无法区分「没配」「配错」「bucket 不存在」「被 RLS 拒绝」。
     */
    private volatile String lastError;

    /** 最近一次上传失败原因；从未失败则为 null */
    public String getLastError() {
        return lastError;
    }

    /** 健康检查用快照：只暴露配置状态与桶名，绝不回显 service key */
    public java.util.Map<String, Object> healthSnapshot() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        boolean ok = isConfigured();
        m.put("status", ok ? "UP" : "DOWN");
        m.put("configured", ok);
        m.put("bucket", bucket);
        if (lastError != null) {
            m.put("lastError", lastError);
        }
        return m;
    }

    /** 截断上游响应体，避免把整页 HTML 灌进日志与健康检查响应 */
    private static String brief(String body) {
        if (body == null) {
            return "(空响应体)";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /**
     * 上传文件到 Supabase Storage，返回公开访问 URL
     *
     * @param file     上传的文件
     * @param fileName 存储文件名（建议加上 userId 前缀避免冲突）
     * @return 公开 URL
     */
    public String upload(MultipartFile file, String fileName) throws IOException {
        // 未配置时快速失败并给出可区分的错误，而不是发起一次注定 DNS 失败的请求后
        // 把原因吞成一句「请稍后重试」
        if (!isConfigured()) {
            lastError = "Supabase Storage 未配置（app.supabase.url / service-key 仍为占位值或为空）";
            throw new IllegalStateException(lastError);
        }
        // 文件名清洗：只保留字母、数字、点、下划线、连字符，防止路径穿越
        String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Supabase Storage REST API: PUT /storage/v1/object/<bucket>/<path>
        String uploadUrl = supabaseUrl + "/storage/v1/object/" + bucket + "/" + safeName;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + serviceKey);
        headers.setContentType(MediaType.parseMediaType(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream"
        ));
        // upsert=true：文件已存在时覆盖
        headers.set("x-upsert", "true");

        HttpEntity<ByteArrayResource> entity = new HttpEntity<>(
                new ByteArrayResource(file.getBytes()), headers
        );

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(uploadUrl, HttpMethod.PUT, entity, String.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // RestTemplate 默认对 4xx/5xx 直接抛异常，走到这里才是常态；
            // 原实现把「非 2xx」判断写在 exchange 之后，是一段永远执行不到的死代码，
            // 真实状态码与响应体（bucket 不存在 / key 无效 / 被 RLS 拒绝 等）就此丢失
            lastError = "上游返回 " + e.getStatusCode() + "：" + brief(e.getResponseBodyAsString());
            throw new IllegalStateException("Supabase 文件上传失败：" + lastError, e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // 域名解析失败 / 连接超时 / TLS 失败 —— 多为配置指向了不存在的域名
            lastError = "无法连接存储服务（" + uploadUrl.replaceAll("(https?://[^/]+).*", "$1")
                    + "）：" + e.getMessage();
            throw new IllegalStateException(lastError, e);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            lastError = "上游返回 " + response.getStatusCode() + "：" + brief(response.getBody());
            throw new IllegalStateException("Supabase 文件上传失败：" + lastError);
        }

        lastError = null;
        // 拼接公开访问 URL
        return supabaseUrl + "/storage/v1/object/public/" + bucket + "/" + safeName;
    }

    /**
     * 判断给定 URL 是否为本系统 Supabase 公共存储域（P1-08：作答附图 SSRF 白名单）。
     *
     * <p>合法的附图 URL 只能来自 {@link #upload} 的返回值（{supabaseUrl}/storage/v1/object/public/...）。
     * 限制到本系统存储域后，即使 Spring AI 的媒体下载器跟随 302 重定向或存在 DNS 重绑定
     * TOCTOU 窗口，攻击者也无法把抓取目标指向自己控制的域名/内网地址。
     */
    public boolean isOwnPublicUrl(String url) {
        if (url == null || url.isBlank() || supabaseUrl == null || supabaseUrl.isBlank()) {
            return false;
        }
        try {
            URI u = new URI(url.trim());
            URI base = new URI(supabaseUrl.trim());
            String host = u.getHost();
            String baseHost = base.getHost();
            if (host == null || baseHost == null) {
                return false;
            }
            return host.equalsIgnoreCase(baseHost)
                    && u.getPath() != null
                    && u.getPath().startsWith("/storage/v1/object/public/");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 为本系统存储的公开 URL 签发短时效签名 URL（P2-06）。
     *
     * <p>调用 Supabase Storage 签名接口
     * {@code POST /storage/v1/object/sign/{bucket}/{path}}（service key 鉴权），
     * 返回带 token 的临时访问 URL，供 AI 媒体下载器在 bucket 转私有后仍可拉取。
     *
     * @param publicUrl  {@link #upload} 返回的公开 URL（{supabaseUrl}/storage/v1/object/public/{bucket}/{path}）
     * @param ttlSeconds 有效期（秒）
     * @return 签名 URL；任何失败（非本系统 URL、Supabase 异常）时回退返回原始 publicUrl，
     *         保证 bucket 仍为公读时功能不受影响（平滑切换）
     */
    public String createSignedUrl(String publicUrl, long ttlSeconds) {
        if (!isOwnPublicUrl(publicUrl)) {
            return publicUrl;
        }
        try {
            String path = new URI(publicUrl.trim()).getPath()
                    .replaceFirst("^/storage/v1/object/public/", "");
            // path = {bucket}/{objectPath}，双重防穿越：拒绝路径上跳
            if (path.contains("..")) {
                return publicUrl;
            }
            String signUrl = supabaseUrl + "/storage/v1/object/sign/" + path;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + serviceKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{\"expiresIn\":" + ttlSeconds + "}", headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    signUrl, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Supabase 签名 URL 签发失败：status={}，回退公开 URL", response.getStatusCode());
                return publicUrl;
            }
            // 响应形如 {"signedURL":"/object/sign/{bucket}/{path}?token=..."}
            String body = response.getBody();
            int idx = body.indexOf("\"signedURL\"");
            if (idx < 0) {
                log.warn("Supabase 签名响应缺少 signedURL 字段，回退公开 URL");
                return publicUrl;
            }
            int colon = body.indexOf(':', idx);
            int quote1 = body.indexOf('"', colon);
            int quote2 = body.indexOf('"', quote1 + 1);
            String signed = body.substring(quote1 + 1, quote2);
            return supabaseUrl + "/storage/v1" + signed;
        } catch (Exception e) {
            log.warn("Supabase 签名 URL 签发异常：{}，回退公开 URL", e.getMessage());
            return publicUrl;
        }
    }
}

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
     * 上传文件到 Supabase Storage，返回公开访问 URL
     *
     * @param file     上传的文件
     * @param fileName 存储文件名（建议加上 userId 前缀避免冲突）
     * @return 公开 URL
     */
    public String upload(MultipartFile file, String fileName) throws IOException {
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

        ResponseEntity<String> response = restTemplate.exchange(
                uploadUrl, HttpMethod.PUT, entity, String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("Supabase 文件上传失败：status={}, body={}",
                    response.getStatusCode(), response.getBody());
            throw new RuntimeException("Supabase 文件上传失败：" + response.getBody());
        }

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

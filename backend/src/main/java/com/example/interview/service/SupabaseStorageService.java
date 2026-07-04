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
}

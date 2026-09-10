package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 第三方招聘平台 HTTP 适配器（智联招聘/前程无忧/BOSS直聘 + 通用渠道）
 *
 * 背景：主流招聘平台官方均未开放公开的岗位查询 API，本适配器对接第三方
 * 招聘数据聚合服务（返回 JSON 岗位数组）实现岗位获取：
 * { "code": 200, "data": [ { "id": "...", "title": "...", "company": "...", ... } ] }
 *
 * 字段名做了宽容映射（title/jobName、company/companyName、deadline/closeTime 等均可），
 * 未配置 endpoint 时 isEnabled()=false，调度自动跳过。
 *
 * v1.26.0：新增通用 channels 渠道（name + endpoint + 可选 apiKey），
 * 任意招聘聚合服务配置即接入，无需改代码；与预置 platforms 平台统一拉取与失败隔离。
 */
@Component
public class HttpJobPlatformAdapter implements JobPlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpJobPlatformAdapter.class);

    /** 平台标识 -> 展示名 */
    private static final java.util.Map<String, String> PLATFORM_NAMES = java.util.Map.of(
            "zhaopin", "智联招聘",
            "job51", "前程无忧",
            "boss", "BOSS直聘"
    );

    private final JobAgentProperties properties;
    private final ObjectMapper objectMapper;

    /** 第三方 HTTP 拉取连接/读取超时（毫秒）：防挂死 endpoint 无限阻塞刷新互斥锁 */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestClient restClient;

    public HttpJobPlatformAdapter(JobAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String platform() {
        // 多平台复用本适配器，实际 platform 值由 fetchFor 动态传入
        return "第三方平台";
    }

    @Override
    public boolean isEnabled() {
        boolean anyPlatform = properties.getPlatforms().values().stream()
                .anyMatch(p -> p.getEndpoint() != null && !p.getEndpoint().isBlank());
        boolean anyChannel = properties.getChannels().stream()
                .anyMatch(c -> c.getEndpoint() != null && !c.getEndpoint().isBlank());
        return anyPlatform || anyChannel;
    }

    /** 指定平台是否已配置 endpoint */
    public boolean isEnabled(String platformKey) {
        JobAgentProperties.PlatformConfig cfg = properties.getPlatforms().get(platformKey);
        return cfg != null && cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank();
    }

    @Override
    public List<JobDto> fetch() {
        List<JobDto> all = new ArrayList<>();
        for (var entry : properties.getPlatforms().entrySet()) {
            try {
                all.addAll(fetchFor(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                // 单平台失败不影响其他平台
                log.warn("平台 {} 岗位拉取失败：{}", entry.getKey(), e.getMessage());
            }
        }
        // v1.26.0：通用渠道拉取（name 即入库 platform）
        for (JobAgentProperties.ChannelConfig channel : properties.getChannels()) {
            if (channel.getEndpoint() == null || channel.getEndpoint().isBlank()) {
                continue;
            }
            try {
                all.addAll(fetchChannel(channel));
            } catch (Exception e) {
                log.warn("渠道 {} 岗位拉取失败：{}", channel.getName(), e.getMessage());
            }
        }
        return all;
    }

    /** 拉取指定平台岗位 */
    public List<JobDto> fetchFor(String platformKey, JobAgentProperties.PlatformConfig cfg) {
        String name = PLATFORM_NAMES.getOrDefault(platformKey, platformKey);
        if (!isEnabled(platformKey)) {
            return List.of();
        }
        String body = restClient.get()
                .uri(cfg.getEndpoint())
                .header("Authorization", cfg.getApiKey() == null ? "" : "Bearer " + cfg.getApiKey())
                .retrieve()
                .body(String.class);
        return parseJobs(name, body);
    }

    /** 拉取通用渠道岗位（v1.26.0） */
    public List<JobDto> fetchChannel(JobAgentProperties.ChannelConfig channel) {
        String name = channel.getName() == null || channel.getName().isBlank()
                ? "第三方渠道" : channel.getName().trim();
        String body = restClient.get()
                .uri(channel.getEndpoint())
                .header("Authorization", channel.getApiKey() == null ? "" : "Bearer " + channel.getApiKey())
                .retrieve()
                .body(String.class);
        return parseJobs(name, body);
    }

    /**
     * 拉取所有已配置平台岗位，按平台展示名分组
     * （智联招聘/前程无忧/BOSS直聘各自独立拉取与失败隔离，平台标识准确入库）
     */
    public java.util.Map<String, List<JobDto>> fetchAllByPlatform() {
        java.util.Map<String, List<JobDto>> result = new java.util.LinkedHashMap<>();
        for (var entry : properties.getPlatforms().entrySet()) {
            String name = PLATFORM_NAMES.getOrDefault(entry.getKey(), entry.getKey());
            if (!isEnabled(entry.getKey())) {
                continue;
            }
            try {
                result.put(name, fetchFor(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                // 单平台失败不影响其他平台
                log.warn("平台 {} 岗位拉取失败：{}", name, e.getMessage());
            }
        }
        // v1.26.0：通用渠道按渠道名分组入库
        for (JobAgentProperties.ChannelConfig channel : properties.getChannels()) {
            if (channel.getEndpoint() == null || channel.getEndpoint().isBlank()) {
                continue;
            }
            String name = channel.getName() == null || channel.getName().isBlank()
                    ? "第三方渠道" : channel.getName().trim();
            try {
                result.put(name, fetchChannel(channel));
            } catch (Exception e) {
                log.warn("渠道 {} 岗位拉取失败：{}", name, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析第三方数据服务返回的岗位 JSON（宽容字段映射）
     */
    List<JobDto> parseJobs(String platformName, String body) {
        List<JobDto> result = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode arr = root.has("data") && root.get("data").isArray()
                    ? root.get("data")
                    : (root.isArray() ? root : null);
            if (arr == null) {
                log.warn("平台 {} 返回格式无法识别：缺少 data 数组", platformName);
                return result;
            }
            for (JsonNode node : arr) {
                String externalId = text(node, "id", "jobId", "externalId", "positionId");
                String title = text(node, "title", "jobName", "positionName");
                String company = text(node, "company", "companyName", "companyFullName");
                if (externalId == null || title == null || company == null) {
                    continue; // 缺关键字段的脏数据直接丢弃
                }
                result.add(new JobDto(
                        externalId,
                        title,
                        company,
                        text(node, "industry", "industryName", "industryType"),
                        text(node, "jobType", "category", "positionType"),
                        text(node, "location", "city", "workCity", "cityDistrict"),
                        text(node, "salary", "salaryDesc", "salaryRange"),
                        text(node, "degree", "education", "educationLevel"),
                        text(node, "experience", "workExp", "experienceRequirement"),
                        text(node, "recruitType", "jobNature") == null ? "AUTUMN"
                                : normalizeRecruitType(text(node, "recruitType", "jobNature")),
                        parseDate(text(node, "deadline", "closeTime", "endDate")),
                        text(node, "applyUrl", "url", "detailUrl", "link"),
                        text(node, "description", "jobDesc", "duty"),
                        text(node, "requirements", "requirement", "jobRequirement"),
                        text(node, "tags", "skillLabel", "welfare")
                ));
            }
        } catch (Exception e) {
            log.warn("平台 {} 岗位 JSON 解析失败：{}", platformName, e.getMessage());
        }
        return result;
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    /** 兼容 yyyy-MM-dd / yyyy/MM/dd / yyyy年MM月dd日 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-").trim();
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-M-d"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeRecruitType(String raw) {
        if (raw == null) return "AUTUMN";
        return switch (raw.toUpperCase()) {
            case "AUTUMN", "秋招", "校园招聘", "校招" -> "AUTUMN";
            case "SPRING", "春招" -> "SPRING";
            case "SOCIAL", "社招", "社会招聘" -> "SOCIAL";
            case "INTERN", "实习" -> "INTERN";
            case "TARGETED", "定向", "专项", "专项招聘", "定向选调", "选调", "定向招聘", "计划单列" -> "TARGETED";
            default -> "AUTUMN";
        };
    }
}

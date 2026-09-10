package com.example.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 招聘信息智能体配置（app.job-agent.*）
 *
 * 内置秋招精选数据源始终启用；第三方平台（智联招聘/前程无忧/BOSS直聘）官方未开放
 * 公开岗位 API，需通过第三方招聘数据服务（聚合类 API，OpenAI 通用 JSON 返回格式）
 * 对接：配置 endpoint 后该平台适配器自动启用，endpoint 为空则跳过。
 */
@ConfigurationProperties(prefix = "app.job-agent")
public class JobAgentProperties {

    /** 第三方平台数据服务配置：key = 平台标识（zhaopin/job51/boss） */
    private Map<String, PlatformConfig> platforms = new LinkedHashMap<>();

    /** 定时刷新间隔（毫秒），默认 6 小时 */
    private long refreshFixedDelayMs = 6 * 3600 * 1000L;

    public Map<String, PlatformConfig> getPlatforms() {
        return platforms;
    }

    public void setPlatforms(Map<String, PlatformConfig> platforms) {
        this.platforms = platforms;
    }

    public long getRefreshFixedDelayMs() {
        return refreshFixedDelayMs;
    }

    public void setRefreshFixedDelayMs(long refreshFixedDelayMs) {
        this.refreshFixedDelayMs = refreshFixedDelayMs;
    }

    public static class PlatformConfig {
        /** 第三方招聘数据服务 endpoint（返回 JSON 岗位数组） */
        private String endpoint;
        /** 数据服务鉴权 key */
        private String apiKey;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}

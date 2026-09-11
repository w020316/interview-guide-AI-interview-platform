package com.example.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模型降级链配置（app.ai.*）
 *
 * v1.22.0 起聊天模型切换为多厂商降级链，默认链路：
 * 1. B.AI · GLM-5.3-Flash（主模型，0 Credits 免费额度，能力最强）
 * 2. B.AI · Qwen3.8-Flash（次模型，0 Credits 免费额度）
 * 3. Agnes AI · agnes-2.5-flash（兜底，原主模型自动降为末位）
 *
 * 链条中 api-key 为空的提供方会被跳过（如未配置 AI_BAI_API_KEY 时自动落到 Agnes）。
 * Embedding 仍走 spring.ai.openai.*（Agnes，text-embedding-3-small），不受本链影响。
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProviderProperties {

    /** 降级链，按顺序尝试 */
    private List<Provider> chain = new ArrayList<>();

    public List<Provider> getChain() {
        return chain;
    }

    public void setChain(List<Provider> chain) {
        this.chain = chain;
    }

    public static class Provider {

        /** OpenAI 兼容协议 Base URL（不带 /v1 后缀，Spring AI 自动拼接） */
        private String baseUrl;

        /** API Key，为空则跳过该提供方 */
        private String apiKey;

        /** 模型名 */
        private String model;

        /** 默认温度（业务 Service 可覆盖） */
        private Double temperature = 0.7;

        /** 提供方名称（用于日志） */
        private String name;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}

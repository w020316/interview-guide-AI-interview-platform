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

        /**
         * 是否在请求体中显式关闭「思考模式」（{@code thinking:{"type":"disabled"}}）。
         *
         * <p><b>为什么需要（2026-09-26 线上实测）</b>：智谱 GLM 系列默认开启思考模式，
         * {@code reasoning_content} 会吃光输出预算，导致**HTTP 200 但正文为空**
         * （实测 {@code glm-4.7-flash}：{@code content=0}、{@code reasoning_content=358}、
         * {@code finish_reason=length}）。本项目 {@code FallbackChatModel} 会把「空正文」
         * 判为该节点不可用并继续降级，于是**兜底节点每次都被静默跳过**，
        * 跨厂商容灾退化为单点，且每次主模型故障都要白撞一次（多 4.25s 与一次免费额度）。
         *
         * <p>实测关闭思考后的效果（同一问题）：{@code glm-4.7-flash} 由 4.25s/空内容
         * 变为 **1.38s/正常内容**；另外三个智谱免费模型也都接受该字段且返回正常内容，
         * 因此可以安全地对整个智谱厂商统一开启。
         *
         * <p>缺省为 {@code null}（不下发该字段）——不改变任何现有厂商的请求体。
         */
        private Boolean thinkingDisabled;

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

        public Boolean getThinkingDisabled() {
            return thinkingDisabled;
        }

        public void setThinkingDisabled(Boolean thinkingDisabled) {
            this.thinkingDisabled = thinkingDisabled;
        }
    }
}

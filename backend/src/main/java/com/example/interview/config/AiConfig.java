package com.example.interview.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 配置
 * 不设置硬编码的岗位默认 system prompt，由各业务 Service 根据岗位动态生成，
 * 避免默认 "Java 后端" 与实际岗位冲突（支持全行业岗位）
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}

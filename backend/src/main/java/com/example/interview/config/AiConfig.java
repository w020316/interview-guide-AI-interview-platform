package com.example.interview.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 配置
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一位资深 Java 后端面试官，拥有 10 年大厂面试经验，" +
                        "擅长根据候选人简历和目标岗位进行个性化面试评估。")
                .build();
    }
}

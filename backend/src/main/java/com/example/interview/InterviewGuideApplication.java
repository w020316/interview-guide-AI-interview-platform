package com.example.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI 智能面试辅助平台 启动类
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class InterviewGuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewGuideApplication.class, args);
        System.out.println("""
                
                ====================================================
                  AI 智能面试辅助平台 启动成功
                  访问地址：http://localhost:8080
                  健康检查：http://localhost:8080/actuator/health
                ====================================================
                """);
    }
}

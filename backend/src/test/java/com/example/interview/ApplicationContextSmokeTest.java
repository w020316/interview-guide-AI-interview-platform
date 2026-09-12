package com.example.interview;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring 上下文启动冒烟测试（v1.31.4）
 *
 * <p>背景：CI 主流程均为 @WebMvcTest + Mockito 切片测试，从不加载完整 Spring 上下文，
 * 导致「构造函数按具体类型注入、而配置类按接口注册 Bean」这类装配错误在 CI 中无法被发现，
 * 上线后表现为启动失败 / 崩溃重启循环（如 JobClassifyService 依赖 FallbackChatModel）。
 *
 * <p>本测试使用 local profile（H2 内存库 + SimpleVectorStore + Redis 容错，零外部依赖），
 * 加载完整上下文并断言可正常启动，固化所有 @Component/@Service/@Configuration 的装配正确性。
 */
@SpringBootTest
@ActiveProfiles("local")
class ApplicationContextSmokeTest {

    @Test
    void contextLoads() {
        // 仅需 Spring 上下文成功装配并启动即可，无需断言具体行为
    }
}

package com.example.interview;

import com.example.interview.config.AiLatencyWindow;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 上下文启动冒烟测试（v1.31.4）
 *
 * <p>背景：CI 主流程均为 @WebMvcTest + Mockito 切片测试，从不加载完整 Spring 上下文，
 * 导致「构造函数按具体类型注入、而配置类按接口注册 Bean」这类装配错误在 CI 中无法被发现，
 * 上线后表现为启动失败 / 崩溃重启循环（如 JobClassifyService 依赖 FallbackChatModel）。
 *
 * <p>本测试使用 local profile（H2 内存库 + SimpleVectorStore + Redis 容错，零外部依赖），
 * 加载完整上下文并断言可正常启动，固化所有 @Component/@Service/@Configuration 的装配正确性。
 *
 * <p>v1.33.0（P1-05）：新增 resume 表读写断言。此前 ResumeEntity 的
 * columnDefinition="JSONB" 在 H2 上 DDL 静默失败（仅 WARN），resume 表不会被创建，
 * 而本冒烟测试仅断言上下文启动、未触碰 resume 仓库，形成假信心；
 * 该断言确保 JSON 列映射在 H2 与 PostgreSQL 两种方言下均可建表并读写。
 */
@SpringBootTest
@ActiveProfiles("local")
// v1.34.0：测试必须与真实数据隔离。local profile 的向量库改为文件快照持久化后，
// 若不禁用，本测试会加载、并可能在关闭时回写 D:/xm/data/vectorstore.json ——
// 用测试数据覆盖用户真实积累的知识库。故显式关闭持久化并指向临时文件。
@TestPropertySource(properties = {
        "app.rag.persist-enabled=false",
        "app.rag.snapshot-file=${java.io.tmpdir}/interview-smoke-vectorstore.json",
        // P2-11（2026-09-20）：数据源隔离。local profile 默认指向共享文件库
        // jdbc:h2:file:D:/xm/data/interview，本地 dev server 运行时会持有文件锁（90028），
        // 导致本测试偶发红；且测试会向真实本地库写入 smoke-user。改指内存库并关闭自动关闭，
        // 与正在运行的 dev server 完全解耦，dev server 常驻也不影响 672/672 绿灯。
        "spring.datasource.url=jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1"
})
class ApplicationContextSmokeTest {

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // 仅需 Spring 上下文成功装配并启动即可，无需断言具体行为
    }

    @Test
    @DisplayName("resume 表在 H2 上可建表并完成 JSON 列读写（P1-05 回归防线）")
    void resumeRepositoryReadWriteOnH2() {
        ResumeEntity entity = ResumeEntity.builder()
                .userId("smoke-user")
                .content("Java 后端，8 年经验")
                .targetJob("Java 高级工程师")
                .analysisResult("{\"scores\":{\"tech\":80}}")
                .build();
        ResumeEntity saved = resumeRepository.saveAndFlush(entity);
        assertNotNull(saved.getId(), "resume 表应已创建并成功写入");

        ResumeEntity loaded = resumeRepository.findById(saved.getId()).orElseThrow();
        assertEquals("smoke-user", loaded.getUserId());
        assertTrue(loaded.getAnalysisResult().contains("\"tech\":80"),
                "JSON 列应可原样读回（H2 json 列映射）");
    }

    /**
     * AI 耗时窗口的**接线**回归（第三轮 P2-03）。
     *
     * <p>为什么放在冒烟测试里：单测只能验「装饰器自己工作正常」，验不了
     * 「`MetricsConfig` 返回的确实是装饰后的 Timer」以及「注入方拿到的就是这个 Bean」。
     * 一旦有人把 `aiCallTimer` 改回返回裸 Timer，P95 会**静默**退化为 0 —— 那正是这个缺陷
     * 连续两轮复发的方式。这里用真实容器装配后的 Bean 走一遍「记录 → 读回」，
     * 把接线锁死。
     */
    @Test
    @DisplayName("AI 耗时窗口接线：注入的 Timer 记录后，AiLatencyWindow 必须读到（P2-03 回归防线）")
    void aiLatencyWindowIsWiredToInjectedTimer() {
        AiLatencyWindow window = applicationContext.getBean(AiLatencyWindow.class);
        Timer timer = applicationContext.getBean("aiCallTimer", Timer.class);
        window.reset();

        timer.record(100, TimeUnit.MILLISECONDS);
        timer.record(1000, TimeUnit.MILLISECONDS);
        timer.record(10000, TimeUnit.MILLISECONDS);

        assertEquals(3, window.size(), "注入的 Timer 必须把耗时写进同一个窗口 Bean");
        assertNotNull(window.p95(), "P95 不能为 null");
        // ★ 核心断言：P95 必须落在最大值上，不能是 0
        assertEquals(10000.0, window.p95(), 0.001,
                "P95 必须由自算窗口给出；若退化为 0，说明 Timer 装饰被去掉了");
    }
}

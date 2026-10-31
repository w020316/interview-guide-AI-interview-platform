package com.example.interview.service.task;

import com.example.interview.entity.AiTaskEntity;
import com.example.interview.repository.AiTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务中心启动收敛的集成测试（P1-1，2026-10-31）。
 *
 * <h2>与另外两条用例的分工</h2>
 *
 * <p>{@code AiTaskRepositoryRecoveryTest} 验证 JPQL 本身（{@code @DataJpaTest}），
 * {@code AiTaskServiceTest} 验证分支选择（Mockito）。两者都**不会**发现
 * 「监听器没注册上 / 事件类型写错 / {@code @Value} 没注入」这类装配问题 ——
 * 而那正是启动收敛最容易静默失效的地方：日志一片安静，库里一条没少。
 *
 * <p>所以这里加载完整上下文，直接发布 {@link ApplicationReadyEvent}，
 * 断言「事件 → 监听器 → 服务 → SQL」整条链路真的把行改掉了。
 *
 * <p>数据源指向独立内存库，与 {@code ApplicationContextSmokeTest} 同口径，
 * 避免与本地 dev server 的文件锁（90028）互相干扰。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-recovery;DB_CLOSE_DELAY=-1",
        "app.rag.persist-enabled=false",
        "app.rag.snapshot-file=${java.io.tmpdir}/interview-recovery-vectorstore.json"
})
@DisplayName("任务中心启动收敛（P1-1 集成）")
class AiTaskStartupRecoveryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);

    @Autowired
    private AiTaskRepository repository;

    @Autowired
    private AiTaskStartupRecovery recovery;

    @Autowired
    private ApplicationContext applicationContext;

    @AfterEach
    void resetFlag() {
        // 单例 Bean 的字段是跨用例共享的，必须复位，否则用例顺序会互相影响
        ReflectionTestUtils.setField(recovery, "enabled", false);
    }

    /** 模拟「应用启动完成」：容器真实发布一次事件，走完整的监听器链路 */
    private void publishApplicationReady() {
        applicationContext.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0],
                (ConfigurableApplicationContext) applicationContext, Duration.ZERO));
    }

    private AiTaskEntity save(String key, String status, int attempt, int maxAttempt) {
        return repository.saveAndFlush(AiTaskEntity.builder()
                .userId("recovery-user")
                .type("interview.questions")
                .idempotencyKey(key)
                .status(status)
                .attempt(attempt)
                .maxAttempt(maxAttempt)
                .progress(40)
                .leaseUntil(NOW.minusMinutes(5))
                .build());
    }

    @Test
    @DisplayName("开关关闭：启动事件把 RUNNING/QUEUED 全部收敛为 FAILED(ROLLBACK)")
    void disabled_convergesOnApplicationReady() {
        ReflectionTestUtils.setField(recovery, "enabled", false);
        AiTaskEntity running = save("it-disabled-running", "RUNNING", 1, 2);
        AiTaskEntity queued = save("it-disabled-queued", "QUEUED", 0, 2);

        publishApplicationReady();

        for (AiTaskEntity row : new AiTaskEntity[]{running, queued}) {
            AiTaskEntity reloaded = repository.findById(row.getId()).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("开关关着时调度器不会跑，留在「进行中/排队中」就是骗用户").isEqualTo("FAILED");
            assertThat(reloaded.getErrorCode()).isEqualTo("ROLLBACK");
            assertThat(reloaded.getFinishedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("开关开启：启动事件把有重试余量的退回队列，次数耗尽的判失败")
    void enabled_requeuesOnApplicationReady() {
        ReflectionTestUtils.setField(recovery, "enabled", true);
        AiTaskEntity withBudget = save("it-enabled-running", "RUNNING", 1, 2);
        AiTaskEntity exhausted = save("it-enabled-exhausted", "RUNNING", 2, 2);

        publishApplicationReady();

        AiTaskEntity requeued = repository.findById(withBudget.getId()).orElseThrow();
        assertThat(requeued.getStatus()).as("被打断但还有余量：退回队列等下一轮调度").isEqualTo("QUEUED");
        assertThat(requeued.getLeaseUntil()).isNull();

        AiTaskEntity failed = repository.findById(exhausted.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("LEASE_EXPIRED");
    }
}

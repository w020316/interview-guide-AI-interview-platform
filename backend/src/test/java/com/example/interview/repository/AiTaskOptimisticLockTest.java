package com.example.interview.repository;

import com.example.interview.entity.AiTaskEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 取消与 worker 并发落终态时，持有旧副本的一方必须失败，不能覆盖对方的结果。 */
@DataJpaTest
@ActiveProfiles("local")
@DisplayName("AiTaskEntity 乐观锁")
class AiTaskOptimisticLockTest {

    @Autowired
    private AiTaskRepository repository;

    @Autowired
    private EntityManager em;

    private AiTaskEntity saveRunning() {
        return repository.saveAndFlush(AiTaskEntity.builder()
                .userId("u1")
                .type("interview.questions")
                .idempotencyKey("k-lock")
                .status("RUNNING")
                .attempt(1)
                .maxAttempt(1)
                .build());
    }

    @Test
    @DisplayName("worker 读到旧版本后，取消先提交 → worker 写 SUCCEEDED 被拒")
    void staleWorkerCannotOverwriteCancel() {
        AiTaskEntity saved = saveRunning();
        em.clear();
        AiTaskEntity workerCopy = repository.findById(saved.getId()).orElseThrow();
        em.detach(workerCopy);

        AiTaskEntity cancelCopy = repository.findById(saved.getId()).orElseThrow();
        cancelCopy.setStatus("CANCELLED");
        repository.saveAndFlush(cancelCopy);
        em.clear();

        workerCopy.setStatus("SUCCEEDED");
        assertThatThrownBy(() -> repository.saveAndFlush(workerCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("批量 claim 会推进版本号，之前读到的副本随之失效")
    void bulkClaimBumpsVersion() {
        AiTaskEntity saved = repository.saveAndFlush(saveRunning().toBuilder().status("QUEUED").build());
        em.clear();
        AiTaskEntity before = repository.findById(saved.getId()).orElseThrow();
        em.detach(before);
        LocalDateTime now = LocalDateTime.now();

        assertThat(repository.claim(saved.getId(), now.plusMinutes(3), now)).isEqualTo(1);

        AiTaskEntity after = repository.findById(saved.getId()).orElseThrow();
        assertThat(after.getVersion()).isGreaterThan(before.getVersion());
    }
}

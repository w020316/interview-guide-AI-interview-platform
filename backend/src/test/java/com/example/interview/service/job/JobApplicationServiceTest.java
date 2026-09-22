package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobApplicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link JobApplicationService} 单元测试（v1.35.0 投递台账）
 *
 * <p>覆盖点：幂等加入台账、人工确认投递、状态推进（回复监测）、IDOR 防护、
 * 看板计数与「待跟进」判定。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobApplicationService 投递台账单元测试")
class JobApplicationServiceTest {

    @Mock
    private JobApplicationRepository repository;

    /** 事务模板依赖；mock 后 getTransaction 返回 null，插入路径可正常执行（不真正开事务） */
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private JobApplicationService service;

    private static final String USER = "user-1";

    private JobPostingEntity job(Long id, String title, String company) {
        return JobPostingEntity.builder()
                .id(id).title(title).companyName(company)
                .platform("内置精选").location("深圳").salary("25k")
                .applyUrl("https://example.com/apply").build();
    }

    private JobApplicationEntity app(Long id, String userId, String status) {
        return JobApplicationEntity.builder()
                .id(id).userId(userId).jobId(1L)
                .title("Java 后端").companyName("腾讯")
                .status(status)
                .build();
    }

    // ───────── 加入台账 ─────────

    @Test
    @DisplayName("addFromJob：首次加入创建 PLANNED 记录并做岗位快照")
    void addFromJob_createsPlanned() {
        when(repository.findByUserIdAndJobId(USER, 1L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(JobApplicationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity saved = service.addFromJob(USER, job(1L, "Java 后端", "腾讯"), "内推");

        assertThat(saved.getStatus()).isEqualTo(JobApplicationEntity.STATUS_PLANNED);
        assertThat(saved.getTitle()).isEqualTo("Java 后端");
        assertThat(saved.getCompanyName()).isEqualTo("腾讯");
        assertThat(saved.getApplyUrl()).isEqualTo("https://example.com/apply");
        assertThat(saved.getNote()).isEqualTo("内推");
    }

    @Test
    @DisplayName("addFromJob：已存在则幂等返回原记录，不再插入")
    void addFromJob_idempotent() {
        JobApplicationEntity existing = app(9L, USER, JobApplicationEntity.STATUS_APPLIED);
        when(repository.findByUserIdAndJobId(USER, 1L)).thenReturn(Optional.of(existing));

        JobApplicationEntity result = service.addFromJob(USER, job(1L, "Java 后端", "腾讯"), null);

        assertThat(result.getId()).isEqualTo(9L);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFromFavorite：从收藏快照转入台账")
    void addFromFavorite_snapshot() {
        JobFavoriteEntity fav = JobFavoriteEntity.builder()
                .id(3L).userId(USER).jobId(7L).title("算法工程师")
                .companyName("字节").location("北京").applyUrl("https://x.com").build();
        when(repository.findByUserIdAndJobId(USER, 7L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(JobApplicationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity saved = service.addFromFavorite(USER, fav, null);

        assertThat(saved.getJobId()).isEqualTo(7L);
        assertThat(saved.getTitle()).isEqualTo("算法工程师");
        assertThat(saved.getStatus()).isEqualTo(JobApplicationEntity.STATUS_PLANNED);
    }

    // ───────── 人工确认投递 ─────────

    @Test
    @DisplayName("confirmApply：PLANNED → APPLIED 并记录投出时间")
    void confirmApply_setsApplied() {
        JobApplicationEntity planned = app(1L, USER, JobApplicationEntity.STATUS_PLANNED);
        when(repository.findById(1L)).thenReturn(Optional.of(planned));
        when(repository.save(any(JobApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity updated = service.confirmApply(USER, 1L);

        assertThat(updated.getStatus()).isEqualTo(JobApplicationEntity.STATUS_APPLIED);
        assertThat(updated.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("confirmApply：非本人记录返回 null（防 IDOR）")
    void confirmApply_otherUser_returnsNull() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(app(1L, "someone-else", JobApplicationEntity.STATUS_PLANNED)));

        assertThat(service.confirmApply(USER, 1L)).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirmApply：id 为 null 时直接返回 null（不查库）")
    void confirmApply_nullId() {
        assertThat(service.confirmApply(USER, null)).isNull();
        verify(repository, never()).findById(any());
    }

    // ───────── 状态推进 / 回复监测 ─────────

    @Test
    @DisplayName("updateStatus：非法状态返回 null 且不落库")
    void updateStatus_invalidStatus() {
        assertThat(service.updateStatus(USER, 1L, "NOT_A_STATUS", null, null)).isNull();
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("updateStatus：推进为 REPLIED 时记录回复时间与备注")
    void updateStatus_replied() {
        JobApplicationEntity applied = app(1L, USER, JobApplicationEntity.STATUS_APPLIED);
        applied.setAppliedAt(LocalDateTime.now().minusDays(2));
        when(repository.findById(1L)).thenReturn(Optional.of(applied));
        when(repository.save(any(JobApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity updated = service.updateStatus(USER, 1L, "replied", "HR 约面", null);

        assertThat(updated.getStatus()).isEqualTo(JobApplicationEntity.STATUS_REPLIED);
        assertThat(updated.getLastReplyAt()).isNotNull();
        assertThat(updated.getNote()).isEqualTo("HR 约面");
    }

    @Test
    @DisplayName("updateStatus：从 PLANNED 直接推进时补记投出时间（统计口径正确）")
    void updateStatus_backfillsAppliedAt() {
        JobApplicationEntity planned = app(1L, USER, JobApplicationEntity.STATUS_PLANNED);
        when(repository.findById(1L)).thenReturn(Optional.of(planned));
        when(repository.save(any(JobApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity updated = service.updateStatus(USER, 1L, "INTERVIEW", null, null);

        assertThat(updated.getStatus()).isEqualTo(JobApplicationEntity.STATUS_INTERVIEW);
        assertThat(updated.getAppliedAt()).isNotNull();
    }

    // ───────── 看板与待跟进 ─────────

    @Test
    @DisplayName("board：分列计数与转化漏斗正确")
    void board_countsAndFunnel() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(
                app(1L, USER, JobApplicationEntity.STATUS_PLANNED),
                app(2L, USER, JobApplicationEntity.STATUS_APPLIED),
                app(3L, USER, JobApplicationEntity.STATUS_REPLIED),
                app(4L, USER, JobApplicationEntity.STATUS_INTERVIEW),
                app(5L, USER, JobApplicationEntity.STATUS_OFFER)));

        Map<String, Object> board = service.board(USER);

        assertThat(board.get("total")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) board.get("counts");
        assertThat(counts.get(JobApplicationEntity.STATUS_APPLIED)).isEqualTo(1);
        assertThat(counts.get(JobApplicationEntity.STATUS_OFFER)).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> funnel = (Map<String, Object>) board.get("funnel");
        assertThat(funnel.get("submitted")).isEqualTo(4);        // APPLIED+REPLIED+INTERVIEW+OFFER
        assertThat(funnel.get("offer")).isEqualTo(1);
    }

    @Test
    @DisplayName("board：已投递超过 7 天无回复进入待跟进")
    void board_detectsSilentApplication() {
        JobApplicationEntity stale = app(1L, USER, JobApplicationEntity.STATUS_APPLIED);
        stale.setAppliedAt(LocalDateTime.now().minusDays(10));
        when(repository.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(stale));

        Map<String, Object> board = service.board(USER);

        assertThat(board.get("followUpCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("board：已到跟进时间的记录进入待跟进；OFFER 不跟进")
    void board_nextActionAndOffer() {
        JobApplicationEntity due = app(1L, USER, JobApplicationEntity.STATUS_REPLIED);
        due.setNextActionAt(LocalDateTime.now().minusHours(1));
        JobApplicationEntity offer = app(2L, USER, JobApplicationEntity.STATUS_OFFER);
        offer.setAppliedAt(LocalDateTime.now().minusDays(30));
        when(repository.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(due, offer));

        Map<String, Object> board = service.board(USER);

        assertThat(board.get("followUpCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("needsFollowUp：刚投出的记录不跟进")
    void needsFollowUp_recentlyApplied_false() {
        JobApplicationEntity fresh = app(1L, USER, JobApplicationEntity.STATUS_APPLIED);
        fresh.setAppliedAt(LocalDateTime.now().minusDays(1));

        assertThat(service.needsFollowUp(fresh, LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("remove：非本人记录返回 false")
    void remove_otherUser_false() {
        when(repository.findById(1L)).thenReturn(Optional.of(app(1L, "other", JobApplicationEntity.STATUS_APPLIED)));

        assertThat(service.remove(USER, 1L)).isFalse();
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("label：状态中文标签映射（供智能体与前端复用）")
    void label_mapsStatus() {
        assertThat(JobApplicationService.label(JobApplicationEntity.STATUS_OFFER)).isEqualTo("已拿 Offer");
        assertThat(JobApplicationService.label(null)).isEqualTo("未知");
    }
}

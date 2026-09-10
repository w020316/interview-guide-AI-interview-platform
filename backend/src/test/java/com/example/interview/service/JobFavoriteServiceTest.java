package com.example.interview.service;

import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobFavoriteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobFavoriteService 单元测试")
class JobFavoriteServiceTest {

    @Mock private JobFavoriteRepository repository;
    @InjectMocks private JobFavoriteService service;

    private JobPostingEntity job() {
        return JobPostingEntity.builder()
                .id(100L).platform("内置精选").externalId("campus-001")
                .title("Java 后端工程师").companyName("示例科技")
                .location("深圳").salary("20-30k")
                .deadline(LocalDate.of(2026, 10, 31))
                .applyUrl("https://example.com/apply").build();
    }

    private JobFavoriteEntity fav() {
        return JobFavoriteEntity.builder()
                .id(1L).userId("u1").jobId(100L)
                .title("Java 后端工程师").companyName("示例科技").build();
    }

    @Test
    @DisplayName("listByUser: 按用户返回收藏列表")
    void listByUser_shouldReturnList() {
        when(repository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(fav()));
        assertThat(service.listByUser("u1")).hasSize(1);
    }

    @Test
    @DisplayName("listFavoriteJobIds: 返回已收藏岗位 ID 集合")
    void listFavoriteJobIds_shouldReturnIds() {
        when(repository.findByUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(List.of(fav(), fav().toBuilder().jobId(101L).build()));
        assertThat(service.listFavoriteJobIds("u1")).containsExactlyInAnyOrder(100L, 101L);
    }

    @Test
    @DisplayName("toggle: 未收藏时新增并返回 true（快照字段完整）")
    void toggle_new_shouldSave() {
        when(repository.findByUserIdAndJobId("u1", 100L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(repository.save(any(JobFavoriteEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean favorited = service.toggle("u1", job());

        assertThat(favorited).isTrue();
        verify(repository).save(any(JobFavoriteEntity.class));
    }

    @Test
    @DisplayName("toggle: 已收藏则取消并返回 false")
    void toggle_existing_shouldDelete() {
        JobFavoriteEntity own = fav();
        when(repository.findByUserIdAndJobId("u1", 100L)).thenReturn(Optional.of(own));

        boolean favorited = service.toggle("u1", job());

        assertThat(favorited).isFalse();
        verify(repository).delete(own);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("toggle: 并发兜底——二次检查命中时不重复保存")
    void toggle_duplicateGuard_shouldSkipSave() {
        when(repository.findByUserIdAndJobId("u1", 100L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(fav()));

        boolean favorited = service.toggle("u1", job());

        assertThat(favorited).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("countByUser: 返回收藏数量")
    void countByUser_shouldReturnCount() {
        when(repository.countByUserId("u1")).thenReturn(2L);
        assertThat(service.countByUser("u1")).isEqualTo(2L);
    }
}

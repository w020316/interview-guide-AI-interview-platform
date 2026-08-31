package com.example.interview.service;

import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.repository.FavoriteQuestionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteService 单元测试")
class FavoriteServiceTest {

    @Mock private FavoriteQuestionRepository favoriteRepository;
    @InjectMocks private FavoriteService service;

    private FavoriteQuestionEntity snapshot() {
        return FavoriteQuestionEntity.builder()
                .questionId(10L).question("什么是依赖注入?")
                .category("技术基础").difficulty("MEDIUM").build();
    }

    @Test
    @DisplayName("listByUser: 按用户返回收藏列表")
    void listByUser_shouldReturnList() {
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(List.of(snapshot().toBuilder().id(1L).userId("u1").build()));
        assertThat(service.listByUser("u1")).hasSize(1);
    }

    @Test
    @DisplayName("listFavoriteQuestionIds: 返回非空题目 ID 集合")
    void listFavoriteQuestionIds_shouldReturnIdsSet() {
        when(favoriteRepository.findByUserId("u1"))
                .thenReturn(List.of(snapshot().toBuilder().questionId(10L).build(),
                        snapshot().toBuilder().questionId(11L).build()));
        Set<Long> ids = service.listFavoriteQuestionIds("u1");
        assertThat(ids).containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    @DisplayName("listFavoriteQuestionIds: 忽略 questionId 为空的收藏")
    void listFavoriteQuestionIds_shouldSkipNullId() {
        when(favoriteRepository.findByUserId("u1"))
                .thenReturn(List.of(snapshot().toBuilder().questionId(null).build()));
        assertThat(service.listFavoriteQuestionIds("u1")).isEmpty();
    }

    @Test
    @DisplayName("toggle: 按 favoriteId 取消本人收藏并返回 false")
    void toggle_byFavoriteId_shouldDelete() {
        FavoriteQuestionEntity own = snapshot().toBuilder().id(7L).userId("u1").build();
        when(favoriteRepository.findById(7L)).thenReturn(Optional.of(own));

        boolean favorited = service.toggle(7L, "u1", null, snapshot());

        assertThat(favorited).isFalse();
        verify(favoriteRepository).delete(own);
    }

    @Test
    @DisplayName("toggle: 按 favoriteId 但非本人收藏时不删除并返回 false")
    void toggle_byFavoriteId_foreign_shouldNotDelete() {
        FavoriteQuestionEntity foreign = snapshot().toBuilder().id(7L).userId("u2").build();
        when(favoriteRepository.findById(7L)).thenReturn(Optional.of(foreign));

        boolean favorited = service.toggle(7L, "u1", null, snapshot());

        assertThat(favorited).isFalse();
        verify(favoriteRepository, never()).delete(any());
    }

    @Test
    @DisplayName("toggle: 按 questionId 已收藏则取消并返回 false")
    void toggle_byQuestionId_existing_shouldDelete() {
        FavoriteQuestionEntity own = snapshot().toBuilder().id(7L).userId("u1").build();
        when(favoriteRepository.findByUserIdAndQuestionId("u1", 10L)).thenReturn(Optional.of(own));

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isFalse();
        verify(favoriteRepository).delete(own);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("toggle: 新收藏时保存并返回 true")
    void toggle_newFavorite_shouldSave() {
        when(favoriteRepository.findByUserIdAndQuestionId("u1", 10L)).thenReturn(Optional.empty());
        when(favoriteRepository.save(any(FavoriteQuestionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isTrue();
        verify(favoriteRepository).save(any(FavoriteQuestionEntity.class));
    }

    @Test
    @DisplayName("toggle: 保存前再次命中收藏（并发兜底）返回 true 不重复保存")
    void toggle_duplicateGuard_shouldSkipSave() {
        FavoriteQuestionEntity own = snapshot().toBuilder().id(8L).userId("u1").build();
        when(favoriteRepository.findByUserIdAndQuestionId("u1", 10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(own));

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isTrue();
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("countByUser: 返回收藏数量")
    void countByUser_shouldReturnCount() {
        when(favoriteRepository.countByUserId("u1")).thenReturn(3L);
        assertThat(service.countByUser("u1")).isEqualTo(3L);
    }
}
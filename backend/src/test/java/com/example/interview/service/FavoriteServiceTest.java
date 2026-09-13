package com.example.interview.service;

import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.repository.FavoriteQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteService 单元测试")
class FavoriteServiceTest {

    @Mock private FavoriteQuestionRepository favoriteRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private FavoriteService service;

    private FavoriteQuestionEntity snapshot() {
        return FavoriteQuestionEntity.builder()
                .questionId(10L).question("什么是依赖注入?")
                .category("技术基础").difficulty("MEDIUM").build();
    }

    @BeforeEach
    void setUp() {
        // 单元测试不经过 Spring 生命周期，手动初始化插入事务模板
        service.initInsertTemplate();
    }

    /** 走插入路径（REQUIRES_NEW 模板）的用例需要事务管理器打桩 */
    private void stubTx() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
    @DisplayName("toggle: 按 questionId 删除行数 > 0 视为已收藏，取消并返回 false（P1-06）")
    void toggle_byQuestionId_existing_shouldDelete() {
        when(favoriteRepository.deleteByUserIdAndQuestionId("u1", 10L)).thenReturn(1L);

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isFalse();
        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("toggle: 新收藏时经独立事务保存并返回 true")
    void toggle_newFavorite_shouldSave() {
        stubTx();
        when(favoriteRepository.deleteByUserIdAndQuestionId("u1", 10L)).thenReturn(0L);
        when(favoriteRepository.saveAndFlush(any(FavoriteQuestionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isTrue();
        verify(favoriteRepository).saveAndFlush(any(FavoriteQuestionEntity.class));
    }

    @Test
    @DisplayName("toggle: 并发双击撞唯一约束时幂等返回 true 而非抛异常（P1-06）")
    void toggle_duplicateConstraintViolation_shouldReturnTrueIdempotently() {
        stubTx();
        when(favoriteRepository.deleteByUserIdAndQuestionId("u1", 10L)).thenReturn(0L);
        when(favoriteRepository.saveAndFlush(any(FavoriteQuestionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_favorite_question_user_question"));

        boolean favorited = service.toggle(null, "u1", 10L, snapshot());

        assertThat(favorited).isTrue();
    }

    @Test
    @DisplayName("toggle: questionId 为空（自定义题不会出现在 toggle 删除分支）直接走新增")
    void toggle_nullQuestionId_shouldInsertDirectly() {
        stubTx();
        when(favoriteRepository.saveAndFlush(any(FavoriteQuestionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean favorited = service.toggle(null, "u1", null,
                snapshot().toBuilder().questionId(null).build());

        assertThat(favorited).isTrue();
        verify(favoriteRepository).saveAndFlush(any(FavoriteQuestionEntity.class));
        verify(favoriteRepository, never()).deleteByUserIdAndQuestionId(any(), isNull());
    }

    @Test
    @DisplayName("countByUser: 返回收藏数量")
    void countByUser_shouldReturnCount() {
        when(favoriteRepository.countByUserId("u1")).thenReturn(3L);
        assertThat(service.countByUser("u1")).isEqualTo(3L);
    }
}

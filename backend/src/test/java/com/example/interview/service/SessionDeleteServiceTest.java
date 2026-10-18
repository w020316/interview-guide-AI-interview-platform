package com.example.interview.service;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewSessionService#deleteSession} 单元测试（G2-b，2026-10-18）。
 *
 * <p>两条边界是本类的重点：
 * <ol>
 *   <li>**必须显式删题目** —— `interview_question.session_id` 是无外键的 VARCHAR，
 *       删会话不会级联；漏了就会留下仍进统计口径的孤儿题；</li>
 *   <li>**不能删收藏夹** —— favorite_question 是题目快照，不属于会话的子表。</li>
 * </ol>
 * 因此断言不只看返回值，还要看「调了哪些仓储、没调哪些」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterviewSessionService.deleteSession 删除会话")
class SessionDeleteServiceTest {

    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private InterviewQuestionRepository questionRepository;

    @InjectMocks private InterviewSessionService service;

    private InterviewSessionEntity session(String sessionId, String userId) {
        return InterviewSessionEntity.builder().sessionId(sessionId).userId(userId).build();
    }

    @Test
    @DisplayName("本人会话 → 先删题目再删会话，返回题目数")
    void delete_ownSession_deletesQuestionsThenSession() {
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session("s1", "alice")));
        when(questionRepository.countBySessionId("s1")).thenReturn(7L);

        long deleted = service.deleteSession("s1", "alice");

        assertThat(deleted).isEqualTo(7);
        // 顺序有意义：先删子表再删主表；反过来会在有外键的库上失败（当前无外键，但语义应一致）
        var order = org.mockito.Mockito.inOrder(questionRepository, sessionRepository);
        order.verify(questionRepository).deleteBySessionId("s1");
        order.verify(sessionRepository).delete(any(InterviewSessionEntity.class));
    }

    @Test
    @DisplayName("无题目也要能删（count=0 是有效值，不是「没有会话」）")
    void delete_sessionWithoutQuestions_stillDeletes() {
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session("s2", "bob")));
        when(questionRepository.countBySessionId("s2")).thenReturn(0L);

        long deleted = service.deleteSession("s2", "bob");

        assertThat(deleted).isZero();
        verify(sessionRepository).delete(any(InterviewSessionEntity.class));
    }

    @Test
    @DisplayName("删他人会话 → 403（AccessDeniedException），且一道题、一条会话都不动")
    void delete_otherUsersSession_throws403AndTouchesNothing() {
        when(sessionRepository.findBySessionId("s3")).thenReturn(Optional.of(session("s3", "carol")));

        assertThatThrownBy(() -> service.deleteSession("s3", "alice"))
                .isInstanceOf(AccessDeniedException.class);

        verify(sessionRepository, never()).delete(any());
        verifyNoInteractions(questionRepository);
    }

    @Test
    @DisplayName("会话不存在 → 404（ResourceNotFoundException）")
    void delete_notFound_throws404() {
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSession("nope", "alice"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(questionRepository);
    }
}

package com.example.interview.service;

import com.example.interview.common.ConflictException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.ResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeService#deleteForUser} 单元测试（G2，2026-10-18）。
 *
 * <p>这个方法的难点不在"能不能删"，而在**三条边界**：
 * <ol>
 *   <li>被面试会话引用时不能直接删（`interview_session.resume_id` 是无 ON DELETE 的外键，
 *       直接删会撞约束变成 500）；</li>
 *   <li>级联删除的**顺序**必须「题目 → 会话 → 简历」，反了立刻撞外键；</li>
 *   <li>归属校验不能省 —— 删他人简历必须 403，不能静默成功。</li>
 * </ol>
 * 因此这里的断言刻意不只看"抛没抛异常"，还看**有没有动数据**。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeService.deleteForUser 删除简历")
class ResumeDeleteServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private InterviewQuestionRepository questionRepository;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ResumeService service;

    private ResumeEntity resume(long id, String userId) {
        return ResumeEntity.builder().id(id).userId(userId).build();
    }

    private InterviewSessionEntity session(String sessionId, Long resumeId) {
        return InterviewSessionEntity.builder().sessionId(sessionId).resumeId(resumeId).build();
    }

    @Test
    @DisplayName("无派生会话 → 直接删除，返回 0")
    void delete_noSessions_deletesResume() {
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume(1L, "alice")));
        when(sessionRepository.findAllByResumeId(1L)).thenReturn(List.of());

        int deleted = service.deleteForUser(1L, "alice", false);

        assertThat(deleted).isZero();
        verify(resumeRepository).delete(any(ResumeEntity.class));
        verifyNoInteractions(questionRepository);
    }

    @Test
    @DisplayName("有派生会话且未级联 → 409（ConflictException），且一份数据都不动")
    void delete_hasSessionsWithoutCascade_throwsConflictAndKeepsData() {
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume(1L, "alice")));
        when(sessionRepository.findAllByResumeId(1L))
                .thenReturn(List.of(session("s1", 1L), session("s2", 1L)));

        assertThatThrownBy(() -> service.deleteForUser(1L, "alice", false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2 场面试");

        verify(resumeRepository, never()).delete(any());
        verify(sessionRepository, never()).deleteAll(any());
        verifyNoInteractions(questionRepository);
    }

    @Test
    @DisplayName("级联删除 → 顺序必须是「题目 → 会话 → 简历」，返回删除的会话数")
    void delete_hasSessionsWithCascade_deletesInForeignKeySafeOrder() {
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume(1L, "alice")));
        List<InterviewSessionEntity> sessions = List.of(session("s1", 1L), session("s2", 1L));
        when(sessionRepository.findAllByResumeId(1L)).thenReturn(sessions);

        int deleted = service.deleteForUser(1L, "alice", true);

        assertThat(deleted).isEqualTo(2);

        // 顺序断言是核心：先删简历会撞 interview_session.resume_id 的外键
        InOrder order = inOrder(questionRepository, sessionRepository, resumeRepository);
        order.verify(questionRepository).deleteBySessionIdIn(List.of("s1", "s2"));
        order.verify(sessionRepository).deleteAll(sessions);
        order.verify(resumeRepository).delete(any(ResumeEntity.class));
    }

    @Test
    @DisplayName("简历不存在 → 404（ResourceNotFoundException），不碰会话表")
    void delete_notFound_throws404() {
        when(resumeRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteForUser(9L, "alice", true))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(sessionRepository, questionRepository);
    }

    @Test
    @DisplayName("删他人简历 → 403（AccessDeniedException），且不碰会话表")
    void delete_otherUsersResume_throws403() {
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume(1L, "bob")));

        assertThatThrownBy(() -> service.deleteForUser(1L, "alice", true))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(sessionRepository, questionRepository);
        verify(resumeRepository, never()).delete(any());
    }
}

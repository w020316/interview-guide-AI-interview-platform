package com.example.interview.service;

import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.InterviewQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterviewSessionService 单元测试")
class InterviewSessionServiceTest {

    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private InterviewQuestionRepository questionRepository;
    @InjectMocks private InterviewSessionService service;

    private InterviewSessionEntity mockSession;

    @BeforeEach
    void setUp() {
        mockSession = InterviewSessionEntity.builder()
                .id(1L).sessionId("test-uuid").userId("user1")
                .jobDescription("Java 后端").status("ONGOING")
                .build();
    }

    @Test
    @DisplayName("createSession: 应保存并返回新会话")
    void createSession_shouldSaveAndReturn() {
        when(sessionRepository.save(any())).thenReturn(mockSession);
        InterviewSessionEntity result = service.createSession("user1", "Java 后端", null);
        assertThat(result.getUserId()).isEqualTo("user1");
        verify(sessionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("listByUser: 应按用户查询历史")
    void listByUser_shouldReturnList() {
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1"))
                .thenReturn(List.of(mockSession));
        List<InterviewSessionEntity> result = service.listByUser("user1");
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getBySessionId: sessionId 不存在时抛 IllegalArgumentException")
    void getBySessionId_notFound_shouldThrow() {
        when(sessionRepository.findBySessionId("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBySessionId("bad-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("finishSession: 应将 status 更新为 FINISHED")
    void finishSession_shouldSetStatusFinished() {
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        when(sessionRepository.save(any())).thenReturn(mockSession);
        InterviewSessionEntity result = service.finishSession("test-uuid");
        assertThat(result.getStatus()).isEqualTo("FINISHED");
    }
}

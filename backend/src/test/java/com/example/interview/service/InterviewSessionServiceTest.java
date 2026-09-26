package com.example.interview.service;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.InterviewQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    @DisplayName("getBySessionId: sessionId 不存在时抛 ResourceNotFoundException（P3-01：资源不存在=404）")
    void getBySessionId_notFound_shouldThrow() {
        when(sessionRepository.findBySessionId("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBySessionId("bad-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("finishSession: 应将 status 更新为 FINISHED")
    void finishSession_shouldSetStatusFinished() {
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        when(sessionRepository.save(any())).thenReturn(mockSession);
        InterviewSessionEntity result = service.finishSession("test-uuid", "user1");
        assertThat(result.getStatus()).isEqualTo("FINISHED");
    }

    @Test
    @DisplayName("finishSession: 非本人会话应拒绝（B-17 service 层归属校验）")
    void finishSession_otherUsersSession_shouldThrow() {
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        assertThatThrownBy(() -> service.finishSession("test-uuid", "someone-else"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作他人会话");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("getBySessionId: 存在时应返回会话")
    void getBySessionId_found_shouldReturnSession() {
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        InterviewSessionEntity result = service.getBySessionId("test-uuid");
        assertThat(result.getSessionId()).isEqualTo("test-uuid");
        assertThat(result.getUserId()).isEqualTo("user1");
    }

    // ─────────────────────────── 题目管理 ───────────────────────────

    @Test
    @DisplayName("saveQuestions: 应为每题设置 sessionId 并批量保存")
    void saveQuestions_shouldSetSessionIdAndSaveAll() {
        InterviewQuestionEntity q1 = InterviewQuestionEntity.builder().question("什么是 JVM？").build();
        InterviewQuestionEntity q2 = InterviewQuestionEntity.builder().question("HashMap 线程安全吗？").build();
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        when(questionRepository.saveAll(anyList())).thenReturn(List.of(q1, q2));

        List<InterviewQuestionEntity> result =
                service.saveQuestions("test-uuid", new ArrayList<>(List.of(q1, q2)), "user1");

        assertThat(q1.getSessionId()).isEqualTo("test-uuid");
        assertThat(q2.getSessionId()).isEqualTo("test-uuid");
        assertThat(result).hasSize(2);
        verify(questionRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("saveQuestions: 非本人会话应拒绝（B-17 service 层归属校验）")
    void saveQuestions_otherUsersSession_shouldThrow() {
        InterviewQuestionEntity q1 = InterviewQuestionEntity.builder().question("什么是 JVM？").build();
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));

        assertThatThrownBy(() -> service.saveQuestions("test-uuid", List.of(q1), "someone-else"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权向他人会话写入题目");
        verify(questionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("saveQuestions: 会话不存在时抛 ResourceNotFoundException（P3-01：走 getBySessionId，资源不存在=404）")
    void saveQuestions_sessionNotFound_shouldThrow() {
        when(sessionRepository.findBySessionId("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveQuestions("bad-id", List.of(new InterviewQuestionEntity()), "user1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("会话不存在");
        verify(questionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("listQuestions: 应按会话查询题目")
    void listQuestions_shouldReturnQuestions() {
        InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                .id(1L).sessionId("test-uuid").question("Q").build();
        when(questionRepository.findBySessionIdOrderByIdAsc("test-uuid")).thenReturn(List.of(q));

        List<InterviewQuestionEntity> result = service.listQuestions("test-uuid");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getQuestion()).isEqualTo("Q");
    }

    @Test
    @DisplayName("saveAnswer: 应保存用户回答与评分")
    void saveAnswer_shouldSaveAnswerAndScore() {
        InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                .id(1L).sessionId("test-uuid").question("Q").build();
        when(questionRepository.findById(1L)).thenReturn(Optional.of(q));
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));
        when(questionRepository.save(q)).thenReturn(q);

        InterviewQuestionEntity result = service.saveAnswer(1L, "我的回答", 85, "user1");

        assertThat(result.getUserAnswer()).isEqualTo("我的回答");
        assertThat(result.getEvaluationScore()).isEqualTo(85);
        verify(questionRepository).save(q);
    }

    @Test
    @DisplayName("saveAnswer: 题目不存在时抛 ResourceNotFoundException（P3-01：资源不存在=404）")
    void saveAnswer_questionNotFound_shouldThrow() {
        when(questionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveAnswer(99L, "回答", 80, "user1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("题目不存在");
    }

    @Test
    @DisplayName("saveAnswer: 题目所属会话不存在时抛 ResourceNotFoundException（P3-01：资源不存在=404）")
    void saveAnswer_sessionNotFound_shouldThrow() {
        InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                .id(1L).sessionId("ghost-session").build();
        when(questionRepository.findById(1L)).thenReturn(Optional.of(q));
        when(sessionRepository.findBySessionId("ghost-session")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveAnswer(1L, "回答", 80, "user1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("saveAnswer: 非本人题目时抛越权异常")
    void saveAnswer_notOwner_shouldThrow() {
        InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                .id(1L).sessionId("test-uuid").build();
        when(questionRepository.findById(1L)).thenReturn(Optional.of(q));
        when(sessionRepository.findBySessionId("test-uuid")).thenReturn(Optional.of(mockSession));

        assertThatThrownBy(() -> service.saveAnswer(1L, "回答", 80, "another-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作他人题目");
        verify(questionRepository, never()).save(any());
    }

    // ─────────────────────────── 知识库关联 ───────────────────────────

    @Test
    @DisplayName("listAllQuestionsByUser: 无会话时返回空列表且不查题目")
    void listAllQuestionsByUser_noSessions_shouldReturnEmpty() {
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of());

        List<InterviewQuestionEntity> result = service.listAllQuestionsByUser("user1");

        assertThat(result).isEmpty();
        verify(questionRepository, never()).findBySessionIdInOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("listAllQuestionsByUser: 跨全部会话查询题目")
    void listAllQuestionsByUser_shouldQueryAllSessions() {
        InterviewSessionEntity s1 = InterviewSessionEntity.builder().sessionId("s1").build();
        InterviewSessionEntity s2 = InterviewSessionEntity.builder().sessionId("s2").build();
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of(s1, s2));
        when(questionRepository.findBySessionIdInOrderByCreatedAtDesc(anyCollection()))
                .thenReturn(List.of(InterviewQuestionEntity.builder().id(1L).build()));

        List<InterviewQuestionEntity> result = service.listAllQuestionsByUser("user1");

        assertThat(result).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor =
                (ArgumentCaptor<Collection<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
        verify(questionRepository).findBySessionIdInOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    @DisplayName("listWrongQuestionsByUser: 阈值过滤下推数据库，按会话收敛查询")
    void listWrongQuestionsByUser_shouldPushFilterDownToRepository() {
        // P1/S-02：过滤条件由 SQL 承担（evaluationScore < threshold，NULL 行天然不满足
        // LessThan 而被排除，与原内存过滤等价），service 只负责收敛会话 ID 与透传阈值
        InterviewQuestionEntity wrong = InterviewQuestionEntity.builder()
                .id(1L).sessionId("s1").evaluationScore(50).build();
        InterviewSessionEntity s1 = InterviewSessionEntity.builder().sessionId("s1").build();
        InterviewSessionEntity s2 = InterviewSessionEntity.builder().sessionId("s2").build();
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of(s1, s2));
        when(questionRepository.findBySessionIdInAndEvaluationScoreLessThanOrderByCreatedAtDesc(
                anyCollection(), eq(60))).thenReturn(List.of(wrong));

        List<InterviewQuestionEntity> result = service.listWrongQuestionsByUser("user1", 60);

        assertThat(result).containsExactly(wrong);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor =
                (ArgumentCaptor<Collection<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
        verify(questionRepository).findBySessionIdInAndEvaluationScoreLessThanOrderByCreatedAtDesc(
                captor.capture(), eq(60));
        assertThat(captor.getValue()).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    @DisplayName("listWrongQuestionsByUser: 无会话时返回空列表且不查题目")
    void listWrongQuestionsByUser_noSessions_shouldReturnEmpty() {
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of());

        List<InterviewQuestionEntity> result = service.listWrongQuestionsByUser("user1", 60);

        assertThat(result).isEmpty();
        verify(questionRepository, never())
                .findBySessionIdInAndEvaluationScoreLessThanOrderByCreatedAtDesc(anyCollection(), eq(60));
    }

    @Test
    @DisplayName("listRecentQuestionsByUser: 分页下推数据库，返回真实总数而非截断条数")
    void listRecentQuestionsByUser_shouldReturnPageWithRealTotal() {
        // P1/S-02：此前 total 取内存截断后的条数（=min(limit,总数)），语义失真；
        // 现由 Page.getTotalElements() 提供真实总数
        InterviewSessionEntity s1 = InterviewSessionEntity.builder().sessionId("s1").build();
        InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                .id(1L).sessionId("s1").question("什么是多态？").build();
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of(s1));
        // 注意：PageImpl 会在 offset+pageSize > total 时用内容条数"纠正"总数，
        // 故桩数据需满足 offset+pageSize <= total（此处 0+10 <= 17），total 才能保留 17
        when(questionRepository.findBySessionIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(q), PageRequest.of(0, 10), 17));

        Page<InterviewQuestionEntity> result = service.listRecentQuestionsByUser("user1", 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(17L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(questionRepository).findBySessionIdIn(anyCollection(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(org.springframework.data.domain.Sort.Order::getDirection)
                .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    @DisplayName("listRecentQuestionsByUser: 无会话时返回空分页且不查题目")
    void listRecentQuestionsByUser_noSessions_shouldReturnEmptyPage() {
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of());

        Page<InterviewQuestionEntity> result = service.listRecentQuestionsByUser("user1", 10);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0L);
        verify(questionRepository, never()).findBySessionIdIn(anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("questionSummary: 空用户应返回全零统计")
    void questionSummary_emptyUser_shouldReturnZeroStats() {
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of());

        Map<String, Object> summary = service.questionSummary("user1");

        assertThat(summary.get("totalQuestions")).isEqualTo(0L);
        assertThat(summary.get("answeredQuestions")).isEqualTo(0L);
        assertThat(summary.get("wrongQuestions")).isEqualTo(0L);
        assertThat(summary.get("averageScore")).isEqualTo(0.0);
        assertThat((List<?>) summary.get("byCategory")).isEmpty();
        assertThat((List<?>) summary.get("byDifficulty")).isEmpty();
    }

    @Test
    @DisplayName("questionSummary: 按分类/难度聚合统计（含空值过滤与均分计算）")
    void questionSummary_shouldAggregateByCategoryAndDifficulty() {
        // q1：已回答且错题（Java基础/EASY）
        InterviewQuestionEntity q1 = InterviewQuestionEntity.builder()
                .id(1L).sessionId("s1").category("Java基础").difficulty("EASY")
                .userAnswer("我的回答").evaluationScore(50).build();
        // q2：回答为空白 → 不计 answered；80 分 → 非错题（Java基础/HARD）
        InterviewQuestionEntity q2 = InterviewQuestionEntity.builder()
                .id(2L).sessionId("s1").category("Java基础").difficulty("HARD")
                .userAnswer("   ").evaluationScore(80).build();
        // q3：分类/难度/回答/评分全空 → 不参与任何统计
        InterviewQuestionEntity q3 = InterviewQuestionEntity.builder()
                .id(3L).sessionId("s1").build();

        InterviewSessionEntity s1 = InterviewSessionEntity.builder().sessionId("s1").build();
        when(sessionRepository.findByUserIdOrderByCreatedAtDesc("user1")).thenReturn(List.of(s1));
        when(questionRepository.findBySessionIdInOrderByCreatedAtDesc(anyCollection()))
                .thenReturn(List.of(q1, q2, q3));

        Map<String, Object> summary = service.questionSummary("user1");

        assertThat(summary.get("totalQuestions")).isEqualTo(3L);
        assertThat(summary.get("answeredQuestions")).isEqualTo(1L);
        assertThat(summary.get("wrongQuestions")).isEqualTo(1L);
        // 仅 q1/q2 有评分：(50+80)/2 = 65.0
        assertThat(summary.get("averageScore")).isEqualTo(65.0);

        // 按分类聚合：Java基础 total=2 answered=1 wrong=1 avg=65.0
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) summary.get("byCategory");
        assertThat(byCategory).hasSize(1);
        Map<String, Object> javaStat = byCategory.get(0);
        assertThat(javaStat.get("category")).isEqualTo("Java基础");
        assertThat(javaStat.get("total")).isEqualTo(2L);
        assertThat(javaStat.get("answered")).isEqualTo(1L);
        assertThat(javaStat.get("wrong")).isEqualTo(1L);
        assertThat(javaStat.get("avgScore")).isEqualTo(65.0);

        // 按难度聚合：EASY/HARD 各 1 题
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDifficulty = (List<Map<String, Object>>) summary.get("byDifficulty");
        assertThat(byDifficulty).hasSize(2);
        assertThat(byDifficulty)
                .extracting(stat -> stat.get("difficulty"))
                .containsExactlyInAnyOrder("EASY", "HARD");
    }
}

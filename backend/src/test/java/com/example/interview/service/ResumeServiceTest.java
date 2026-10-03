package com.example.interview.service;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.ResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeService 单元测试")
class ResumeServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ResumeService service;

    private ResumeEntity mockResume;

    @BeforeEach
    void setUp() {
        mockResume = ResumeEntity.builder()
                .id(1L).userId("alice")
                .content("我的简历").targetJob("Java 后端")
                .overallScore(85)
                .analysisResult("{\"overallScore\":85}")
                .build();
    }

    @Test
    @DisplayName("saveResume: 应解析 overallScore 并保存")
    void saveResume_shouldParseScoreAndSave() {
        when(resumeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ResumeEntity saved = service.saveResume("alice", "简历", "Java",
                "{\"overallScore\":85,\"dimensions\":[{\"name\":\"岗位匹配度\",\"score\":80}],\"strengths\":[],\"improvements\":[]}");
        assertThat(saved.getOverallScore()).isEqualTo(85);
        verify(resumeRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("saveResume: 不可修复的串走兜底，不得写入 overallScore，且带 AI_PARSE_FAILED（P1-01 回归）")
    void saveResume_unrepairableFallback_shouldNotWriteScore() {
        when(resumeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 复现原缺陷的输入形状：AI 原始输出结构性损坏、JsonRepairUtil 也修不回来
        ResumeEntity saved = service.saveResume("alice", "简历", "Java", "{not json at all");
        assertThat(saved.getOverallScore())
                .as("兜底结果不得把 overallScore 落库（否则 0 分与解析失败无法区分）")
                .isNull();
        assertThat(saved.getAnalysisResult()).contains("AI_PARSE_FAILED");
        verify(resumeRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("saveResume: dimensions 为空（旧兜底串形状）即使 overallScore=0 也不得落库评分（P1-01 回归）")
    void saveResume_emptyDimensions_shouldNotWriteScore() {
        when(resumeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 历史脏数据形状：合法 JSON，但 dimensions 为空数组、overallScore=0
        // —— 这正是线上 id=17 那条 93 字节记录的形态
        ResumeEntity saved = service.saveResume("alice", "简历", "Java",
                "{\"strengths\":[],\"dimensions\":[],\"improvements\":[\"AI 返回内容无法解析，请稍后重试\"],\"overallScore\":0}");
        assertThat(saved.getOverallScore())
                .as("无维度的分析结果视为失败，不得把 overallScore=0 写入数字列")
                .isNull();
    }

    @Test
    @DisplayName("saveResume: 解析失败时不抛异常，overallScore 保持 null")
    void saveResume_invalidJson_shouldNotThrow() {
        when(resumeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ResumeEntity saved = service.saveResume("alice", "x", "j", "not-json");
        assertThat(saved.getOverallScore()).isNull();
    }

    @Test
    @DisplayName("listByUser: 应按用户查询历史")
    void listByUser_shouldReturnList() {
        when(resumeRepository.findByUserIdOrderByCreatedAtDesc("alice"))
                .thenReturn(List.of(mockResume));
        List<ResumeEntity> list = service.listByUser("alice");
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("getByIdAndUser: 简历不存在时应抛 ResourceNotFoundException（P3-01：资源不存在=404）")
    void getByIdAndUser_notFound_shouldThrow() {
        when(resumeRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByIdAndUser(99L, "alice"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("简历不存在");
    }

    @Test
    @DisplayName("getByIdAndUser: 简历存在但属于他人时应抛 AccessDeniedException（IDOR 防护，v1.16 语义修正）")
    void getByIdAndUser_otherUser_shouldThrowAccessDenied() {
        // v1.16：越权改抛 AccessDeniedException，GlobalExceptionHandler 映射为 HTTP 403
        ResumeEntity other = ResumeEntity.builder()
                .id(2L).userId("bob").build();
        when(resumeRepository.findById(2L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.getByIdAndUser(2L, "alice"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("无权访问该简历");
    }

    @Test
    @DisplayName("countByUser: 应返回用户简历数量")
    void countByUser_shouldReturnCount() {
        when(resumeRepository.countByUserId("alice")).thenReturn(3L);
        assertThat(service.countByUser("alice")).isEqualTo(3L);
    }
}

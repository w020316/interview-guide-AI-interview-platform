package com.example.interview.service;

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
        when(resumeRepository.save(any())).thenReturn(mockResume);
        ResumeEntity saved = service.saveResume("alice", "简历", "Java",
                "{\"overallScore\":85,\"dimensions\":[]}");
        assertThat(saved.getOverallScore()).isEqualTo(85);
        verify(resumeRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("saveResume: 解析失败时不抛异常，overallScore 保持 null")
    void saveResume_invalidJson_shouldNotThrow() {
        ResumeEntity entity = ResumeEntity.builder()
                .id(2L).userId("alice").content("x").targetJob("j")
                .analysisResult("not-json").build();
        when(resumeRepository.save(any())).thenReturn(entity);
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
    @DisplayName("getByIdAndUser: 简历不存在时应抛异常")
    void getByIdAndUser_notFound_shouldThrow() {
        when(resumeRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByIdAndUser(99L, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("简历不存在");
    }

    @Test
    @DisplayName("getByIdAndUser: 简历存在但属于他人时应抛异常（IDOR 防护）")
    void getByIdAndUser_otherUser_shouldThrow() {
        ResumeEntity other = ResumeEntity.builder()
                .id(2L).userId("bob").build();
        when(resumeRepository.findById(2L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.getByIdAndUser(2L, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("countByUser: 应返回用户简历数量")
    void countByUser_shouldReturnCount() {
        when(resumeRepository.countByUserId("alice")).thenReturn(3L);
        assertThat(service.countByUser("alice")).isEqualTo(3L);
    }
}

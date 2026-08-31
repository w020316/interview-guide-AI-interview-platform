package com.example.interview.service;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.repository.InterviewEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterviewEventService 单元测试")
class InterviewEventServiceTest {

    @Mock private InterviewEventRepository eventRepository;
    @InjectMocks private InterviewEventService service;

    private final LocalDateTime at = LocalDateTime.of(2026, 9, 5, 14, 0);

    private InterviewEventEntity event(long id, String userId) {
        return InterviewEventEntity.builder()
                .id(id).userId(userId).title("字节跳动 · 后端一面")
                .interviewAt(at).status("UPCOMING").build();
    }

    @Test
    @DisplayName("listByUser: 按用户返回日程（升序）")
    void listByUser_shouldReturnList() {
        when(eventRepository.findByUserIdOrderByInterviewAtAsc("u1"))
                .thenReturn(List.of(event(1L, "u1")));
        assertThat(service.listByUser("u1")).hasSize(1);
    }

    @Test
    @DisplayName("listByRange: 返回时间范围内的日程")
    void listByRange_shouldReturn() {
        when(eventRepository.findByUserIdAndInterviewAtBetween("u1", at.minusDays(1), at.plusDays(1)))
                .thenReturn(List.of(event(1L, "u1")));
        List<InterviewEventEntity> result = service.listByRange("u1", at.minusDays(1), at.plusDays(1));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("create: 注入 userId，未指定状态时默认 UPCOMING")
    void create_shouldSetUserAndDefaultStatus() {
        InterviewEventEntity input = InterviewEventEntity.builder()
                .title("面试").interviewAt(at).build();
        when(eventRepository.save(any(InterviewEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InterviewEventEntity created = service.create("u1", input);

        assertThat(created.getUserId()).isEqualTo("u1");
        assertThat(created.getId()).isNull();
        assertThat(created.getStatus()).isEqualTo("UPCOMING");
    }

    @Test
    @DisplayName("update: 仅本人可更新，非法操作他人触发异常")
    void update_foreign_shouldThrow() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event(1L, "u2")));
        assertThatThrownBy(() -> service.update(1L, "u1", new InterviewEventEntity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作");
    }

    @Test
    @DisplayName("update: 本人日程可更新指定字段")
    void update_owned_shouldApply() {
        InterviewEventEntity existing = event(1L, "u1");
        when(eventRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(eventRepository.save(any(InterviewEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InterviewEventEntity updates = new InterviewEventEntity();
        updates.setTitle("已改标题");
        updates.setStatus("DONE");
        InterviewEventEntity result = service.update(1L, "u1", updates);

        assertThat(result.getTitle()).isEqualTo("已改标题");
        assertThat(result.getStatus()).isEqualTo("DONE");
        assertThat(result.getInterviewAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("update: 不存在的日程抛出异常")
    void update_notFound_shouldThrow() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, "u1", new InterviewEventEntity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日程不存在");
    }

    @Test
    @DisplayName("delete: 本人日程可删除")
    void delete_owned_shouldDelete() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event(1L, "u1")));
        service.delete(1L, "u1");
        verify(eventRepository).delete(any(InterviewEventEntity.class));
    }

    @Test
    @DisplayName("delete: 非本人日程不删除并抛异常")
    void delete_foreign_shouldThrowAndNotDelete() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event(1L, "u2")));
        assertThatThrownBy(() -> service.delete(1L, "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作");
        verify(eventRepository, never()).delete(any());
    }
}
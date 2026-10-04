package com.example.interview.controller;

import com.example.interview.common.ConflictException;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InterviewEventController} MockMvc 集成测试（第六轮 P1-04，v1.47.0）。
 *
 * <p>锁定：日历 {@code status} 必须有枚举白名单校验（create + update），非法值返回 400
 * 而非原样落库；合法值放行；null/缺失仍走「未提供」（由 service 默认 UPCOMING）。
 */
@WebMvcTest(controllers = InterviewEventController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class InterviewEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InterviewEventService eventService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";
    private static final String VALID_TIME = "2026-09-05T14:00:00";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static String createBody(String statusJson) {
        String base = "{\"title\":\"字节跳动 · 后端一面\",\"interviewAt\":\"" + VALID_TIME + "\"";
        return statusJson == null ? base + "}" : base + ",\"status\":" + statusJson + "}";
    }

    @Nested
    @DisplayName("POST /api/calendar/event 创建日程")
    class Create {

        @Test
        @DisplayName("非法 status（NOT_A_STATUS_XYZ / 999 / 空串）返回 400，不落库（P1-04 回归）")
        void create_illegalStatus_returns400() throws Exception {
            String[] illegal = {"\"NOT_A_STATUS_XYZ\"", "\"999\"", "\"\""};
            for (String s : illegal) {
                mockMvc.perform(post("/api/calendar/event")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(s)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(400))
                        .andExpect(jsonPath("$.message").value(
                                "status 取值非法，仅支持：UPCOMING（待面试）、DONE（已完成）、CANCELLED（已取消）"));
            }
        }

        @Test
        @DisplayName("合法 status（UPCOMING / DONE / CANCELLED）返回 200")
        void create_legalStatus_returns200() throws Exception {
            for (String s : new String[]{"UPCOMING", "DONE", "CANCELLED"}) {
                when(eventService.create(eq(USER_ID), any())).thenAnswer(inv -> {
                    InterviewEventEntity e = inv.getArgument(1);
                    e.setId(1L);
                    return e;
                });
                mockMvc.perform(post("/api/calendar/event")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody("\"" + s + "\"")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.status").value(s));
            }
        }

        @Test
        @DisplayName("status 缺失时按「未提供」处理（200），实体 status 为 null 交给 service 默认 UPCOMING")
        void create_missingStatus_passesNullToService() throws Exception {
            // 注意：返回**新实体**而非改动入参，避免污染下面 captor 捕获的对象
            when(eventService.create(eq(USER_ID), any())).thenAnswer(inv -> {
                InterviewEventEntity e = inv.getArgument(1);
                return InterviewEventEntity.builder()
                        .id(1L).userId(USER_ID).title(e.getTitle())
                        .interviewAt(e.getInterviewAt())
                        // 复刻 InterviewEventService.create 的默认行为（已由 InterviewEventServiceTest 锁定）
                        .status(e.getStatus() == null ? "UPCOMING" : e.getStatus())
                        .build();
            });

            mockMvc.perform(post("/api/calendar/event")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value("UPCOMING"));

            ArgumentCaptor<InterviewEventEntity> captor = ArgumentCaptor.forClass(InterviewEventEntity.class);
            org.mockito.Mockito.verify(eventService).create(eq(USER_ID), captor.capture());
            assertThat(captor.getValue().getStatus())
                    .as("控制器不得为缺失 status 发明取值，交由 service 默认")
                    .isNull();
        }

        @Test
        @DisplayName("同标题+同时刻已存在 → HTTP 409 + code 409（第三批收口：不静默去重）")
        void create_duplicateEvent_returns409() throws Exception {
            doThrow(new ConflictException("日历中已存在「同标题 + 同时刻」的日程，未重复创建"))
                    .when(eventService).create(any(), any());

            mockMvc.perform(post("/api/calendar/event")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("日历中已存在「同标题 + 同时刻」的日程，未重复创建"));
        }
    }

    @Nested
    @DisplayName("PUT /api/calendar/event/{id} 更新日程")
    class Update {

        @Test
        @DisplayName("非法 status 返回 400，不落库（P1-04 回归）")
        void update_illegalStatus_returns400() throws Exception {
            mockMvc.perform(put("/api/calendar/event/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"NOT_A_STATUS\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(
                            "status 取值非法，仅支持：UPCOMING（待面试）、DONE（已完成）、CANCELLED（已取消）"));
        }

        @Test
        @DisplayName("合法 status 返回 200")
        void update_legalStatus_returns200() throws Exception {
            InterviewEventEntity updated = InterviewEventEntity.builder()
                    .id(1L).userId(USER_ID).title("已改")
                    .interviewAt(LocalDateTime.parse(VALID_TIME)).status("CANCELLED").build();
            when(eventService.update(eq(1L), eq(USER_ID), any())).thenReturn(updated);

            mockMvc.perform(put("/api/calendar/event/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CANCELLED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("status 缺失时不下发更新（200）")
        void update_missingStatus_doesNotSet() throws Exception {
            when(eventService.update(eq(1L), eq(USER_ID), any())).thenAnswer(inv -> {
                InterviewEventEntity e = inv.getArgument(2);
                return InterviewEventEntity.builder()
                        .id(1L).userId(USER_ID).title("原标题")
                        .interviewAt(LocalDateTime.parse(VALID_TIME)).status("UPCOMING").build();
            });

            mockMvc.perform(put("/api/calendar/event/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"新标题\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            ArgumentCaptor<InterviewEventEntity> captor = ArgumentCaptor.forClass(InterviewEventEntity.class);
            org.mockito.Mockito.verify(eventService).update(eq(1L), eq(USER_ID), captor.capture());
            assertThat(captor.getValue().getStatus())
                    .as("未提供 status 时不得修改")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("DELETE /api/calendar/event/{id} 删除日程")
    class Delete {

        @Test
        @DisplayName("日程不存在返回 HTTP 404 + code 404（v1.48.0 统一口径）")
        void delete_missingEvent_returns404() throws Exception {
            doThrow(new com.example.interview.common.ResourceNotFoundException("日程不存在：999999999"))
                    .when(eventService).delete(eq(999999999L), eq(USER_ID));

            mockMvc.perform(delete("/api/calendar/event/999999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("日程不存在：999999999"));
        }

        @Test
        @DisplayName("删除成功返回 200")
        void delete_ok_returns200() throws Exception {
            mockMvc.perform(delete("/api/calendar/event/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }
}

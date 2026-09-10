package com.example.interview.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能体控制器测试
 * 重点覆盖：JWT 认证上下文注入、会话列表/历史消息返回、防越权空返回
 *
 * 说明：Controller 直接从 SecurityContextHolder 读取 principal，
 * standalone MockMvc 不自动填充，测试中手动 set/clear。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private com.example.interview.service.agent.AgentService agentService;

    @InjectMocks
    private AgentController agentController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(agentController).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("会话列表：正常返回用户会话")
    void conversationsOk() throws Exception {
        when(agentService.listConversations(anyString()))
                .thenReturn(List.of(
                        com.example.interview.entity.AgentConversationEntity.builder()
                                .id(1L).userId("u1").title("找岗位").build()));

        mockMvc.perform(get("/api/agent/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("找岗位"));
    }

    @Test
    @DisplayName("历史消息：他人会话返回空列表（防越权）")
    void messagesOfOthersReturnEmpty() throws Exception {
        when(agentService.listMessages(anyString(), anyLong()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agent/conversations/999/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("流式对话：空消息返回 error 事件")
    void streamChatEmptyMessage() throws Exception {
        // SseEmitter 为异步派发，需等待 async 结果后再断言
        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        String content = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 注：MockMvc 默认 ISO-8859-1 编码会损坏中文，仅断言 error 事件名与非空 data
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("event:error"));
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("data:"));
    }
}

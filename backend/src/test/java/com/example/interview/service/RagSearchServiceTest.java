package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService 单元测试")
class RagSearchServiceTest {

    private static final String USER_ID = "test-user-1";

    @Mock private VectorStore vectorStore;
    @Mock private ChatClient chatClient;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private RagSearchService service;

    @BeforeEach
    void setUp() {
        // 模拟 chatClient 链式调用
        // 由于 ChatClient 链较复杂，这里仅做行为验证不真正调用 AI
    }

    @Test
    @DisplayName("search: 空查询应返回 []")
    void search_blankQuery_shouldReturnEmptyArray() {
        assertThat(service.search("", 5, USER_ID)).isEqualTo("[]");
        assertThat(service.search(null, 5, USER_ID)).isEqualTo("[]");
        assertThat(service.search("   ", 5, USER_ID)).isEqualTo("[]");
    }

    @Test
    @DisplayName("search: 异常时应返回 []")
    void search_exception_shouldReturnEmptyArray() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("sim failed"));
        String result = service.search("test", 5, USER_ID);
        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("answerWithRag: 空问题应返回提示")
    void answerWithRag_blank_shouldReturnHint() {
        String result = service.answerWithRag("", USER_ID);
        assertThat(result).contains("问题不能为空");
    }

    @Test
    @DisplayName("importKnowledge: 空列表应返回 0")
    void importKnowledge_emptyList_shouldReturnZero() {
        assertThat(service.importKnowledge(null, USER_ID)).isEqualTo(0);
        assertThat(service.importKnowledge(List.of(), USER_ID)).isEqualTo(0);
        assertThat(service.importKnowledge(List.of("", "  "), USER_ID)).isEqualTo(0);
    }
}


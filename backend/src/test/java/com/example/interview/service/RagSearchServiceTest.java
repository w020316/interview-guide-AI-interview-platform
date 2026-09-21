package com.example.interview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService 单元测试")
class RagSearchServiceTest {

    private static final String USER_ID = "test-user-1";

    @Mock private VectorStore vectorStore;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec chatClientRequestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private Counter ragCounter;
    @Mock private Timer aiCallTimer;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private RagSearchService service;

    /** mock ChatClient 同步调用链：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

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

    // ─────────────────────────── search: 正常路径 ───────────────────────────

    @Test
    @DisplayName("search: 命中文档时返回 JSON 数组（id/content/score）")
    void search_docsFound_returnsJsonArray() throws Exception {
        Document d1 = Document.builder()
                .id("d1").text("HashMap 内容").metadata(Map.of("distance", 0.92)).build();
        Document d2 = Document.builder()
                .id("d2").text("ConcurrentHashMap 内容").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(d1, d2));

        String result = service.search("HashMap", 5, USER_ID);

        JsonNode arr = new ObjectMapper().readTree(result);
        assertThat(arr).hasSize(2);
        assertThat(arr.get(0).get("id").asText()).isEqualTo("d1");
        assertThat(arr.get(0).get("content").asText()).isEqualTo("HashMap 内容");
        assertThat(arr.get(0).get("score").asDouble()).isEqualTo(0.92);
        // d2 无 distance metadata → 默认 0.0
        assertThat(arr.get(1).get("score").asDouble()).isZero();
    }

    @Test
    @DisplayName("search: 向量库返回 null 时应返回 []")
    void search_nullDocs_shouldReturnEmptyArray() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);
        assertThat(service.search("test", 5, USER_ID)).isEqualTo("[]");
    }

    @Test
    @DisplayName("search: topK 超过 50 应夹紧为 50，并按 userId+shared 过滤")
    void search_topKClampedTo50_andUsesUserFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search("test", 200, USER_ID);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(50);
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    // ──────────────────────── answerWithRag: 正常路径 ────────────────────────

    @Test
    @DisplayName("answerWithRag: 检索到资料时构建含参考资料的 prompt 并返回 AI 回答")
    void answerWithRag_withKnowledge_returnsAiResponse() {
        Document doc = Document.builder().id("d1").text("HashMap 是数组+链表").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        stubChatClient("HashMap 底层是数组加链表。");

        String result = service.answerWithRag("HashMap 原理", USER_ID);

        assertThat(result).isEqualTo("HashMap 底层是数组加链表。");
        // 真实构建的 prompt 应包含检索到的参考资料与净化后的问题
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("【参考】").contains("HashMap 是数组+链表")
                .contains("【问题】").contains("HashMap 原理");
        // finally 埋点
        verify(ragCounter).increment();
        verify(aiCallTimer).record(anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("answerWithRag: 无检索结果时 prompt 资料段为“无”，仍返回 AI 回答")
    void answerWithRag_noKnowledge_promptsWithoutMaterial() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        stubChatClient("没有资料也能回答。");

        String result = service.answerWithRag("什么是 JIT", USER_ID);

        assertThat(result).isEqualTo("没有资料也能回答。");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("【参考资料】\n无");
    }

    @Test
    @DisplayName("answerWithRag: AI 返回空白时应返回兜底提示")
    void answerWithRag_aiBlankResponse_returnsFallbackHint() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        stubChatClient("  ");

        String result = service.answerWithRag("test", USER_ID);

        assertThat(result).isEqualTo("AI 暂时无法生成回答，请稍后重试。");
        verify(ragCounter).increment();
    }

    @Test
    @DisplayName("answerWithRag: 检索异常不应阻断 AI 回答")
    void answerWithRag_searchException_stillReturnsAiResponse() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("embedding down"));
        stubChatClient("AI 正常回答");

        String result = service.answerWithRag("question", USER_ID);

        assertThat(result).isEqualTo("AI 正常回答");
        verify(chatClient).prompt();
    }

    // ──────────────────────── addToVectorStore: 容量保护 ────────────────────────

    @Test
    @DisplayName("addToVectorStore: null 或空列表返回 0 且不触库")
    void addToVectorStore_nullOrEmpty_returnsZero() {
        assertThat(service.addToVectorStore(null)).isZero();
        assertThat(service.addToVectorStore(List.of())).isZero();
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("addToVectorStore: 容量未满时全部入库并累计计数")
    void addToVectorStore_withinCapacity_addsAll() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        List<Document> docs = List.of(
                Document.builder().id("d1").text("a").build(),
                Document.builder().id("d2").text("b").build());

        int stored = service.addToVectorStore(docs);

        assertThat(stored).isEqualTo(2);
        verify(vectorStore).add(docs);
        // 连续入库会累计计数
        assertThat(service.addToVectorStore(List.of(Document.builder().id("d3").text("c").build())))
                .isEqualTo(1);
        verify(vectorStore, times(2)).add(anyList());
    }

    @Test
    @DisplayName("addToVectorStore: 容量已满时拒绝入库")
    void addToVectorStore_full_rejectsAll() {
        ReflectionTestUtils.setField(service, "maxDocuments", 0);

        int stored = service.addToVectorStore(
                List.of(Document.builder().id("d1").text("a").build()));

        assertThat(stored).isZero();
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("addToVectorStore: 容量不足时部分入库（截断到剩余额度）")
    void addToVectorStore_partialAcceptance_truncatesToRemaining() {
        ReflectionTestUtils.setField(service, "maxDocuments", 2);
        List<Document> docs = List.of(
                Document.builder().id("d1").text("a").build(),
                Document.builder().id("d2").text("b").build(),
                Document.builder().id("d3").text("c").build());

        int stored = service.addToVectorStore(docs);

        assertThat(stored).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(extractTexts(captor.getValue())).containsExactly("a", "b");
    }

    /** 提取文档文本列表（辅助断言） */
    private List<String> extractTexts(List<Document> docs) {
        return docs.stream().map(Document::getText).toList();
    }

    // ──────────────────────── importKnowledge: 去重与入库 ────────────────────────

    @Test
    @DisplayName("importKnowledge: 非重复文档全部入库并携带 userId metadata")
    void importKnowledge_newDocuments_storesWithUserId() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        // 去重预检：similaritySearch 返回空 → 全部视为新文档
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        int stored = service.importKnowledge(List.of("知识点 A", "知识点 B"), USER_ID);

        assertThat(stored).isEqualTo(2);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> added = captor.getValue();
        assertThat(added).hasSize(2);
        assertThat(added.get(0).getMetadata())
                .containsEntry("userId", USER_ID)
                .containsEntry("type", "knowledge");
        assertThat(added.get(1).getText()).isEqualTo("知识点 B");
    }

    @Test
    @DisplayName("importKnowledge: 重复文档跳过，仅导入新增部分")
    void importKnowledge_duplicateSkipped_storesOnlyNew() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        // 第 1 条命中已有文档（重复），第 2 条未命中（新增）
        Document existing = Document.builder().id("old").text("知识点 A").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(existing))
                .thenReturn(List.of());

        int stored = service.importKnowledge(List.of("知识点 A", "知识点 B"), USER_ID);

        assertThat(stored).isEqualTo(1);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getText()).isEqualTo("知识点 B");
    }

    @Test
    @DisplayName("importKnowledge: 去重预检异常按非重复处理，不阻断导入")
    void importKnowledge_dedupCheckException_treatedAsNew() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("embedding 超时"));

        int stored = service.importKnowledge(List.of("知识点 A", "知识点 B"), USER_ID);

        assertThat(stored).isEqualTo(2);
        verify(vectorStore).add(anyList());
    }

    @Test
    @DisplayName("importKnowledge: 全部重复时返回 0 且不入库")
    void importKnowledge_allDuplicates_returnsZero() {
        Document existing = Document.builder().id("old").text("已有").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(existing));

        int stored = service.importKnowledge(List.of("已有", "已有"), USER_ID);

        assertThat(stored).isZero();
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("importKnowledge: 入库异常时吞掉异常返回 0")
    void importKnowledge_storeException_returnsZero() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        doThrow(new RuntimeException("vector store down"))
                .when(vectorStore).add(anyList());

        int stored = service.importKnowledge(List.of("知识点 A"), USER_ID);

        assertThat(stored).isZero();
    }

    // ───────── 容量计数成对增减（v1.34.1 P2-6 修复回归）─────────
    // 背景：删除路径此前直接 vectorStore.delete，绕过 storedDocs 计数；
    // 计数只有「启动恢复 set」与「新增 addAndGet」两处写入口，无任何递减路径，
    // 反复分析同一简历会让计数单调虚高，最终「向量库实际未满却拒绝导入知识」。

    @Test
    @DisplayName("removeFromVectorStore: 删除成功后容量计数成对递减（P2-6 回归）")
    void removeFromVectorStore_decrementsStoredCount() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        service.addToVectorStore(List.of(doc("d1"), doc("d2"), doc("d3")));
        assertThat(service.storedCount()).isEqualTo(3);

        int removed = service.removeFromVectorStore(List.of("d2"));

        assertThat(removed).isEqualTo(1);
        assertThat(service.storedCount()).isEqualTo(2);
        verify(vectorStore).delete(List.of("d2"));
    }

    @Test
    @DisplayName("removeFromVectorStore: 删除后容量释放，原本被拒的入库可再次成功")
    void removeFromVectorStore_freesCapacityForNewImport() {
        ReflectionTestUtils.setField(service, "maxDocuments", 2);
        service.addToVectorStore(List.of(doc("d1"), doc("d2")));
        // 已达上限：新入库被拒
        assertThat(service.addToVectorStore(List.of(doc("d3")))).isZero();

        service.removeFromVectorStore(List.of("d1"));

        // 删除释放 1 个名额后，入库恢复可用（修复前计数不降，这里会一直是 0）
        assertThat(service.addToVectorStore(List.of(doc("d3")))).isEqualTo(1);
    }

    @Test
    @DisplayName("removeFromVectorStore: 删除失败时计数不变；计数不会降为负数")
    void removeFromVectorStore_deleteFailureKeepsCountAndNeverNegative() {
        ReflectionTestUtils.setField(service, "maxDocuments", 10);
        service.addToVectorStore(List.of(doc("d1")));

        doThrow(new RuntimeException("delete down")).when(vectorStore).delete(anyList());
        assertThat(service.removeFromVectorStore(List.of("d1"))).isZero();
        assertThat(service.storedCount()).as("删除失败不应改计数").isEqualTo(1);

        // 空列表为无害空操作
        assertThat(service.removeFromVectorStore(List.of())).isZero();
        assertThat(service.removeFromVectorStore(null)).isZero();

        // 过度删除时钳到 0，不得为负（否则 maxDocuments - storedDocs 会放开上限）
        doNothing().when(vectorStore).delete(anyList());
        service.removeFromVectorStore(List.of("x1", "x2", "x3", "x4", "x5"));
        assertThat(service.storedCount()).isZero();
    }

    private static org.springframework.ai.document.Document doc(String id) {
        return org.springframework.ai.document.Document.builder().id(id).text("t-" + id).build();
    }
}


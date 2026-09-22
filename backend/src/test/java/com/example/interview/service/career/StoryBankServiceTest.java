package com.example.interview.service.career;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.StoryBankEntity;
import com.example.interview.repository.StoryBankRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link StoryBankService} 单元测试（v1.36.0 面试故事库）
 *
 * <p>覆盖点：STAR 提炼（合法/非法/空返回兜底）、六项质检、追问链、
 * CRUD 归属校验（防 IDOR）与容量上限。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoryBankService 面试故事库单元测试")
class StoryBankServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private StoryBankRepository repository;

    @InjectMocks
    private StoryBankService service;

    private static final String VALID_EXTRACT_JSON =
            "{\"stories\":[{\"title\":\"二手交易平台性能优化\",\"situation\":\"校园平台没人用\","
                    + "\"task\":\"负责搜索模块\",\"action\":\"把 MySQL 模糊查询改成 ES 并加缓存\","
                    + "\"result\":\"日活从 30 涨到 200\",\"evidence\":\"上线前后监控数据\","
                    + "\"capabilities\":[\"性能优化\",\"快速学习\"]}]}";
    private static final String VALID_CHECK_JSON =
            "{\"items\":[{\"name\":\"结构是否清楚\",\"pass\":true,\"comment\":\"STAR 完整\"}],"
                    + "\"overallScore\":78,\"suggestion\":\"把第二段铺垫删掉\"}";
    private static final String VALID_FOLLOWUP_JSON =
            "{\"followups\":[\"ES 索引怎么设计的？\",\"为什么不用数据库分区？\",\"重做一次会怎么做？\"]}";

    /** ChatClient 同步调用链 stub：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    private StoryBankEntity story() {
        return StoryBankEntity.builder()
                .id(1L).userId("user-1").title("二手交易平台性能优化")
                .situation("校园平台没人用").task("负责搜索模块")
                .action("把 MySQL 模糊查询改成 ES").result("日活 30→200")
                .evidence("监控数据").targetTrack("Java 后端")
                .build();
    }

    @BeforeEach
    void setUp() {
        // 各用例自行 stub，避免 Mockito 严格模式报未使用 stub
    }

    // ---------- extract ----------

    @Test
    @DisplayName("extract：合法 JSON 原样返回")
    void extract_validJson() {
        stubChatClient(VALID_EXTRACT_JSON);

        String result = service.extract("user-1", "我做过二手交易平台……", "Java 后端");

        assertThat(result).contains("stories").contains("性能优化");
        verify(chatClient, times(1)).prompt();
    }

    @Test
    @DisplayName("extract：非法返回回退兜底结构（不抛异常、仍是合法 JSON）")
    void extract_unparsable_usesFallback() {
        stubChatClient("抱歉，无法完成。");

        String result = service.extract("user-1", "经历描述", null);

        assertThat(result).isEqualTo(StoryBankService.EXTRACT_FALLBACK_JSON);
        assertThat(com.example.interview.util.JsonRepairUtil.isValid(result)).isTrue();
    }

    // ---------- check ----------

    @Test
    @DisplayName("check：返回六项质检 JSON")
    void check_validJson() {
        stubChatClient(VALID_CHECK_JSON);

        String result = service.check(story(), "我们当时做了个平台，我负责搜索优化……");

        assertThat(result).contains("items").contains("overallScore").contains("suggestion");
    }

    @Test
    @DisplayName("check：模型返回空时抛业务异常（质检需要真实结果，不允许静默兜底误导用户）")
    void check_emptyResponse_throwsBusinessException() {
        stubChatClient("");

        assertThatThrownBy(() -> service.check(story(), "回答"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- followUp ----------

    @Test
    @DisplayName("followUp：返回追问链 JSON")
    void followUp_validJson() {
        stubChatClient(VALID_FOLLOWUP_JSON);

        String result = service.followUp(story(), "我把慢查询改成了 ES");

        assertThat(result).contains("followups").contains("ES");
    }

    // ---------- CRUD 与归属校验 ----------

    @Test
    @DisplayName("save：标题为空抛业务异常")
    void save_blankTitle_throws() {
        StoryBankEntity s = StoryBankEntity.builder().title(" ").build();

        assertThatThrownBy(() -> service.save("user-1", s))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("save：超过容量上限抛业务异常")
    void save_capacityLimit_throws() {
        when(repository.countByUserId("user-1")).thenReturn(50L);

        assertThatThrownBy(() -> service.save("user-1", story()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("save：强制重置 id/userId 后入库（防伪造归属）")
    void save_forcesOwnership() {
        StoryBankEntity s = story();
        s.setId(999L);
        s.setUserId("other-user");
        when(repository.countByUserId("user-1")).thenReturn(0L);
        when(repository.save(any(StoryBankEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StoryBankEntity saved = service.save("user-1", s);

        assertThat(saved.getId()).isNull(); // 入库前被重置，由数据库生成
        assertThat(saved.getUserId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("findOwned：非本人故事返回 null（防 IDOR）")
    void findOwned_otherUser_returnsNull() {
        when(repository.findByIdAndUserId(1L, "user-1")).thenReturn(Optional.empty());

        assertThat(service.findOwned("user-1", 1L)).isNull();
    }

    @Test
    @DisplayName("delete：非本人故事抛业务异常")
    void delete_otherUser_throws() {
        when(repository.findByIdAndUserId(1L, "user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("user-1", 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("update：仅覆盖传入字段，保留归属与质检快照")
    void update_partialPatch_keepsOwnership() {
        StoryBankEntity existing = story();
        existing.setCheckResult("{\"items\":[]}");
        when(repository.findByIdAndUserId(1L, "user-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(StoryBankEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        StoryBankEntity patch = new StoryBankEntity();
        patch.setTitle("新标题");
        patch.setAction("行动改写");

        StoryBankEntity updated = service.update("user-1", 1L, patch);

        assertThat(updated.getTitle()).isEqualTo("新标题");
        assertThat(updated.getAction()).isEqualTo("行动改写");
        assertThat(updated.getResult()).isEqualTo("日活 30→200");
        assertThat(updated.getCheckResult()).isEqualTo("{\"items\":[]}");
    }

    @Test
    @DisplayName("list：按用户查询倒序列表")
    void list_byUser() {
        when(repository.findByUserIdOrderByUpdatedAtDesc("user-1")).thenReturn(List.of(story()));

        List<StoryBankEntity> result = service.list("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).contains("性能优化");
    }
}

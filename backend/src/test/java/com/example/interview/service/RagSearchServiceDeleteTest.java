package com.example.interview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RagSearchService#deleteOwnedDocument(String, String)} 单元测试（P1-5）。
 *
 * <h2>为什么这个方法的测试权重高于普通 CRUD</h2>
 *
 * <p>删除是知识库唯一的破坏性操作，且向量库的 {@code docId} 是**全局 UUID**：
 * 只要拿到别人的 docId 就能删掉别人的知识点（典型 IDOR）。所以本测试的核心不是
 * 「能删掉」，而是三条**拒绝路径**：
 *
 * <ol>
 *   <li><b>不是本人的文档一律拒绝</b>（{@link NotOwned}）；</li>
 *   <li><b>校验不了就拒绝</b>（{@link OwnershipCheckFailure}）——
 *       查询异常时若「放行」，一次数据库抖动就会变成静默越权；</li>
 *   <li><b>非法入参在触库之前就拒绝</b>（{@link IllegalInput}）——
 *       非 UUID 形态不可能存在于库中，不该为它付出一次数据库往返。</li>
 * </ol>
 *
 * <p>测试用 {@code @ExtendWith(MockitoExtension.class)} 纯 Mockito，不启动 Spring 上下文：
 * 本方法全部依赖（{@code jdbcTemplate} / {@code vectorStore}）都能用 mock 精确驱动，
 * 启动容器只会把失败原因掩盖成「上下文装配失败」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSearchService.deleteOwnedDocument 归属校验与边界")
class RagSearchServiceDeleteTest {

    /** 归属查询语句（须与实现完全一致，任何改写都应让测试失败） */
    private static final String OWNERSHIP_SQL =
            "SELECT metadata->>'userId' FROM vector_store WHERE id = ?";

    /** 合法 UUID 形态的文档 id */
    private static final String DOC_ID = "3f2b8c1a-9d4e-4a71-b0c5-6e8f2d1a7c93";

    private static final String OWNER = "user-1";

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private RagSearchService service;

    /** 注入 pgvector 的 JdbcTemplate，使服务走「prod 归属校验」分支 */
    private JdbcTemplate injectJdbc() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        return jdbc;
    }

    // ───────────────────────────── 1. 非法入参 ─────────────────────────────

    @Nested
    @DisplayName("非法入参：不触库，直接拒绝")
    class IllegalInput {

        @Test
        @DisplayName("docId 为 null / 空白 → false，且不查询也不删除")
        void blankDocId_returnsFalseWithoutTouchingStorage() {
            JdbcTemplate jdbc = injectJdbc();

            assertThat(service.deleteOwnedDocument(null, OWNER)).isFalse();
            assertThat(service.deleteOwnedDocument("", OWNER)).isFalse();
            assertThat(service.deleteOwnedDocument("   ", OWNER)).isFalse();

            verifyNoInteractions(jdbc);
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("userId 为 null / 空白 → false，且不查询也不删除")
        void blankUserId_returnsFalseWithoutTouchingStorage() {
            JdbcTemplate jdbc = injectJdbc();

            assertThat(service.deleteOwnedDocument(DOC_ID, null)).isFalse();
            assertThat(service.deleteOwnedDocument(DOC_ID, "")).isFalse();
            assertThat(service.deleteOwnedDocument(DOC_ID, " \t ")).isFalse();

            verifyNoInteractions(jdbc);
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("docId 非 UUID 形态（\"abc\"）→ false，且不发起任何数据库查询")
        void nonUuidDocId_returnsFalseWithoutAnyDbQuery() {
            JdbcTemplate jdbc = injectJdbc();

            assertThat(service.deleteOwnedDocument("abc", OWNER)).isFalse();
            // 也覆盖「UUID 后面粘了东西」「截断的 UUID」这类看起来像但不是的情况
            assertThat(service.deleteOwnedDocument(DOC_ID + "-extra", OWNER)).isFalse();

            // 核心断言：形态非法即可判定不存在，不该为它付一次数据库往返
            verifyNoInteractions(jdbc);
            verifyNoInteractions(vectorStore);
        }
    }

    // ───────────────────────── 2. IDOR：非本人文档 ─────────────────────────

    @Nested
    @DisplayName("归属校验：非本人文档必须拒绝（IDOR 核心）")
    class NotOwned {

        @Test
        @DisplayName("文档存在但 owner != userId → false，且绝不调用删除")
        void otherUsersDocument_returnsFalseAndNeverDeletes() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenReturn("user-2");

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();

            // 只查不删：一旦这里被改成「查不到就删」，越权删除立刻发生
            verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("文档元数据 owner 为 null → false（无归属视为非本人）")
        void nullOwner_returnsFalse() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenReturn(null);

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();

            verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("系统预置共享知识（owner 为 \"shared\"）不可被普通用户删除")
        void sharedDocument_returnsFalse() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenReturn("shared");

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();

            verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        }
    }

    // ───────────────────── 3. 查不到 / 校验失败（拒绝而非放行） ─────────────────────

    @Nested
    @DisplayName("查不到与校验失败：一律拒绝，且不泄露存在性")
    class OwnershipCheckFailure {

        @Test
        @DisplayName("文档不存在（EmptyResultDataAccessException）→ false，且不调用删除")
        void missingDocument_returnsFalse() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();

            verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("归属查询抛异常（模拟 vector_store 表不存在）→ false，且不调用删除")
        void ownershipQueryFails_returnsFalse() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenThrow(new DataAccessResourceFailureException(
                            "relation \"vector_store\" does not exist"));

            // 「校验不了 ≠ 放行」：数据库抖动时宁可删不掉，也不能静默越权删除
            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();

            verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        }
    }

    // ───────────────────────── 4. 正常删除（两种存储介质） ─────────────────────────

    @Nested
    @DisplayName("归属校验通过 / 无校验介质时的正常删除")
    class SuccessfulDelete {

        @Test
        @DisplayName("owner == userId → 调用 vectorStore.delete 并返回 true")
        void ownerMatches_deletesAndReturnsTrue() {
            JdbcTemplate jdbc = injectJdbc();
            when(jdbc.queryForObject(OWNERSHIP_SQL, String.class, UUID.fromString(DOC_ID)))
                    .thenReturn(OWNER);

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isTrue();

            verify(vectorStore).delete(List.of(DOC_ID));
        }

        @Test
        @DisplayName("jdbcTemplate == null（local 文件型向量库）→ 不做归属校验，直接删除并返回 true")
        void nullJdbcTemplate_deletesDirectly() {
            ReflectionTestUtils.setField(service, "jdbcTemplate", null);

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isTrue();

            verify(vectorStore).delete(List.of(DOC_ID));
        }

        @Test
        @DisplayName("向量库删除抛异常 → false（不能向用户谎报删除成功）")
        void vectorDeleteFails_returnsFalse() {
            ReflectionTestUtils.setField(service, "jdbcTemplate", null);
            doThrow(new RuntimeException("vector store down")).when(vectorStore).delete(List.of(DOC_ID));

            assertThat(service.deleteOwnedDocument(DOC_ID, OWNER)).isFalse();
        }
    }
}

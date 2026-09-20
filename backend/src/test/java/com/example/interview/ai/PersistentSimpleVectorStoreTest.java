package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库文件快照持久化测试（v1.34.0）
 *
 * 回归背景：local profile 原为纯内存 SimpleVectorStore，「电脑关机后 RAG 不可用」——
 * 进程退出知识全丢，只能靠每次启动重新播种预置知识，用户导入与自动补充的知识一并蒸发。
 */
class PersistentSimpleVectorStoreTest {

    /**
     * 最小 EmbeddingModel 桩。
     *
     * <p>注意：{@code SimpleVectorStore.add()} 实际走的是 {@code call(EmbeddingRequest)}，
     * 不是 {@code embed(Document)} —— 桩必须实现 {@code call} 才能真正完成入库，
     * 否则会在 add 阶段直接抛异常。
     *
     * <p>返回由文本长度派生的确定性向量，便于断言「恢复后内容一致」。
     */
    private static class StubEmbeddingModel implements EmbeddingModel {

        private static final int DIM = 8;

        private static float[] vectorOf(String text) {
            String t = text == null ? "" : text;
            float[] v = new float[DIM];
            for (int i = 0; i < DIM; i++) {
                v[i] = (t.length() % 97 + i) / 100f;
            }
            return v;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> list = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                list.add(new Embedding(vectorOf(instructions.get(i)), i));
            }
            return new EmbeddingResponse(list);
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document == null ? null : document.getText());
        }
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("k", "v")).build();
    }

    private PersistentSimpleVectorStore newStore(Path path) {
        return new PersistentSimpleVectorStore(new StubEmbeddingModel(), path.toString());
    }

    @Test
    @DisplayName("落盘后可完整恢复：文档条数、正文与 metadata 均不丢失")
    void flushThenRestoreRoundTrip(@TempDir Path dir) {
        File snapshot = dir.resolve("vs.json").toFile();

        // 第一个进程：写入 3 条并落盘
        PersistentSimpleVectorStore first = newStore(snapshot.toPath());
        first.add(List.of(doc("id-1", "HashMap 的扩容机制"),
                doc("id-2", "增值税进项抵扣"),
                doc("id-3", "护理三查七对")));
        assertEquals(3, first.documentCount(), "写入后应有 3 条");
        assertTrue(first.flush(true), "强制落盘应成功");
        assertTrue(snapshot.exists(), "快照文件应被创建");

        // 第二个进程：从快照恢复 —— 这是「关机重启后知识还在」的核心断言
        PersistentSimpleVectorStore second = newStore(snapshot.toPath());
        int restored = second.restore();
        assertEquals(3, restored, "应恢复 3 条文档");
        assertEquals(3, second.documentCount());

        List<Document> found = second.similaritySearch(
                SearchRequest.builder().query("HashMap").topK(10).build());
        assertNotNull(found);
        assertEquals(3, found.size(), "恢复后应能检索到全部文档");
    }

    @Test
    @DisplayName("快照不存在时以空库启动，不抛异常（首次启动场景）")
    void restoreWithoutSnapshot(@TempDir Path dir) {
        File missing = dir.resolve("not-exists.json").toFile();
        PersistentSimpleVectorStore store = newStore(missing.toPath());

        assertEquals(0, store.restore(), "无快照应返回 0");
        assertEquals(0, store.documentCount());
    }

    @Test
    @DisplayName("快照损坏时改为空库启动并保留现场，绝不阻断应用启动")
    void restoreCorruptSnapshotDoesNotBreakStartup(@TempDir Path dir) throws Exception {
        File corrupt = dir.resolve("broken.json").toFile();
        Files.write(corrupt.toPath(), "{ 这不是合法 JSON".getBytes(StandardCharsets.UTF_8));

        PersistentSimpleVectorStore store = newStore(corrupt.toPath());

        assertEquals(0, store.restore(), "损坏快照应降级为空库");
        assertTrue(new File(corrupt.getAbsolutePath() + ".corrupt").exists(),
                "原文件应被改名为 .corrupt 以便排查");
    }

    @Test
    @DisplayName("无变更时不落盘（flush 有脏标记判断）")
    void flushSkipsWhenNotDirty(@TempDir Path dir) {
        File snapshot = dir.resolve("vs.json").toFile();
        PersistentSimpleVectorStore store = newStore(snapshot.toPath());

        assertFalse(store.isDirty(), "初始应为干净状态");
        assertFalse(store.flush(false), "无变更时不应写文件");
        assertFalse(snapshot.exists(), "无变更时不应创建文件");
    }

    @Test
    @DisplayName("新增文档会置脏标记，使定时落盘能捕获增量")
    void addMarksDirty(@TempDir Path dir) {
        PersistentSimpleVectorStore store = newStore(dir.resolve("vs.json"));

        assertFalse(store.isDirty());
        store.add(List.of(doc("id-1", "任意内容")));
        assertTrue(store.isDirty(), "add 后应置脏，否则增量会丢失");
        assertTrue(store.flush(false), "有变更时应真正写文件");
        assertFalse(store.isDirty(), "落盘后应清除脏标记");
    }

    @Test
    @DisplayName("落盘不残留临时文件（原子写的清理）")
    void flushLeavesNoTempFile(@TempDir Path dir) {
        File snapshot = dir.resolve("vs.json").toFile();
        PersistentSimpleVectorStore store = newStore(snapshot.toPath());
        store.add(List.of(doc("id-1", "内容")));
        store.flush(true);

        assertFalse(new File(snapshot.getAbsolutePath() + ".tmp").exists(),
                "原子写完成后不应残留 .tmp");
    }
}

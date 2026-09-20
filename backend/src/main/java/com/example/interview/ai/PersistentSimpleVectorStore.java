package com.example.interview.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带文件快照的 SimpleVectorStore（local profile 专用）
 *
 * ── 为什么需要它（2026-09-20）────────────────────────────────────────
 * local profile 原先用内存版 SimpleVectorStore，**进程一退知识全部蒸发**：
 * 每次重启都只能靠 {@code KnowledgeSeedInitializer} 重新播种那几十条预置知识，
 * 用户导入的知识、以及「自动补充知识」机制累积的条目统统丢失。
 * 这直接违背「电脑关机后 RAG 仍可正常使用」的诉求。
 *
 * ── 实现要点 ────────────────────────────────────────────────────────
 * 1. 复用 Spring AI 自带的 {@code save(File)} / {@code load(File)} 序列化能力，
 *    不自己实现向量序列化（避免格式漂移）。
 * 2. 覆写模板方法 {@code doAdd} / {@code doDelete} 打脏标记，
 *    由 {@link com.example.interview.config.VectorStorePersistenceManager}
 *    定时 + 关闭时落盘，**不在写入路径同步 IO**（避免拖慢 embedding 批量播种）。
 * 3. 落盘用「先写 .tmp 再原子 move」：进程中途被杀不会留下半截 JSON
 *    导致下次启动解析失败。
 * 4. 快照损坏时**不阻断启动**：改名保留现场后以空库继续，
 *    与项目「AI 相关故障不拖垮非 AI 功能」的一致性原则保持一致。
 *
 * ── 线程安全 ────────────────────────────────────────────────────────
 * {@code restore()} 与 {@code flush()} 加锁串行；{@code doAdd}/{@code doDelete}
 * 由 Spring AI 内部在调用线程执行，此处只切换一个 {@link AtomicBoolean}，无锁竞争。
 */
public class PersistentSimpleVectorStore extends SimpleVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentSimpleVectorStore.class);

    private final File snapshotFile;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /**
     * @param embeddingModel 向量化模型（本项目为本地 OpenAI 兼容服务）
     * @param snapshotPath   快照文件绝对路径，如 {@code D:/xm/data/vectorstore.json}
     */
    public PersistentSimpleVectorStore(EmbeddingModel embeddingModel, String snapshotPath) {
        super(SimpleVectorStore.builder(embeddingModel));
        this.snapshotFile = new File(snapshotPath);
    }

    @Override
    public void doAdd(List<Document> documents) {
        super.doAdd(documents);
        dirty.set(true);
    }

    @Override
    public void doDelete(List<String> ids) {
        super.doDelete(ids);
        dirty.set(true);
    }

    /** 当前文档数。用于恢复 {@code RagSearchService} 的容量计数，避免重启后计数归零导致超限。 */
    public int documentCount() {
        return store == null ? 0 : store.size();
    }

    public File snapshotFile() {
        return snapshotFile;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * 从磁盘快照恢复向量库。
     *
     * @return 恢复的文档条数；无快照或快照损坏时返回 0
     */
    public synchronized int restore() {
        if (!snapshotFile.exists() || snapshotFile.length() == 0) {
            log.info("向量库快照不存在，以空库启动（首次启动属正常）：{}", snapshotFile.getAbsolutePath());
            return 0;
        }
        try {
            load(snapshotFile);
            int restored = documentCount();
            log.info("向量库已从快照恢复：{} 条文档（{}）", restored, snapshotFile.getAbsolutePath());
            return restored;
        } catch (Exception e) {
            // 快照损坏不能阻断启动：改名保留现场，以空库继续（后续会重新播种）
            log.warn("向量库快照加载失败，改以空库启动（原文件改名为 .corrupt 保留现场）：{}", e.toString());
            try {
                Files.move(snapshotFile.toPath(),
                        new File(snapshotFile.getAbsolutePath() + ".corrupt").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignore) {
                // 改名失败无妨，下次启动仍会尝试加载并再次告警
            }
            return 0;
        }
    }

    /**
     * 落盘。
     *
     * @param force true=无条件写入（关闭/播种后等关键时点）；false=仅在有变更时写入
     * @return 是否真正写入了文件
     */
    public synchronized boolean flush(boolean force) {
        if (!force && !dirty.get()) {
            return false;
        }
        File tmp = new File(snapshotFile.getAbsolutePath() + ".tmp");
        try {
            Path target = snapshotFile.toPath();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            save(tmp);
            Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            dirty.set(false);
            return true;
        } catch (Exception e) {
            log.warn("向量库落盘失败（内存检索不受影响，下次变更会重试）：{}", e.toString());
            // 清理可能残留的半截临时文件
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignore) {
                // 忽略
            }
            return false;
        }
    }
}

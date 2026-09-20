package com.example.interview.config;

import com.example.interview.ai.PersistentSimpleVectorStore;
import com.example.interview.service.RagSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 向量库持久化管理器（local profile）
 *
 * 解决「电脑关机后 RAG 不可用」：本地向量库默认是纯内存实现，进程退出即清空。
 * 本类负责在三个时点做快照的读/写：
 *
 * <pre>
 *   启动   @PostConstruct  → restore()，并同步 RagSearchService 的容量计数
 *   运行中 @Scheduled 3s    → 有变更才落盘（写入路径不做同步 IO）
 *   播种后 @Order(110)     → 立刻落盘，避免刚播完种就退出导致重复播种
 *   关闭   @PreDestroy     → 无条件落盘
 * </pre>
 *
 * 全部动作受 {@code app.rag.persist-enabled} 开关控制；关闭后行为与改造前一致
 * （纯内存，重启即空），便于对比排查与单元测试。
 */
@Component
@Profile("local")
@Order(110)
public class VectorStorePersistenceManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStorePersistenceManager.class);

    private final PersistentSimpleVectorStore vectorStore;
    private final RagSearchService ragSearchService;

    @Value("${app.rag.persist-enabled:true}")
    private boolean persistEnabled;

    @Autowired
    public VectorStorePersistenceManager(PersistentSimpleVectorStore vectorStore,
                                         RagSearchService ragSearchService) {
        this.vectorStore = vectorStore;
        this.ragSearchService = ragSearchService;
    }

    /**
     * 启动即恢复：必须早于 {@link KnowledgeSeedInitializer}（后者以 ApplicationRunner
     * 形式在全部单例 Bean 初始化完成后才执行），因此用 {@code @PostConstruct} 保证时序。
     */
    @PostConstruct
    public void restoreOnStartup() {
        if (!persistEnabled) {
            log.info("向量库持久化已禁用（app.rag.persist-enabled=false），本次运行为纯内存模式");
            return;
        }
        int restored = vectorStore.restore();
        // 关键：计数必须与恢复后的库内实际条数对齐。否则重启后计数归零，
        // 容量上限（app.rag.max-documents）形同虚设，知识库会被无限追加直至 OOM。
        ragSearchService.syncStoredCount(restored);
    }

    /**
     * 播种完成后立即落盘。
     *
     * <p>{@code @Order(110)} 排在 {@link KnowledgeSeedInitializer}（{@code @Order(100)}）之后，
     * 确保首次启动播下的种子当场持久化 —— 否则若进程在定时落盘前退出，
     * 下次启动会因库为空而重复播种（虽有确定性 ID 兜底不产生重复条目，仍会白跑一轮 embedding）。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!persistEnabled) {
            return;
        }
        if (vectorStore.flush(true)) {
            log.info("向量库快照已写入：{} 条文档 → {}", vectorStore.documentCount(),
                    vectorStore.snapshotFile().getAbsolutePath());
        }
    }

    /** 周期性落盘：仅在 doAdd/doDelete 发生过变更时真正写文件 */
    @Scheduled(fixedDelayString = "${app.rag.snapshot-flush-interval-ms:3000}",
            initialDelayString = "${app.rag.snapshot-flush-interval-ms:3000}")
    public void flushIfDirty() {
        if (!persistEnabled || !vectorStore.isDirty()) {
            return;
        }
        if (vectorStore.flush(false)) {
            log.debug("向量库快照已更新：{} 条文档", vectorStore.documentCount());
        }
    }

    /** 优雅关闭时兜底落盘（SIGKILL 不触发，此时最多丢失一个刷新周期的增量） */
    @PreDestroy
    public void flushOnShutdown() {
        if (!persistEnabled) {
            return;
        }
        if (vectorStore.flush(true)) {
            log.info("关闭前向量库快照已保存：{} 条文档", vectorStore.documentCount());
        }
    }
}

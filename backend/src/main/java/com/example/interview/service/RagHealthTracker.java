package com.example.interview.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 子系统健康跟踪（v1.34.1）
 *
 * <p><b>为什么需要它</b>：知识库（内置题库 + 向量检索 + RAG 问答）是一条**端到端依赖外部
 * Embedding 服务**的功能链。当 Embedding 的 Key 未配置/失效/欠费时，实际后果是：
 * <ul>
 *   <li>启动播种失败 —— 但只打了一条 WARN，应用照常启动；</li>
 *   <li>知识导入、RAG 增强问答、自动补充全部失败；</li>
 *   <li>而 {@code /api/health} 与 {@code /api/health/detail} 仍报告 {@code status: UP}。</li>
 * </ul>
 * 于是出现「监控全绿、功能全废」的**静默降级**——2026-09-21 生产实测即为此状态
 * （{@code POST /api/knowledge/import/batch} 返回 503，向量库条数为 0，但体检报告 UP），
 * 定位只能靠翻平台日志，成本极高。
 *
 * <p>本类把「播种结果」与「最近一次向量化失败原因」记录下来，由
 * {@code GET /api/health/detail} 暴露，使该故障**无需登录平台即可自诊断**。
 *
 * <p>线程安全：字段均为 volatile / AtomicReference，读写无锁。
 */
@Component
public class RagHealthTracker {

    /** 播种状态 */
    public enum SeedStatus {
        /** 应用已启动但尚未执行（正常只在启动瞬间出现） */
        NOT_RUN,
        /** 播种成功 */
        SUCCESS,
        /** 播种失败（通常是 Embedding 不可用） */
        FAILED,
        /** 通过 app.rag.seed-enabled=false 显式关闭 */
        DISABLED
    }

    private volatile SeedStatus seedStatus = SeedStatus.NOT_RUN;

    /** 播种结果说明（成功为条数，失败为原因） */
    private volatile String seedDetail = "";

    /** 播种完成时间（epoch 秒） */
    private volatile long seedAtEpochSec = 0L;

    /** 最近一次向量化/入库失败（写入向量库时抛出） */
    private final AtomicReference<Failure> lastFailure = new AtomicReference<>();

    /** 最近一次向量化失败：原因 + 时间 */
    public record Failure(String reason, long atEpochSec) {
    }

    /** 记录播种结果 */
    public void markSeed(SeedStatus status, String detail) {
        this.seedStatus = status;
        this.seedDetail = detail == null ? "" : detail;
        this.seedAtEpochSec = Instant.now().getEpochSecond();
    }

    /** 记录一次向量化/入库失败（不吞异常，仅旁路记录） */
    public void markFailure(String reason) {
        String safe = reason == null ? "unknown" : (reason.length() > 300 ? reason.substring(0, 300) : reason);
        lastFailure.set(new Failure(safe, Instant.now().getEpochSecond()));
    }

    /** 清除失败记录（一次成功入库后调用，避免旧故障长期驻留误导运维） */
    public void clearFailure() {
        lastFailure.set(null);
    }

    public SeedStatus seedStatus() {
        return seedStatus;
    }

    public String seedDetail() {
        return seedDetail;
    }

    public long seedAtEpochSec() {
        return seedAtEpochSec;
    }

    public Failure lastFailure() {
        return lastFailure.get();
    }

    /**
     * 构造健康报告片段。
     *
     * @param storedCount  向量库当前条数
     * @param maxDocuments 容量上限
     * @return 含 status / seed / counts / lastFailure 的只读视图
     */
    public Map<String, Object> snapshot(int storedCount, int maxDocuments) {
        Map<String, Object> m = new LinkedHashMap<>();
        SeedStatus st = seedStatus;
        Failure f = lastFailure.get();

        // 状态判定：播种成功且无残留失败 → UP；播种失败或最近失败存在 → DEGRADED；显式关闭 → DISABLED
        String status;
        if (st == SeedStatus.DISABLED) {
            status = "DISABLED";
        } else if (st == SeedStatus.FAILED || f != null) {
            status = "DEGRADED";
        } else if (st == SeedStatus.SUCCESS) {
            status = "UP";
        } else {
            status = "UNKNOWN";
        }
        m.put("status", status);
        m.put("seedStatus", st.name());
        m.put("seedDetail", seedDetail);
        if (seedAtEpochSec > 0) {
            m.put("seedAt", seedAtEpochSec);
        }
        m.put("documents", storedCount);
        m.put("maxDocuments", maxDocuments);
        if (f != null) {
            m.put("lastFailure", f.reason());
            m.put("lastFailureAt", f.atEpochSec());
        }
        // 提示语直接给出可执行的排查方向，避免运维再去翻代码
        if ("DEGRADED".equals(status)) {
            m.put("hint", "知识库不可用：请检查 Embedding 配置"
                    + "（AI_EMBEDDING_BASE_URL / AI_EMBEDDING_API_KEY / AI_EMBEDDING_MODEL 及额度）");
        }
        return m;
    }
}

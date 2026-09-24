package com.example.interview.config;

import com.example.interview.service.RagSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 向量库容量计数启动校准（P2-C）。
 *
 * <p><b>背景</b>：{@code RagSearchService.storedDocs} 是进程内计数，生产（pgvector）
 * 重启后归零。主修复在 {@code KnowledgeSeedInitializer}（播种成功后回读真实行数），
 * 但还有两条播种未覆盖的路径：
 * <ul>
 *   <li>{@code app.rag.seed-enabled=false}（本地联调/测试）—— 生产虽不常见，但一旦配置，
 *       计数将永远是 0；</li>
 *   <li>播种首试失败且退避重试尚未成功 —— 此时计数为 0，而库里已有历史数据；
 *       若 embedding 恢复后用户开始导入，容量检查会基于虚低的计数放行，
 *       造成无界增长风险（220MB 堆的内存向量库扛不住）。</li>
 * </ul>
 *
 * <p>本 Runner 在播种器（@Order(100)）之后执行，无条件做一次「以库内真实行数覆盖计数」；
 * pgvector 表不存在（本地文件型向量库）时由被调方静默跳过，不影响 local profile
 * 既有的文件快照恢复计数路径。播种退避重试成功后 {@code doSeed()} 内部还会再校准一次，
 * 因此此处与重试路径不产生竞态危害。
 */
@Component
@Order(101)
public class VectorStoreCountSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreCountSyncRunner.class);

    private final RagSearchService ragSearchService;

    @Autowired
    public VectorStoreCountSyncRunner(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ragSearchService.syncStoredCountFromVectorStore();
        } catch (Exception e) {
            // 校准失败不阻断启动：计数仍有播种路径兜底，这里只是多一道保险
            log.warn("向量库容量计数启动校准失败（忽略）：{}", e.getMessage());
        }
    }
}

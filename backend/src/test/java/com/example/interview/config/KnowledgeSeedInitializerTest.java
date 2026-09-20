package com.example.interview.config;

import com.example.interview.service.RagSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享知识库播种器测试
 *
 * 回归背景（2026-09-19 真机验证）：RAG 问答对全部用户恒返回「参考资料中没有相关内容」。
 * 根因是向量库中从不存在任何 `shared=true` 文档 —— schema.sql 里的 5 条预置知识
 * 从未被写入向量库，而检索过滤条件要求 `userId=当前用户 OR shared=true`，
 * 新用户两条都不满足，检索必然为空。
 */
class KnowledgeSeedInitializerTest {

    /** 记录入库文档的假服务 */
    private static class RecordingRagService extends RagSearchService {
        final List<Document> captured = new ArrayList<>();
        boolean shouldThrow = false;

        @Override
        public int addToVectorStore(List<Document> docs) {
            if (shouldThrow) {
                throw new RuntimeException("embedding 不可用");
            }
            captured.addAll(docs);
            return docs.size();
        }
    }

    private KnowledgeSeedInitializer newInitializer(RecordingRagService svc, boolean enabled) {
        KnowledgeSeedInitializer init = new KnowledgeSeedInitializer(svc);
        ReflectionTestUtils.setField(init, "seedEnabled", enabled);
        return init;
    }

    @Test
    @DisplayName("播种：预置知识写入向量库，且全部标记 shared=true 供所有用户检索")
    void seedsSharedKnowledge() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, true).run(null);

        assertFalse(svc.captured.isEmpty(), "应写入预置知识");

        for (Document d : svc.captured) {
            // 关键断言：没有 shared=true 标记，检索过滤条件就命不中，RAG 又会空转
            assertEquals("true", String.valueOf(d.getMetadata().get("shared")),
                    "每条预置知识都必须标记 shared=true：" + d.getText());
            assertEquals("system-seed", String.valueOf(d.getMetadata().get("source")));
            assertTrue(d.getMetadata().containsKey("category"), "应带分类便于展示/过滤");
            assertTrue(d.getMetadata().containsKey("title"), "应带标题");
        }
    }

    @Test
    @DisplayName("播种：正文包含关键词术语（保证向量可被检索命中）")
    void seedContentCarriesKeywords() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, true).run(null);

        String all = svc.captured.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
        // 依据 content_policy 的常规技术面试主题，抽查若干高频术语是否入正文
        for (String kw : new String[]{"HashMap", "Spring", "B+ 树", "Redis", "TCP", "三次握手"}) {
            assertTrue(all.contains(kw), "预置知识应覆盖术语：" + kw);
        }
    }

    @Test
    @DisplayName("播种：重复执行只入库一次（进程内幂等）")
    void seedsOnlyOnce() {
        RecordingRagService svc = new RecordingRagService();
        KnowledgeSeedInitializer init = newInitializer(svc, true);

        init.run(null);
        int afterFirst = svc.captured.size();
        init.run(null);
        init.run(null);

        assertEquals(afterFirst, svc.captured.size(), "重复 run 不应重复入库");
    }

    @Test
    @DisplayName("播种：开关关闭时不调用向量库")
    void skipsWhenDisabled() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, false).run(null);
        assertTrue(svc.captured.isEmpty(), "seed-enabled=false 时不应入库");
    }

    @Test
    @DisplayName("播种：Embedding 不可用时不抛异常（不阻断应用启动）")
    void doesNotBreakStartupOnFailure() {
        RecordingRagService svc = new RecordingRagService();
        svc.shouldThrow = true;
        KnowledgeSeedInitializer init = newInitializer(svc, true);

        // 播种失败必须被吞掉——否则知识库配置问题会拖垮登录等无关功能
        init.run(null);
        assertTrue(svc.captured.isEmpty());
    }

    @Test
    @DisplayName("播种：失败后可重试（未标记为已播种）")
    void allowsRetryAfterFailure() {
        RecordingRagService svc = new RecordingRagService();
        svc.shouldThrow = true;
        KnowledgeSeedInitializer init = newInitializer(svc, true);
        init.run(null);

        svc.shouldThrow = false;
        init.run(null);

        assertFalse(svc.captured.isEmpty(), "首次失败后再次 run 应能成功播种");
    }

    // ── v1.34.0：全行业覆盖 ──────────────────────────────────────────

    @Test
    @DisplayName("全行业：预置知识必须覆盖非 IT 行业（平台面向全行业，不只是计算机）")
    void seedsCoverNonItIndustries() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, true).run(null);

        // 按 category 归集，确认存在大量非 IT 分类
        List<String> categories = svc.captured.stream()
                .map(d -> String.valueOf(d.getMetadata().get("category")))
                .distinct()
                .toList();

        String[] mustHave = {"财会税务", "人力资源", "销售", "市场营销", "医疗临床",
                "护理", "教育", "法律", "公务员事业单位", "制造业", "供应链物流"};
        for (String c : mustHave) {
            assertTrue(categories.contains(c),
                    "预置知识应覆盖行业分类：" + c + "，实际分类=" + categories);
        }
        assertTrue(categories.size() >= 30,
                "行业覆盖过窄，仅 " + categories.size() + " 个分类；平台需服务全行业");
    }

    @Test
    @DisplayName("全行业：非技术术语也能被检索命中（财会/医疗/建筑等关键词入正文）")
    void industryKeywordsPresent() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, true).run(null);
        String all = svc.captured.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);

        for (String kw : new String[]{"资产负债", "增值税", "STAR", "净息差", "三查七对",
                "三电", "HAZOP", "8D", "MECE", "RevPAR"}) {
            assertTrue(all.contains(kw), "全行业预置知识应覆盖术语：" + kw);
        }
    }

    @Test
    @DisplayName("幂等：同一知识点派生的 ID 稳定且互不相同")
    void deterministicIdIsStableAndUnique() {
        String a1 = KnowledgeSeedInitializer.deterministicId("财会税务", "财务报表三大报表及其勾稽关系");
        String a2 = KnowledgeSeedInitializer.deterministicId("财会税务", "财务报表三大报表及其勾稽关系");
        String b = KnowledgeSeedInitializer.deterministicId("财会税务", "增值税进销项抵扣与税负管理");
        String c = KnowledgeSeedInitializer.deterministicId("医疗临床", "财务报表三大报表及其勾稽关系");

        // 稳定 ID 是「扩充预置知识后重启不产生重复条目」的前提
        assertEquals(a1, a2, "同一分类+标题必须派生出相同 ID");
        assertFalse(a1.equals(b), "不同标题应派生不同 ID");
        assertFalse(a1.equals(c), "不同分类应派生不同 ID");
    }

    @Test
    @DisplayName("幂等：播种出的文档 ID 均为确定性 ID（非随机 UUID）")
    void seedsCarryDeterministicIds() {
        RecordingRagService svc = new RecordingRagService();
        newInitializer(svc, true).run(null);

        long distinctIds = svc.captured.stream().map(Document::getId).distinct().count();
        assertEquals(svc.captured.size(), distinctIds, "不应存在重复 ID");

        // 再播一次（新建实例绕过进程内标记），ID 集合必须完全一致
        RecordingRagService svc2 = new RecordingRagService();
        newInitializer(svc2, true).run(null);
        assertEquals(svc.captured.stream().map(Document::getId).sorted().toList(),
                svc2.captured.stream().map(Document::getId).sorted().toList(),
                "两次播种的 ID 集合应完全一致，重复播种才会覆盖而非新增");
    }
}

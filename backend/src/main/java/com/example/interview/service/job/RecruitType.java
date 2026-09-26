package com.example.interview.service.job;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 招聘类型的**规范取值**（后端单一来源）。
 *
 * <h2>为什么要有这个枚举（2026-09-26 第三轮 P3-03）</h2>
 *
 * <p>实测 {@code GET /api/jobs?recruitType=ZZZ} 返回 {@code code=200} 且 {@code total=0}
 * —— 非法取值被当成「没有匹配」，用户会以为「真的没有岗位」。
 *
 * <p>而排查时发现这套取值此前**散落在至少 6 处**：实体注释、{@code AdminService} 的 javadoc、
 * {@code AgentTools} 的中文别名 switch 与提示词、{@code HttpJobPlatformAdapter} 的入库映射、
 * 各种子源、以及前端 {@code JobsView.vue} 的 {@code recruitTabs}。没有任何一处是权威的，
 * 于是「校验用哪份清单」本身就成了问题——这正是本类要解决的。
 *
 * <h2>本类的职责边界</h2>
 *
 * <p><b>只负责「HTTP 入口的取值校验」</b>。以下两处**刻意不改**，因为它们各有正当理由：
 *
 * <ul>
 *   <li>{@code AgentTools}：把用户口语（「社招」「社会招聘」）归一成 code，并把**无法识别
 *       的取值当作「不限」**——这是对话场景的正确行为（宁可放宽也不该报错），
 *       已有 {@code AgentToolsTest} 锁定「空/null/非法值→不限」；</li>
 *   <li>{@code HttpJobPlatformAdapter}：把外部平台返回的中文招聘类型映射成 code，
 *       同样需要宽容（遇到没见过的类型不能整批丢弃）。</li>
 * </ul>
 *
 * <p>也就是说：**入库与对话路径要宽容，HTTP 查询入口要严格**。前者面对的是不可控的外部输入，
 * 后者面对的是我们自己的前端——它只会发这 6 个值（外加空串表示「全部国内」），
 * 发别的值一定是 bug，静默返回空结果会让 bug 长期藏住。
 *
 * <p>⚠️ 新增招聘类型时，本枚举与前端 {@code JobsView.vue} 的 {@code recruitTabs}
 * 必须同步（前者是校验依据，后者是展示依据）。
 */
public enum RecruitType {

    AUTUMN("秋招"),
    SPRING("春招"),
    SOCIAL("社招"),
    INTERN("实习"),
    PART_TIME("兼职"),
    TARGETED("定向专项");

    private final String label;

    RecruitType(String label) {
        this.label = label;
    }

    /** 面向用户的中文标签（与前端 {@code recruitTabs} 的口径一致） */
    public String label() {
        return label;
    }

    /**
     * 按 code 解析（大小写不敏感）。
     *
     * @return 无法识别时返回 {@code null}（调用方自行决定是「宽容放过」还是「严格拒绝」）
     */
    public static RecruitType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (RecruitType t : values()) {
            if (t.name().equals(normalized)) {
                return t;
            }
        }
        return null;
    }

    /**
     * HTTP 入口的取值校验。
     *
     * <p>空值/空白视为「不限」，放行 —— 前端「全部国内」分栏就是发空串。
     *
     * @return 非法时返回面向用户的中文提示；合法返回 {@code null}
     */
    public static String validationError(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (fromCode(code) != null) {
            return null;
        }
        return "招聘类型取值无效，可选：" + allowedDescription();
    }

    /** 形如「秋招(AUTUMN)、春招(SPRING)、…」，用于错误提示与文档 */
    public static String allowedDescription() {
        return Arrays.stream(values())
                .map(t -> t.label + "(" + t.name() + ")")
                .collect(Collectors.joining("、"));
    }
}

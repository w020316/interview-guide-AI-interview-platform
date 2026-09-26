package com.example.interview.util;

/**
 * 分页参数契约。
 *
 * <h2>为什么需要（2026-09-26 第三轮 UX 测试 P3-04）</h2>
 *
 * <p>实测 {@code GET /api/jobs?size=-5} 返回 {@code code=200} 且 {@code size=1}
 * —— 服务层的钳制是 {@code Math.max(1, size)}，于是「要 5 条」和「要 -5 条」
 * 得到的是同一个「1 条」的意外结果。同时 {@code size=99999} 会被钳到 50（这是合理的，
 * 「给我尽量多」有明确意图），{@code page=-1} 归一到 0 也合理。
 *
 * <p>本类只负责**把「无意义的负数」显式拒绝**，不改变「超出上限就钳制」的既有行为：
 * 前者是调用方的 bug，静默返回一个奇怪的条数会让它长期不被发现；
 * 后者是「要得太多」，钳到上限是业界通行做法。
 */
public final class PaginationSupport {

    /** 与各接口声明的默认值保持一致 */
    public static final int DEFAULT_SIZE = 10;

    /** 单页上限，与 Service 层钳制保持一致 */
    public static final int MAX_SIZE = 50;

    private PaginationSupport() {
    }

    /**
     * 校验 size。
     *
     * @return 非法时返回面向用户的中文提示；合法返回 {@code null}
     */
    public static String validateSize(int size) {
        if (size < 1) {
            return "分页大小需为不小于 1 的整数（size ≥ 1）";
        }
        return null;
    }
}

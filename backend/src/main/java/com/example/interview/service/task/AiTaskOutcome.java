package com.example.interview.service.task;

/**
 * 执行器返回的结果（阶段六 M3）。
 *
 * <p>为什么需要两种「结果」：会话/简历类任务的产物写在业务表，任务只需记住 ID
 * （{@link #resultRef()}）；而出题/评分的结果原本只回给前端、没有业务表可挂，
 * 异步化后必须能取回，只能把正文随任务保存（{@link #resultText()}，有硬上限）。
 *
 * <p>两者都可空：执行器确实没有产物时返回 {@link #EMPTY}。
 */
public record AiTaskOutcome(String resultRef, String resultText) {

    /** 无产物（如后台补充类任务） */
    public static final AiTaskOutcome EMPTY = new AiTaskOutcome(null, null);

    /** 仅正文（出题 / 评分） */
    public static AiTaskOutcome ofText(String text) {
        return new AiTaskOutcome(null, text);
    }

    /** 仅引用（会话 / 简历） */
    public static AiTaskOutcome ofRef(String ref) {
        return new AiTaskOutcome(ref, null);
    }
}

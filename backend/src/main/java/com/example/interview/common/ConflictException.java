package com.example.interview.common;

/**
 * 业务冲突异常（第三批 C · 数据导入冲突保护）。
 *
 * <h2>为什么需要单独一个类型</h2>
 *
 * <p>个人数据导入的「覆盖（replace）」模式要求：当导入文件记录的数据指纹（{@code expectedFingerprint}）
 * 与当前服务端数据的指纹不一致时，**必须拒绝覆盖**，除非调用方显式传 {@code force:true}。
 * 该语义对应 HTTP <b>409 Conflict</b>（见 {@code docs/api-error-contract.md} §3 的
 * {@code OptimisticLockingFailureException → 409「内容已被更新，请刷新后重试」} 同族）。
 *
 * <p>若沿用 {@link IllegalArgumentException}（映射 400）会表达成「参数错误」——但参数本身完全合法，
 * 冲突点在「数据在你导出之后又变了」，客户端应提示用户确认后再重试，而不是让用户去改参数。
 *
 * <h2>为什么 extends RuntimeException</h2>
 *
 * <p>{@code @ExceptionHandler} 按**最具体**类型匹配。继承 {@code RuntimeException} 后，
 * {@link GlobalExceptionHandler} 可单独把本类型映射为 {@code 409}，不会污染其它分支语义。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

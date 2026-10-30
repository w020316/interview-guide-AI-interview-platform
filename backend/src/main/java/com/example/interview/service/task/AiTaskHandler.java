package com.example.interview.service.task;

import com.example.interview.entity.AiTaskEntity;

/**
 * AI 任务执行器契约（阶段六 M1 骨架，M3 接入出题/评分两个实现）。
 *
 * <p>为什么要先定接口：出题、评分、简历分析三条链路的耗时与重试语义不同
 * （出题 77s / 评分 11–29s / 简历 21s），但「领取—执行—回报」的骨架一致。
 * 先固定契约，实现只需按 {@link #type()} 注册，调度器无需感知具体业务。
 *
 * <p>实现要求：
 * <ul>
 *   <li>必须幂等：同一任务被重复执行时不得产生重复业务数据（如重复创建会话）；</li>
 *   <li>不得吞异常：失败必须抛出，由调度器统一判定可重试性并回写状态；</li>
 *   <li>不得长时间阻塞调度线程：M3 起调度器只负责领取，执行放到独立线程。</li>
 * </ul>
 */
public interface AiTaskHandler {

    /** 该执行器负责的任务类型，与 {@code ai_task.type} 取值一致 */
    String type();

    /**
     * 执行任务。
     *
     * @param task 已领取（RUNNING）的任务，含 payload 快照与归属用户
     * @return 结果（引用或正文）；无产物返回 {@link AiTaskOutcome#EMPTY}，不得返回 null
     */
    AiTaskOutcome execute(AiTaskEntity task);
}

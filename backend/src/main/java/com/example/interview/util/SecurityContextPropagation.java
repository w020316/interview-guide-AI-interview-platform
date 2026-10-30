package com.example.interview.util;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把 {@code SecurityContext} 搬运到异步线程（P1-05，2026-10-30 质量循环）。
 *
 * <p><b>为什么需要它</b>：{@code SecurityContextHolder} 默认是 {@code MODE_THREADLOCAL}，
 * 虚拟线程池、Reactor 的 {@code boundedElastic}、裸 {@code new Thread} 都<b>不会</b>继承
 * 调用线程的上下文。于是异步任务里 {@code getAuthentication()} 是 null 或匿名，
 * 依赖当前用户的逻辑（自持 AI Key 路由、按用户限流、按用户数据隔离）全部静默失效。
 *
 * <p>Spring Security 提供了 {@code DelegatingSecurityContextExecutorService}，
 * 但 Reactor 的调度器包装不到（{@code subscribeOn} 会再次换线程），
 * 因此这里补一个**在任务体内**设置/还原的显式入口。
 *
 * <p><b>为什么用 try/finally 还原原值而不是 clear</strong>：线程会被复用，
 * 若直接 clear 会破坏外层（例如同一线程上嵌套调用的其它上下文）；还原「执行前的快照」
 * 语义最稳，且空上下文同样是一个合法快照。
 */
public final class SecurityContextPropagation {

    private SecurityContextPropagation() {
    }

    /** 在给定上下文中执行任务，执行结束后还原为进入前的上下文 */
    public static void runWith(SecurityContext context, Runnable task) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(context);
            task.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}

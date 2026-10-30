package com.example.interview.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文搬运测试（P1-05）。
 *
 * <p>复现原缺陷：裸虚拟线程看不到提交线程的认证信息；用本工具后能看到，
 * 且执行完会把线程还原成原来的（空）上下文，不污染线程池里的后续任务。
 */
@DisplayName("SecurityContext 跨线程搬运")
class SecurityContextPropagationTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("裸线程池丢失上下文（复现 P1-05 的根因）")
    void rawVirtualThread_losesContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u1", null, List.of()));
        ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<String> seen = new AtomicReference<>("unset");
        try {
            raw.submit(() -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                seen.set(auth == null ? "null" : String.valueOf(auth.getPrincipal()));
            }).get(5, TimeUnit.SECONDS);
        } finally {
            raw.shutdownNow();
        }
        assertThat(seen.get()).isEqualTo("null");
    }

    @Test
    @DisplayName("runWith 在异线程内恢复上下文，执行后还原（不污染线程）")
    void runWith_restoresContext_thenClears() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u1", null, List.of()));
        SecurityContext captured = SecurityContextHolder.getContext();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> inside = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        try {
            pool.submit(() -> SecurityContextPropagation.runWith(captured, () -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                inside.set(auth == null ? "null" : String.valueOf(auth.getPrincipal()));
            }));
            // 同一个线程再跑一次空任务，验证还原（而不是把 u1 留在池线程里）
            pool.submit(() -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                after.set(auth == null ? "null" : String.valueOf(auth.getPrincipal()));
            }).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(inside.get()).isEqualTo("u1");
        assertThat(after.get()).isEqualTo("null");
    }
}

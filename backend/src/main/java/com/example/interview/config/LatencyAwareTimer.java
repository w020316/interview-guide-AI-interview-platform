package com.example.interview.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 既喂 Micrometer Timer、又喂 {@link AiLatencyWindow} 的耗时计时器。
 *
 * <h2>为什么做成装饰器</h2>
 *
 * <p>项目里有 **8 处** AI 调用埋点（{@code InterviewService} 4 处、{@code JobAnalysisService}、
 * {@code RagSearchService}、{@code ResumeAnalysisService} 2 处），全部写成
 * {@code aiCallTimer.record(elapsed, NANOSECONDS)}。
 *
 * <p>若在每处再加一行写窗口，就会产生 8 个可能被后人漏掉的位置；而做成装饰器后，
 * **任何人注入 {@code Timer} 并 record，都会自动进入耗时窗口**，包括将来新增的调用点。
 *
 * <p>Micrometer 的 {@code count()/mean()} 继续由被装饰的 Timer 提供（线上实测正常），
 * 只有百分位改由窗口计算（线上实测恒为 0，根因见 {@link AiLatencyWindow} 的类注释）。
 */
final class LatencyAwareTimer implements Timer {

    private final Timer delegate;
    private final AiLatencyWindow window;

    LatencyAwareTimer(Timer delegate, AiLatencyWindow window) {
        this.delegate = delegate;
        this.window = window;
    }

    @Override
    public void record(long amount, TimeUnit unit) {
        delegate.record(amount, unit);
        window.recordNanos(unit.toNanos(amount));
    }

    @Override
    public void record(Duration duration) {
        delegate.record(duration);
        window.recordNanos(duration.toNanos());
    }

    @Override
    public <T> T record(Supplier<T> supplier) {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void record(Runnable runnable) {
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public <T> T recordCallable(Callable<T> callable) throws Exception {
        long start = System.nanoTime();
        try {
            return callable.call();
        } finally {
            record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public double totalTime(TimeUnit unit) {
        return delegate.totalTime(unit);
    }

    @Override
    public double max(TimeUnit unit) {
        return delegate.max(unit);
    }

    @Override
    public HistogramSnapshot takeSnapshot() {
        return delegate.takeSnapshot();
    }

    @Override
    public TimeUnit baseTimeUnit() {
        return delegate.baseTimeUnit();
    }

    @Override
    public Meter.Id getId() {
        return delegate.getId();
    }

    /** 供 AdminService 读取自算的 P95（毫秒，无样本为 null） */
    AiLatencyWindow window() {
        return window;
    }
}

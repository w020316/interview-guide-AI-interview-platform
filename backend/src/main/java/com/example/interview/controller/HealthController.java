package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.RagHealthTracker;
import com.example.interview.service.RagSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查与系统信息接口
 *
 * <p>v1.32.0 增强：新增 {@code GET /api/health} 深度体检（数据库 / Redis / JVM / 运行时长），
 * 供保活任务、外部监控（UptimeRobot 等）与运维巡检使用。
 * 所有探测均为只读、超时受控，任何单项失败只降级该字段，不影响接口返回（返回整体 200，
 * 由调用方根据字段判定），避免监控自身误报。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RagSearchService ragSearchService;
    private final RagHealthTracker ragHealthTracker;

    public HealthController(JdbcTemplate jdbcTemplate, RedisTemplate<String, Object> redisTemplate,
                            RagSearchService ragSearchService, RagHealthTracker ragHealthTracker) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.ragSearchService = ragSearchService;
        this.ragHealthTracker = ragHealthTracker;
    }

    /**
     * 系统信息（轻量，供前端/保活 ping）
     */
    @GetMapping("/info")
    public Result<Map<String, String>> info() {
        return Result.success(Map.of(
                "name", "AI 智能面试辅助平台",
                "version", "1.0.0",
                "description", "基于 Spring Boot 3.3 + Spring AI 1.0 + Java 21",
                "docs", "/api/docs"
        ));
    }

    /**
     * 轻量探活（匿名可访问，供保活任务/外部监控 ping）。
     * P2-08：不再执行 DB SELECT 1 / Redis PING、不暴露 JVM 细节——此前匿名深度体检
     * 既是无认证的资源消耗放大点，也向匿名者泄露基础设施信息。
     * 深度体检移至需认证的 {@code GET /api/health/detail}（走 anyRequest().authenticated()）。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(java.util.Collections.<String, Object>singletonMap("status", "UP"));
    }

    /**
     * 深度健康体检（需登录）：数据库 / Redis / JVM / 运行时长 / **RAG 知识库**（v1.32.0 引入，P2-08 起需认证）
     *
     * <p>v1.34.1 增补 {@code rag} 与 {@code degraded}：知识库链路依赖外部 Embedding 服务，
     * 其故障（Key 失效/欠费/维度不匹配）此前只在启动日志里留一条 WARN，
     * 而本接口恒报 {@code status: UP} —— 2026-09-21 生产实测正是「体检全绿但知识库完全不可用」。
     * 现把播种结果与最近一次向量化失败原因一并暴露，使该故障无需登录平台即可自诊断。
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> healthDetail() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> rag = ragHealthTracker == null
                ? null
                : ragHealthTracker.snapshot(ragSearchService.storedCount(), ragSearchService.maxDocuments());
        // 健康检查接口绝不应因可选子系统缺位而 500，也不应产出「无 status 的区块」：
        // 跟踪器缺位、或返回 null/不完整快照时，如实报 UNKNOWN（而非让调用方拿到空对象误判）
        if (rag == null || !rag.containsKey("status")) {
            rag = new LinkedHashMap<>();
            rag.put("status", "UNKNOWN");
        }

        // 核心依赖（DB/Redis）决定 status；RAG 属可选子系统，只计入 degraded 列表，
        // 避免知识库故障被误判为「整个服务不可用」
        data.put("status", "UP");
        data.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("database", databaseStatus());
        data.put("redis", redisStatus());
        data.put("jvm", jvmStatus());
        data.put("rag", rag);

        if (!"UP".equals(rag.get("status")) && !"UNKNOWN".equals(rag.get("status"))) {
            data.put("degraded", List.of("rag"));
        }
        return Result.success(data);
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Long one = jdbcTemplate.queryForObject("SELECT 1", Long.class);
            m.put("status", Long.valueOf(1).equals(one) ? "UP" : "DOWN");
        } catch (DataAccessException e) {
            log.warn("健康检查：数据库不可达：{}", e.getMessage());
            m.put("status", "DOWN");
        }
        return m;
    }

    private Map<String, Object> redisStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String pong = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) conn -> conn.ping());
            m.put("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN");
        } catch (Exception e) {
            log.warn("健康检查：Redis 不可达：{}", e.getMessage());
            m.put("status", "DOWN");
        }
        return m;
    }

    private Map<String, Object> jvmStatus() {
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mx.getHeapMemoryUsage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapUsedMb", heap.getUsed() / 1024 / 1024);
        m.put("heapMaxMb", heap.getMax() / 1024 / 1024);
        m.put("threads", Thread.activeCount());
        return m;
    }
}

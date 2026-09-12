package com.example.interview.controller;

import com.example.interview.common.Result;
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

    public HealthController(JdbcTemplate jdbcTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
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
     * 深度健康体检（v1.32.0）：数据库 / Redis / JVM / 运行时长
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("database", databaseStatus());
        data.put("redis", redisStatus());
        data.put("jvm", jvmStatus());
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

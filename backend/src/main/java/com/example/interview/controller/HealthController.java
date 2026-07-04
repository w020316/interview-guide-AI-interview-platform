package com.example.interview.controller;

import com.example.interview.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 健康检查与系统信息接口
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 系统信息
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
}

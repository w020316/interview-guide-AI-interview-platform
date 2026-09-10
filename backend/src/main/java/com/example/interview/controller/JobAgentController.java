package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.job.JobAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 招聘信息智能体接口
 * - 岗位列表：关键词搜索 + 行业/职位类型/地点/招聘类型/来源多条件筛选
 * - 岗位详情 / 筛选元数据 / 手动刷新
 *
 * 遵循全局 JWT 认证（SecurityConfig anyRequest().authenticated()），userId 不入库（岗位为公共数据）。
 */
@Tag(name = "招聘信息", description = "秋招/社招岗位聚合搜索与筛选")
@RestController
@RequestMapping("/api/jobs")
public class JobAgentController {

    private final JobAgentService jobAgentService;

    public JobAgentController(JobAgentService jobAgentService) {
        this.jobAgentService = jobAgentService;
    }

    /**
     * 岗位列表（多条件筛选）
     * GET /api/jobs?keyword=java&industry=互联网&jobType=技术&location=深圳&recruitType=AUTUMN&source=内置精选&page=0&size=10
     */
    @Operation(summary = "岗位列表（多条件筛选搜索）")
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String location,
            @RequestParam(required = false, defaultValue = "AUTUMN") String recruitType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Page<JobPostingEntity> result = jobAgentService.search(
                keyword, industry, jobType, location, recruitType, source, page, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("items", result.getContent());
        return Result.success(data);
    }

    /**
     * 岗位详情
     * GET /api/jobs/{id}
     */
    @Operation(summary = "岗位详情")
    @GetMapping("/{id}")
    public Result<JobPostingEntity> detail(@PathVariable Long id) {
        JobPostingEntity job = jobAgentService.findById(id);
        if (job == null) {
            return Result.error(404, "岗位不存在或已下架");
        }
        return Result.success(job);
    }

    /**
     * 筛选面板元数据：行业/职位类型/来源/各招聘类型数量/最近更新时间
     * GET /api/jobs/meta
     */
    @Operation(summary = "筛选面板元数据")
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        return Result.success(jobAgentService.meta());
    }

    /**
     * 手动刷新（也可由定时任务自动执行）
     * POST /api/jobs/refresh
     */
    @Operation(summary = "手动刷新岗位数据")
    @PostMapping("/refresh")
    public Result<JobAgentService.RefreshResult> refresh() {
        return Result.success(jobAgentService.refresh());
    }
}

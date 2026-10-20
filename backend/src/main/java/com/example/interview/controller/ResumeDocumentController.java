package com.example.interview.controller;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.common.Result;
import com.example.interview.entity.ResumeDocumentEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.service.resume.ResumeAutofillService;
import com.example.interview.service.resume.ResumeDocumentService;
import com.example.interview.service.resume.ResumeTemplateCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历工作台接口（v1.67.0）
 *
 * <p>提供「模板库 → 结构化编辑 → 导出」链路的服务端能力。设计要点：
 * <ul>
 *   <li><b>列表不返回正文</b>：{@code GET /documents} 只回摘要（标题 / 模板 / 更新时间），
 *       正文体积可达上百 KB，列表页带上会让首屏变慢；</li>
 *   <li><b>模板元数据由服务端下发</b>：前端不硬编码模板清单，
 *       新增模板只需改 {@link ResumeTemplateCatalog}（前端版式实现仍需同步，见该类注释）；</li>
 *   <li><b>userId 一律取 JWT subject</b>，不接受请求体传入；按 ID 操作全部做归属校验。</li>
 * </ul>
 *
 * <p><b>合规边界</b>：本控制器只处理用户自己维护的简历内容，
 * 不涉及任何招聘平台登录态、不代理平台接口、不做自动投递。
 */
@Tag(name = "简历工作台", description = "简历模板库与结构化简历文档")
@RestController
@RequestMapping("/api/resume")
public class ResumeDocumentController {

    private final ResumeDocumentService documentService;
    private final ResumeAutofillService autofillService;
    private final ResumeRepository resumeRepository;

    public ResumeDocumentController(ResumeDocumentService documentService,
                                    ResumeAutofillService autofillService,
                                    ResumeRepository resumeRepository) {
        this.documentService = documentService;
        this.autofillService = autofillService;
        this.resumeRepository = resumeRepository;
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 模板库清单
     * GET /api/resume/templates
     */
    @Operation(summary = "简历模板清单")
    @GetMapping("/templates")
    public Result<List<ResumeTemplateCatalog.Template>> templates() {
        return Result.success(ResumeTemplateCatalog.all());
    }

    /**
     * 我的简历文档列表（摘要，不含正文）
     * GET /api/resume/documents
     */
    @Operation(summary = "我的简历文档列表")
    @GetMapping("/documents")
    public Result<List<Map<String, Object>>> listDocuments() {
        String userId = currentUserId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResumeDocumentEntity e : documentService.list(userId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("title", e.getTitle());
            item.put("templateId", e.getTemplateId());
            item.put("sourceResumeId", e.getSourceResumeId());
            item.put("createdAt", e.getCreatedAt());
            item.put("updatedAt", e.getUpdatedAt());
            out.add(item);
        }
        return Result.success(out);
    }

    /**
     * 新建简历文档
     * POST /api/resume/documents
     * 请求体：{ templateId?, title?, docData?, sourceResumeId? }
     */
    @Operation(summary = "新建简历文档")
    @PostMapping("/documents")
    public Result<ResumeDocumentEntity> create(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String templateId = asString(req.get("templateId"));
        String title = asString(req.get("title"));
        String docData = asString(req.get("docData"));
        Long sourceResumeId = asLong(req.get("sourceResumeId"));
        return Result.success(documentService.create(userId, templateId, title, docData, sourceResumeId));
    }

    /**
     * 读取单份简历文档（含正文）
     * GET /api/resume/documents/{id}
     */
    @Operation(summary = "读取简历文档")
    @GetMapping("/documents/{id}")
    public Result<ResumeDocumentEntity> getDocument(@PathVariable Long id) {
        return Result.success(documentService.getOwned(currentUserId(), id));
    }

    /**
     * 保存简历文档
     * PUT /api/resume/documents/{id}
     * 请求体：{ templateId?, title?, docData? } —— 只更新传入的字段
     */
    @Operation(summary = "保存简历文档")
    @PutMapping("/documents/{id}")
    public Result<ResumeDocumentEntity> updateDocument(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String templateId = asString(req.get("templateId"));
        String title = asString(req.get("title"));
        String docData = asString(req.get("docData"));
        return Result.success(documentService.update(userId, id, templateId, title, docData));
    }

    /**
     * 删除简历文档
     * DELETE /api/resume/documents/{id}
     */
    @Operation(summary = "删除简历文档")
    @DeleteMapping("/documents/{id}")
    public Result<Map<String, Object>> deleteDocument(@PathVariable Long id) {
        documentService.delete(currentUserId(), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("deleted", true);
        return Result.success(data);
    }

    /**
     * 从已有简历自动填充结构化内容
     * POST /api/resume/documents/{id}/autofill
     *
     * <p>请求体（都可选）：{@code { resumeText?, sourceResumeId? }}
     * <ul>
     *   <li>传 {@code resumeText}：直接用这段文本；</li>
     *   <li>传 {@code sourceResumeId}：读该份简历的解析文本（<b>做归属校验</b>）；</li>
     *   <li>都不传：取该用户<b>最新</b>的一份简历。</li>
     * </ul>
     *
     * <p>⚠️ 只返回抽取结果，<b>不直接写库</b>：用户要先看一眼抽得对不对，
     * 确认后再由前端调保存接口。自动填充是「帮用户少打字」，不是「替用户决定内容」。
     */
    @Operation(summary = "从已有简历自动填充")
    @PostMapping("/documents/{id}/autofill")
    public Result<Map<String, Object>> autofill(@PathVariable Long id,
                                                @RequestBody(required = false) Map<String, Object> req) {
        String userId = currentUserId();
        // 归属校验：只能给自己的文档做填充
        documentService.getOwned(userId, id);

        Map<String, Object> body = req == null ? Map.of() : req;
        String resumeText = asString(body.get("resumeText"));
        Long sourceResumeId = asLong(body.get("sourceResumeId"));

        String content = resumeText;
        Long usedSourceId = null;

        if (content == null || content.isBlank()) {
            ResumeEntity source = null;
            if (sourceResumeId != null) {
                source = resumeRepository.findById(sourceResumeId).orElse(null);
                // 防 IDOR：指定了别人的简历 id 时按「不存在」处理，不暴露其存在性
                if (source != null && !userId.equals(source.getUserId())) {
                    throw new ResourceNotFoundException("未找到该简历");
                }
            } else {
                List<ResumeEntity> mine = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
                if (!mine.isEmpty()) {
                    source = mine.get(0);
                }
            }
            if (source == null || source.getContent() == null || source.getContent().isBlank()) {
                return Result.error(400, "还没有可用的简历文本，请先到「简历分析」上传一份简历");
            }
            content = source.getContent();
            usedSourceId = source.getId();
        }

        String docData = autofillService.autofill(content);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docData", docData);
        result.put("sourceResumeId", usedSourceId);
        return Result.success(result);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

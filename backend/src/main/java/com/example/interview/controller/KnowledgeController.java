package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.RagSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RAG 知识库接口 */
@Tag(name = "知识库", description = "RAG 检索、问答、批量导入")
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    @Autowired private RagSearchService ragSearchService;
    @Autowired private VectorStore vectorStore;

    @Operation(summary = "知识库语义检索")
    @GetMapping("/search")
    public Result<String> search(@RequestParam String query,
                                 @RequestParam(defaultValue = "5") int topK) {
        return Result.success(ragSearchService.search(query, topK));
    }

    @Operation(summary = "RAG 增强问答")
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) return Result.error(400, "问题不能为空");
        return Result.success(ragSearchService.answerWithRag(question));
    }

    @Operation(summary = "导入知识文档（简单模式）")
    @PostMapping("/import")
    public Result<String> importKnowledge(@RequestBody Map<String, List<String>> request) {
        List<String> documents = request.get("documents");
        if (documents == null || documents.isEmpty()) return Result.error(400, "文档列表不能为空");
        ragSearchService.importKnowledge(documents);
        return Result.success("成功导入 " + documents.size() + " 条文档");
    }

    /**
     * 批量导入 Markdown 知识分块（P1）
     * POST /api/knowledge/import/batch
     * Body: {"category":"Spring","chunks":["内容1","内容2"]}
     */
    @Operation(summary = "批量导入 Markdown 知识分块并向量化")
    @PostMapping("/import/batch")
    public Result<Map<String, Object>> batchImport(@RequestBody Map<String, Object> request) {
        String category = (String) request.getOrDefault("category", "通用");
        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) request.get("chunks");
        if (chunks == null || chunks.isEmpty()) return Result.error(400, "chunks 不能为空");

        List<Document> docs = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            docs.add(Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(chunk)
                    .metadata(Map.of("category", category, "source", "batch-import"))
                    .build());
        }
        vectorStore.add(docs);
        return Result.success(Map.of("imported", docs.size(), "category", category));
    }
}

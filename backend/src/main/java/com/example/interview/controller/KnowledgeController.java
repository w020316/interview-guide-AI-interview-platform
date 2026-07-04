package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.RagSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库接口
 */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    @Autowired
    private RagSearchService ragSearchService;

    /**
     * 检索相关知识
     * GET /api/knowledge/search?query=HashMap&topK=5
     */
    @GetMapping("/search")
    public Result<String> search(@RequestParam String query,
                                 @RequestParam(defaultValue = "5") int topK) {
        try {
            String result = ragSearchService.search(query, topK);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error("检索失败：" + e.getMessage());
        }
    }

    /**
     * RAG 问答
     * POST /api/knowledge/ask
     * Body: {"question": "HashMap 和 Hashtable 的区别？"}
     */
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            return Result.error(400, "问题不能为空");
        }

        try {
            String answer = ragSearchService.answerWithRag(question);
            return Result.success(answer);
        } catch (Exception e) {
            return Result.error("问答失败：" + e.getMessage());
        }
    }

    /**
     * 导入知识文档
     * POST /api/knowledge/import
     * Body: {"documents": ["文档1", "文档2"]}
     */
    @PostMapping("/import")
    public Result<String> importKnowledge(@RequestBody Map<String, List<String>> request) {
        List<String> documents = request.get("documents");
        if (documents == null || documents.isEmpty()) {
            return Result.error(400, "文档列表不能为空");
        }

        try {
            ragSearchService.importKnowledge(documents);
            return Result.success("成功导入 " + documents.size() + " 条文档");
        } catch (Exception e) {
            return Result.error("导入失败：" + e.getMessage());
        }
    }
}

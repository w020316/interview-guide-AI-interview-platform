package com.example.interview.service;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 简历 PDF 解析服务
 * - 使用 Apache Tika 解析 PDF / Word / 纯文本格式简历
 * - 解析完成后交由 ResumeAnalysisService 做 AI 评分
 */
@Service
public class ResumeParseService {

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    /**
     * 解析上传的简历文件，返回 AI 分析结果
     *
     * @param file      上传的简历文件（PDF / DOCX / TXT）
     * @param targetJob 目标岗位
     * @return AI 分析 JSON 字符串
     */
    public String parseAndAnalyze(MultipartFile file, String targetJob) throws IOException {
        // 1. 将 MultipartFile 包装为 Spring Resource，供 Tika 读取
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                // Tika 根据文件名判断 MIME 类型，务必透传原始文件名
                return file.getOriginalFilename();
            }
        };

        // 2. TikaDocumentReader 解析文件，提取纯文本
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        String resumeText = reader.get().stream()
                .map(doc -> doc.getText())
                .collect(Collectors.joining("\n"));

        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("简历文件内容为空，请检查文件是否损坏");
        }

        // 3. 调用 AI 分析（含 Redis 缓存 + 向量化存储）
        return resumeAnalysisService.analyze(resumeText, targetJob);
    }
}

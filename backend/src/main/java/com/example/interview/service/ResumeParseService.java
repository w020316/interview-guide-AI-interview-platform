package com.example.interview.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 简历 PDF 解析服务
 * - 使用 PDFBox 轻量解析 PDF（替代 Tika，大幅减少 Metaspace 占用）
 * - 纯文本文件直接读取
 * - 解析完成后交由 ResumeAnalysisService 做 AI 评分
 */
@Service
public class ResumeParseService {

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    /**
     * 解析上传的简历文件，返回 AI 分析结果
     *
     * @param file      上传的简历文件（PDF / TXT）
     * @param targetJob 目标岗位
     * @return AI 分析 JSON 字符串
     */
    public String parseAndAnalyze(MultipartFile file, String targetJob) throws IOException {
        String filename = file.getOriginalFilename();
        String resumeText;

        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            // PDFBox 解析 PDF
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                resumeText = stripper.getText(document);
            }
        } else {
            // 纯文本直接读取
            resumeText = new String(file.getBytes());
        }

        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("简历文件内容为空，请检查文件是否损坏");
        }

        // 调用 AI 分析（含 Redis 缓存）
        return resumeAnalysisService.analyze(resumeText, targetJob);
    }
}

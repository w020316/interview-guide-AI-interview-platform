package com.example.interview.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 简历文件解析服务
 * - 使用 PDFBox 轻量解析 PDF（替代 Tika，大幅减少 Metaspace 占用）
 * - 纯文本文件直接读取
 * - DOCX/DOC 暂不支持（PDFBox 无法解析 Word 格式）
 * - 解析完成后交由 ResumeAnalysisService 做 AI 评分
 */
@Service
public class ResumeParseService {

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    /**
     * 解析上传的简历文件，返回 AI 分析结果
     *
     * @param file      上传的简历文件（仅支持 PDF / TXT）
     * @param targetJob 目标岗位
     * @return AI 分析 JSON 字符串
     */
    public String parseAndAnalyze(MultipartFile file, String targetJob) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String lower = filename.toLowerCase();
        String resumeText;

        if (lower.endsWith(".pdf")) {
            // PDFBox 解析 PDF
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                resumeText = stripper.getText(document);
            }
        } else if (lower.endsWith(".txt")) {
            // 纯文本直接读取（显式指定 UTF-8，避免 Windows 默认 GBK 导致中文乱码）
            resumeText = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            // DOCX/DOC 及其他格式暂不支持
            throw new IllegalArgumentException(
                    "暂不支持该格式，请将简历转换为 PDF 或 TXT 后重试（当前仅支持 .pdf / .txt）");
        }

        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("简历文件内容为空，请检查文件是否损坏");
        }

        // 调用 AI 分析（含 Redis 缓存）
        return resumeAnalysisService.analyze(resumeText, targetJob);
    }
}

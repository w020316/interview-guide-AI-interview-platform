package com.example.interview.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 简历文件解析服务
 * - 使用 PDFBox 轻量解析 PDF（替代 Tika，大幅减少 Metaspace 占用）
 * - 使用 jsoup 解析 HTML（提取纯文本）
 * - Markdown 文件直接读取（AI 能理解 Markdown 格式）
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
     * @param userId    用户 ID（用于缓存隔离）
     * @param file      上传的简历文件（支持 PDF / HTML / MD / TXT）
     * @param targetJob 目标岗位
     * @return AI 分析 JSON 字符串
     */
    public String parseAndAnalyze(String userId, MultipartFile file, String targetJob) throws IOException {
        String resumeText = parseToText(file);
        // 调用 AI 分析（含 Redis 缓存，key 含 userId 防跨用户串扰）
        return resumeAnalysisService.analyze(userId, resumeText, targetJob);
    }

    /**
     * 解析上传的简历文件，返回纯文本
     * 供 Controller 在 upload 时同步拿到 resumeText 返回给前端，
     * 前端可基于此文本调用 /api/resume/optimize 生成优化简历
     *
     * @param file 上传的简历文件（支持 PDF / HTML / MD / TXT）
     * @return 简历纯文本
     */
    public String parseToText(MultipartFile file) throws IOException {
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
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            // jsoup 解析 HTML，提取纯文本
            String html = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            resumeText = extractTextFromHtml(html);
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            // Markdown 直接读取（AI 能理解 Markdown 格式）
            resumeText = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else if (lower.endsWith(".txt")) {
            // 纯文本直接读取（显式指定 UTF-8，避免 Windows 默认 GBK 导致中文乱码）
            resumeText = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            // 其他格式暂不支持
            throw new IllegalArgumentException(
                    "暂不支持该格式，请将简历转换为 PDF / HTML / MD / TXT 后重试（当前支持 .pdf / .html / .htm / .md / .markdown / .txt）");
        }

        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("简历文件内容为空，请检查文件是否损坏");
        }

        return resumeText;
    }

    /**
     * 从 HTML 中提取纯文本
     * 使用 jsoup 的 clean 方法去除所有标签，保留文本内容
     *
     * @param html HTML 内容
     * @return 纯文本
     */
    private String extractTextFromHtml(String html) {
        // 使用 Safelist.none() 去除所有标签，保留文本
        String cleanText = Jsoup.clean(html, Safelist.none());
        // 清理多余空白行（保留段落结构）
        return cleanText.replaceAll("\\n{3,}", "\n\n").trim();
    }
}

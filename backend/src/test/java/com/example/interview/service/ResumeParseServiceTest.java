package com.example.interview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeParseService} 单元测试
 *
 * <p>覆盖核心方法 {@code parseToText} 和 {@code parseAndAnalyze}：
 * <ul>
 *   <li>多格式分发：PDF / HTML / HTM / MD / MARKDOWN / TXT</li>
 *   <li>异常分支：文件名为空、不支持格式、内容为空</li>
 *   <li>HTML 提取：jsoup 去标签 + 多余空白压缩</li>
 *   <li>parseAndAnalyze：委托 ResumeAnalysisService.analyze</li>
 * </ul>
 *
 * <p>Mock 策略：
 * <ul>
 *   <li>MultipartFile：使用 Spring 的 MockMultipartFile 构造真实文件内容（无需 mock）</li>
 *   <li>ResumeAnalysisService：@Mock，验证 parseAndAnalyze 的委托调用</li>
 *   <li>PDF 测试：跳过（需真实 PDF 二进制，仅验证非 PDF 格式分发逻辑）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResumeParseService 单元测试")
class ResumeParseServiceTest {

    @Mock
    private ResumeAnalysisService resumeAnalysisService;

    @InjectMocks
    private ResumeParseService service;

    @Nested
    @DisplayName("parseToText: 简历文件解析")
    class ParseToText {

        @Test
        @DisplayName("TXT 文件正常解析为纯文本")
        void parseToText_txtFile_returnsText() throws IOException {
            String content = "张三的简历\nJava 后端工程师";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.txt", "text/plain",
                    content.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("MD 文件正常解析为 Markdown 文本")
        void parseToText_mdFile_returnsMarkdown() throws IOException {
            String content = "# 张三的简历\n## 技能\n- Java\n- Spring Boot";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.md", "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("MARKDOWN 扩展名正常解析")
        void parseToText_markdownExtension_returnsText() throws IOException {
            String content = "# 简历内容";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.markdown", "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("HTML 文件解析为纯文本（去标签）")
        void parseToText_htmlFile_stripsTags() throws IOException {
            String html = "<html><body><h1>张三</h1><p>Java 工程师</p></body></html>";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.html", "text/html",
                    html.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).contains("张三");
            assertThat(result).contains("Java 工程师");
            assertThat(result).doesNotContain("<html>");
            assertThat(result).doesNotContain("<h1>");
            assertThat(result).doesNotContain("<p>");
        }

        @Test
        @DisplayName("HTM 扩展名正常解析")
        void parseToText_htmExtension_stripsTags() throws IOException {
            String html = "<p>简历内容</p>";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.htm", "text/html",
                    html.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).contains("简历内容");
            assertThat(result).doesNotContain("<p>");
        }

        @Test
        @DisplayName("HTML 多余空白行被压缩为最多两个换行")
        void parseToText_htmlWithBlankLines_compressed() throws IOException {
            // jsoup clean 后会产生多个换行，正则 \\n{3,} 压缩为 \n\n
            String html = "<div><p>段落1</p><p>段落2</p><p>段落3</p></div>";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.html", "text/html",
                    html.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            // 验证不存在连续 3 个及以上换行
            assertThat(result).doesNotContain("\n\n\n");
        }

        @Test
        @DisplayName("文件名为空时抛 IllegalArgumentException")
        void parseToText_emptyFileName_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "", "text/plain", "content".getBytes());

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件名不能为空");
        }

        @Test
        @DisplayName("文件名为 null 时抛 IllegalArgumentException")
        void parseToText_nullFileName_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", null, "text/plain", "content".getBytes());

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件名不能为空");
        }

        @Test
        @DisplayName("不支持的格式抛 IllegalArgumentException")
        void parseToText_unsupportedFormat_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.docx", "application/octet-stream",
                    "content".getBytes());

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("暂不支持该格式");
        }

        @Test
        @DisplayName("DOC 文件不支持抛 IllegalArgumentException")
        void parseToText_docFile_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.doc", "application/msword",
                    "content".getBytes());

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("暂不支持该格式");
        }

        @Test
        @DisplayName("TXT 文件内容为空时抛 IllegalArgumentException")
        void parseToText_emptyContent_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.txt", "text/plain", new byte[0]);

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("简历文件内容为空");
        }

        @Test
        @DisplayName("TXT 文件内容仅空白字符时抛 IllegalArgumentException")
        void parseToText_blankContent_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.txt", "text/plain", "   \n\t  ".getBytes());

            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("简历文件内容为空");
        }

        @Test
        @DisplayName("大写扩展名 .TXT 正常解析（大小写不敏感）")
        void parseToText_upperCaseExtension_parsesNormally() throws IOException {
            String content = "简历内容";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.TXT", "text/plain",
                    content.getBytes(StandardCharsets.UTF_8));

            String result = service.parseToText(file);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("大写扩展名 .PDF 走 PDF 分支（非 PDF 内容会抛异常）")
        void parseToText_upperCasePdfExtension_entersPdfBranch() {
            // 非 PDF 二进制内容传入 PDFBox 会抛异常
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.PDF", "application/pdf",
                    "not a real pdf".getBytes());

            // PDFBox 解析非 PDF 内容会抛 IOException
            assertThatThrownBy(() -> service.parseToText(file))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("parseAndAnalyze: 解析 + AI 分析")
    class ParseAndAnalyze {

        @Test
        @DisplayName("解析 TXT 后委托 ResumeAnalysisService.analyze")
        void parseAndAnalyze_txtFile_delegatesToAnalysisService() throws IOException {
            String content = "张三的简历";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.txt", "text/plain",
                    content.getBytes(StandardCharsets.UTF_8));
            String aiResult = "{\"overallScore\":85}";
            when(resumeAnalysisService.analyze("user1", content, "Java 后端"))
                    .thenReturn(aiResult);

            String result = service.parseAndAnalyze("user1", file, "Java 后端");

            assertThat(result).isEqualTo(aiResult);
            verify(resumeAnalysisService).analyze("user1", content, "Java 后端");
        }

        @Test
        @DisplayName("解析 HTML 后委托 ResumeAnalysisService（传去除标签的纯文本）")
        void parseAndAnalyze_htmlFile_delegatesWithStrippedText() throws IOException {
            String html = "<p>简历内容</p>";
            MultipartFile file = new MockMultipartFile(
                    "file", "resume.html", "text/html",
                    html.getBytes(StandardCharsets.UTF_8));
            when(resumeAnalysisService.analyze(any(), any(), any()))
                    .thenReturn("{\"score\":90}");

            service.parseAndAnalyze("user1", file, "岗位");

            // 验证传给 analyze 的是去标签纯文本
            verify(resumeAnalysisService).analyze(
                    org.mockito.ArgumentMatchers.eq("user1"),
                    org.mockito.ArgumentMatchers.contains("简历内容"),
                    org.mockito.ArgumentMatchers.eq("岗位"));
        }

        @Test
        @DisplayName("文件名空时 parseAndAnalyze 抛 IllegalArgumentException")
        void parseAndAnalyze_emptyFileName_throwsException() {
            MultipartFile file = new MockMultipartFile(
                    "file", "", "text/plain", "content".getBytes());

            assertThatThrownBy(() -> service.parseAndAnalyze("user1", file, "岗位"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件名不能为空");
        }
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}

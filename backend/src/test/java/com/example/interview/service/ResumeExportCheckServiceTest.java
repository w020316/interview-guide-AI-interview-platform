package com.example.interview.service;

import com.example.interview.service.ResumeExportCheckService.CheckItem;
import com.example.interview.service.ResumeExportCheckService.ExportCheckReport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出体检的确定性验证。
 *
 * <p>为什么这些用例值得写：本服务的全部价值在于「同样的文件永远得出同样的结论」，
 * 并且**从不出让简历原文**。因此除了正常/异常路径，还专门断言报告里不含邮箱与手机号原值。
 *
 * <p>测试 PDF 用 PDFBox 现场生成（Helvetica 只支持 ASCII），所以板块关键词走英文写法，
 * 中文关键词由纯函数级用例覆盖。
 */
class ResumeExportCheckServiceTest {

    private final ResumeExportCheckService service = new ResumeExportCheckService();

    private static final List<String> GOOD_LINES = List.of(
            "EDUCATION",
            "Peking University, Computer Science, 2018 - 2022",
            "EXPERIENCE",
            "Acme Corp, Backend Engineer, 2022.07 - 2024.06",
            "Built payment service with Java, Spring Boot, MySQL and Redis.",
            "Reduced p99 latency from 480ms to 120ms and cut cost by 30%.",
            "PROJECT",
            "Interview platform: 3200 daily users, 99.95% availability.",
            "SKILLS",
            "Java, TypeScript, SQL, Kubernetes, Kafka, Docker.",
            "Contact: zhang.san@example.com  13800138000");

    private static final List<String> MORE_LINES = List.of(
            "ADDITIONAL",
            "Open source contributor, 2021 - 2024.",
            "Mentored three junior engineers and wrote the onboarding guide.");

    @Test
    @DisplayName("可读的两页 PDF：全部检查通过，并如实给出版本、页数与字数")
    void readableTwoPagePdfPasses() throws IOException {
        ExportCheckReport report = check(pdf(List.of(GOOD_LINES, MORE_LINES)));

        assertThat(report.verdict()).isEqualTo("pass");
        assertThat(report.pageCount()).isEqualTo(2);
        assertThat(report.charCount()).isGreaterThan(200);
        assertThat(report.summary()).contains("全部 7 项检查通过");
        assertThat(report.checks()).hasSize(7);
        assertThat(report.checks()).allMatch(c -> "pass".equals(c.status()));
    }

    @Test
    @DisplayName("报告不得回传邮箱/手机号原值（隐私红线）")
    void reportNeverLeaksContactValues() throws IOException {
        ExportCheckReport report = check(pdf(List.of(GOOD_LINES, MORE_LINES)));

        String serialized = report.checks().toString() + report.summary();
        assertThat(serialized).doesNotContain("zhang.san@example.com");
        assertThat(serialized).doesNotContain("13800138000");
        assertThat(item(report, "contact").detail()).contains("邮箱与手机号");
    }

    @Test
    @DisplayName("没有文字层的 PDF（截图/图片导出）：文字层判失败，总结论失败")
    void imageOnlyPdfFails() throws IOException {
        ExportCheckReport report = check(pdf(List.of(List.of())));
        // 空文本页：PDFBox 提取不到任何文字
        ExportCheckReport blank = check(pdfWithNoContent(List.of(1)));

        assertThat(blank.verdict()).isEqualTo("fail");
        assertThat(item(blank, "text-layer").status()).isEqualTo("fail");
        assertThat(item(blank, "text-layer").detail()).contains("招聘系统无法检索");
        assertThat(blank.charCount()).isZero();
        // 空页也没有任何文字内容，同样应判失败
        assertThat(report.verdict()).isEqualTo("fail");
    }

    @Test
    @DisplayName("超过 2 页：页数给建议，但不判失败")
    void threePagesWarnOnly() throws IOException {
        ExportCheckReport report = check(pdf(List.of(GOOD_LINES, MORE_LINES, MORE_LINES)));

        assertThat(report.pageCount()).isEqualTo(3);
        assertThat(item(report, "pages").status()).isEqualTo("warn");
        assertThat(item(report, "pages").detail()).contains("压到 2 页内");
        assertThat(report.verdict()).isEqualTo("warn");
    }

    @Test
    @DisplayName("缺少联系方式：给出提醒而不是失败")
    void missingContactWarns() throws IOException {
        List<String> noContact = List.of(
                "EDUCATION",
                "Peking University, Computer Science, 2018 - 2022",
                "EXPERIENCE",
                "Acme Corp, Backend Engineer, 2022.07 - 2024.06",
                "PROJECT",
                "Interview platform with Java and Spring Boot, 99.95% availability.",
                "SKILLS",
                "Java, TypeScript, SQL, Kubernetes, Kafka, Docker.");

        ExportCheckReport report = check(pdf(List.of(noContact)));

        assertThat(item(report, "contact").status()).isEqualTo("warn");
        assertThat(item(report, "contact").detail()).contains("没有在文字层找到邮箱或手机号");
        assertThat(report.verdict()).isEqualTo("warn");
    }

    @Test
    @DisplayName("加密 PDF：明确说明「招聘系统同样打不开」，不做无意义的后续检查")
    void encryptedPdfIsBlocked() throws IOException {
        ExportCheckReport report = check(encryptedPdf());

        assertThat(report.verdict()).isEqualTo("fail");
        assertThat(report.pageCount()).isZero();
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).status()).isEqualTo("fail");
        assertThat(report.checks().get(0).detail()).contains("打开密码");
    }

    @Test
    @DisplayName("伪装成 PDF 的垃圾字节：判为无法打开，而不是抛异常")
    void corruptedBytesIsBlocked() {
        ExportCheckReport report = check("%PDF-1.7 but the rest is garbage".getBytes(StandardCharsets.UTF_8));

        assertThat(report.verdict()).isEqualTo("fail");
        assertThat(report.summary()).startsWith("无法完成体检");
        assertThat(report.checks()).hasSize(1);
    }

    @Test
    @DisplayName("空文件与超限文件：在校验阶段直接拦下")
    void emptyAndOversizeBlocked() {
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getSize()).thenReturn((long) ResumeExportCheckService.MAX_FILE_BYTES + 1);

        assertThat(service.check(empty).summary()).contains("文件为空");
        assertThat(service.check(huge).summary()).contains("超过 10MB");
        assertThat(service.check(null).verdict()).isEqualTo("fail");
    }

    @Test
    @DisplayName("乱码统计：零宽/控制/私用区/替换符计入，换行与制表符不计")
    void suspiciousCharsCounting() {
        String text = "正常文本\u200B零宽\uFEFFBOM\u0001控制\uE000私用\uFFFD替换\n\t 空格";

        assertThat(ResumeExportCheckService.countSuspiciousChars(text)).isEqualTo(5);
        assertThat(ResumeExportCheckService.countSuspiciousChars("clean text")).isZero();
        assertThat(ResumeExportCheckService.countSuspiciousChars(null)).isZero();
    }

    @Test
    @DisplayName("字数统计：按码点计、跳过空白")
    void nonWhitespaceCounting() {
        assertThat(ResumeExportCheckService.countNonWhitespace(" a b\nc\t")).isEqualTo(3);
        assertThat(ResumeExportCheckService.countNonWhitespace("")).isZero();
        assertThat(ResumeExportCheckService.countNonWhitespace(null)).isZero();
    }

    @Test
    @DisplayName("年份统计：只认 4 位完整年份，不被更长数字误命中")
    void yearCounting() {
        assertThat(ResumeExportCheckService.countYears("2018 - 2022")).isEqualTo(2);
        assertThat(ResumeExportCheckService.countYears("12022 和 20245 都不算")).isZero();
        assertThat(ResumeExportCheckService.countYears("无年份")).isZero();
    }

    // ───────────────────────── helpers ─────────────────────────

    private ExportCheckReport check(byte[] bytes) {
        return service.check(new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes));
    }

    private static CheckItem item(ExportCheckReport report, String id) {
        Optional<CheckItem> found = report.checks().stream().filter(c -> id.equals(c.id())).findFirst();
        assertThat(found).as("缺少检查项 " + id).isPresent();
        return found.orElseThrow();
    }

    private static byte[] pdf(List<List<String>> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.setLeading(14f);
                    cs.newLineAtOffset(50, 780);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            return save(doc);
        }
    }

    /** 完全不含内容流的页面：模拟「整页截图但没做任何文字层」的导出 */
    private static byte[] pdfWithNoContent(List<Integer> pageCounts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCounts.size(); i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            return save(doc);
        }
    }

    private static byte[] encryptedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 780);
                cs.showText("Secret resume");
                cs.endText();
            }
            AccessPermission permission = new AccessPermission();
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-pwd", "user-pwd", permission);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return save(doc);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}

package com.example.interview.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历 PDF「导出体检」服务。
 *
 * <h2>这条能力解决什么问题</h2>
 *
 * <p>用户在站内排好版、导出 PDF 之后，真正决定「这份简历能不能被读到」的是导出文件本身：
 * 截图转 PDF 没有文字层、字体没嵌入导致乱码、加密 PDF 打不开、联系方式藏在图片里。
 * 这些问题在编辑器里完全看不出来，投出去才知道石沉大海。
 *
 * <h2>为什么全部是本地规则、不调 AI</h2>
 *
 * <p>① 这类判断有客观答案（有没有文字层、能不能打开、有没有邮箱），规则比模型更准更稳；
 * ② 简历含姓名、电话、邮箱等强 PII，上传到第三方模型的链路越少越好。
 * 因此本服务只用 PDFBox 解析，结果完全可复现，同样的文件永远得到同一份报告。
 *
 * <h2>隐私</h2>
 *
 * <p>只返回统计量与结论，**从不回传简历原文、邮箱或手机号的值**；日志同样不记录正文。
 */
@Service
public class ResumeExportCheckService {

    /** 与上传接口一致的 10MB 上限：更大的文件在服务端解析会占用过多内存 */
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    /** 最多扫描的页数：只用前 10 页就足以判断「文字能不能被读出来」，避免长文档拖慢解析 */
    private static final int MAX_SCANNED_PAGES = 10;

    /** 文字层充足线：低于此值给出「偏少」提示 */
    private static final int TEXT_OK = 200;

    /** 文字层失败线：低于此值视为几乎不可检索（典型是截图/图片导出的 PDF） */
    private static final int TEXT_FAIL = 50;

    /** 乱码/不可见字符占比达到此百分比即判失败（典型是字体未嵌入或编码错误） */
    private static final double SUSPICIOUS_RATIO_FAIL = 2.0;

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 中国大陆手机号与「+国际区号」两类写法 */
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?86[\\s-]?)?1[3-9]\\d{9}(?!\\d)|\\+\\d{1,3}[\\s-]?\\d{6,14}");

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");

    /** 板块关键词（英文按小写比较）；命中任一即视为该板块存在 */
    private record Section(String label, List<String> keywords) {}

    private static final List<Section> SECTIONS = List.of(
            new Section("教育背景", List.of("教育", "学历", "毕业", "education", "academic")),
            new Section("工作/实习经历",
                    List.of("工作经历", "工作经验", "实习经历", "实习经验", "任职", "employment",
                            "work experience", "experience")),
            new Section("项目经历", List.of("项目经历", "项目经验", "项目", "project")),
            new Section("技能", List.of("技能", "技术栈", "擅长", "skill", "expertise", "tech stack")));

    /** 单项检查结果；status 取 pass / warn / fail */
    public record CheckItem(String id, String label, String status, String detail) {}

    /**
     * 体检报告。
     *
     * @param verdict   总结论：pass（全部通过）/ warn（可读但建议优化）/ fail（有必须先处理的问题）
     * @param pageCount 页数
     * @param charCount 文字层字符数（不含空白）；文件打不开时为 0
     */
    public record ExportCheckReport(String verdict, String summary, int pageCount, int charCount,
                                    List<CheckItem> checks) {}

    /**
     * 对上传的 PDF 做确定性体检。
     *
     * <p>不抛异常：文件损坏 / 加密 / 读不出来都表达为 {@code verdict=fail} 的报告，
     * 这样前端只需要渲染一份报告，不必为每种坏文件单独写提示。
     */
    public ExportCheckReport check(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return blocked("文件为空，请重新选择后再试");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            return blocked("文件超过 10MB，请压缩后再试");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return blocked("文件读取失败，请重试");
        }

        PDDocument document;
        try {
            document = Loader.loadPDF(bytes);
        } catch (InvalidPasswordException e) {
            return blocked("这份 PDF 设了打开密码，招聘系统同样打不开。请在导出时去掉密码保护后再试");
        } catch (IOException e) {
            return blocked("无法作为 PDF 打开：文件可能已损坏，或只是把别的格式改成了 .pdf 后缀");
        }

        int pageCount;
        int scannedPages;
        String text;
        try (PDDocument doc = document) {
            pageCount = doc.getNumberOfPages();
            scannedPages = Math.min(pageCount, MAX_SCANNED_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(scannedPages);
            text = stripper.getText(doc);
        } catch (IOException | RuntimeException e) {
            // PDF 是用户输入，结构异常时 PDFBox 可能抛 IOException 或 IllegalArgumentException
            return blocked("PDF 内容读取失败：文件结构异常，请重新导出后再试");
        }

        int charCount = countNonWhitespace(text);
        List<CheckItem> checks = new ArrayList<>();
        checks.add(pass("pdf-open", "PDF 结构", "可正常打开，共 " + pageCount + " 页"));
        checks.add(checkPages(pageCount));
        checks.add(checkTextLayer(charCount, pageCount, scannedPages));
        checks.add(checkContact(text));
        checks.add(checkSections(text));
        checks.add(checkTimeline(text));
        checks.add(checkGlyphs(text, charCount));

        return summarize(checks, pageCount, charCount);
    }

    private CheckItem checkPages(int pageCount) {
        if (pageCount <= 2) {
            return pass("pages", "页数", pageCount + " 页，在常见的 1-2 页范围内");
        }
        return warn("pages", "页数", pageCount + " 页：HR 初筛通常只看前 1-2 页，建议压到 2 页内");
    }

    private CheckItem checkTextLayer(int charCount, int pageCount, int scannedPages) {
        if (charCount == 0) {
            return fail("text-layer", "文字可复制",
                    "没有提取到任何文字：这份 PDF 很可能是截图或图片导出，招聘系统无法检索其中的内容");
        }
        if (charCount < TEXT_FAIL) {
            return fail("text-layer", "文字可复制",
                    "只提取到 " + charCount + " 个字，几乎不可检索：请用「导出 PDF」（浏览器打印）生成，"
                            + "而不是截图或图片转 PDF");
        }
        if (charCount < TEXT_OK) {
            return warn("text-layer", "文字可复制",
                    "只提取到 " + charCount + " 个字，偏少；请确认正文都在文字层，而不是图片里");
        }
        String scope = pageCount > scannedPages ? "（仅检查前 " + scannedPages + " 页）" : "";
        return pass("text-layer", "文字可复制", "提取到 " + charCount + " 个字" + scope);
    }

    private CheckItem checkContact(String text) {
        boolean email = EMAIL.matcher(text).find();
        boolean phone = PHONE.matcher(text).find();
        if (email && phone) {
            return pass("contact", "联系方式", "邮箱与手机号都能在文字层找到");
        }
        if (email || phone) {
            return warn("contact", "联系方式",
                    (email ? "只找到邮箱" : "只找到手机号") + "，建议邮箱和手机号都写上");
        }
        return warn("contact", "联系方式",
                "没有在文字层找到邮箱或手机号：招聘系统可能解析不到你的联系方式");
    }

    private CheckItem checkSections(String text) {
        List<String> found = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (Section section : SECTIONS) {
            if (section.keywords().stream().anyMatch(lower::contains)) {
                found.add(section.label());
            }
        }
        if (found.size() >= 2) {
            return pass("sections", "关键板块", "识别到：" + String.join(" / ", found));
        }
        if (found.size() == 1) {
            return warn("sections", "关键板块",
                    "只识别到「" + found.get(0) + "」：建议补齐教育、经历、项目、技能等板块标题");
        }
        return warn("sections", "关键板块",
                "没有识别到教育/经历/项目/技能等常见板块标题；如果你的简历用了别的写法，可以忽略这条");
    }

    private CheckItem checkTimeline(String text) {
        int years = countYears(text);
        if (years >= 2) {
            return pass("timeline", "时间信息", "检测到 " + years + " 处年份，经历起止时间可被读取");
        }
        return warn("timeline", "时间信息",
                "只检测到 " + years + " 处年份：建议为每段经历标注起止年月（如 2022.03 - 2024.06）");
    }

    private CheckItem checkGlyphs(String text, int charCount) {
        if (charCount == 0) {
            return warn("glyphs", "字符完整度", "没有文字可供检查");
        }
        int suspicious = countSuspiciousChars(text);
        if (suspicious == 0) {
            return pass("glyphs", "字符完整度", "未发现乱码或不可见字符");
        }
        double ratio = suspicious * 100.0 / charCount;
        if (ratio >= SUSPICIOUS_RATIO_FAIL) {
            return fail("glyphs", "字符完整度",
                    "检测到 " + suspicious + " 个乱码/不可见字符（约占 " + Math.round(ratio)
                            + "%）：多半是字体没有嵌入，请换一种常见字体或改用浏览器打印导出");
        }
        return warn("glyphs", "字符完整度",
                "检测到 " + suspicious + " 个不可见字符（如零宽空格），一般不影响阅读");
    }

    private ExportCheckReport summarize(List<CheckItem> checks, int pageCount, int charCount) {
        long fails = checks.stream().filter(c -> "fail".equals(c.status())).count();
        long warns = checks.stream().filter(c -> "warn".equals(c.status())).count();
        String verdict = fails > 0 ? "fail" : warns > 0 ? "warn" : "pass";
        String summary = switch (verdict) {
            case "fail" -> "有 " + fails + " 项必须先处理，否则招聘系统或 HR 可能读不到关键内容";
            case "warn" -> "这份 PDF 可以被读取，另有 " + warns + " 项建议优化";
            default -> "全部 " + checks.size() + " 项检查通过，这份 PDF 可以被招聘系统正常读取";
        };
        return new ExportCheckReport(verdict, summary, pageCount, charCount, checks);
    }

    private static ExportCheckReport blocked(String detail) {
        return new ExportCheckReport("fail", "无法完成体检：" + detail, 0, 0,
                List.of(new CheckItem("pdf-open", "PDF 结构", "fail", detail)));
    }

    private static CheckItem pass(String id, String label, String detail) {
        return new CheckItem(id, label, "pass", detail);
    }

    private static CheckItem warn(String id, String label, String detail) {
        return new CheckItem(id, label, "warn", detail);
    }

    private static CheckItem fail(String id, String label, String detail) {
        return new CheckItem(id, label, "fail", detail);
    }

    /** 文字层字符数：按码点计数并跳过空白，避免把排版留白算成「有内容」 */
    static int countNonWhitespace(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    /**
     * 统计乱码/不可见字符：控制字符、格式字符（零宽空格、BOM 等）、私用区字符、替换符 U+FFFD。
     *
     * <p>这四类在正常的浏览器打印 PDF 里都不该出现，一旦大量出现，几乎都是字体未嵌入
     * 或编码错误 —— 用户看到的是「正常汉字」，复制出来却是空白或方块。
     */
    static int countSuspiciousChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean lineBreak = cp == '\n' || cp == '\r' || cp == '\t';
            if (!lineBreak) {
                int type = Character.getType(cp);
                if (type == Character.CONTROL || type == Character.FORMAT
                        || type == Character.PRIVATE_USE || cp == 0xFFFD) {
                    count++;
                }
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    static int countYears(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = YEAR.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}

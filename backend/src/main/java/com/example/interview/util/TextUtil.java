package com.example.interview.util;

/**
 * 文本处理工具
 *
 * <p>统一各 Service 中重复的文本截断逻辑。原 {@code InterviewService} / {@code ResumeAnalysisService}
 * 内联三元表达式实现截断，{@code JobAnalysisService} 抽成 {@code truncate} 静态方法，
 * 三处逻辑等价但形态不一致且 null 防御不齐，本类提供统一实现。
 *
 * <p>所有方法 null 安全：{@code null} 入参返回空串，避免 NPE。
 */
public final class TextUtil {

    /** 默认省略符 */
    private static final String DEFAULT_ELLIPSIS = "...";

    private TextUtil() {}

    /**
     * 截断文本到指定长度，超长则尾部追加省略符 "..."。
     *
     * <p>语义与原各 Service 内联实现一致：
     * <pre>{@code
     * text.length() > maxLen ? text.substring(0, maxLen) + "..." : text
     * }</pre>
     *
     * @param text  原始文本，可为 null
     * @param maxLen 最大保留长度（不含省略符），必须 &gt;= 0
     * @return 截断后的文本；null 返回空串；长度未超则原样返回
     */
    public static String truncate(String text, int maxLen) {
        return truncate(text, maxLen, DEFAULT_ELLIPSIS);
    }

    /**
     * 截断文本到指定长度，超长则尾部追加自定义省略符。
     *
     * @param text     原始文本，可为 null
     * @param maxLen   最大保留长度（不含省略符），必须 &gt;= 0
     * @param ellipsis 省略符，null 视为空串
     * @return 截断后的文本；null 返回空串；长度未超则原样返回
     */
    public static String truncate(String text, int maxLen, String ellipsis) {
        if (text == null) return "";
        if (maxLen <= 0) return ellipsis == null ? "" : ellipsis;
        if (text.length() <= maxLen) return text;
        String suffix = ellipsis == null ? "" : ellipsis;
        return text.substring(0, maxLen) + suffix;
    }
}

package com.example.interview.util;

import java.util.regex.Pattern;

/**
 * 个人信息脱敏（P2-07，2026-10-30 质量循环）。
 *
 * <p><b>要解决什么</b>：知识库「自动补充」会把用户真实提问送进**共享**向量库
 * （{@code META_SHARED=true}，所有用户都能检索到）。而提问里经常夹带姓名、手机号、
 * 邮箱、身份证号、公司名与薪资 —— 这些内容会以「通用知识」的名义永久留在共享库里，
 * 被其他用户的 RAG 检索命中。这是隐私泄露，不是体验问题。
 *
 * <p><b>边界（必须诚实标注）</b>：这是**正则脱敏**，能拦住格式化明确的联系方式与证件号；
 * 它<b>拦不住</b>「我叫张三，在某某公司做…」这类自由文本里的姓名/公司。
 * 因此真正的防线是两层：① 本类做结构化 PII 掩码；② prompt 明确要求
 * 「不要复述提问、不要包含任何个人信息」。不做「已彻底匿名化」的承诺。
 *
 * <p>顺序很关键：先遮邮箱（邮箱里可能包含连续数字），再遮身份证（18 位），
 * 最后遮手机号（11 位）。否则 18 位证件号会被手机号规则切掉一部分。
 */
public final class PiiMasker {

    /** 邮箱：xxx@yyy.zzz */
    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    /** 身份证号：18 位，末位可为 X */
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 中国大陆手机号：1[3-9] 开头共 11 位 */
    private static final Pattern MOBILE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private PiiMasker() {
    }

    /**
     * 对文本做结构化 PII 掩码。
     *
     * @return 掩码后的文本；输入为 null 时返回 null（调用方自行决定空值语义）
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = EMAIL.matcher(text).replaceAll("[邮箱]");
        masked = ID_CARD.matcher(masked).replaceAll("[身份证号]");
        masked = MOBILE.matcher(masked).replaceAll("[手机号]");
        return masked;
    }
}

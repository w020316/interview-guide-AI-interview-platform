package com.example.interview.util;

import java.util.Locale;
import java.util.Set;

/**
 * 图片类型校验（P1/B-12，2026-09-20）
 *
 * <p>上传接口原先仅校验 {@code contentType.startsWith("image/")}：
 * <ul>
 *   <li>{@code image/svg+xml} 直接放行——SVG 是文本型 XML，可内嵌 {@code <script>}，
 *       写入 bucket 后被浏览器以 {@code image/svg+xml} 渲染即形成存储型 XSS；</li>
 *   <li>不校验文件头——把任意载荷改个 Content-Type 就能上传（伪装文件）。</li>
 * </ul>
 *
 * <p>现改为「白名单 contentType + magic bytes 双重校验」：两者必须一致才放行，
 * 扩展名由服务端根据校验结果决定，完全不信任客户端文件名。
 */
public final class ImageTypeValidator {

    /** 需要读入用于比对的文件头长度（WEBP 需要前 12 字节） */
    public static final int HEADER_LENGTH = 12;

    /** 允许上传的图片 Content-Type 白名单（刻意排除 image/svg+xml） */
    private static final Set<String> ALLOWED =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private ImageTypeValidator() {
    }

    /**
     * contentType 是否在白名单内（null 安全，大小写不敏感）
     */
    public static boolean isAllowed(String contentType) {
        return contentType != null && ALLOWED.contains(contentType.toLowerCase(Locale.ROOT));
    }

    /**
     * 按文件头 magic bytes 校验内容与声明类型是否一致。
     *
     * @param contentType 白名单内的图片类型
     * @param header      文件头字节（长度至少 {@link #HEADER_LENGTH}）
     * @return 一致时返回对应扩展名（.jpg/.png/.webp/.gif）；不一致或头太短返回 null
     */
    public static String resolveExtension(String contentType, byte[] header) {
        if (contentType == null || header == null || header.length < HEADER_LENGTH) {
            return null;
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> startsWith(header, 0xFF, 0xD8, 0xFF) ? ".jpg" : null;
            case "image/png" -> startsWith(header, 0x89, 'P', 'N', 'G') ? ".png" : null;
            case "image/gif" -> startsWith(header, 'G', 'I', 'F', '8') ? ".gif" : null;
            case "image/webp" -> startsWith(header, 'R', 'I', 'F', 'F')
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P'
                    ? ".webp" : null;
            default -> null;
        };
    }

    private static boolean startsWith(byte[] actual, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if ((actual[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}

package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageTypeValidator} 单元测试
 *
 * <p>回归防线（P1/B-12）：作答附图上传此前仅校验 {@code startsWith("image/")}，
 * SVG 直接放行（存储型 XSS）且不校验文件头（任意载荷伪装上传）。
 * 本测试固化「白名单 + magic bytes 双重校验」行为。
 */
class ImageTypeValidatorTest {

    private static byte[] header(int... prefix) {
        byte[] h = new byte[ImageTypeValidator.HEADER_LENGTH];
        for (int i = 0; i < prefix.length; i++) {
            h[i] = (byte) prefix[i];
        }
        return h;
    }

    // ───────────────────────── isAllowed：白名单 ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp", "image/gif", "IMAGE/PNG"})
    @DisplayName("isAllowed: 白名单内放行，大小写不敏感")
    void isAllowed_whitelisted_shouldPass(String contentType) {
        assertThat(ImageTypeValidator.isAllowed(contentType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "image/bmp", "text/html", "application/pdf", "image/"})
    @DisplayName("isAllowed: 白名单外拒绝（SVG 必须拒绝）")
    void isAllowed_notWhitelisted_shouldReject(String contentType) {
        assertThat(ImageTypeValidator.isAllowed(contentType)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("isAllowed: null/空串拒绝")
    void isAllowed_nullOrEmpty_shouldReject(String contentType) {
        assertThat(ImageTypeValidator.isAllowed(contentType)).isFalse();
    }

    // ───────────────────── resolveExtension：magic bytes ─────────────────────

    @ParameterizedTest
    @CsvSource({
            "image/jpeg, .jpg",
            "image/png,  .png",
            "image/gif,  .gif",
            "image/webp, .webp"
    })
    @DisplayName("resolveExtension: 真实图片头返回对应扩展名")
    void resolveExtension_realImageHeader_shouldReturnExtension(String contentType, String expected) {
        byte[] h = switch (contentType) {
            case "image/jpeg" -> header(0xFF, 0xD8, 0xFF, 0xE0);
            case "image/png" -> header(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/gif" -> header('G', 'I', 'F', '8', '9', 'a');
            case "image/webp" -> header('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
            default -> throw new IllegalArgumentException(contentType);
        };
        assertThat(ImageTypeValidator.resolveExtension(contentType, h)).isEqualTo(expected.trim());
    }

    @Test
    @DisplayName("resolveExtension: SVG 载荷 + image/svg+xml 声明 → 拒绝（存储型 XSS 回归）")
    void resolveExtension_svgPayload_shouldReject() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        byte[] h = svg.getBytes(StandardCharsets.UTF_8);
        // 即使有人把白名单放开，SVG 文本头也不可能匹配任何图片 magic bytes
        assertThat(ImageTypeValidator.isAllowed("image/svg+xml")).isFalse();
        assertThat(ImageTypeValidator.resolveExtension("image/svg+xml",
                java.util.Arrays.copyOf(h, ImageTypeValidator.HEADER_LENGTH))).isNull();
    }

    @Test
    @DisplayName("resolveExtension: 内容与声明类型不符 → 拒绝（防伪装文件）")
    void resolveExtension_mismatchedHeader_shouldReject() {
        byte[] pngHeader = header(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        assertThat(ImageTypeValidator.resolveExtension("image/jpeg", pngHeader)).isNull();
    }

    @Test
    @DisplayName("resolveExtension: 纯文本载荷伪装成图片 → 拒绝")
    void resolveExtension_textPayload_shouldReject() {
        byte[] h = "Hello World!".getBytes(StandardCharsets.UTF_8);
        assertThat(ImageTypeValidator.resolveExtension("image/png", h)).isNull();
    }

    @Test
    @DisplayName("resolveExtension: 头部长度不足 → 拒绝")
    void resolveExtension_shortHeader_shouldReject() {
        assertThat(ImageTypeValidator.resolveExtension("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8}))
                .isNull();
    }

    @Test
    @DisplayName("resolveExtension: null 头部 → 拒绝")
    void resolveExtension_nullHeader_shouldReject() {
        assertThat(ImageTypeValidator.resolveExtension("image/png", null)).isNull();
    }

    @Test
    @DisplayName("resolveExtension: 非 null 但非法的 contentType → 拒绝")
    void resolveExtension_unknownContentType_shouldReject() {
        byte[] h = header('B', 'M');
        assertThat(ImageTypeValidator.resolveExtension("image/bmp", h)).isNull();
    }
}

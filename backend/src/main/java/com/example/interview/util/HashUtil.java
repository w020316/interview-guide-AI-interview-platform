package com.example.interview.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 哈希工具
 *
 * <p>统一各 Service 中重复的 SHA-256 短哈希实现。原 {@code InterviewService} 和
 * {@code ResumeAnalysisService} 各持有一份完全相同的 {@code sha256} 私有方法，
 * 用于生成 Redis 缓存键，本类提供共享实现。
 *
 * <p><b>关键约束</b>：算法与截取长度（前 32 个十六进制字符）必须与原实现完全一致，
 * 否则线上 Redis 缓存键将全部 miss，导致缓存雪崩。异常降级路径
 * （返回 {@code String.valueOf(input.hashCode())}）也保持一致。
 */
public final class HashUtil {

    /** 短哈希保留长度（前 32 个十六进制字符 = 128 位，足够防碰撞） */
    private static final int SHORT_HASH_LENGTH = 32;

    private HashUtil() {}

    /**
     * SHA-256 短哈希：取前 32 个十六进制字符。
     *
     * <p>用于生成无碰撞的 Redis 缓存键。SHA-256 算法异常时降级为 {@code input.hashCode()}，
     * 保证流程不中断（仅牺牲少量碰撞概率）。
     *
     * @param input 原始输入，可为 null
     * @return 32 字符十六进制串；null 返回 "0"
     */
    public static String sha256Short(String input) {
        if (input == null) return String.valueOf(0);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, SHORT_HASH_LENGTH);
        } catch (Exception e) {
            // 降级用 hashCode，保证流程不中断
            return String.valueOf(input.hashCode());
        }
    }
}

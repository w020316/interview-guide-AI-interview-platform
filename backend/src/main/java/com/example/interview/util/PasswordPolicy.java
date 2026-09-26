package com.example.interview.util;

import java.util.Set;

/**
 * 注册密码强度策略。
 *
 * <h2>为什么需要（2026-09-26 第三轮 UX 测试 P3-02）</h2>
 *
 * <p>实测 {@code POST /api/auth/register} 用 {@code password=12345678} 返回
 * {@code code=200 success} —— 此前只校验长度 6~64，常见弱口令一律放行。
 *
 * <p>本类**只用于注册**，不介入登录：存量用户可能已经有弱密码，
 * 在登录路径上加校验会把人挡在门外（那是「改密码」流程该做的事）。
 *
 * <h2>规则（四条，都刻意保持「可解释」）</h2>
 *
 * <ol>
 *   <li>常见弱口令黑名单（不区分大小写）</li>
 *   <li>整串是同一个字符（{@code 111111} / {@code aaaaaa}）</li>
 *   <li>整串是连续序列（{@code 123456} / {@code abcdef} / 反向 {@code 654321}）</li>
 *   <li>包含用户名（用户名长度 ≥ 3 时），这是撞库最先试的组合</li>
 * </ol>
 *
 * <p>刻意**不做**「必须含大小写+数字+符号」这类复杂度规则：它会把
 * {@code correct horse battery staple} 这种长口令误杀，却拦不住 {@code Passw0rd!}。
 * 黑名单 + 长度下限的组合对真实攻击更有效，也不会逼用户把密码写在便签上。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** 常见弱口令（覆盖中文用户高频选择 + 国际榜单前几名，统一小写比较） */
    private static final Set<String> COMMON_WEAK = Set.of(
            "123456", "1234567", "12345678", "123456789", "1234567890",
            "111111", "000000", "666666", "888888", "123123", "112233",
            "abc123", "a123456", "123456a", "qwerty", "qwertyuiop", "asdfgh",
            "password", "passw0rd", "p@ssword", "iloveyou", "admin", "administrator",
            "root", "letmein", "welcome", "monkey", "dragon", "sunshine",
            "woaini", "woaini1314", "5201314", "1314520", "a123456789",
            "qazwsx", "zxcvbnm", "1qaz2wsx", "qwer1234", "test123", "test1234");

    /**
     * 校验密码。
     *
     * @param password 待校验密码（已确认非空）
     * @param username 用户名，用于「密码包含用户名」判断；可为空
     * @return 不合格时返回面向用户的中文原因；合格返回 {@code null}
     */
    public static String validate(String password, String username) {
        if (password == null || password.isEmpty()) {
            return "密码长度需 6-64 字符";
        }
        String lower = password.toLowerCase();

        if (COMMON_WEAK.contains(lower)) {
            return "密码过于常见，容易被猜到，请更换";
        }
        if (isSingleRepeatedChar(password)) {
            return "密码不能是重复的同一个字符";
        }
        if (isSequential(password)) {
            return "密码不能是连续的数字或字母（如 123456、abcdef）";
        }
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase())) {
            return "密码不能包含用户名";
        }
        return null;
    }

    /** 整串由同一个字符组成 */
    private static boolean isSingleRepeatedChar(String s) {
        char first = s.charAt(0);
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * 整串是连续序列（步长 +1 或 -1），且**必须混合不了其他字符**。
     *
     * <p>只对纯字母或纯数字生效：{@code a1b2c3} 这类不判为连续，
     * 否则会把正常密码误杀。
     */
    private static boolean isSequential(String s) {
        if (s.length() < 3) {
            return false;
        }
        boolean allDigit = s.chars().allMatch(Character::isDigit);
        boolean allLetter = s.chars().allMatch(Character::isLetter);
        if (!allDigit && !allLetter) {
            return false;
        }
        int step = s.charAt(1) - s.charAt(0);
        if (step != 1 && step != -1) {
            return false;
        }
        for (int i = 2; i < s.length(); i++) {
            if (s.charAt(i) - s.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}

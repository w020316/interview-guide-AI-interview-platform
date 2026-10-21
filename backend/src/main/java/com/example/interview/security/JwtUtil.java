package com.example.interview.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * JWT 工具类
 * - 签发 token（登录/注册成功后调用，可按管理员名单嵌入 ROLE_ADMIN/ROLE_USER）
 * - 解析 token（每次请求过滤器中调用）
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long expirationMs; // 默认 24 小时

    /** 管理员账号名单（逗号分隔）；命中者签发 ROLE_ADMIN。v1.31.4 新增 */
    @Value("${app.admin-usernames:}")
    private String adminUsernames;

    /** 启动时解析管理员名单缓存 */
    private volatile Set<String> adminUsernameSet = Set.of();

    /** JWT issuer / audience，防止 token 跨服务重放 */
    private static final String ISSUER = "interview-guide";
    private static final String AUDIENCE = "interview-guide-client";
    /** 角色 claim 名 */
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_USER = "ROLE_USER";

    /** 启动时校验 secret 长度，HS256 要求 >= 32 字节 */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret 长度不足，HS256 要求至少 32 字节。请检查 JWT_SECRET 环境变量。");
        }
        // P1-3（2026-10-21 质量循环）：只校验长度会放过「照抄 .env.example」的部署者。
        // 示例里的占位串有 47 字符，长度校验必然通过 → 得到一个**公开可知**的签名密钥，
        // 任何人都能签出 {"sub":"1","role":"ROLE_ADMIN"} 的 token 直接调 /api/admin/**。
        // 这里补两道：① 已知占位特征词 ② 低熵（全同字符 / 连续序列）。
        // 判定刻意保守：只拦「随机 base64 不可能出现」的模式，避免误杀真实密钥导致线上启动失败。
        String lower = jwtSecret.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                throw new IllegalStateException("JWT secret 使用了示例/占位值（含 \"" + marker
                        + "\"），任何人都能据此伪造 token。请用 `openssl rand -base64 48` 生成后写入 JWT_SECRET。");
            }
        }
        if (isLowEntropy(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT secret 过于简单（全同字符或连续序列），请用 `openssl rand -base64 48` 生成后写入 JWT_SECRET。");
        }
        adminUsernameSet = (adminUsernames == null || adminUsernames.isBlank())
                ? Set.of()
                : Arrays.stream(adminUsernames.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * 占位密钥特征词。刻意只取「随机 base64 不可能出现」的长词：
     * 随机串里出现 8 个连续指定字符的概率约为 1/64^8，可忽略；
     * 而 {@code your-} / {@code example} 这类短词容易误伤真实密钥，故不纳入。
     */
    private static final String[] PLACEHOLDER_MARKERS = {
            "change-this", "change-me", "changeme", "placeholder", "replace-me"
    };

    /**
     * 全同字符（如 {@code aaaa…}）视为低熵。
     *
     * <p>刻意**不**检测「连续序列」（{@code 0123456789abcdef}）：真实随机密钥里出现
     * 8~16 个连续字符的概率虽低，但测试夹具与人工构造的密钥经常包含这类片段，
     * 一刀切会误杀导致启动失败 —— 而启动失败比弱密钥的代价更高、更直接。
     * 占位词检测已覆盖「照抄示例」这个真实风险，此处只兜最极端的全同字符。
     */
    private static boolean isLowEntropy(String s) {
        return s == null || s.isEmpty() || s.chars().distinct().count() == 1;
    }

    /** 生成签名 Key */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 JWT（含真实用户信息）
     *
     * @param subject  用户标识（统一的 users.id）
     * @param username 用户名，用于对管理员名单判定角色（ROLE_ADMIN/ROLE_USER）
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(String subject, String username) {
        boolean admin = isAdminUsername(username);
        return Jwts.builder()
                .subject(subject)
                // jti：token 唯一标识（P1 2026-09-20）——登出黑名单按它精确吊销单个 token，
                // 不影响同一用户的其他会话
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim(CLAIM_ROLE, admin ? ROLE_ADMIN : ROLE_USER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    /** 从 token 中解析 subject（用户标识） */
    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 token 中解析 jti（P1 2026-09-20：登出黑名单依据）。
     * 升级前签发的旧 token 无该 claim，返回 null——调用方应视为「不可吊销」放行。
     */
    public String extractJti(String token) {
        String jti = parseClaims(token).getId();
        return jti == null || jti.isBlank() ? null : jti;
    }

    /** 从 token 中解析过期时间（epoch 毫秒），供黑名单计算条目存活期 */
    public long extractExpirationMs(String token) {
        return parseClaims(token).getExpiration().getTime();
    }

    /**
     * 判断用户名是否命中管理员保留名单。
     * 注册入口必须拒绝保留名，防止先注册名单用户名即可在签发时获得 ROLE_ADMIN（越权）。
     */
    public boolean isAdminUsername(String username) {
        return username != null && adminUsernameSet.contains(username);
    }

    /** 从 token 中解析角色（ROLE_ADMIN / ROLE_USER；旧 token 无该 claim 时按普通用户处理） */
    public String extractRole(String token) {
        Object role = parseClaims(token).get(CLAIM_ROLE);
        return role == null ? ROLE_USER : role.toString();
    }

    /**
     * 验证 token 是否有效（未过期且签名合法）
     * 区分异常类型并记录日志，便于排查认证失败原因
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT 签名无效: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT 格式错误: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
        } catch (Exception e) {
            log.error("JWT 校验异常", e);
        }
        return false;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

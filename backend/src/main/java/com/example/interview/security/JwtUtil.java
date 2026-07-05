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

/**
 * JWT 工具类
 * - 签发 token（登录/注册成功后调用）
 * - 解析 token（每次请求过滤器中调用）
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long expirationMs; // 默认 24 小时

    /** 启动时校验 secret 长度，HS256 要求 >= 32 字节 */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret 长度不足，HS256 要求至少 32 字节。请检查 JWT_SECRET 环境变量。");
        }
    }

    /** 生成签名 Key */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 JWT
     *
     * @param subject 用户标识（写入 subject，建议用 users.id）
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    /**
     * 从 token 中解析 subject（用户标识）
     */
    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
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
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

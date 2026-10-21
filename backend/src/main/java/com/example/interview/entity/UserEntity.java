package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 用户实体
 * 对应数据库表 users
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名（唯一） */
    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 加密后的密码 */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** 邮箱（可选，唯一） */
    @Column(name = "email", unique = true, length = 128)
    private String email;

    /** 注册时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 令牌版本（P1-2，2026-10-21）：登出 / 改密 / 封禁时 +1，
     * 使该用户此前签发的<b>所有</b> token 立即失效（JWT 本身无法撤销，只能靠版本比对）。
     *
     * <p><b>{@code @Builder.Default} 不能省</b>：本类用 Lombok {@code @Builder}，而
     * {@code @Builder} <b>不会</b>应用字段初始值 —— 漏了它，{@code UserEntity.builder()}
     * 构造出的对象 {@code tokenVersion} 是 {@code null}，写入时撞 DB 的 {@code NOT NULL}
     * 约束，而报错信息（约束违反）与真实原因（Lombok 行为）看起来毫无关系，极难定位。
     * {@code UserEntityTokenVersionTest} 有一条专门钉死这一点。
     */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Integer tokenVersion = 1;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

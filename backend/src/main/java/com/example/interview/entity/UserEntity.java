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

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户自持 AI Key 设置（第四批 · 竞品清单 #15）。
 *
 * <p>api_key_cipher 为 AES-256-GCM 密文（主密钥由 JWT_SECRET 派生，见 UserKeyCipher），
 * 数据库里**永远不存明文**；对外接口只回显掩码（前 2 + 后 4 位）。
 *
 * <p>base_url 为 OpenAI 兼容端点（必须 https，私网/保留地址在保存时被拒——SSRF 防护）；
 * model 为该端点上的模型名。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_ai_setting")
public class UserAiSettingEntity {

    /** 用户 ID（主键：一人一套自持配置） */
    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /** 自持 API Key 的密文（AES-256-GCM，base64(iv+ct)） */
    @Column(name = "api_key_cipher", nullable = false, columnDefinition = "TEXT")
    private String apiKeyCipher;

    /** OpenAI 兼容端点（必须 https；私网/保留地址在保存时被拒——SSRF 防护） */
    @Column(name = "base_url", nullable = false, length = 200)
    private String baseUrl;

    /** 模型名（该端点上的模型，如 glm-4-flash） */
    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

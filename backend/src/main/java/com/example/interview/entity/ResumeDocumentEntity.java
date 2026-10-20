package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 结构化简历文档实体（v1.67.0）
 * 对应数据库表 resume_document
 *
 * <p>与 {@link ResumeEntity} 的分工：
 * <ul>
 *   <li>{@code resume} = 用户<b>上传/粘贴</b>的原始简历文本 + AI 分析结果，只读快照；</li>
 *   <li>{@code resume_document} = 用户在<b>模板编辑器</b>里维护的结构化简历，可反复编辑并套用多套模板。</li>
 * </ul>
 *
 * <p>数据模型（借鉴 JSON Resume 分段，裁剪为 6 段）：
 * <pre>
 *   { basics:{name,title,phone,email,location,links[]}, summary,
 *     education[], experience[], projects[], skills[], extras[] }
 * </pre>
 * 经历类条目的要点一律是 {@code bullets[]} 数组而非整块富文本——
 * 这是<b>结构性约束</b>：防止编辑器退化成富文本编辑器（见产品评审「明确不做」）。
 *
 * <p>{@code doc_data} 列名刻意避开 {@code data}（SQL 保留字风险）。
 * JSON 映射沿用 {@link ResumeEntity#getAnalysisResult()} 的跨方言做法：
 * PostgreSQL 生成 jsonb、H2 生成 json，读写语义一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "resume_document")
public class ResumeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（来自 JWT subject） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 模板 ID，取值见 ResumeTemplateCatalog */
    @Column(name = "template_id", nullable = false, length = 40)
    private String templateId;

    /** 文档标题（用户自定义，便于区分「投 A 公司的版本」） */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 结构化简历 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "doc_data")
    private String docData;

    /** 来源：由哪份上传简历自动填充而来（可为空） */
    @Column(name = "source_resume_id")
    private Long sourceResumeId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

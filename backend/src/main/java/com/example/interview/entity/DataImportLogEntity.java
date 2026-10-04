package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据导入幂等日志实体（第三批 C）。
 *
 * <p>对应数据库表 {@code data_import_log}。用于保证「同一次导入（同一 {@code importId}）重复提交」
 * 不会二次落库：首次导入成功后写入一行，(user_id, import_id) 唯一；再次收到同 importId 的
 * 导入请求时直接返回上次的汇总结果，不重复写入。
 *
 * <p><b>为什么用持久表而不是内存 Map</b>：生产部署在 Render 免费层，实例会随闲置/发布重启，
 * 内存态幂等记录会丢失——重启后重复提交同一 importId 可能二次导入，正是本表要消除的风险。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "data_import_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_data_import_log_user_import",
                columnNames = {"user_id", "import_id"}),
        indexes = @Index(name = "idx_data_import_log_user", columnList = "user_id"))
public class DataImportLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID（JWT subject） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 客户端生成的导入唯一标识（幂等键） */
    @Column(name = "import_id", nullable = false, length = 64)
    private String importId;

    /** 导入模式：merge / replace */
    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    /** 本次导入的汇总（JSON 文本，供幂等重放直接返回） */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    /** 写入时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

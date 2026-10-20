package com.example.interview.repository;

import com.example.interview.entity.ResumeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 结构化简历文档 JPA Repository（v1.67.0）
 */
@Repository
public interface ResumeDocumentRepository extends JpaRepository<ResumeDocumentEntity, Long> {

    /** 我的简历文档列表，按最近更新倒序 */
    List<ResumeDocumentEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 按 ID + 归属查询。
     * 刻意不用 {@code findById}：归属校验必须在查询层完成，
     * 否则调用方漏判一次就是 IDOR（越权读取他人简历）。
     */
    Optional<ResumeDocumentEntity> findByIdAndUserId(Long id, String userId);

    /** 我的文档数量（用于上限保护） */
    long countByUserId(String userId);
}

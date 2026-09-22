package com.example.interview.repository;

import com.example.interview.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户 JPA Repository
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * 指定时间后注册的用户时间戳（v1.37.0，管理后台近 N 天注册趋势）。
     *
     * <p>取时间戳后在 Java 侧按天归组，避免依赖 `DATE()` 等方言相关函数
     * （H2 与 PostgreSQL 写法不一致，会造成「本地绿、线上红」）。
     */
    @Query("SELECT u.createdAt FROM UserEntity u WHERE u.createdAt >= :since")
    List<LocalDateTime> findCreatedAtSince(@Param("since") LocalDateTime since);
}

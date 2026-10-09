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
     * 按小写用户名集合查询（B-11）。
     *
     * <p>供管理员计数使用：此前用 {@code findAll()} 全表扫描再在内存过滤；
     * 改为 DB 侧按 lower(username) IN (...) 精确取回目标用户（通常 0~数条），
     * 避免随用户量增长的全表加载。调用方传入的集合须已小写。
     */
    @Query("SELECT u FROM UserEntity u WHERE lower(u.username) IN :names")
    List<UserEntity> findByLowerUsernameIn(@Param("names") java.util.Collection<String> names);

    /**
     * 指定时间后注册的用户时间戳（v1.37.0，管理后台近 N 天注册趋势）。
     *
     * <p>取时间戳后在 Java 侧按天归组，避免依赖 `DATE()` 等方言相关函数
     * （H2 与 PostgreSQL 写法不一致，会造成「本地绿、线上红」）。
     */
    @Query("SELECT u.createdAt FROM UserEntity u WHERE u.createdAt >= :since")
    List<LocalDateTime> findCreatedAtSince(@Param("since") LocalDateTime since);
}

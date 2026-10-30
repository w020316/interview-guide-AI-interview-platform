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

    /**
     * 令牌版本 +1（P1-2，2026-10-21）：登出 / 改密 / 封禁时调用，使该用户此前签发的
     * 所有 token 立即失效。
     *
     * <p>用 {@code UPDATE ... SET x = x + 1} 而不是「读实体 → set → save」：
     * 后者在两个并发登出请求下会互相覆盖（都读到 1，都写 2），等于只 +1 一次；
     * 原子自增则是 +2，语义正确。
     *
     * <p>{@code @Transactional} 必须加在本方法上：{@code @Modifying} 查询需要写事务，
     * 而调用方（登出、改密、封禁）不一定处于事务中。
     *
     * <p>{@code clearAutomatically + flushAutomatically}：批量 UPDATE 绕过持久化上下文，
     * 若不清理，同一事务/同一 EntityManager 里已加载的 {@code UserEntity} 仍是旧版本
     * （读到 1 而库里已是 2）。这不是理论问题——{@code UserRepositoryTokenVersionTest}
     * 的第一版就是靠真实执行把它照出来的。生产路径上「写后立刻读同一实体」目前不存在，
     * 但这层保护让后续任何人加代码时不会踩到。
     *
     * @return 受影响行数（0 表示该用户不存在）
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :id")
    int incrementTokenVersion(@Param("id") Long id);

    /**
     * 当前被封禁的用户 id 集合（P2-14）。
     *
     * <p>只投影 id，避免把用户实体整表拉进内存；启动时用它回填内存名单。
     * 若历史库还没有 banned 列（首次部署、DDL 尚未执行），调用方会收到异常并按空集合降级。
     */
    @Query("SELECT u.id FROM UserEntity u WHERE u.banned = true")
    List<Long> findBannedIds();

    /**
     * 写入封禁态（P2-14）。用原子 UPDATE 而不是「读实体 → set → save」：
     * 并发 ban/unban 时后者可能互相覆盖。
     *
     * @return 受影响行数（0 表示用户不存在）
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.banned = :banned WHERE u.id = :id")
    int updateBanned(@Param("id") Long id, @Param("banned") boolean banned);
}

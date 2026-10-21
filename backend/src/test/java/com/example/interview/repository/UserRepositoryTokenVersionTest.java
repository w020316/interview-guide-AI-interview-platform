package com.example.interview.repository;

import com.example.interview.entity.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserRepository#incrementTokenVersion(Long)} 的真实 SQL 测试（P1-2，2026-10-21）。
 *
 * <h2>为什么必须起 JPA 而不是 mock</h2>
 *
 * <p>这条查询是整条吊销链路的地基：登出/改密/封禁都靠它把版本 +1。它有两个只有真实
 * 执行才能验证的点：
 *
 * <ol>
 *   <li>{@code @Modifying} + {@code @Transactional} 是否真的落库（漏了注解会静默不动库，
 *       表现为「登出返回 200 但 token 还能用」——最坏的一种缺陷：看起来成功了）；</li>
 *   <li>{@code SET x = x + 1} 的原子自增语义（而不是读到内存再写回）。</li>
 * </ol>
 *
 * <p>用 {@code @DataJpaTest} + 内存库，不触真实数据库，也不需要 Spring Web 层。
 */
@DataJpaTest
@ActiveProfiles("local")
@DisplayName("UserRepository.incrementTokenVersion（P1-2）")
class UserRepositoryTokenVersionTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("自增后读回：1 → 2 → 3（连续登出会持续累加，不会互相覆盖）")
    void increment_appliesAndPersists() {
        UserEntity saved = userRepository.saveAndFlush(UserEntity.builder()
                .username("token-version-user")
                .passwordHash("hash")
                .build());
        assertThat(saved.getTokenVersion()).as("新建用户默认版本为 1").isEqualTo(1);

        assertThat(userRepository.incrementTokenVersion(saved.getId())).isEqualTo(1);
        assertThat(userRepository.findById(saved.getId()).orElseThrow().getTokenVersion())
                .as("一次自增后为 2（旧 token 的 ver=1 立即失效）").isEqualTo(2);

        assertThat(userRepository.incrementTokenVersion(saved.getId())).isEqualTo(1);
        assertThat(userRepository.findById(saved.getId()).orElseThrow().getTokenVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("用户不存在：返回 0 行，不抛异常（登出幂等性依赖这一点）")
    void increment_missingUser_returnsZero() {
        assertThat(userRepository.incrementTokenVersion(999_999L)).isZero();
    }

    @Test
    @DisplayName("自增不会影响其它用户（按 id 精确更新）")
    void increment_onlyAffectsTargetUser() {
        UserEntity a = userRepository.saveAndFlush(UserEntity.builder()
                .username("inc-user-a").passwordHash("h").build());
        UserEntity b = userRepository.saveAndFlush(UserEntity.builder()
                .username("inc-user-b").passwordHash("h").build());

        userRepository.incrementTokenVersion(a.getId());

        assertThat(userRepository.findById(a.getId()).orElseThrow().getTokenVersion()).isEqualTo(2);
        assertThat(userRepository.findById(b.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    }
}

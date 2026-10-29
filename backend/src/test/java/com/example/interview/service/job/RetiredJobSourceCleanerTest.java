package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 已退休数据源下架器测试（v1.89.0）。
 *
 * <p>覆盖三件在线上会真出事的事：
 * <ol>
 *   <li>开关关闭时**一次都不能**调用仓库——否则「恢复」流程（先关开关再改数据）
 *       会因为清理器继续下手而看起来无效；</li>
 *   <li>开关打开时用的是**逐字一致的 3 个平台名**——差一个空格就安静地匹配 0 行；</li>
 *   <li>仓库抛异常时 {@code clean()} 不抛、返回 0——本类是 {@code CommandLineRunner}，
 *       新库首次启动表可能还没建，把启动拖死是不可接受的。</li>
 * </ol>
 */
@DisplayName("已退休数据源下架器")
class RetiredJobSourceCleanerTest {

    private JobPostingRepository repository;
    private JobAgentProperties properties;
    private PlatformTransactionManager txManager;
    private RetiredJobSourceCleaner cleaner;

    @BeforeEach
    void setUp() {
        repository = mock(JobPostingRepository.class);
        properties = new JobAgentProperties();
        // 事务管理器用 mock：TransactionTemplate.execute 只依赖 getTransaction/commit，
        // mock 默认返回 null 且 commit 为空操作，恰好让被测逻辑在无真实事务下可测
        txManager = mock(PlatformTransactionManager.class);
        cleaner = new RetiredJobSourceCleaner(repository, properties, txManager);
    }

    @Test
    @DisplayName("常量与已删除适配器 platform() 的返回值逐字一致（含空格）")
    void retiredPlatforms_mustMatchLegacyPlatformNames() {
        // 这三个字符串就是 v1.88.0 及之前入库时写进 job_posting.platform 的原值。
        // 改动它们等于放弃对存量数据的清理，必须同时改 SQL 脚本并做数据核对。
        assertThat(RetiredJobSourceCleaner.RETIRED_PLATFORMS).containsExactly(
                "Arbeitnow 欧洲", "Jobicy 全球远程", "Himalayas 全球远程");
    }

    @Test
    @DisplayName("开关为 false 时：不触碰仓库，返回 0（这是「恢复」流程的前提）")
    void clean_disabled_doesNotTouchRepository() {
        properties.setRetiredCleanupEnabled(false);

        int count = cleaner.clean();

        assertThat(count).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("开关为 true 时：用精确的 3 个平台名调用下架，返回受影响行数")
    void clean_enabled_deactivatesExactlyTheRetiredPlatforms() {
        properties.setRetiredCleanupEnabled(true);
        when(repository.deactivateByPlatforms(anyCollection())).thenReturn(7);

        int count = cleaner.clean();

        assertThat(count).isEqualTo(7);
        verify(repository).deactivateByPlatforms(RetiredJobSourceCleaner.RETIRED_PLATFORMS);
    }

    @Test
    @DisplayName("仓库抛异常时：不抛、返回 0（表还没建也不能把启动拖死）")
    void clean_repositoryFails_doesNotThrow() {
        properties.setRetiredCleanupEnabled(true);
        when(repository.deactivateByPlatforms(anyCollection()))
                .thenThrow(new IllegalStateException("表 \"job_posting\" 不存在"));

        int count = cleaner.clean();   // 若这里抛异常，本用例直接变红
        assertThat(count).isZero();

        // run() 是 CommandLineRunner 的入口，同样不能抛——抛了 Spring 会中止启动
        assertThatCode(() -> cleaner.run()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("受影响行数为 0 时仍正常返回（已无残留 = 幂等生效，不是异常）")
    void clean_zeroAffected_isNotAnError() {
        properties.setRetiredCleanupEnabled(true);
        when(repository.deactivateByPlatforms(anyCollection())).thenReturn(0);

        assertThat(cleaner.clean()).isZero();
        verify(repository).deactivateByPlatforms(List.copyOf(RetiredJobSourceCleaner.RETIRED_PLATFORMS));
        verify(repository, never()).deleteStaleThirdParty(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}

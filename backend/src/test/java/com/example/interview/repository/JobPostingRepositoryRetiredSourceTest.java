package com.example.interview.repository;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.job.RetiredJobSourceCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位仓库「已退休数据源批量下架」测试（v1.89.0）。
 *
 * <p>用真实的 H2 仓库验证三条必须成立的性质：
 * <ol>
 *   <li>只命中这 3 个 platform，别的一行都不能动；</li>
 *   <li>是**下架**不是**删除**——表内总行数必须不变（这是「可恢复」的前提）；</li>
 *   <li>幂等——再调用一次必须返回 0，否则日志里的「下架 N 条」会长期虚高。</li>
 * </ol>
 *
 * <p>⚠️ 这三条断言是有「反向保护力」的：把 {@code deactivateByPlatforms} 的
 * {@code AND j.active = true} 去掉，第 3 条会红；把平台清单或 {@code IN} 改成别的，
 * 第 1 条会红；把 UPDATE 换成 DELETE，第 2 条会红。
 */
@DataJpaTest
@ActiveProfiles("local")
@DisplayName("岗位仓库：已退休数据源批量下架")
class JobPostingRepositoryRetiredSourceTest {

    /**
     * ⚠️ 刻意**不**在这里另抄一份平台名，而是直接引用生产常量。
     *
     * <p>若这里抄一份，那么「常量里写错一个字/少一个空格」这种最可能发生的错误就没人拦得住
     * ——测试会和错误实现一起错。种子数据用**硬编码的历史值**（模拟真实库里的行），
     * 下架调用用 {@code RETIRED_PLATFORMS}，两边对不上就是 0 行，用例立刻变红。
     */
    private static final List<String> RETIRED = RetiredJobSourceCleaner.RETIRED_PLATFORMS;

    @Autowired
    private JobPostingRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    private static JobPostingEntity job(String platform, String externalId) {
        return JobPostingEntity.builder()
                .platform(platform)
                .externalId(externalId)
                .title(platform + " 的岗位 " + externalId)
                .companyName("示例公司")
                .industry("互联网")
                .jobType("技术")
                .recruitType("SOCIAL")
                .active(true)
                .build();
    }

    /** 3 个退休 platform + 1 个内置精选 + 1 个国内官方源 */
    private void seed() {
        repository.saveAll(List.of(
                job("Arbeitnow 欧洲", "a1"),
                job("Jobicy 全球远程", "j1"),
                job("Himalayas 全球远程", "h1"),
                job("内置精选", "b1"),
                job("腾讯招聘", "t1")));
        repository.flush();
    }

    private Integer deactivate() {
        return new TransactionTemplate(txManager).execute(status ->
                repository.deactivateByPlatforms(RETIRED));
    }

    @Test
    @DisplayName("只下架这 3 个 platform；其余源的岗位保持 active；表内总行数不变（可恢复）")
    void deactivateByPlatforms_onlyTouchesRetiredSources_andKeepsRows() {
        seed();
        long before = repository.count();
        assertThat(before).isEqualTo(5);

        Integer affected = deactivate();

        assertThat(affected).isEqualTo(3);
        // 「下架」而非「删除」：行数必须原样保留，否则历史岗位再也恢复不回来
        assertThat(repository.count()).isEqualTo(before);

        List<JobPostingEntity> all = repository.findAll();
        assertThat(all)
                .filteredOn(j -> RETIRED.contains(j.getPlatform()))
                .extracting(JobPostingEntity::getActive)
                .containsExactlyInAnyOrder(false, false, false);
        assertThat(all)
                .filteredOn(j -> !RETIRED.contains(j.getPlatform()))
                .extracting(JobPostingEntity::getActive)
                .containsExactlyInAnyOrder(true, true);
    }

    @Test
    @DisplayName("幂等：第二次调用返回 0（日志里的「下架 N 条」才有观测价值）")
    void deactivateByPlatforms_isIdempotent() {
        seed();

        assertThat(deactivate()).isEqualTo(3);
        assertThat(deactivate()).isZero();
        // 幂等调用不能把任何一行误删
        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("下架后这 3 个源不再出现在「数据来源」筛选列表（前端 meta 不再露出死源）")
    void findDistinctPlatforms_hidesRetiredSources() {
        seed();
        assertThat(repository.findDistinctPlatforms())
                .contains("Arbeitnow 欧洲", "Jobicy 全球远程", "Himalayas 全球远程");

        deactivate();

        assertThat(repository.findDistinctPlatforms())
                .contains("内置精选", "腾讯招聘")
                .doesNotContain("Arbeitnow 欧洲", "Jobicy 全球远程", "Himalayas 全球远程");
    }

    @Test
    @DisplayName("只传部分平台名时，只下架被传入的那些（平台名必须逐字匹配，含空格）")
    void deactivateByPlatforms_matchesExactNamesOnly() {
        seed();

        // 少一个空格 —— 故意制造「看起来一样」的错误写法，必须一行都匹配不到
        Integer affected = new TransactionTemplate(txManager).execute(status ->
                repository.deactivateByPlatforms(List.of("Arbeitnow  欧洲")));

        assertThat(affected).isZero();
        assertThat(repository.countByActiveTrue()).isEqualTo(5);
    }
}

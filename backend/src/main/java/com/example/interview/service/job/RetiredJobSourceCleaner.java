package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.repository.JobPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 已退休数据源的存量岗位下架器（v1.89.0，2026-10-29）。
 *
 * <p><b>背景</b>：v1.89.0 下线了 {@code ArbeitnowJobProvider} / {@code JobicyJobProvider} /
 * {@code HimalayasJobProvider} 三个海外源（单源配额收紧到 10 后，它们的产出与维护成本
 * 不成比例）。适配器删除是干净的，但**库里的历史岗位没有跟着消失**。
 *
 * <p><b>为什么必须处理，而且不能拖</b>：「海外 / 远程」分栏的源清单由适配器声明推导
 * （{@code JobAgentService#overseasPlatforms()} 遍历 {@code JobPlatformAdapter#overseas()}）。
 * 这 3 个名字从清单里消失后会发生三件坏事：
 * <ol>
 *   <li>国内分栏的判据是 {@code platform NOT IN (海外源)} —— 名字不在清单里，
 *       于是这些英文岗位被**当成国内岗位**显示，比「不显示」错得更离谱；</li>
 *   <li>{@code findDistinctPlatforms()} 仍把它们列进「数据来源」筛选，点进去必然空列表；</li>
 *   <li>要等 60 天陈旧下架规则（{@code deactivateStaleThirdParty}）才自然消失——
 *       而下架后 {@code updatedAt} 不再刷新，这段时间里 UI 一直是错的。</li>
 * </ol>
 *
 * <p><b>为什么是下架而不是物理删除</b>：下架可恢复（把 {@code active} 改回 true 即可），
 * 物理删除没有任何回退路径，而这些行里的岗位正文与直达申请链接仍然有价值。
 * 「只下架、不删除」也是本项目 P1-6 之后对第三方数据的一贯口径。
 *
 * <p><b>为什么不在「查询层加一张黑名单」</b>：查询层做过滤意味着
 * {@code search} / {@code computeMeta} / {@code findDistinctPlatforms} / 简历匹配
 * **每一处**都要记得排除这 3 个名字，散落在多处就一定会漏一处，而漏掉的那一处
 * 不会报错、只会悄悄显示错误数据。把状态写进数据（{@code active=false}）只有一处事实来源。
 *
 * <p><b>为什么放在启动时而不是每轮刷新里</b>：这是一次性的数据迁移，
 * 每轮刷新都跑一遍纯属下限为零的重复劳动；而且它必须发生在用户能看到列表**之前**
 * （刷新是启动后 60 秒才跑第一轮，中间这段时间窗口正好会被用户看到错误的分栏）。
 *
 * <p><b>幂等</b>：下架后行满足 {@code active=false}，{@code deactivateByPlatforms} 带
 * {@code AND active = true} 条件，因此第二次执行返回 0，日志会如实写「下架 0 条」。
 *
 * <p><b>恢复流程</b>（开关就是为它而存在，三步缺一不可）：
 * <ol>
 *   <li>先关开关：{@code JOB_RETIRED_CLEANUP_ENABLED=false}；
 *   <li>再改数据：{@code UPDATE job_posting SET active = true WHERE platform = '...'}；
 *   <li>最后补回适配器并恢复开关。
 * </ol>
 * 若跳过第 1 步，改回 {@code active=true} 的行会在下次重启时被本类再次下架——
 * 表现为「恢复没生效」，实际是清理器在下手。
 *
 * <p><b>可恢复窗口：180 天，从「最后一次真实抓到」起算（不是从本次下架起算）</b>：
 * 本次下架**刻意不刷新 {@code updatedAt}**，因此这些行的 {@code updated_at} 仍停在
 * 下线前最后一次被上游返回的时刻；一旦满足 {@code updatedAt < now - 180d}，
 * 就会被既有的 {@code deleteStaleThirdParty} 物理清理。
 * <ul>
 *   <li>对「下线前仍在上游正常返回」的行：恢复窗口≈从下线这一刻起的 180 天；</li>
 *   <li>对「下线前就已不再被上游返回」的行（外部源按页抓取，很常见）：
 *       窗口**短于** 180 天，极端情况下可能在下架后不久就被清理。</li>
 * </ul>
 * 所以「下架」不等于「永久保留」，也说不上精确的半年反悔期——要恢复请尽早做，
 * 且必须先查一下目标行的 {@code updated_at}（见运维脚本第 ① 段的统计查询）。
 * 这是有意为之：物理清理是防止库无界增长的最终保障。
 */
@Component
public class RetiredJobSourceCleaner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RetiredJobSourceCleaner.class);

    /**
     * 已退休数据源的 platform 取值（<b>单一来源</b>，勿在别处再抄一份）。
     *
     * <p>⚠️ 必须与已删除适配器 {@code platform()} 的返回值**逐字一致**（含空格）：
     * {@code ArbeitnowJobProvider → "Arbeitnow 欧洲"}、
     * {@code JobicyJobProvider → "Jobicy 全球远程"}、
     * {@code HimalayasJobProvider → "Himalayas 全球远程"}。
     * 差一个空格就匹配不到，且不会有任何报错——只会安静地「下架 0 条」。
     * 运维脚本 {@code scripts/sql/2026-10-29-retire-overseas-job-sources.sql} 里的清单
     * 必须与本常量同步修改。
     *
     * <p>刻意声明为 {@code public}：仓库层单测要拿它当作「下架入参」，与硬编码的历史
     * platform 值对撞——若这里写错一个字，单测会直接变红。若改成包私有，测试就只能
     * 再抄一份，两边一起写错却全绿（正是本类要防的那类安静失败）。
     */
    public static final List<String> RETIRED_PLATFORMS = List.of(
            "Arbeitnow 欧洲",
            "Jobicy 全球远程",
            "Himalayas 全球远程"
    );

    private final JobPostingRepository repository;
    private final JobAgentProperties properties;
    private final TransactionTemplate transactionTemplate;

    public RetiredJobSourceCleaner(JobPostingRepository repository,
                                   JobAgentProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        // @Modifying 查询必须在事务内执行；与 JobAgentService 的清理查询保持同一写法
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        clean();
    }

    /**
     * 执行下架。
     *
     * <p><b>启动阶段绝不允许抛异常</b>：本类与 {@code SchemaInitializer} 都是
     * {@code CommandLineRunner}，执行顺序不保证。全新库首次启动时 {@code job_posting}
     * 可能还没建表，此时下架会失败——这属于「预期内的失败」，记 {@code warn} 即可，
     * 下一轮重启会补上（本方法幂等）。
     *
     * <p><b>为什么 try/catch 必须在 {@code transactionTemplate} 外面</b>：
     * {@code @Modifying} 查询抛异常时 Spring 会把该事务标成 rollback-only，即使 catch 住
     * 异常，提交阶段仍会抛 {@code UnexpectedRollbackException}。若 catch 写在
     * {@code @Transactional} 方法内部，这个提交期异常会在方法返回**之后**才冒出来，
     * 谁也拦不住，直接把启动拖死。放在外层，异常在 execute() 调用点就被接住。
     *
     * @return 本次真正被下架的行数；开关关闭或执行失败时为 0
     */
    public int clean() {
        if (!properties.isRetiredCleanupEnabled()) {
            // 明确打日志：开关关掉与「代码没跑」在观感上必须区分开，否则出问题时
            // 第一个怀疑方向会错（本项目的「安静失败」铁律）
            log.info("已退休数据源下架器已按配置关闭（app.job-agent.retired-cleanup-enabled=false），"
                    + "本次不下架 {} 的存量岗位", RETIRED_PLATFORMS);
            return 0;
        }
        Integer affected;
        try {
            affected = transactionTemplate.execute(status ->
                    repository.deactivateByPlatforms(RETIRED_PLATFORMS));
        } catch (Exception e) {
            log.warn("已退休数据源存量岗位下架失败（不影响启动，下次重启会重试）：{}", e.getMessage());
            return 0;
        }
        int count = affected == null ? 0 : affected;
        // count = 0 也必须打日志：它有两种含义——「已经没有残留（幂等生效）」或
        // 「平台名一个都没匹配上（常量与历史数据不一致）」。没有这行日志，二者无法区分。
        log.info("已退休数据源存量岗位下架完成：本次下架 {} 条（数据源：{}）；"
                + "若反复为 0 请核对 platform 取值是否与历史数据逐字一致", count, RETIRED_PLATFORMS);
        return count;
    }
}

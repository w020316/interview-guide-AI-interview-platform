-- =============================================================================
-- 2026-10-29 · v1.89.0 · 下线三个海外岗位数据源（存量数据下架，可恢复）
-- =============================================================================
--
-- 【用途】
--   v1.89.0 删除了 ArbeitnowJobProvider / JobicyJobProvider / HimalayasJobProvider
--   三个适配器（单源配额收紧到 10 后，它们是单位收益最低的三个源）。
--   适配器删除不会影响库里的历史行，本脚本用于把这三个 platform 的存量岗位
--   **下架（active = false）**，而不是物理删除。
--
--   应用启动时 RetiredJobSourceCleaner 也会做同样的事；
--   本脚本用于两种情况：
--     ① 需要在**不重启服务**的情况下立即生效（线上窗口期）；
--     ② 需要在下架前/后留一份人工可核对的统计与回滚记录。
--
-- 【与 Java 常量的一致性约定】
--   下面的平台名必须与
--     backend/src/main/java/com/example/interview/service/job/RetiredJobSourceCleaner.java
--   里的 RETIRED_PLATFORMS **逐字一致（含空格）**：
--     "Arbeitnow 欧洲" / "Jobicy 全球远程" / "Himalayas 全球远程"
--   差一个空格就匹配不到，而且**不会报错**——只会「更新 0 行」。
--   改任何一边都必须同步改另一边。
--
-- 【方言】
--   PostgreSQL（生产库 Supabase）。
--
-- 【可恢复窗口：180 天，从「最后一次真实抓到」起算（不是从本次下架起算）】
--   本脚本刻意**不刷新 updated_at**：这些行的时间戳仍停在最后一次被上游返回的时刻。
--   一旦满足 updated_at < now() - 180 天，就会被既有的
--   JobAgentService → deleteStaleThirdParty 物理清理（防库无界增长的有意设计）。
--     · 下线前仍在上游正常返回的行 → 恢复窗口≈从下线这一刻起的 180 天；
--     · 下线前就已不再被上游返回的行（外部源按页抓取，很常见）→ 窗口**更短**，
--       极端情况下可能在下架后不久就被清理。
--   所以「下架」不等于永久保留。恢复前请先跑第 ① 段看 oldest_update 判断真实剩余窗口；
--   窗口内的恢复操作见文件末尾「回滚块」。
--
-- 【执行顺序】① 统计 → ② 下架 → ③ 校验 →（如需回滚）④ 回滚
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ① 下架前统计：确认这 3 个 platform 在库里的规模（total = 全部行，active = 仍在展示的行）
--    预期：total > active（因为 60 天陈旧规则可能已下架一部分）；如果 total = 0，
--    说明该 platform 从未入库或已被 180 天规则清理，可以跳过 ② ③。
-- -----------------------------------------------------------------------------
SELECT platform,
       COUNT(*)                                            AS total_rows,
       COUNT(*) FILTER (WHERE active IS TRUE)              AS active_rows,
       MIN(updated_at)                                     AS oldest_update,
       MAX(updated_at)                                     AS newest_update
FROM job_posting
WHERE platform IN ('Arbeitnow 欧洲', 'Jobicy 全球远程', 'Himalayas 全球远程')
GROUP BY platform
ORDER BY platform;


-- -----------------------------------------------------------------------------
-- ② 下架（**只改 active，不 DELETE**）
--    AND active IS TRUE：只统计「本次真正被下架」的行数，便于核对；
--    同时保证脚本可重复执行（第二次执行更新 0 行）。
--    注意：本语句刻意**不**更新 updated_at —— 保持原时间戳，180 天物理清理的窗口
--    才会从「最后一次真实抓到」起算，而不是从「我们下架它」的时刻重新起算。
-- -----------------------------------------------------------------------------
UPDATE job_posting
SET active = false
WHERE platform IN ('Arbeitnow 欧洲', 'Jobicy 全球远程', 'Himalayas 全球远程')
  AND active IS TRUE;
-- 期望：UPDATE 3（实际数字以 ① 的 active_rows 合计为准）


-- -----------------------------------------------------------------------------
-- ③ 下架后统计 + 校验
--    校验口径：
--      · active_rows 必须为 0（否则有并发写入又把这些源抓回来了——检查适配器是否真的删干净）；
--      · total_rows 必须与 ① 完全一致（**证明只下架、未删除**，数据仍可恢复）。
-- -----------------------------------------------------------------------------
SELECT platform,
       COUNT(*)                                            AS total_rows,
       COUNT(*) FILTER (WHERE active IS TRUE)              AS active_rows
FROM job_posting
WHERE platform IN ('Arbeitnow 欧洲', 'Jobicy 全球远程', 'Himalayas 全球远程')
GROUP BY platform
ORDER BY platform;

-- 顺带确认：这三个源不再出现在前端「数据来源」筛选里
-- （findDistinctPlatforms 只取 active = true 的行）
SELECT DISTINCT platform
FROM job_posting
WHERE active IS TRUE
  AND platform IN ('Arbeitnow 欧洲', 'Jobicy 全球远程', 'Himalayas 全球远程');
-- 期望：0 行


-- -----------------------------------------------------------------------------
-- ④ 回滚块（默认注释掉，确实要恢复某个源时再手工执行）
--
--   顺序不能反：**先关应用侧开关，再改数据**。
--   原因是 RetiredJobSourceCleaner 在每次启动时都会幂等执行下架；
--   如果先把 active 改回 true 而不关开关，下一次重启（或部署）就会把它重新下架，
--   现象是「恢复没生效」，而日志里只会写「下架 N 条」，很容易查错方向。
--
--   第 1 步（运维/配置侧，不是 SQL）：
--     设置环境变量 JOB_RETIRED_CLEANUP_ENABLED=false 并重启服务
--     （等价于 app.job-agent.retired-cleanup-enabled=false）。
--     设置后启动日志会出现：
--       「已退休数据源下架器已按配置关闭（...），本次不下架 [...] 的存量岗位」
--
--   第 2 步（SQL）：
--     UPDATE job_posting
--     SET active = true
--     WHERE platform IN ('Arbeitnow 欧洲', 'Jobicy 全球远程', 'Himalayas 全球远程');
--
--   第 3 步：补回对应适配器（provider 类 + 平台注册），再恢复开关
--     （JOB_RETIRED_CLEANUP_ENABLED=true 或直接删掉该环境变量）。
--     只恢复数据不恢复适配器的话，这些岗位会像 v1.88.0 之前一样长期不刷新，
--     180 天后仍会被物理清理。
--
--   提醒：这些行的 updated_at 停留在下线前的时间。若下线已超过 60 天，
--   恢复后 JobAgentService 的下一轮刷新（deactivateStaleThirdParty，60 天判据）
--   会立刻再次把它们标记 active = false —— 也就是说**恢复必须配合适配器一起做**，
--   单独恢复数据在刷新一轮后就失效了。
-- -----------------------------------------------------------------------------

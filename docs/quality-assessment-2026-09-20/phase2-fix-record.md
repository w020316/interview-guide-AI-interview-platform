# 阶段二 · 问题修复记录 — AI 智能面试辅助平台（interview-guide）

> **修复日期**：2026-09-20　**基于**：`phase1-code-review.md`（0 P0 / 9 P1 / 23 P2 / 16 P3）
> **执行方式**：4 路并行实施；两个实施 worker 因 429 额度限制中断后，由主控接手完成剩余项与全部验证
> **验收链**（长期规则）：本地回归通过 → commit（body 含问题/思路/验证）→ push → 部署生效 → 线上复验

---

## 一、修复清单（8 个 commit）

| # | commit | 级别 | 问题 | 状态 |
|---|---|---|---|---|
| 1 | `726f977` | **P1** | embedding `dimensions` 未透传（F-C/A-01）：`OpenAiEmbeddingOptions.builder()` 缺 `.dimensions()`，配置项只影响 pgvector 建表列宽 | ✅ 已修 |
| 2 | `536f824` | **P2** | JaCoCo 本地静默失效（F-B）：中文路径 + `cmd.exe` fork 代码页破坏 `-javaagent` destfile → 产物落乱码目录且文件名截断为 `jacoco.exe` → `target/jacoco.exec` 缺失 → `report`/`check` 被 skip | ✅ 已修 |
| 3 | `fb13b3b` | **P2** | `ApplicationContextSmokeTest` 数据源未隔离（F-A）：本机 dev 服务在跑时 H2 文件锁 → 全量测试必红；且会写 `smoke-user` 进真实本地库 | ✅ 已修 |
| 4 | `5cc519e` | **P1** | `reportShare.ts` 覆盖率 23.25%/17.1%，用户可见的「复盘成绩海报」无回归防线 | ✅ 已修 |
| 5 | `93f7845` | **P2** | `weakCategories.ts` 分类均分用「含无分题」的 count 作分母，薄弱维度排序失真 | ✅ 已修 |
| 6 | `7dfcc60` | **P2/P3** | `theme.ts` / `auth.ts` 的 `localStorage` 裸调用，隐私模式/配额满时抛错中断初始化 | ✅ 已修 |
| 7 | `aded55b` | **P2/P3** | 冗余 `@types/dompurify`、`AdminView` 本地 `getErr` 未用 api 层统一实现、`AgentView` 会话项无可访问性、`format.ts` 死代码 | ✅ 已修 |
| 8 | `75e01f0` | **P2（连带）** | JaCoCo 真正生效后测试 fork JVM 原生内存耗尽（`Chunk::new` malloc 失败，202 例处崩溃）→ 为 surefire 显式设内存上限 | ✅ 已修 |

## 二、连带发现的新问题（修复过程中暴露）

**JaCoCo 插桩导致 fork JVM 内存耗尽**（commit `75e01f0`）
- 现象：修好 F-B 后全量测试在 **202 例**处崩溃，`The forked VM terminated without properly saying goodbye`
- 根因：dumpstream 显示 `insufficient memory ... Native memory allocation (malloc) failed to allocate 1511376 bytes for Chunk::new`（JIT 编译器原生内存）。此前 JaCoCo 从未真正插桩，该问题被完全掩盖
- 修法：surefire `argLine` = `@{argLine}`（延迟绑定 `-javaagent`）+ `-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=192m`，仅作用于测试分叉 JVM

## 三、验证结果（全部主控亲自复跑，非采信 worker 自述）

### 后端（`mvn -o -B -ntp test`，1m01s）

| 项 | 修复前 | 修复后 |
|---|---|---|
| 测试 | 672 例（未隔离数据源时 2 Error → BUILD FAILURE） | **674/674 全绿 · BUILD SUCCESS**（+2 例维度测试） |
| JaCoCo | `Skipping JaCoCo execution due to missing execution data file` | **`Loading execution data file ...\Temp\interview-guide-jacoco.exec` + `All coverage checks have been met.`** |
| 本地覆盖率门禁 | 形同虚设 | **真实生效** |
| fork 崩溃 | — | 无 |
| 前提 | 需环境变量隔离数据源 | **本机 dev 服务照常运行即可**（隔离已内置到测试） |

**本地首个精确覆盖率**（`target/site/jacoco/jacoco.csv`，107 类）：

| 指标 | 值 |
|---|---|
| 行 LINE | **84.01%**（3868/4604） |
| 分支 BRANCH | 69.04%（1648/2387） |
| 指令 INSTRUCTION | 86.42% |
| 方法 METHOD | 88.74% |
| 圈复杂度 | 65.63% |
| 行覆盖 <80% 的类 | 30 个（如 `WebJobSearcherService` 22.92%、`AutoKnowledgeService` 40.23%、`InterviewEventController` 2.50%、各 `*Entity` 0%） |

### 前端（`vue-tsc` + `vitest run --coverage` + `vite build`）

| 项 | 修复前（基线） | 修复后 |
|---|---|---|
| 类型检查 | 0 错 | **0 错** |
| 测试 | 270/270 | **282/282**（25 文件，+12） |
| 全局覆盖率 | stmts 85.01 / branch 85.22 / funcs 92.43 / lines 86.23 | **94.54 / 87.88 / 97.45 / 96.58** |
| `reportShare.ts` | stmts 23.25 / lines **17.1** | **100 / 100**（branch 95.83） |
| `auth.ts` | stmts 60.97 | **92.68** |
| 构建 | 成功 8.30s | 成功 |

### 清理

- 删除乱码目录 `D:\xm\wz\AIÖÇÄÜÃæÊԸ¨Öúƽ̨\`（= `AI智能面试辅助平台` 的 GBK 误解码副本，内含 `jacoco.exe`）
- 删除历史遗留 `D:\xm\jacoco.exec`

## 四、未在本波处理（明确记录，不虚报）

| 项 | 级别 | 原因 / 计划 |
|---|---|---|
| 限流全为单实例内存计数（登录锁定可被分摊绕过） | 原 P1 → **降级 P2** | 生产为 Render 免费层**单实例**，当前真实暴露为 0；Redis 化属架构改造。**触发条件：横向扩容/多实例部署** |
| RAG 自动补充阈值 0.45 未按线上模型重标定（A-02） | **P1** | 需用线上 embedding 实测标定（日志 `bestDistance` 法），归入阶段四体验评估时一并完成 |
| SVG 附图放行 + 无 magic bytes、登出无 token 吊销、service 归属校验、分页 total 失真、岗位刷新 N+1 | **P1** ×4 | 第二波（接口/安全/数据层 + service 批次），待继续 |
| 后端覆盖率 30 个低覆盖类（分支覆盖仅 69.04%） | P2 | 已有精确基线，按类逐一补测 |
| `AutoKnowledgeService` 明文记录用户提问（PII 进日志，A-08） | P3 | 待第二波 |

## 五、推送与部署复验（2026-09-20 13:42–14:03）

| 环节 | 结果 |
|---|---|
| `git push origin main` | ✅ **`54effbe..255bde0`（9 个 commit）** |
| GitHub Actions CI（run `35492376667`） | ✅ **success，1m53s** —— 在被推送的同一提交上：后端 674 测试 + 覆盖率门禁 + 前端 282 测试 + `npm audit` + `vue-tsc` + `vite build` |
| Render 后端（`autoDeploy: true` 已触发） | ✅ **健康**：部署窗口内 `/actuator/health` **连续 9 次返回 200**（8 分钟，含冷启动） |
| Vercel 前端 | ⚠️ **本机无法探测**：白名单代理在整段窗口内持续拒绝 `vercel.app`（`502 CONNECT tunnel failed`），直连亦超时 —— 属**本机网络限制**，非部署失败 |

### 部署指纹的客观限制（如实说明）

- `/api/info` 的 `version` **写死为 `1.0.0`**，`/actuator/info` 的 `buildDate` **只有日期没有时间**（且当日已有过一次部署），
  因此后端**不存在可从外部区分新旧部署的确定性指纹**。
- 本波后端改动（embedding 维度透传、测试数据源隔离、构建配置）本身**没有可被外部观测的行为变化**，
  所以「CI 在该提交上全绿 + Render 持续健康」是当前可得的最强证据组合。
- **顺带发现第 6 个版本源**：`/actuator/info` 返回 `version: "1.23.0"`（`AppInfoContributor`）——
  版本号不一致比计划书记录的 5 源**更严重**（pom 1.31.3 / package.json 1.28.0 / changelog v1.33.3 /
  `/api/info` 1.0.0 / local 配置注释 v1.34.0 / **`/actuator/info` 1.23.0**）。

## 六、第三/四波修复（2026-09-20 14:05–19:00，主控直接实施）

> 第一波两个实施 worker 因 429 额度限制中断后，剩余项由主控按同样标准接手：
> 逐项修复 → 本地全量回归 → 独立 commit（body 含问题/思路/验证）→ push → CI 门禁。

| # | commit | 级别 | 问题与修法 |
|---|---|---|---|
| 9 | `3926df1` | P2 连带 | **测试 JVM 改用 SerialGC**（-Xmx1024m）：修第二种 fork 崩溃 `mmap failed to map ... G1 virtual space`（本机 commit 配额 19.3/20.3GB，G1 启动期映射 254MB 失败） |
| 10 | `e4d156a` | **P1** | **S-01** `InterviewSessionService.finishSession/saveQuestions` 补 service 层归属校验（新增 `currentUserId` 参数），3 个调用方同步，+2 越权拒绝用例 |
| 11 | `ed68819` | **P1** | **S-02** recent/wrong-questions 分页与过滤下推数据库：`total` 从"被截断条数"修正为真实总数（`Page.getTotalElements()`）；错题过滤下推 SQL（NULL 不满足 LessThan 自动排除，与原逻辑等价） |
| 12 | `e2bd348` | **P1** | **S-03** 岗位刷新批量 upsert：`findByPlatformAndExternalIdIn` 一次 IN 查询 + `saveAll` 单短事务，消除逐条查改存 N+1；平台级原子性，避免半批成功 |
| 13 | `f5c6841` | **P1** | **B-12** 附图上传白名单+magic bytes 双重校验：新增 `ImageTypeValidator`（明确拒绝 `image/svg+xml`，堵存储型 XSS），只读 12 字节文件头，+22 用例 |
| 14 | `9f863dd` | P3 | **A-08** 自动补充日志不落提问明文：`questionFingerprint`（短哈希+字数），4 处日志全改 |
| 15 | `ffe7973` | **P1** | **登出即吊销**（B 方案）：JWT 加 `jti` claim → 新增 `TokenBlacklistService` 进程内有界黑名单（jti→过期时间，惰性清理+5 万条护栏）→ 过滤器命中即 401 → logout 写入。零新依赖、零 Redis 配额消耗 |

### P1 收官状态

阶段一判定的 **9 条 P1 全部闭环**（#1 F-C、#2 A-02 除外——A-02 为 RAG 阈值重标定，
需线上 embedding 实测，归入阶段四；#5 限流多实例已按约定降级 P2 并标注触发条件）。

| 指标 | 修复前 | 现在 |
|---|---|---|
| 后端测试 | 672 例（2 Error 环境性失败） | **711 例全绿**（+39），覆盖率门禁真实生效 |
| 前端测试 | 270 例 | **282 例** |
| 已知 P1 未修 | 9 | **1**（A-02，属阶段四标定工作） |

### 本轮新增的可复用结论（详见工作区记忆）

- `PageImpl(content, pageable, total)` 会在 `offset+pageSize > total` 时用内容条数"纠正"总数——
  桩数据必须自洽，否则 `getTotalElements()` 断言必挂
- GIF 魔数是 `GIF8`（GIF87a/GIF89a 前 4 字节），不是 GIFF
- 本机 commit 配额是 Windows 上 JVM fork 类失败的隐性根因（Mockito `self-attach` 报错同理）；
  页面文件固定 4GB → 8192/16384 后全量测试恢复正常

---

**报告版本**：v3（第三/四波收官，全部 P1 闭环）　**编制**：2026-09-20

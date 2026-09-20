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

---

**报告版本**：v1　**编制**：2026-09-20

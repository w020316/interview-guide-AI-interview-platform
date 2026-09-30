# 招聘广场数据源扩容 —— 实测与选型（产品官，2026-10-01）

> 任务：诊断「国内仅 ~256 条」的结构性根因；**逐源实测**候选新数据源；给出可执行建议。
> 纪律：只做 GET 查询，未登录任何招聘平台、未提交任何申请。

## 一、线上实测基线（v1.44.2，登录 ux3_user 后取 `/api/jobs/meta`）

| 分栏 | 计数 | 来源 |
|---|---|---|
| `overseas=false`（全部国内） | **327** | 8 个内置种子源 |
| `overseas=true`（海外远程） | **3251** | 5 个海外公开 API |
| 全局 | **3578** | 327 + 3251 |

- 国内分栏明细：`PART_TIME 36 / SOCIAL 71 / TARGETED 14 / SPRING 11 / AUTUMN 162 / INTERN 33 = 327`
- 国内来源（8 个，全是种子）：`兼职专区 / 内置精选 / 服务精选 / 热招速递 / 社招精选 / 秋招精选2027 / 精选频道 / 行业精选`
- 海外来源（5 个，全是公开 API）：`Arbeitnow 欧洲 / Himalayas 全球远程 / Jobicy 全球远程 / RemoteOK 全球远程 / Remotive 全球远程`
- 代码侧种子条数核对：8 个 `Seed*.java` 内 `new JobDto(` 合计 **327**，与线上完全一致。

## 二、结构性诊断

**根因 = 源太少 + 无实时增量（国内 0 个活源），不是配额，也不是分类口径。**

1. **源太少**：国内 327 条 100% 来自 8 个**硬编码种子**（`Seed*.java`），更新依赖发版；海外 3251 条来自 5 个**实时 API**，会随上游轮换持续累积。
2. **配额不是国内少的原因**：`MAX_ITEMS_PER_REFRESH=25` 定义在 `AbstractOpenApiJobProvider`，**只作用于 5 个海外 API 子类**；种子源不继承该类，不受 25 限制。但它**是新增国内 API 源的天花板**——直接继承会被卡在 25/6h（`AbstractOpenApiJobProvider.java:111`）。
3. **分类口径正确**：`meta(overseas=false)` 用适配器自声明的 `overseas()` 过滤（`JobAgentService.java:341-350`），327 与种子数逐条吻合，无重复/漏算。

## 三、候选源实测表（2026-10-01）

网络说明：本机 curl 走 127.0.0.1:64829 代理，**V2EX/Google/NodeSeek 被代理 502 或直连超时**；V2EX 改由 WebFetch（另一出口）实测成功。所有 GET 均为匿名。

| 源 | URL | Key | 实测码 | 可解析条数 | 中文 | 合规 | 结论 |
|---|---|---|---|---|---|---|---|
| **V2EX 酷工作(Atom)** | `https://www.v2ex.com/feed/jobs.xml` | 否 | 200(WebFetch) | **24** 条/feed，每日滚动 | ✅ | 只读，不登录不投递 | **推荐**（中文活源） |
| **V2EX 酷工作(JSON)** | `https://www.v2ex.com/api/topics/show.json?node_name=jobs` | 否 | 200(WebFetch) | 8 条/次，字段 title/content/created/url/id | ✅ | 同上；需合规 UA + 限速 | **推荐**（结构化） |
| Greenhouse | `https://boards-api.greenhouse.io/v1/boards/{co}/jobs` | 否 | 200 | canonical 3672 中在华 22、mongodb 8、stripe 3 | ❌英文 | 公开板，只读 | **可选**（外企在华，需城市过滤） |
| Ashby | `https://api.ashbyhq.com/posting-api/job-board/{co}` | 否 | 200 | ramp 156 / openai 159 / notion 128，在华≈0 | ❌ | 只读 | 备选 |
| Lever | `https://api.lever.co/v0/postings/{co}?mode=json` | 否 | 200(kraken) | 需精确 slug，猜的多数 404 | ❌ | 只读 | 备选 |
| Workable | `https://apply.workable.com/api/v1/widget/accounts/{acct}?details=true` | 否 | 200 | 需真实账号，测试账号 jobs=[] | ❌ | 只读 | 备选 |
| SmartRecruiters | `https://api.smartrecruiters.com/v1/companies/{co}/postings` | 否 | 200 | 测 Bosch/Siemens/Ubisoft 均 totalFound=0 | ❌ | 只读 | 低产 |
| The Muse | `https://www.themuse.com/api/public/jobs?page=1` | 否 | 200 | 全量 41 万，美国为主 | ❌ | 需署名 | 低产 |
| HN "Who is hiring"(Algolia) | `https://hn.algolia.com/api/v1/search?query=...&tags=story` | 否 | 200(WebFetch) | 命中 1194，**正文在评论里** | ❌ | 只读 | 解析成本高 |
| Working Nomads | `https://www.workingnomads.com/api/exposed_jobs/` | 否 | 200 | 海外远程 | ❌ | 只读 | 海外补充 |
| Ruby China 招聘 | `https://ruby-china.org/api/v3/topics?node_id=25&limit=40` | 否 | 200 | 40 条，最新一条 2026-08，**不活跃** | ✅ | 只读 | 低产 |
| 电鸭 eleduck | 站点内 `/api/*` 全 404（Next.js SPA） | - | 404 | 0 | ✅ | - | **不可用** |
| 实习僧/牛客/拉勾/BOSS/智联/前程 | 均为 SPA，无公开 JSON API | - | 200(HTML) | 0 | ✅ | 反爬/需 cookie | **不可用** |
| 中国公共招聘网(mohrss) | `http://job.mohrss.gov.cn/...` | - | 403 | 0 | ✅ | 政府站封禁 | **不可用** |
| Jooble / Adzuna / Findwork | - | **需 Key** | 未测 | - | - | 需注册 | **未验证** |
| Arbeitnow / WWR / Jobspresso | 既有源 | - | **403** | - | ❌ | - | ⚠️ 本机代理 403，线上源仍正常 |

## 四、可执行建议

1. **接入 V2EX 酷工作**（唯一验证通过的中文活源）：新增 `V2exJobsJobProvider`，读 JSON API（`show.json?node_name=jobs`）或 Atom feed；`externalId = v2ex-{topicId}`；`overseas()=false`；`recruitType` 由标题规则归类。帖为「内推帖」，标题含岗位信息，需按 `招/内推/岗位/工程师/实习` 等关键词过滤「求职/闲聊」噪声（估留 60~70%）。
2. **接入外企在华 ATS 板**：维护 20~40 家 Greenhouse/Ashby 公司 slug 清单，**按 location 含 China/北京/上海/深圳/杭州/香港/台北 过滤**，只收在华岗；`overseas()=false`（或新开「外企」栏）。
3. **改配额为「按源可配」**：把 `MAX_ITEMS_PER_REFRESH` 从 `static final` 常量改为可覆写方法 `protected int maxItemsPerRefresh()`。海外保持 25（防淹没），**国内活源给 60~80/轮**。
4. **刷新频率**：海外保持 6h；国内活源可设 3h（V2EX 官方限速约 120 次/时，安全）。
5. **兜底扩种子**（零风险、立竿见影）：现有 8 源 327 条可扩到 ~650（发版更新）。

**国内提升估算**（算式）：
- V2EX：每日新增约 2~8 帖 × 过滤后 0.65 ≈ 1~5/日；30 天累积 ≈ **+30~150**（首轮 24 条即入，后续滚动）。
- 外企 ATS：抽样 11 在华岗/板 × 30 板 × 去重 0.5 ≈ **+50~160**。
- 种子扩容：**+300**（发版即得）。
- **合计：327 → 约 700~950（不含种子扩容）／1000+（含）**，海外:国内 从 10:1 降到约 3:1。

## 五、未能验证的项（如实标注）

- **Jooble / Adzuna / Findwork / The Muse 高配额**：均需注册 Key，本轮无凭据，**未实测**，不写入「可用」。
- **V2EX 从 Render 出口的可达性**：本机 curl 被代理拦截，仅经 WebFetch 验证；**上线前需在 Render 侧再验一次**（含 UA 与限速）。
- **Arbeitnow/WWR/Jobspresso 的 403**：疑为本机代理所致（既有线上源仍正常），**未判定为上游故障**。
- **外企 ATS 的在华岗位长期产出率**：仅抽样 3 家 Greenhouse 板，样本小。

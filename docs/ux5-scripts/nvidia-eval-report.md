# NVIDIA build.nvidia.com 免费模型评测报告

- 评测日期：2026-09-30
- 评测人：gstack-investigator（GStack 工程团队 · 调查员）
- 被测端点：`https://integrate.api.nvidia.com/v1`（OpenAI 兼容）
- 凭据：`NVIDIA_API_KEY` 已由主理人写入 `backend/.env`（本报告不记录任何密钥内容）
- 任务形态：中文简历评估 → 只输出 JSON `{"score","strengths","weaknesses","summary"}`
- 判定标准：能否 `json.loads` 解析成功、`finish_reason`、是否返回 `reasoning_content`（思考预算）、延迟、并发稳定性

---

## 1. 逐模型实测（4 路并发下，max_tokens=1024 / 2048 各一次）

| 模型 | HTTP | 耗时(1024/2048) | JSON解析(1024/2048) | finish_reason | reasoning_content | 中文质量 |
|---|---|---|---|---|---|---|
| **openai/gpt-oss-20b** | 200 / 200 | **6.9s / 5.2s** | ✅ / ✅ | stop | 有（857~1160 字） | 好 |
| **nvidia/nemotron-3-super-120b-a12b** | 200 / 200 | **8.4s / 16.1s** | ✅ / ✅ | stop | 有（759~1076 字） | 好 |
| z-ai/glm-5.3-flash | 200 / 200 | 88.8s / 83.5s | ✅ / ✅ | stop | 有（1872~2472 字） | 很好（会自行算出「P99 降低 85%」） |
| moonshotai/kimi-k3 | 200 / 200 | 177.0s / 196.2s | ✅ / ❌（正文为空） | stop | 有 | 好 |
| nvidia/nemotron-3.5-lightning-30b-a3b | 200 / 200 | 111.7s / 208.5s | ❌（length截断）/ ✅ | length / stop | 有（**3164~3996 字**，吃爆预算） | 中 |
| z-ai/glm-5.3 | 超时(240s) | — | — | — | — | — |
| deepseek-ai/deepseek-v4.1-flash | 超时(240s) | — | — | — | — | — |
| google/gemma-4-31b-it | 超时(240s) | — | — | — | — | — |
| moonshotai/kimi-k2.6 | **404** | 1.2s | — | — | — | 账号无权限（见 §4） |

**复测超时项**（用「说一句话」+ max_tokens=64，150s 超时）：`glm-5.3` / `deepseek-v4.1-flash` / `gemma-4-31b-it` **三个仍然全部超时** → 对当前账号**实际不可用**，不是「慢」而是「挂」。

---

## 2. 单发延迟基线（串行，max_tokens=2048，各 3 次）

| 模型 | 三次耗时 | 中位 | JSON 成功 |
|---|---|---|---|
| openai/gpt-oss-20b | 5.9s / 13.8s / 4.1s | **≈5.9s** | 3/3 |
| nvidia/nemotron-3-super-120b-a12b | 11.3s / 8.2s / 6.7s | **≈8.2s** | 3/3 |

延迟波动大（4~16s），无稳定 SLA。

---

## 3. 并发 ×3 结果

| 模型 | 成功率 | 各次耗时 | 备注 |
|---|---|---|---|
| **openai/gpt-oss-20b** | **3/3** | 5.4s / 7.9s / 15.3s | 全 200 + stop，最稳 |
| nvidia/nemotron-3-super-120b-a12b | **2/3** | 1.8s / 7.4s / 10.1s | 1 次 **HTTP 503 `Service temporarily overloaded`** |
| z-ai/glm-5.3-flash | 3/3 | 53s / 91s / 120s | 能成但慢到不可用 |

4 路并发与并发 ×3 **均未触发 429**（唯一失败是 503 平台过载）→ 低速率下未见 RPM 限流。

---

## 4. 官方免费额度（含来源与日期）

**关键区分：官方口径 ≠ 社区观测。**

- **官方口径**（NVIDIA 开发者论坛，经 decodethefuture.org 2026-05-16 转述）：
  - **不公布统一的免费档 RPM**；限制「因模型而异且不公开」；
  - **credit/积分制已废止**，改为按模型、不公开的速率限制；
  - 无公开的每日/每月请求上限；
  - 「没有官方途径绕过该速率限制或在同层级提额」。
  - 来源：https://decodethefuture.org/en/nvidia-nim-api-pricing-limits-guide/ （2026-05-16）
- **社区观测**（非 SLA，仅历史观察值）：
  - 多数模型 **≈40 RPM**、**不按 token 计费、无总量配额**、**免信用卡**、API Key 有效期最长 12 个月、中国大陆可直连。
  - 来源：https://yangmao.ai/zh/providers/nvidia-build/free-tier/ （更新 2026-06-24）、https://igetoken.com/deals/2026-09-nvidia-nim-free-tier/ （2026-09-05）
  - 论坛用户自述当前限额 40 RPM：https://forums.developer.nvidia.cn/t/request-for-nvidia-nim-api-rate-limit-increase-40-200-rpm/30493 （2026-07-03）
- **我方实测补充**：响应头**不含** `x-ratelimit-*`，无法从响应读到配额；`/v1/models` 返回 81 个模型，但**清单不代表可用**（见 §5）。

---

## 5. 重要反例：清单里有、实际调不通

- `moonshotai/kimi-k2.6`：在 `/v1/models` 里存在，实际返回 **HTTP 404 `Function ... Not found for account ...`** → 该模型对**本账号未开通**。
- 结论：**不能用 `/v1/models` 判断「额度能不能用」，必须真发请求**。

---

## 6. 与现有链路对比

现有链路（2026-09-30 实测）：主力 Agnes `agnes-2.5-flash` 单发 **1422ms**、并发 3/3；兜底 智谱 `glm-4.7-flash` 758ms 但并发仅 1/3（1305 过载）；末位 智谱 `glm-4-flash` 940ms、并发 3/3。

| 维度 | Agnes 主力 | 智谱 glm-4.7-flash | **NVIDIA gpt-oss-20b** | **NVIDIA nemotron-super** |
|---|---|---|---|---|
| 单发延迟 | 1.4s | 0.76s | **≈5.9s（慢 4×）** | ≈8.2s（慢 6×） |
| 并发×3 | 3/3 | **1/3** | **3/3** | 2/3（503） |
| 中文/JSON | 优 | 优 | 好 | 好 |
| 额度 | 10 RPM、无 token 配额 | 有限 | 官方不公布（社区 ≈40 RPM、无总量） | 同左 |

**按「每分钟请求数」比**：NVIDIA 社区观测 ≈40 RPM > Agnes 实际 10 RPM → NVIDIA 更宽。
**按「总量」比**：两者均「无 token 配额」，打平。
**按延迟/稳定性比**：NVIDIA 明显更差（4~6 倍延迟、延迟抖动大、偶发 503）。

---

## 7. 明确结论

1. **NVIDIA 有可用模型，但没有「更优选的主力」**：唯一能稳定跑的就是 `openai/gpt-oss-20b`，其延迟是 Agnes 的约 4 倍（≈5.9s vs 1.4s），不适合做用户可感知的实时主力。→ **不建议替换 Agnes 主力。**
2. **唯一值得接入的位置：跨厂商兜底**。`openai/gpt-oss-20b` 并发 **3/3**，明显优于现兜底 `glm-4.7-flash` 的 **1/3**；且 RPM 上限（社区 ≈40）远高于 Agnes 的 10，适合在主力限流时接管。建议位置：**兜底1（替换/并列 智谱 glm-4.7-flash）**，保留 `glm-4-flash` 作末位。
3. **不建议**用 `nemotron-3-super-120b-a12b` 做兜底：质量相当，但并发出现 **503 平台过载**，稳定性不如 gpt-oss-20b。
4. `glm-5.3-flash` 中文质量最好但 **~85s** 不可用；`kimi-k3` ~180s 且 2048 档正文为空；`nemotron-lightning` reasoning 吃爆预算；`glm-5.3`/`deepseek-v4.1-flash`/`gemma-4-31b-it` 直接超时 → **全部淘汰**。

### 接入注意（若采纳 gpt-oss-20b）
- 本项目链路三处必须一致：`render.yaml` / `application.yml` 默认值 / `backend/.env`。
- gpt-oss-20b **也是思考模型**（`reasoning_content` 857~1160 字），`max_tokens` 至少 2048；项目现用 2500 够用。
- 需确认本项目 `thinking-disabled` 开关对 NVIDIA 是否有效（**未验证**）。

---

## 8. 我没能验证的项

- NVIDIA 免费档**官方确切 RPM / 是否按账号分级**：官方不公布，响应头无 `x-ratelimit-*`，**未能实测出确切上限**（仅知低速率下无 429）。
- 峰值时段（非本次测试时段）的稳定性与 503 频率。
- 是否能通过参数关闭 gpt-oss-20b 的 `reasoning_content`。
- 长期额度政策是否变化（社区数字非 SLA）。

---

## 附：脚本与原始数据
- `nvidia_probe_par.py` / `nvidia_concurrency.py`（Key 均从 `.env` 读取，不落盘、不打印）
- `par_run.log`（18 条实测）、`conc_run.log`（并发 ×3）、`nvidia_probe_result.json`

# 自托管 Embedding 服务（免费方案）

## 为什么存在

第三方 embedding API（硅基流动 / 智谱 / 阿里百炼）的 embedding 模型**全部收费**，
而本服务用 `fastembed`(ONNX) + `BAAI/bge-small-zh-v1.5` 实现零成本、无限额度的自托管：

| 方案 | 费用 | 维度 | 备注 |
|---|---|---|---|
| 阿里百炼 text-embedding-v4 | 付费 | 1024 | 曾配置，Key 失效/需充值 |
| 智谱 embedding-3 | 付费（需余额） | 1024 | 账户无余额 |
| 硅基流动 Qwen3-Embedding-0.6B | 付费（需余额） | 1024 | 国际站已注册，402 |
| **本服务（bge-small-zh-v1.5）** | **免费** | **512** | ONNX 推理，无额度限制 |

额外收益：与**本地开发环境使用同一模型**（`backend/.env` local profile 即 bge-small-zh-v1.5），
`RAG_AUTO_SUPPLEMENT_MAX_DISTANCE=0.45` 的距离阈值当年就是按它标定的——
线上换回同模型后阈值重新有效，无需重新标定。

## 部署（Render 免费层）

已在 `render.yaml` 中声明为第二个免费 Web 服务：

- 服务名：`interview-guide-embedding`
- 地址：`https://interview-guide-embedding.onrender.com`
- 健康检查：`/health` → `{"status":"ok","model":"BAAI/bge-small-zh-v1.5","dimensions":512}`
- Docker 构建期烘焙模型（约 95MB），运行期离线加载，冷启动仅需加载模型（无网络下载）

后端环境变量（Render → interview-guide-backend → Environment）：

```
AI_EMBEDDING_BASE_URL=https://interview-guide-embedding.onrender.com
AI_EMBEDDING_API_KEY=<与下表的 EMBED_AUTH_TOKEN 保持一致>   ← 面板维护，不进仓库
AI_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5
AI_EMBEDDING_DIMENSIONS=512
```

### 鉴权与令牌轮换（2026-09-23 加固）

两个服务共用同一个共享令牌：

| 服务 | 变量 | 说明 |
|---|---|---|
| `interview-guide-embedding` | `EMBED_AUTH_TOKEN` | 本服务的入站令牌，**支持逗号分隔多值** |
| `interview-guide-backend` | `AI_EMBEDDING_API_KEY` | 调用本服务时带出的令牌，**单值** |

**语义要点**：

- `EMBED_AUTH_TOKEN` **未配置时拒绝所有请求**（fail-closed）。
  旧实现是「为空则不校验」，意味着**误删环境变量会让服务对公网完全开放** —— 比配置泄露更危险，故已改正。
  本地调试如需免鉴权，显式设置 `EMBED_ALLOW_NO_AUTH=true`。
- 两个变量都必须在 **Render 面板**维护（`render.yaml` 中为 `sync: false`），**不要写进版本库**。
  起因：二者原先以明文 `value:` 写在 `render.yaml` 里，而仓库是公开的，实测外部可直接调用该服务。

**零中断轮换步骤**（任一步骤都不会出现 401）：

1. 面板把本服务的 `EMBED_AUTH_TOKEN` 改为 `新令牌,旧令牌` → 本服务重启（两者都被接受）
2. 面板把后端的 `AI_EMBEDDING_API_KEY` 改为 `新令牌` → 后端重启
3. 面板把本服务的 `EMBED_AUTH_TOKEN` 改回 `新令牌` → 旧令牌彻底失效

> 第 1 步依赖服务端的**多令牌支持**，因此轮换前必须已部署含该支持的版本。
> 若跳过第 1 步直接替换令牌，两个服务之间会存在一段 401 窗口（表现为知识库检索/导入失败）。

## 维度变更注意（重要）

向量表 `vector_store` 的维度由 `AI_EMBEDDING_DIMENSIONS` 决定。
从 1024 切到 512 时，后端内置了**空表自动迁移**：启动时若表存在、为空且维度不匹配，
自动 DROP 后由 Spring AI 按新维度重建（空表无数据丢失）；
若表非空且维度不匹配，则仅告警并拒绝自动迁移（避免误删真实数据），需人工处理。

## 免费层注意事项

- Render 免费服务 15 分钟无请求会休眠，冷启动需加载模型（约 10–20s）；
  仓库内置的 `keepalive.yml` 每 5 分钟 ping 后端，可顺带对本服务保活（见 workflows）
- 内存 512MB：ONNX + 95MB 模型实测占用约 250–300MB，余量充足

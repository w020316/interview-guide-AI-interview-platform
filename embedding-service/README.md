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
AI_EMBEDDING_API_KEY=local-embedding
AI_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5
AI_EMBEDDING_DIMENSIONS=512
```

## 维度变更注意（重要）

向量表 `vector_store` 的维度由 `AI_EMBEDDING_DIMENSIONS` 决定。
从 1024 切到 512 时，后端内置了**空表自动迁移**：启动时若表存在、为空且维度不匹配，
自动 DROP 后由 Spring AI 按新维度重建（空表无数据丢失）；
若表非空且维度不匹配，则仅告警并拒绝自动迁移（避免误删真实数据），需人工处理。

## 免费层注意事项

- Render 免费服务 15 分钟无请求会休眠，冷启动需加载模型（约 10–20s）；
  仓库内置的 `keepalive.yml` 每 5 分钟 ping 后端，可顺带对本服务保活（见 workflows）
- 内存 512MB：ONNX + 95MB 模型实测占用约 250–300MB，余量充足

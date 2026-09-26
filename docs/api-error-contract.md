# API 错误响应契约（AI 智能面试辅助平台）

> 固化日期：2026-09-21（v1.34.1）
> 背景：全功能审查（`docs/ai-full-review-2026-09-20/03-AI全功能测试报告.md` P2-9）发现错误码存在**两种载体并存**的情况，
> 新客户端/第三方集成若不理解规则会误判请求结果。本文档把既有事实固化为**明确契约**，供前后端与第三方共同遵守。

---

## 1. 统一响应体

所有接口（含错误）都返回同一结构：

```json
{ "code": 200, "message": "success", "data": null, "timestamp": 1789915321443 }
```

- `code`：**业务语义码**，与 `Result.error(code, msg)` 的入参一致
- `message`：面向用户的可读文案（成功为 `"success"`）
- `data`：成功时的载荷；错误时恒为 `null`
- `timestamp`：服务端毫秒时间戳

## 2. 契约规则（**判断结果必须看 `code`，不能只看 HTTP 状态码**）

本项目采用「**HTTP 状态码与 `code` 一致，但当错误由控制器直接返回时 HTTP 恒为 200**」的混合形态。规则如下：

| 错误来源 | HTTP 状态码 | body `code` | 典型场景 |
|---|---|---|---|
| **控制器内校验后直接 `return Result.error(...)`** | **200** | 4xx 语义码 | 缺参/空值/越界/业务规则不满足（如 `code=400/403/404/429`） |
| **异常抛出后由 `GlobalExceptionHandler` 处理** | **与 `code` 相同** | 同 HTTP 码 | 见 §3 映射表 |

> **唯一正确的客户端判据**：先取 body 的 `code`，`code == 200` 才是成功；
> 出现 `HTTP 200 但 code != 200` 是**预期行为**，不是异常。

### 2.1 为什么不做成单一形态

统一为「HTTP 状态码即语义码」需要改动所有控制器返回点与前端拦截器，并会破坏既有 700+ 后端测试与 280+ 前端测试的断言；统一为「全部 HTTP 200」则会丢失网关/监控层对 5xx 的告警能力（`5xx 告警`是现网可用性观测的重要信号）。因此**保持现状并把规则显式化**，作为契约而非歧义。

## 3. `GlobalExceptionHandler` 错误映射表（HTTP == code）

| 异常 | HTTP / code | 用户可见文案要点 |
|---|---|---|
| `AuthenticationException` | 401 | 未认证或登录已过期 |
| `AccessDeniedException` | 403 | 无权访问该资源 |
| `ResourceNotFoundException` | 404 | 资源不存在（v1.44.0 新增，如「会话不存在：xxx」） |
| `IllegalArgumentException` | 400 | 参数非法（透出原始消息；**不含**资源不存在） |
| `MethodArgumentNotValidException` | 400 | 字段校验失败 |
| `MethodArgumentTypeMismatchException` | 400 | 参数类型不匹配 |
| `MissingServletRequestParameterException` | 400 | 缺少必填参数：xxx |
| `MissingServletRequestPartException` | 400 | 请上传文件（缺少 file 部件） |
| `MultipartException` | 400 | 请求格式不正确，请使用 multipart/form-data 上传文件 |
| `MaxUploadSizeExceededException` | 400 | 文件大小超出限制（最大 10MB） |
| `HttpMessageNotReadableException` | 400 | 请求体格式错误，请检查 JSON 语法 |
| `HttpRequestMethodNotSupportedException` | 405 | 不支持的请求方法 |
| `NoHandlerFoundException` / `NoResourceFoundException` | 404 | 接口不存在 |
| `OptimisticLockingFailureException` | 409 | 内容已被更新，请刷新后重试 |
| `JsonBodySizeLimitFilter` 拦截（请求体 > 1024KB） | 413 | 请求体过大，超过 1024KB 限制 |
| `BusinessException` | 503 | 业务故障，文案原文透出（如「AI 服务暂时不可用，请稍后重试」） |
| `AiGateTimeoutException`（v1.34.1 新增） | 503 | AI 并发闸门排队超时，请稍后重试 |
| `TimeoutException` | 504 | AI 服务响应超时，请稍后重试 |
| `DataAccessException` | 500 | 数据库操作失败，请联系管理员 |
| `IllegalStateException`（其余） | 500 | 服务器内部错误（**不透出内部细节**） |
| 其他 `RuntimeException` / `Exception` | 500 | 服务器内部错误 |

## 4. 状态码语义约定（与上游 AI 故障对齐）

| 码 | 语义 | 客户端建议动作 |
|---|---|---|
| 400 | 入参问题 | 修正参数后重试，不要自动重试 |
| 401 | 未认证 / token 失效 | 跳登录页 |
| 403 | 无权限 / 账号被禁用 | 提示用户 |
| 404 | 资源不存在 | 提示用户，返回列表 |
| 405 | 方法不支持 | 前端 bug，上报 |
| 409 | 并发冲突 | 提示刷新后重试 |
| 413 | 请求体过大 | 前端先做体量预检 |
| **429** | **限流/配额耗尽**（注册限流、AI 接口限流、岗位刷新限流） | **Banner 提示 + 倒计时，禁止自动重试** |
| **503** | **AI 服务暂不可用**（降级链全失败、闸门排队超时） | 提示「服务繁忙」，可延后手动重试 |
| 504 | 上游超时 | 同 503 |
| 500 | 未预期内部错误 | 上报，不要重试 |

> **429 与 503 的区别对本项目尤其重要**：`402/429` 表示额度/限流（等一会儿可能就好），
> `503` 表示当前无可用通道或并发已满（服务侧问题）。前端不应把两者合并为同一提示。

## 5. 前端约定（`frontend/src/api/index.ts`）

- 拦截器统一读取 body `code`；`code != 200` 时抛出携带 `code/message` 的错误对象
- 对 `401` 统一清理本地 token 并跳登录
- 对 `429` 展示限流提示并**禁止自动重试**（避免加剧限流）
- 对 `5xx` 展示「服务繁忙」类文案，提供手动重试入口

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
自托管 OpenAI 兼容 Embedding 服务（部署版）
==========================================

由本地 D:/xm/embed_server.py 改造，用于部署到 Render 免费层：
- 监听 0.0.0.0（容器内必须），端口取 Render 注入的 PORT
- 模型在 **Docker 构建期**下载并烘焙进镜像（运行期零下载，冷启动只加载模型）
- 提供接口:
    POST /v1/embeddings   OpenAI 兼容，Spring AI 的 OpenAiEmbeddingModel 可直接对接
    GET  /health          健康检查（Render 健康检查路径）
    GET  /v1/models       模型列表

为什么自托管：第三方 embedding API（硅基流动/智谱/百炼）的 embedding 模型全部收费，
而本服务用 fastembed(ONNX) + bge-small-zh-v1.5 完全免费、无限额度，
且与本地开发环境（backend/.env 的 local profile）使用同一模型，距离阈值标定一致。
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT") or os.environ.get("EMBED_PORT") or "8001")
MODEL_NAME = os.environ.get("EMBED_MODEL", "BAAI/bge-small-zh-v1.5")
# 模型在构建期已烘焙到 /app/models，运行期直接离线加载
CACHE_DIR = os.environ.get("EMBED_CACHE", "/app/models")
# 可选：用共享令牌做最简单的鉴权（为空则不校验）。Render 环境变量里配置同名 key。
AUTH_TOKEN = os.environ.get("EMBED_AUTH_TOKEN", "")

_model = None
_dim = None


def load_model():
    global _model, _dim
    if _model is not None:
        return _model
    print(f"[加载] 模型 {MODEL_NAME}，缓存目录 {CACHE_DIR}", flush=True)
    t0 = time.time()
    from fastembed import TextEmbedding
    _model = TextEmbedding(model_name=MODEL_NAME, cache_dir=CACHE_DIR, local_files_only=True)
    probe = list(_model.embed(["维度探测"]))[0]
    _dim = len(probe)
    print(f"[加载] 完成，维度={_dim}，耗时 {time.time()-t0:.1f}s", flush=True)
    return _model


def embed_texts(texts):
    model = load_model()
    vecs = list(model.embed(texts))
    return [v.tolist() for v in vecs]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if not AUTH_TOKEN:
            return True
        auth = self.headers.get("Authorization") or ""
        return auth == f"Bearer {AUTH_TOKEN}"

    def do_GET(self):
        if self.path.startswith("/health"):
            try:
                dim = load_model() and _dim
                self._send(200, {"status": "ok", "model": MODEL_NAME, "dimensions": dim})
            except Exception as e:
                self._send(503, {"status": "error", "message": str(e)})
        elif self.path.startswith("/v1/models"):
            self._send(200, {
                "object": "list",
                "data": [{"id": MODEL_NAME, "object": "model", "owned_by": "self-hosted-fastembed"}],
            })
        else:
            self._send(404, {"error": {"message": "not found", "type": "invalid_request_error"}})

    def do_POST(self):
        if not self._authorized():
            self._send(401, {"error": {"message": "unauthorized", "type": "invalid_request_error"}})
            return
        if not self.path.endswith("/embeddings"):
            self._send(404, {"error": {"message": "not found", "type": "invalid_request_error"}})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            req = json.loads(raw.decode("utf-8"))

            inp = req.get("input")
            if isinstance(inp, str):
                texts, single = [inp], True
            elif isinstance(inp, list) and inp and all(isinstance(x, str) for x in inp):
                texts, single = inp, False
            else:
                self._send(400, {"error": {"message": "input 仅支持字符串或字符串数组",
                                           "type": "invalid_request_error"}})
                return
            if not texts:
                self._send(400, {"error": {"message": "input 为空数组", "type": "invalid_request_error"}})
                return

            t0 = time.time()
            vectors = embed_texts(texts)
            ms = (time.time() - t0) * 1000

            data = [{"object": "embedding", "index": i, "embedding": v} for i, v in enumerate(vectors)]
            approx = sum(max(1, len(t) // 4) for t in texts)
            self._send(200, {
                "object": "list", "data": data, "model": MODEL_NAME,
                "usage": {"prompt_tokens": approx, "total_tokens": approx},
            })
            self.log_message("embeddings n=%d dim=%d %.0fms", len(texts), len(vectors[0]), ms)
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._send(500, {"error": {"message": str(e), "type": "server_error"}})


def main():
    print("=" * 68, flush=True)
    print("自托管 OpenAI 兼容 Embedding 服务", flush=True)
    print(f"  监听: 0.0.0.0:{PORT}  模型: {MODEL_NAME}  缓存: {CACHE_DIR}", flush=True)
    print("=" * 68, flush=True)
    try:
        load_model()
    except Exception as e:
        print(f"[警告] 模型预热失败（服务仍启动，首次请求会重试）：{e}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()

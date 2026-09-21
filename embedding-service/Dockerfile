# 自托管 Embedding 服务（免费方案）
# fastembed(ONNX) 无需 torch，模型在构建期烘焙进镜像，运行期零下载
FROM python:3.11-slim

WORKDIR /app

# 依赖：fastembed 含 onnxruntime，纯 CPU 推理
RUN pip install --no-cache-dir "fastembed>=0.3,<1.0"

# 构建期预下载模型并烘焙进镜像（约 95MB），运行期 local_files_only 离线加载
# 模型：BAAI/bge-small-zh-v1.5（512 维，中文优化，与本地开发环境一致）
ENV EMBED_MODEL=BAAI/bge-small-zh-v1.5 \
    EMBED_CACHE=/app/models
RUN python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='BAAI/bge-small-zh-v1.5', cache_dir='/app/models'); print('model baked')"

COPY server.py .

# Render 注入 PORT；健康检查走 /health
ENV PORT=8000
EXPOSE 8000

CMD ["python", "server.py"]

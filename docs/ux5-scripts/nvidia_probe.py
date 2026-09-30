# -*- coding: utf-8 -*-
"""NVIDIA build.nvidia.com 免费模型实测探针。
Key 从 backend/.env 读取，绝不打印/落盘。
"""
import os, sys, json, time, ssl, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENV_PATH = os.path.join(BASE_DIR, "backend", ".env")

def load_env(path):
    env = {}
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env

ENV = load_env(ENV_PATH)
API_KEY = ENV.get("NVIDIA_API_KEY", "")
BASE_URL = ENV.get("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1").rstrip("/")

CTX = ssl.create_default_context()

RESUME = "张三，男，28岁，本科，5年Java后端开发经验。熟悉Spring Boot、Spring Cloud、MySQL、Redis、Kafka。主导过日活百万的电商订单系统重构，将下单接口P99从800ms降到120ms。带领3人小组完成微服务拆分，有高并发与分布式事务实践经验。"

PROMPT = (
    '请只输出 JSON（不要任何解释、不要 markdown 代码块）：'
    '{"score":0-100,"strengths":["..."],"weaknesses":["..."],"summary":"..."}。'
    "评估这段简历：" + RESUME
)

def call(model, max_tokens, timeout=150):
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": PROMPT}],
        "max_tokens": max_tokens,
        "temperature": 0.7,
        "stream": False,
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE_URL + "/chat/completions", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + API_KEY)
    req.add_header("Accept", "application/json")
    t0 = time.time()
    out = {"model": model, "max_tokens": max_tokens}
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            out["http"] = resp.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        out["http"] = e.code
    except Exception as e:
        out["http"] = None
        out["error"] = type(e).__name__ + ": " + str(e)[:200]
        out["ms"] = int((time.time() - t0) * 1000)
        return out
    out["ms"] = int((time.time() - t0) * 1000)
    try:
        j = json.loads(body)
    except Exception:
        out["raw_head"] = body[:300]
        out["json_ok"] = False
        return out
    choices = j.get("choices") or []
    if not choices:
        out["json_ok"] = False
        out["raw_head"] = json.dumps(j, ensure_ascii=False)[:300]
        return out
    ch = choices[0]
    msg = ch.get("message") or {}
    content = (msg.get("content") or "")
    out["finish_reason"] = ch.get("finish_reason")
    out["has_reasoning"] = bool(msg.get("reasoning_content"))
    out["reasoning_len"] = len(msg.get("reasoning_content") or "")
    out["content_len"] = len(content)
    out["usage"] = j.get("usage")
    out["content_head"] = content[:400]
    # 尝试解析 JSON 正文
    txt = content.strip()
    if txt.startswith("```"):
        txt = txt.strip("`")
        if txt.lower().startswith("json"):
            txt = txt[4:]
        txt = txt.strip()
    try:
        parsed = json.loads(txt)
        out["json_ok"] = isinstance(parsed, dict) and ("score" in parsed)
        out["parsed_keys"] = list(parsed.keys()) if isinstance(parsed, dict) else None
        out["score"] = parsed.get("score") if isinstance(parsed, dict) else None
    except Exception as e:
        out["json_ok"] = False
        out["json_err"] = str(e)[:120]
    return out

def main():
    models = sys.argv[1].split(",") if len(sys.argv) > 1 else [
        "z-ai/glm-5.3-flash", "z-ai/glm-5.3", "deepseek-ai/deepseek-v4.1-flash",
        "moonshotai/kimi-k2.6", "moonshotai/kimi-k3",
        "nvidia/nemotron-3.5-lightning-30b-a3b", "nvidia/nemotron-3-super-120b-a12b",
        "openai/gpt-oss-20b", "google/gemma-4-31b-it",
    ]
    if not API_KEY:
        print(json.dumps({"fatal": "NVIDIA_API_KEY 未读取到"}, ensure_ascii=False))
        return
    results = []
    for m in models:
        for mt in (1024, 2048):
            r = call(m, mt)
            results.append(r)
            print(json.dumps(r, ensure_ascii=False), flush=True)
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "nvidia_probe_result.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""NVIDIA 免费模型实测（受控并发版）：所有 (model, max_tokens) 组合用线程池并发跑。
Key 从 backend/.env 读取，绝不打印。latency 为 4 路并发下的观测值。
"""
import os, sys, json, time, ssl, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENV_PATH = os.path.join(BASE_DIR, "backend", ".env")

def load_env(path):
    env = {}
    for line in open(path, "r", encoding="utf-8", errors="ignore"):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env

ENV = load_env(ENV_PATH)
API_KEY = ENV.get("NVIDIA_API_KEY", "")
BASE_URL = ENV.get("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1").rstrip("/")
CTX = ssl.create_default_context()

RESUME = "张三，男，28岁，本科，5年Java后端开发经验。熟悉Spring Boot、Spring Cloud、MySQL、Redis、Kafka。主导过日活百万的电商订单系统重构，将下单接口P99从800ms降到120ms。带领3人小组完成微服务拆分，有高并发与分布式事务实践经验。"
PROMPT = ('请只输出 JSON（不要任何解释、不要 markdown 代码块）：'
          '{"score":0-100,"strengths":["..."],"weaknesses":["..."],"summary":"..."}。'
          "评估这段简历：" + RESUME)

def call(model, max_tokens, timeout=240):
    payload = {"model": model, "messages": [{"role": "user", "content": PROMPT}],
               "max_tokens": max_tokens, "temperature": 0.7, "stream": False}
    req = urllib.request.Request(BASE_URL + "/chat/completions",
                                 data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + API_KEY)
    t0 = time.time()
    out = {"model": model, "max_tokens": max_tokens}
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read().decode("utf-8", errors="replace"); out["http"] = r.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace"); out["http"] = e.code
        out["err_head"] = body[:250]
    except Exception as e:
        out["http"] = None; out["err_head"] = type(e).__name__ + ":" + str(e)[:150]
        out["ms"] = int((time.time() - t0) * 1000); return out
    out["ms"] = int((time.time() - t0) * 1000)
    try:
        j = json.loads(body)
    except Exception:
        out["json_ok"] = False; out["raw_head"] = body[:250]; return out
    ch = (j.get("choices") or [{}])[0]; msg = ch.get("message") or {}
    content = (msg.get("content") or "")
    out["finish_reason"] = ch.get("finish_reason")
    out["has_reasoning"] = bool(msg.get("reasoning_content"))
    out["reasoning_len"] = len(msg.get("reasoning_content") or "")
    out["content_len"] = len(content)
    out["usage"] = j.get("usage")
    out["content_head"] = content[:500]
    txt = content.strip().strip("`")
    if txt.lower().startswith("json"): txt = txt[4:]
    try:
        p = json.loads(txt.strip())
        out["json_ok"] = isinstance(p, dict) and ("score" in p)
        out["score"] = p.get("score") if isinstance(p, dict) else None
    except Exception as e:
        out["json_ok"] = False; out["json_err"] = str(e)[:100]
    return out

def main():
    models = [
        "z-ai/glm-5.3-flash", "z-ai/glm-5.3", "deepseek-ai/deepseek-v4.1-flash",
        "moonshotai/kimi-k2.6", "moonshotai/kimi-k3",
        "nvidia/nemotron-3.5-lightning-30b-a3b", "nvidia/nemotron-3-super-120b-a12b",
        "openai/gpt-oss-20b", "google/gemma-4-31b-it",
    ]
    if len(sys.argv) > 1:
        models = sys.argv[1].split(",")
    workers = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    jobs = [(m, mt) for m in models for mt in (1024, 2048)]
    results = []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(call, m, mt): (m, mt) for m, mt in jobs}
        for f in as_completed(futs):
            r = f.result(); results.append(r)
            print(json.dumps(r, ensure_ascii=False), flush=True)
    results.sort(key=lambda x: (x["model"], x["max_tokens"]))
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "nvidia_probe_result.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("ALL_DONE", len(results), flush=True)

if __name__ == "__main__":
    main()

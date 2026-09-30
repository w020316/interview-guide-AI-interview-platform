# -*- coding: utf-8 -*-
"""NVIDIA 免费模型 并发×3 测试。Key 从 backend/.env 读取，绝不打印。"""
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

RESUME = "李四，女，26岁，硕士，3年前端开发经验，精通React、TypeScript、Vue3。负责过公司中台系统的组件库建设，将首屏加载从3.2s优化到0.9s。熟悉Webpack/Vite构建优化，有Node.js BFF开发经验。"
PROMPT = ('请只输出 JSON（不要任何解释、不要 markdown 代码块）：'
          '{"score":0-100,"strengths":["..."],"weaknesses":["..."],"summary":"..."}。评估这段简历：' + RESUME)

def call(model, tag, max_tokens=2048, timeout=180):
    payload = {"model": model, "messages": [{"role": "user", "content": PROMPT}],
               "max_tokens": max_tokens, "temperature": 0.7, "stream": False}
    req = urllib.request.Request(BASE_URL + "/chat/completions",
                                 data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + API_KEY)
    t0 = time.time()
    out = {"model": model, "tag": tag}
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read().decode("utf-8", errors="replace"); out["http"] = r.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace"); out["http"] = e.code
        out["err_head"] = body[:200]
    except Exception as e:
        out["http"] = None; out["err_head"] = type(e).__name__ + ":" + str(e)[:120]
        out["ms"] = int((time.time() - t0) * 1000); return out
    out["ms"] = int((time.time() - t0) * 1000)
    try:
        j = json.loads(body); ch = (j.get("choices") or [{}])[0]; msg = ch.get("message") or {}
        content = (msg.get("content") or "").strip()
        out["finish_reason"] = ch.get("finish_reason")
        out["content_len"] = len(content)
        txt = content.strip("`")
        if txt.lower().startswith("json"): txt = txt[4:]
        try:
            p = json.loads(txt.strip()); out["json_ok"] = isinstance(p, dict) and "score" in p
        except Exception:
            out["json_ok"] = False
    except Exception:
        out["json_ok"] = False
    return out

def main():
    models = sys.argv[1].split(",")
    results = []
    for m in models:
        with ThreadPoolExecutor(max_workers=3) as ex:
            futs = [ex.submit(call, m, "c%d" % i) for i in range(1, 4)]
            for f in as_completed(futs):
                r = f.result(); results.append(r)
                print(json.dumps(r, ensure_ascii=False), flush=True)
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "nvidia_concurrency_result.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()

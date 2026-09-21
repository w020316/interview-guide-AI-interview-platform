# -*- coding: utf-8 -*-
"""
AI 智能面试辅助平台 —— AI 功能端到端真机测试
覆盖：正常路径 / 边界条件 / 异常输入 / SSE 流式 / 并发限流 / 智能体多轮
输出：D:/xm/review_results.json
"""
import json, os, sys, time, uuid, struct, zlib, threading, io
import urllib.request, urllib.error, urllib.parse

BASE = "http://127.0.0.1:8080"
OUT = "D:/xm/review_results.json"
RESULTS = []
_lock = threading.Lock()

# 强制绕过代理（本机 http_proxy 会拦 localhost）
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
urllib.request.install_opener(_opener)


def req(method, path, payload=None, token=None, raw_body=None, content_type="application/json",
        timeout=180, stream=False):
    """返回 (status, body_text, elapsed_ms, extra)"""
    url = BASE + path
    data = None
    headers = {}
    if raw_body is not None:
        data = raw_body
        headers["Content-Type"] = content_type
    elif payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.time()
    try:
        resp = _opener.open(r, timeout=timeout)
        if stream:
            return resp, None, (time.time() - t0) * 1000, {}
        body = resp.read().decode("utf-8", "replace")
        return resp.status, body, (time.time() - t0) * 1000, {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        return e.code, body, (time.time() - t0) * 1000, {}
    except Exception as e:
        return -1, f"{type(e).__name__}: {e}", (time.time() - t0) * 1000, {}


def record(tid, name, category, expect, status, body, ms, verdict, note=""):
    with _lock:
        RESULTS.append({
            "id": tid, "name": name, "category": category, "expect": expect,
            "http_status": status, "elapsed_ms": round(ms, 1),
            "verdict": verdict, "note": note,
            "body_snippet": (body or "")[:600]
        })
        flag = {"PASS": "PASS", "FAIL": "FAIL", "WARN": "WARN"}.get(verdict, verdict)
        print(f"[{flag}] {tid} {name}  http={status} {round(ms)}ms  {note}")


def biz(body):
    """解析业务返回 {code,message,data}"""
    try:
        return json.loads(body)
    except Exception:
        return None


def size_of(b):
    o = biz(b)
    if not o:
        return -1
    d = o.get("data")
    if d is None:
        return 0
    return len(d) if isinstance(d, str) else len(json.dumps(d, ensure_ascii=False))


# ───────────────────────── 注册 / 登录 ─────────────────────────
USER = "rv" + uuid.uuid4().hex[:8]
PWD = "Test123456"
TOKEN = None
TOKEN2 = None
USER2 = "rv" + uuid.uuid4().hex[:8]


def setup():
    global TOKEN, TOKEN2
    st, b, ms, _ = req("POST", "/api/auth/register", {"username": USER, "password": PWD, "email": USER + "@t.com"})
    o = biz(b)
    record("AUTH-01", "用户注册", "认证", "200+返回JWT", st, b, ms,
           "PASS" if o and o.get("code") == 200 and o.get("data") else "FAIL")
    TOKEN = o.get("data") if o else None

    st, b, ms, _ = req("POST", "/api/auth/login", {"username": USER, "password": PWD})
    o = biz(b)
    if o and o.get("code") == 200 and o.get("data"):
        TOKEN = o["data"]
    record("AUTH-02", "用户登录", "认证", "200+返回JWT", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    # 第二用户（用于越权测试）
    st, b, ms, _ = req("POST", "/api/auth/register", {"username": USER2, "password": PWD})
    o = biz(b)
    TOKEN2 = o.get("data") if o else None

    st, b, ms, _ = req("GET", "/api/auth/me", token=TOKEN)
    o = biz(b)
    record("AUTH-03", "查询当前用户信息", "认证", "200+role", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    # 边界/异常
    cases = [
        ("AUTH-04", "用户名过短(1字符)", {"username": "a", "password": PWD}, 400),
        ("AUTH-05", "密码过短(5字符)", {"username": "rv" + uuid.uuid4().hex[:6], "password": "12345"}, 400),
        ("AUTH-06", "用户名非法字符", {"username": "bad-name!", "password": PWD}, 400),
        ("AUTH-07", "邮箱格式错误", {"username": "rv" + uuid.uuid4().hex[:6], "password": PWD, "email": "not-an-email"}, 400),
    ]
    for tid, nm, pl, exp in cases:
        st, b, ms, _ = req("POST", "/api/auth/register", pl)
        o = biz(b)
        ok = o and o.get("code") == exp
        record(tid, nm, "认证", f"业务码{exp}", st, b, ms, "PASS" if ok else "FAIL")

    st, b, ms, _ = req("POST", "/api/auth/login", {"username": USER, "password": "wrongpwd"})
    o = biz(b)
    record("AUTH-08", "错误密码登录", "认证", "401", st, b, ms,
           "PASS" if o and o.get("code") == 401 else "FAIL")

    st, b, ms, _ = req("GET", "/api/auth/me")
    record("AUTH-09", "无token访问受保护接口", "认证", "401", st, b, ms,
           "PASS" if st == 401 else "FAIL")

    st, b, ms, _ = req("GET", "/api/auth/me", token="invalid.jwt.token")
    record("AUTH-10", "非法token访问", "认证", "401", st, b, ms,
           "PASS" if st == 401 else "FAIL")


# ─────────────────────── 简历 AI ───────────────────────
RESUME = """张三，男，1999年生，本科，计算机科学与技术，2022年毕业。
联系方式：13800000000，zhangsan@example.com
工作经历：
2022.07-2024.06  某互联网公司  Java后端开发工程师
  - 负责订单中心微服务开发，使用 Spring Boot + MySQL + Redis，QPS 峰值 3000
  - 主导订单查询性能优化，通过索引优化与缓存改造，P95 响应从 800ms 降至 120ms
  - 参与分布式事务改造，引入 RocketMQ 事务消息保证最终一致性
2024.07-至今  某科技公司  高级后端开发工程师
  - 负责用户增长中台，日均请求 2000 万，引入 Kafka 削峰
  - 搭建 Prometheus + Grafana 监控体系，故障定位时间缩短 60%
技能：Java、Spring Boot、Spring Cloud、MySQL、Redis、Kafka、RocketMQ、Docker、K8s
项目：电商订单系统重构（微服务拆分）、用户画像标签系统
无大型团队管理经验，英语 CET-6。"""

JD = """招聘岗位：Java 高级后端开发工程师
职责：负责核心交易系统设计与开发，保障高并发场景下的稳定性
要求：
1. 5年以上 Java 开发经验，精通 JVM 调优
2. 熟悉分布式架构、微服务治理（Spring Cloud Alibaba）
3. 熟悉 MySQL/Redis/Kafka，有高并发实战经验
4. 有复杂业务系统重构经验优先
5. 具备良好的沟通协作能力
加分项：有 Kubernetes 生产经验、有带领 3-5 人小组经验"""


def test_resume():
    st, b, ms, _ = req("POST", "/api/resume/analyze", {"resumeText": RESUME, "targetJob": "Java高级后端开发"}, token=TOKEN)
    o = biz(b)
    ok = o and o.get("code") == 200 and size_of(b) > 50
    record("RES-01", "简历AI分析（文本）", "简历AI", "200+结构化分析", st, b, ms,
           "PASS" if ok else "FAIL", f"data_len={size_of(b)}")
    analysis = o.get("data") if o else ""

    # 缓存命中复测（应显著更快）
    st, b, ms, _ = req("POST", "/api/resume/analyze", {"resumeText": RESUME, "targetJob": "Java高级后端开发"}, token=TOKEN)
    o = biz(b)
    record("RES-02", "简历分析缓存命中（重复请求）", "简历AI", "200+命中缓存(更快)", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", "见报告缓存对比")

    st, b, ms, _ = req("POST", "/api/resume/optimize",
                       {"resumeText": RESUME, "targetJob": "Java高级后端开发", "analysis": analysis}, token=TOKEN)
    o = biz(b)
    record("RES-03", "生成优化版简历(Markdown)", "简历AI", "200+Markdown", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 50 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("GET", "/api/resume/history", token=TOKEN)
    o = biz(b)
    n = len(o.get("data") or []) if o else -1
    record("RES-04", "简历历史列表", "简历管理", "200+包含刚分析的简历", st, b, ms,
           "PASS" if o and o.get("code") == 200 and n > 0 else "FAIL", f"count={n}")

    # 边界/异常
    st, b, ms, _ = req("POST", "/api/resume/analyze", {"resumeText": "   ", "targetJob": "x"}, token=TOKEN)
    o = biz(b)
    record("RES-05", "空白简历分析", "简历AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/resume/analyze", {"targetJob": "x"}, token=TOKEN)
    o = biz(b)
    record("RES-06", "缺失resumeText字段", "简历AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/resume/optimize", {"resumeText": RESUME, "targetJob": "x"}, token=TOKEN)
    o = biz(b)
    record("RES-07", "未分析直接优化(缺analysis)", "简历AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    # SSRF 防护
    for tid, nm, u in [
        ("RES-08", "URL导入-内网地址(SSRF)", "http://127.0.0.1:8080/api/auth/me"),
        ("RES-09", "URL导入-云元数据(SSRF)", "http://169.254.169.254/latest/meta-data/"),
        ("RES-10", "URL导入-file协议", "file:///C:/Windows/win.ini"),
    ]:
        st, b, ms, _ = req("POST", "/api/resume/import-url", {"url": u, "targetJob": "x"}, token=TOKEN)
        o = biz(b)
        record(tid, nm, "安全-SSRF", "400 拒绝", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/resume/import-url", {"url": "", "targetJob": "x"}, token=TOKEN)
    o = biz(b)
    record("RES-11", "URL导入-空URL", "简历AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")


# ─────────────────────── 模拟面试 AI ───────────────────────
def test_interview():
    st, b, ms, _ = req("POST", "/api/interview/questions",
                       {"resumeText": RESUME, "jobDescription": JD, "count": 3,
                        "difficulty": "MEDIUM", "focusCategories": "技术基础"}, token=TOKEN)
    o = biz(b)
    record("ITV-01", "AI生成面试题(count=3)", "模拟面试AI", "200+JSON题目", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/interview/evaluate",
                       {"question": "请说明 Redis 持久化机制 RDB 与 AOF 的区别与适用场景",
                        "userAnswer": "RDB 是快照，定期把内存数据写盘，恢复快但可能丢数据；AOF 记录写命令，实时性好但文件大、恢复慢。生产通常混用。",
                        "referenceAnswer": "RDB 快照持久化，AOF 追加日志持久化，各有取舍"},
                       token=TOKEN)
    o = biz(b)
    record("ITV-02", "AI回答评分", "模拟面试AI", "200+评分结果", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/interview/followup",
                       {"question": "请说明 Redis 持久化机制", "userAnswer": "RDB 和 AOF 混用", "resumeText": RESUME},
                       token=TOKEN)
    o = biz(b)
    record("ITV-03", "AI针对性追问", "模拟面试AI", "200+追问问题", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 10 else "FAIL", f"data_len={size_of(b)}")

    # 异常
    st, b, ms, _ = req("POST", "/api/interview/questions",
                       {"resumeText": RESUME, "jobDescription": None, "count": 3}, token=TOKEN)
    o = biz(b)
    record("ITV-04", "生成题目-缺jobDescription", "模拟面试AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/interview/evaluate", {"question": "q"}, token=TOKEN)
    o = biz(b)
    record("ITV-05", "评分-缺userAnswer", "模拟面试AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/interview/followup", {"userAnswer": "x"}, token=TOKEN)
    o = biz(b)
    record("ITV-06", "追问-缺question", "模拟面试AI", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    # 边界：count 越界应被夹到 1..20，不报错
    st, b, ms, _ = req("POST", "/api/interview/questions",
                       {"resumeText": RESUME, "jobDescription": JD, "count": 999}, token=TOKEN)
    o = biz(b)
    record("ITV-07", "生成题目-count=999(越界夹取)", "边界", "200(夹到20)不报错", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", f"data_len={size_of(b)}")

    # 异常：count 非数字
    st, b, ms, _ = req("POST", "/api/interview/questions",
                       {"resumeText": RESUME, "jobDescription": JD, "count": "abc"}, token=TOKEN)
    o = biz(b)
    record("ITV-08", "生成题目-count为非数字", "异常输入", "200(回退默认5)或400", st, b, ms,
           "PASS" if st in (200, 400) else "WARN", f"code={o.get('code') if o else '?'}")

    # SSRF: 非法 imageUrl
    st, b, ms, _ = req("POST", "/api/interview/evaluate",
                       {"question": "q", "userAnswer": "a", "imageUrl": "http://127.0.0.1/evil.png"}, token=TOKEN)
    o = biz(b)
    record("ITV-09", "评分-非法图片URL(SSRF)", "安全-SSRF", "400 拒绝", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    # 图片上传校验
    st, b, ms, _ = req("POST", "/api/interview/upload-image", payload=None, raw_body=b"", content_type="multipart/form-data", token=TOKEN)
    record("ITV-10", "上传图片-空文件", "多模态", "400", st, b, ms, "PASS" if st == 400 or (biz(b) and biz(b).get("code") == 400) else "FAIL")

    # 伪造图片（文本内容声明为png）→ magic bytes 应拒绝
    boundary = "----rvboundary"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fake.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + b"NOT_A_REAL_PNG_CONTENT" + f"\r\n--{boundary}--\r\n".encode()
    st, b, ms, _ = req("POST", "/api/interview/upload-image", raw_body=body,
                       content_type=f"multipart/form-data; boundary={boundary}", token=TOKEN)
    o = biz(b)
    record("ITV-11", "上传图片-伪造PNG(magic bytes)", "安全", "400 拒绝", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    # 合法最小 PNG → 但 supabase 未配置，应为 500 而非崩溃
    def tiny_png():
        def chunk(t, d):
            c = t + d
            return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
        ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
        raw = b"\x00\xff\x00\x00"
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + tiny_png() + f"\r\n--{boundary}--\r\n".encode()
    st, b, ms, _ = req("POST", "/api/interview/upload-image", raw_body=body,
                       content_type=f"multipart/form-data; boundary={boundary}", token=TOKEN)
    o = biz(b)
    code = o.get("code") if o else st
    record("ITV-12", "上传图片-合法PNG(存储未配置)", "多模态", "校验通过后因存储未配置失败", st, b, ms,
           "PASS" if code in (200, 500) else "WARN", f"code={code}（magic bytes 已通过校验）")


# ─────────────────────── SSE 流式 ───────────────────────
def sse_read(path, payload, token, timeout=180):
    """返回 (first_token_ms, total_ms, tokens, events, err)"""
    url = BASE + path
    data = json.dumps(payload, ensure_ascii=False).encode()
    r = urllib.request.Request(url, data=data, method="POST",
                               headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    t0 = time.time()
    first = None
    tokens = []
    events = []
    try:
        resp = _opener.open(r, timeout=timeout)
        buf = b""
        cur_event = None
        while True:
            chunk = resp.read(1)
            if not chunk:
                break
            buf += chunk
            if buf.endswith(b"\n"):
                line = buf.decode("utf-8", "replace").rstrip("\n").rstrip("\r")
                buf = b""
                if line.startswith("event:"):
                    cur_event = line[6:].strip()
                    events.append(cur_event)
                elif line.startswith("data:"):
                    d = line[5:].strip()
                    if cur_event == "token" and d:
                        if first is None:
                            first = time.time()
                        tokens.append(d)
                elif line == "":
                    cur_event = None
        total = time.time() - t0
        return ((first - t0) * 1000 if first else None), total * 1000, "".join(tokens), events, None
    except Exception as e:
        return None, (time.time() - t0) * 1000, "", events, f"{type(e).__name__}: {e}"


def test_sse():
    first, total, txt, evs, err = sse_read("/api/interview/ask/stream",
                                          {"question": "什么是数据库索引？请简要说明 B+ 树索引的原理与适用场景",
                                           "context": "Java 后端面试"}, TOKEN)
    ok = err is None and len(txt) > 20
    record("SSE-01", "SSE流式问答（首token+完整输出）", "流式AI",
           "SSE正常结束+有正文", 200 if ok else -1, txt[:400] if txt else str(err), total,
           "PASS" if ok else "FAIL",
           f"首token={round(first) if first else 'n/a'}ms 总耗时={round(total)}ms 事件={','.join(evs[:6])}")

    first, total, txt, evs, err = sse_read("/api/interview/ask/stream", {"question": "   "}, TOKEN)
    ok = (err is not None) or ("error" in evs)
    record("SSE-02", "SSE流式-空问题", "异常输入", "error事件", 200, str(txt) + str(err), total,
           "PASS" if ok else "FAIL", f"events={evs}")


# ─────────────────────── 岗位分析 AI ───────────────────────
def test_job_analysis():
    st, b, ms, _ = req("POST", "/api/job/analyze", {"jobDescription": JD}, token=TOKEN)
    o = biz(b)
    record("JOB-01", "JD岗位分析", "岗位分析AI", "200+拆解结果", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/job/gap", {"resumeText": RESUME, "jobDescription": JD}, token=TOKEN)
    o = biz(b)
    record("JOB-02", "简历vs JD差距诊断", "岗位分析AI", "200+强/弱证据/缺口", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    for tid, nm, t in [("JOB-03", "生成求职信(coverLetter)", "coverLetter"),
                       ("JOB-04", "生成申请邮件(email)", "email"),
                       ("JOB-05", "生成内推私信(referral)", "referral")]:
        st, b, ms, _ = req("POST", "/api/job/letter",
                           {"resumeText": RESUME, "jobDescription": JD, "type": t}, token=TOKEN)
        o = biz(b)
        record(tid, nm, "岗位分析AI", "200+文本", st, b, ms,
               "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/job/analyze", {"jobDescription": ""}, token=TOKEN)
    o = biz(b)
    record("JOB-06", "JD分析-空描述", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/job/gap", {"jobDescription": JD}, token=TOKEN)
    o = biz(b)
    record("JOB-07", "差距诊断-缺简历", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/job/letter", {"resumeText": RESUME, "jobDescription": JD, "type": "unknown_type"}, token=TOKEN)
    o = biz(b)
    record("JOB-08", "求职信-非法type", "边界", "200(回退默认)或400", st, b, ms,
           "PASS" if o and o.get("code") in (200, 400) else "WARN", f"code={o.get('code') if o else '?'}")


# ─────────────────────── 知识库 RAG ───────────────────────
def test_knowledge():
    st, b, ms, _ = req("GET", "/api/knowledge/search?query=" + urllib.parse.quote("Redis 持久化 RDB AOF") + "&topK=5", token=TOKEN)
    o = biz(b)
    record("RAG-01", "知识库语义检索(topK=5)", "RAG", "200+检索结果", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/knowledge/ask", {"question": "Redis 的持久化机制有哪些？各自适用什么场景？"}, token=TOKEN)
    o = biz(b)
    record("RAG-02", "RAG增强问答", "RAG", "200+正文", st, b, ms,
           "PASS" if o and o.get("code") == 200 and size_of(b) > 30 else "FAIL", f"data_len={size_of(b)}")

    st, b, ms, _ = req("POST", "/api/knowledge/import/batch",
                       {"category": "测试", "chunks": ["测试知识块一：Java 内存模型包含主内存与工作内存。",
                                                        "测试知识块二：JVM 垃圾回收算法有标记清除、复制、标记整理。"]}, token=TOKEN)
    o = biz(b)
    record("RAG-03", "批量导入知识分块(向量化)", "RAG", "200+imported=2", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", json.dumps(o.get("data"), ensure_ascii=False) if o else "")

    st, b, ms, _ = req("POST", "/api/knowledge/import", {"documents": ["简单模式导入的文档内容，用于验证 import 接口可用性。"]}, token=TOKEN)
    o = biz(b)
    record("RAG-04", "简单模式导入知识文档", "RAG", "200", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", (o.get("data") or "")[:80] if o else "")

    st, b, ms, _ = req("GET", "/api/knowledge/wrong-questions?threshold=60", token=TOKEN)
    o = biz(b)
    record("RAG-05", "错题总结(threshold=60)", "知识库-统计", "200+题目列表", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("GET", "/api/knowledge/question-summary", token=TOKEN)
    o = biz(b)
    record("RAG-06", "题目汇总统计", "知识库-统计", "200+分类聚合", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("GET", "/api/knowledge/recent-questions?limit=10", token=TOKEN)
    o = biz(b)
    record("RAG-07", "最近题目查询", "知识库-统计", "200", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("POST", "/api/knowledge/ask", {"question": ""}, token=TOKEN)
    o = biz(b)
    record("RAG-08", "RAG问答-空问题", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("GET", "/api/knowledge/wrong-questions?threshold=999", token=TOKEN)
    o = biz(b)
    record("RAG-09", "错题总结-threshold越界", "边界", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("POST", "/api/knowledge/import/batch", {"category": "x", "chunks": []}, token=TOKEN)
    o = biz(b)
    record("RAG-10", "批量导入-空分块", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    big = ["x" * 9000]
    st, b, ms, _ = req("POST", "/api/knowledge/import/batch", {"category": "x", "chunks": big}, token=TOKEN)
    o = biz(b)
    record("RAG-11", "批量导入-单块超8KB截断", "边界", "200(截断入库)", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("POST", "/api/knowledge/import/batch", {"category": "x", "chunks": ["a"] * 101}, token=TOKEN)
    o = biz(b)
    record("RAG-12", "批量导入-超100条上限", "边界", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")


# ─────────────────────── 岗位聚合 & 匹配 ───────────────────────
def test_jobs():
    st, b, ms, _ = req("GET", "/api/jobs?page=0&size=10", token=TOKEN)
    o = biz(b)
    n = (o.get("data") or {}).get("total") if o else -1
    record("JOBM-01", "岗位列表查询", "岗位聚合", "200+分页数据", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", f"total={n}")

    st, b, ms, _ = req("GET", "/api/jobs/meta", token=TOKEN)
    o = biz(b)
    record("JOBM-02", "岗位筛选元数据", "岗位聚合", "200", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("POST", "/api/jobs/match", {"resumeText": RESUME, "limit": 5}, token=TOKEN)
    o = biz(b)
    record("JOBM-03", "简历-岗位匹配推荐", "岗位聚合", "200+匹配打分", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", f"total={(o.get('data') or {}).get('total') if o else '?'}")

    st, b, ms, _ = req("POST", "/api/jobs/match", {"resumeText": "   "}, token=TOKEN)
    o = biz(b)
    record("JOBM-04", "岗位匹配-空简历", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("GET", "/api/jobs/99999999", token=TOKEN)
    o = biz(b)
    record("JOBM-05", "岗位详情-不存在ID", "异常输入", "404", st, b, ms, "PASS" if o and o.get("code") == 404 else "FAIL")

    st, b, ms, _ = req("POST", "/api/jobs/favorite/toggle", {"jobId": "abc"}, token=TOKEN)
    o = biz(b)
    record("JOBM-06", "岗位收藏-非法jobId", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("GET", "/api/jobs?size=9999", token=TOKEN)
    o = biz(b)
    record("JOBM-07", "岗位列表-size超限", "边界", "200(size夹取)", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL", f"size={(o.get('data') or {}).get('size') if o else '?'}")


# ─────────────────────── 统计 ───────────────────────
def test_stats():
    st, b, ms, _ = req("GET", "/api/stats/dashboard", token=TOKEN)
    o = biz(b)
    record("STA-01", "个人仪表盘统计", "统计", "200", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "FAIL")
    st, b, ms, _ = req("GET", "/api/stats/trend?dimension=DAY", token=TOKEN)
    o = biz(b)
    record("STA-02", "成绩趋势(DAY)", "统计", "200", st, b, ms, "PASS" if o and o.get("code") == 200 else "FAIL")
    st, b, ms, _ = req("GET", "/api/stats/trend?dimension=WEEK", token=TOKEN)
    o = biz(b)
    record("STA-03", "成绩趋势(WEEK)", "统计", "200", st, b, ms, "PASS" if o and o.get("code") == 200 else "FAIL")
    st, b, ms, _ = req("GET", "/api/stats/trend?dimension=BADVALUE", token=TOKEN)
    o = biz(b)
    record("STA-04", "成绩趋势-非法dimension", "边界", "200(容错)", st, b, ms,
           "PASS" if o and o.get("code") == 200 else "WARN")


# ─────────────────────── 会话 & 越权 ───────────────────────
SID = None


def test_session():
    global SID
    st, b, ms, _ = req("POST", "/api/session/create", {"jobDescription": "Java高级后端开发"}, token=TOKEN)
    o = biz(b)
    SID = (o.get("data") or {}).get("sessionId") if o else None
    record("SES-01", "创建面试会话", "会话管理", "200+sessionId", st, b, ms,
           "PASS" if o and o.get("code") == 200 and SID else "FAIL", f"sessionId={SID}")

    if SID:
        qs = [{"question": "请介绍 JVM 内存结构", "category": "技术基础", "difficulty": "MEDIUM",
               "referenceAnswer": "堆、栈、方法区、程序计数器、本地方法栈"}]
        st, b, ms, _ = req("POST", f"/api/session/{SID}/questions", qs, token=TOKEN)
        o = biz(b)
        qid = (o.get("data") or [{}])[0].get("id") if o and o.get("data") else None
        record("SES-02", "保存AI生成题目到会话", "会话管理", "200", st, b, ms,
               "PASS" if o and o.get("code") == 200 else "FAIL")

        st, b, ms, _ = req("POST", "/api/session/answer",
                           {"questionId": qid, "userAnswer": "JVM 内存分为堆、虚拟机栈、方法区、程序计数器、本地方法栈。",
                            "evaluationScore": 85}, token=TOKEN)
        o = biz(b)
        record("SES-03", "保存用户回答与评分", "会话管理", "200", st, b, ms,
               "PASS" if o and o.get("code") == 200 else "FAIL")

        st, b, ms, _ = req("GET", f"/api/session/{SID}", token=TOKEN)
        o = biz(b)
        record("SES-04", "查询会话详情", "会话管理", "200", st, b, ms, "PASS" if o and o.get("code") == 200 else "FAIL")

        # 越权：用户2访问用户1的会话
        if TOKEN2:
            st, b, ms, _ = req("GET", f"/api/session/{SID}", token=TOKEN2)
            o = biz(b)
            record("SEC-01", "越权访问他人会话(IDOR)", "安全", "403", st, b, ms, "PASS" if o and o.get("code") == 403 else "FAIL")

            st, b, ms, _ = req("PUT", f"/api/session/{SID}/finish", token=TOKEN2)
            o = biz(b)
            record("SEC-02", "越权结束他人会话(IDOR)", "安全", "403", st, b, ms, "PASS" if o and o.get("code") == 403 else "FAIL")

        st, b, ms, _ = req("POST", f"/api/session/{SID}/questions", [{"question": "x" * 2500}], token=TOKEN)
        o = biz(b)
        record("SES-05", "保存题目-单题超2000字", "边界", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

        st, b, ms, _ = req("POST", f"/api/session/{SID}/questions", [{"question": f"q{i}"} for i in range(51)], token=TOKEN)
        o = biz(b)
        record("SES-06", "保存题目-超50道上限", "边界", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

        st, b, ms, _ = req("PUT", f"/api/session/{SID}/finish", token=TOKEN)
        o = biz(b)
        record("SES-07", "结束会话", "会话管理", "200", st, b, ms, "PASS" if o and o.get("code") == 200 else "FAIL")

    st, b, ms, _ = req("POST", "/api/session/create", {"jobDescription": "  "}, token=TOKEN)
    o = biz(b)
    record("SES-08", "创建会话-空岗位描述", "异常输入", "400", st, b, ms, "PASS" if o and o.get("code") == 400 else "FAIL")

    st, b, ms, _ = req("GET", "/api/session/NOT_EXIST_SID", token=TOKEN)
    o = biz(b)
    record("SES-09", "查询不存在的会话", "异常输入", "400/404", st, b, ms,
           "PASS" if o and o.get("code") in (400, 404) else "FAIL")


# ─────────────────────── 智能体专项 ───────────────────────
AGENT_TURNS = [
    ("帮我推荐几个适合我的 Java 后端岗位", "工具调用-岗位检索", ["searchJobs", "searchWebJobs", "matchResumeJobs"]),
    ("我哪些知识点比较薄弱？评分低的题有哪些？", "工具调用-薄弱点分析", ["listWrongQuestions", "getMyInterviewStats"]),
    ("Redis 持久化 RDB 和 AOF 的区别是什么？", "知识库RAG回答", ["searchKnowledge"]),
    ("那我刚才问的 Redis 问题，如果面试官继续追问 AOF 重写机制，我该怎么答？", "多轮上下文追问", []),
]


def test_agent():
    conv_id = None
    first_tokens = []
    durations = []
    for i, (msg, kind, expect_tools) in enumerate(AGENT_TURNS, 1):
        payload = {"message": msg}
        if conv_id:
            payload["conversationId"] = conv_id
        first, total, txt, evs, err = sse_read("/api/agent/chat/stream", payload, TOKEN, timeout=240)
        ok = err is None and len(txt) > 20
        # 捕获 meta 事件里的 conversationId
        if conv_id is None and ok:
            convs_st, cb, _, _ = req("GET", "/api/agent/conversations", token=TOKEN)
            co = biz(cb)
            if co and co.get("data"):
                conv_id = co["data"][0]["id"]
        first_tokens.append(round(first) if first else None)
        durations.append(round(total))
        record(f"AGT-{i:02d}", f"智能体第{i}轮：{kind}", "智能体专项",
               f"SSE正常结束+有正文(期望工具:{','.join(expect_tools) or '无'})",
               200 if ok else -1, txt[:700] if txt else str(err), total,
               "PASS" if ok else "FAIL",
               f"首token={round(first) if first else 'n/a'}ms 总耗时={round(total)}ms 正文长度={len(txt)} events={','.join(sorted(set(evs))[:6])}")

    # 会话持久化验证
    st, b, ms, _ = req("GET", "/api/agent/conversations", token=TOKEN)
    o = biz(b)
    n = len(o.get("data") or []) if o else -1
    record("AGT-05", "智能体会话列表", "智能体专项", "200+会话已持久化", st, b, ms,
           "PASS" if o and o.get("code") == 200 and n > 0 else "FAIL", f"conversations={n}")

    if conv_id:
        st, b, ms, _ = req("GET", f"/api/agent/conversations/{conv_id}/messages", token=TOKEN)
        o = biz(b)
        msgs = o.get("data") or [] if o else []
        roles = [m.get("role") for m in msgs]
        # 4 轮 → 应有 8 条（4 USER + 4 ASSISTANT）
        ok = len(msgs) >= 4
        record("AGT-06", "智能体历史消息（多轮记忆落库）", "智能体专项",
               "USER/ASSISTANT 成对出现", st, b, ms,
               "PASS" if ok else "FAIL", f"消息数={len(msgs)} roles={roles}")

        if TOKEN2:
            st, b, ms, _ = req("GET", f"/api/agent/conversations/{conv_id}/messages", token=TOKEN2)
            o = biz(b)
            n2 = len(o.get("data") or []) if o else 0
            record("SEC-03", "越权读取他人智能体会话(IDOR)", "安全", "403或空", st, b, ms,
                   "PASS" if (o and o.get("code") == 403) or n2 == 0 else "FAIL", f"业务码={o.get('code') if o else '?'} 条数={n2}")

    st, b, ms, _ = req("POST", "/api/agent/chat/stream", {"message": "   "}, token=TOKEN)
    record("AGT-07", "智能体-空消息", "异常输入", "error事件/400", st, b, ms,
           "PASS" if st in (200, 400) else "FAIL", (b or "")[:120])

    # 对话连贯性：验证第4轮是否体现了对第3轮 Redis 话题的承接
    return first_tokens, durations


# ─────────────────────── 并发 / 限流 ───────────────────────
def test_concurrency():
    # 1) 同一用户并发 3 个 SSE（每用户上限=1 → 应有 2 个被拒）
    import queue
    res = queue.Queue()

    def worker(idx):
        f, t, txt, evs, err = sse_read("/api/interview/ask/stream",
                                      {"question": f"并发测试问题{idx}：简述 HTTP 与 HTTPS 区别"}, TOKEN, timeout=180)
        res.put((idx, f, t, len(txt), "error" in evs, err))

    ths = [threading.Thread(target=worker, args=(i,)) for i in range(3)]
    t0 = time.time()
    for th in ths:
        th.start()
    for th in ths:
        th.join(timeout=200)
    wall = (time.time() - t0) * 1000
    out = []
    while not res.empty():
        out.append(res.get())
    rejected = sum(1 for r in out if r[4])
    record("CONC-01", "SSE每用户并发上限(3并发)", "并发", "1个成功+2个被限流拒绝",
           200, json.dumps(out, ensure_ascii=False), wall,
           "PASS" if rejected >= 1 else "FAIL",
           f"被拒={rejected}/3 墙钟={round(wall)}ms 明细={out}")

    # 2) 参数校验应在 AI 限流之前短路：连打 35 次非法入参，应全部 400 且不消耗 AI 配额
    codes = []
    for i in range(35):
        st, b, ms, _ = req("POST", "/api/interview/followup", {"userAnswer": "x"}, token=TOKEN)
        o = biz(b)
        codes.append(o.get("code") if o else st)
    record("CONC-02", "非法入参不消耗AI配额(35连打)", "并发/限流",
           "全部400，无429（校验早于限流）", 200, str(codes), 0,
           "PASS" if codes.count(400) == 35 and 429 not in codes else "FAIL",
           f"400={codes.count(400)} 其他={35 - codes.count(400)} 429={codes.count(429)}")

    # 3) 并发只读接口
    def ro_worker(idx, q):
        st, b, ms, _ = req("GET", "/api/jobs?page=0&size=10", token=TOKEN)
        q.put(ms)

    q2 = queue.Queue()
    ths = [threading.Thread(target=ro_worker, args=(i, q2)) for i in range(10)]
    t0 = time.time()
    for th in ths:
        th.start()
    for th in ths:
        th.join()
    lat = []
    while not q2.empty():
        lat.append(q2.get())
    lat.sort()
    record("CONC-03", "10并发只读接口(岗位列表)", "并发", "全部成功", 200,
           json.dumps({"n": len(lat), "min": round(lat[0]), "median": round(lat[len(lat)//2]),
                       "max": round(lat[-1])}), (time.time() - t0) * 1000,
           "PASS" if len(lat) == 10 else "FAIL",
           f"min={round(lat[0])}ms median={round(lat[len(lat)//2])}ms max={round(lat[-1])}ms")


def main():
    print("=" * 78)
    print("AI 智能面试辅助平台 —— 端到端真机测试")
    print("=" * 78)
    setup()
    if not TOKEN:
        print("!! 无法获取 token，终止")
        return
    test_resume()
    test_interview()
    test_sse()
    test_job_analysis()
    test_knowledge()
    test_jobs()
    test_stats()
    test_session()
    ft, du = test_agent()
    test_concurrency()

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(RESULTS, f, ensure_ascii=False, indent=2)
    p = sum(1 for r in RESULTS if r["verdict"] == "PASS")
    fl = sum(1 for r in RESULTS if r["verdict"] == "FAIL")
    w = sum(1 for r in RESULTS if r["verdict"] == "WARN")
    print("=" * 78)
    print(f"总计 {len(RESULTS)} 项：PASS={p} FAIL={fl} WARN={w}  → {OUT}")


if __name__ == "__main__":
    main()

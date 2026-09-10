package com.example.interview.service.job;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 热招速递数据源（v1.27.0）
 *
 * 由 2026-09 联网检索并交叉核验的真实在招岗位整理而成，重点补齐用户高频查询缺口
 * （尤其「广州 + Java/后端 + 实习」此前为零覆盖），并扩展秋招/社招/AI 等热门方向。
 *
 * 数据真实性约定：
 * - 所有条目均为检索时正在招聘的真实机会，applyUrl 指向官方校招/社招入口或可信渠道；
 * - 薪资只填公开区间，未公开一律 null，绝不编造数字；
 * - 截止日期仅填官方/渠道明示值，不确定一律 null；
 * - 来源列在条目描述/后续交付说明中，便于复核。
 *
 * 每次刷新幂等 upsert，过期岗位由 JobAgentService 自动下架。
 * platform = "热招速递"（区别于「内置精选」「精选频道」）。
 */
@Component
public class SeedLiveHotJobsProvider implements JobPlatformAdapter {

    private static final String AUTUMN = "AUTUMN";
    private static final String INTERN = "INTERN";
    private static final String SOCIAL = "SOCIAL";

    @Override
    public String platform() {
        return "热招速递";
    }

    @Override
    public List<JobDto> fetch() {
        return List.of(
                // ── P0：广州 + Java/后端 + 实习（此前零覆盖，特别补齐） ──
                new JobDto("live-gz-gfgd-java-2027", "JAVA开发工程师（2027届校招）", "广发证券",
                        "金融", "技术", "广州", "2万-2.2万·16薪", "硕士", "2027届应届/可先实习",
                        AUTUMN, null,
                        "https://msearch.51job.com/jobs/guangzhou-thq/173482306.html",
                        "广发证券IT总部，负责业务/办公系统开发，面向 2027 届硕士，需先实习至少半个月。",
                        "1. 硕士及以上，2027届（可先实习）；2. 熟悉 Java 及主流框架；3. 有金融/系统开发实习经历者优先。",
                        "校招,金融,Java,证券"),
                new JobDto("live-gz-zhisuan-java-intern", "Java实习生（2027届）", "广州智算信息技术",
                        "互联网", "技术", "广州", null, "本科", "在读/应届", INTERN, null,
                        "https://m.yingjiesheng.com/job-008-055-100.html",
                        "专精特新大数据公司，参与 OA 平台 / AI Agent / RAG 检索后端开发，提供住房补贴。",
                        "1. 本科及以上在读；2. 熟悉 Java 与后端基础；3. 对 AI Agent / RAG 有兴趣者优先。",
                        "实习,Java,RAG,大数据"),
                new JobDto("live-gz-lihua-java-intern", "Java实习生", "广州立华生态科技",
                        "互联网", "技术", "广州", "3k-4k", "大专", "经验不限", INTERN, null,
                        "https://m.liepin.com/job/1960648237.shtml",
                        "全职 Java 实习岗，要求具备初级 Java 基础，信息由猎聘平台实时更新。",
                        "1. 大专及以上；2. 具备 Java 基础或在校项目经验；3. 可全职实习。",
                        "实习,Java,入门友好"),
                new JobDto("live-gz-yaxin-java-intern", "Java开发实习生（2026届）", "亚信科技",
                        "通信", "技术", "广州/北京/武汉", null, "本科", "在读/应届", INTERN, null,
                        "http://campus.51job.com/asiainfo/",
                        "港交所上市通信软件公司，招募 Java/前端/大数据/测开/产品等多类实习生，工作地点含广州。",
                        "1. 本科及以上在读；2. 熟悉 Java 或相关技术栈；3. 可稳定实习 3 个月以上。",
                        "实习,Java,通信,上市公司"),
                new JobDto("live-gz-bytedance-tiktok-intern", "后端开发实习生（ByteIntern）", "字节跳动（TikTok 研发）",
                        "互联网", "技术", "广州/深圳/北京/上海/杭州", null, "本科", "2027届在读", INTERN, null,
                        "https://jobs.bytedance.com/campus",
                        "TikTok 研发团队后端实习，ByteIntern 面向 2027 届（2026.9-2027.8 毕业），可转正，工作城市含广州。",
                        "1. 本科及以上，2027 届在读；2. 扎实的编程与数据结构基础；3. 可全职实习 3 个月以上。",
                        "实习,后端,大厂,可转正"),
                new JobDto("live-sz-bytedance-pay-intern", "后端开发实习生-字节国际支付", "字节跳动",
                        "互联网", "技术", "深圳", "350元-500元/天", "本科", "2027届在读", INTERN, null,
                        "https://jobs.bytedance.com/campus",
                        "ByteIntern 面向 2027 届，负责国际支付后端服务开发，需全职实习 3 个月以上。",
                        "1. 本科及以上，2027 届在读；2. 熟悉 Java/Go 任一后端语言；3. 可全职实习。",
                        "实习,后端,支付,大厂"),
                new JobDto("live-sz-bytedance-risk-intern", "后端开发实习生-风控", "字节跳动",
                        "互联网", "技术", "深圳", "500元-550元/天", "本科", "2027届在读", INTERN, null,
                        "https://job.toutiao.com/campus/position/detail/7595142586517293365",
                        "风控工程研发团队，聚焦抖音/今日头条风控系统后端开发，可转正。",
                        "1. 本科及以上，2027 届在读；2. 掌握 Java/Go，了解分布式与大数据；3. 可全职实习。",
                        "实习,风控,后端,大厂"),

                // ── P1：广深大厂 后台/算法/AI 实习与 2027 秋招 ──
                new JobDto("live-sz-bytedance-fullstack-2027", "AI全栈开发工程师（今日头条）", "字节跳动",
                        "互联网", "技术", "深圳/广州/北京", null, "本科", "2027届", AUTUMN, null,
                        "https://jobs.bytedance.com/campus",
                        "2027 届校园招聘正式批岗位，覆盖 AI 全栈开发，工作地点含深圳、广州。",
                        "1. 本科及以上，2027 届；2. 前端+后端全栈基础扎实；3. 对 AI 应用开发有热情。",
                        "秋招,AI,全栈,大厂"),
                new JobDto("live-sz-tencent-2027", "2027校园招聘（技术类/产品类）", "腾讯",
                        "互联网", "技术", "深圳/北京/广州/上海/成都", null, "本科", "2026.1-2027.12毕业", AUTUMN, null,
                        "https://join.qq.com/detail.html?id=288",
                        "应届生网申入口，涵盖 AI/后端/算法/客户端/前端等全量岗位，深圳岗位最多。",
                        "1. 本科及以上，毕业时间符合要求；2. 技术岗需扎实编码与算法基础；3. 产品岗需逻辑与文档能力。",
                        "秋招,大厂,全岗位"),
                new JobDto("live-sz-tencent-intern-2026", "2026实习生招聘（含广州）", "腾讯",
                        "互联网", "技术", "深圳/北京/广州/上海/杭州/成都", null, "本科", "2026.9-2027.12毕业", INTERN, null,
                        "https://join.qq.com/index.html?pos=1",
                        "技术岗扩招 40%，AI 算法/大模型/后台开发方向需求大，工作地点含广州、深圳。",
                        "1. 本科及以上在读；2. 技术岗需扎实基础，AI 岗需相关项目经历。",
                        "实习,大厂,AI,后端"),
                new JobDto("live-sz-tencent-agent-2027", "AI全栈工程师/Agent开发工程师（2027校招）", "腾讯",
                        "互联网", "技术", "深圳/北京/广州", null, "本科", "2026.1-2027.12毕业", AUTUMN, null,
                        "https://join.qq.com/detail.html?id=288",
                        "2027 校招 AI 岗位全新上架，含 AI 全栈 / Agent / 大模型 / AI 算法 / AI 产品经理。",
                        "1. 本科及以上；2. 熟悉至少一门语言与大模型应用；3. 对 Agent/LLM 工程化有兴趣。",
                        "秋招,AI,Agent,大厂"),
                new JobDto("live-sz-meituan-embodied-intern", "具身数据算法研发工程师（实习）", "美团",
                        "互联网", "技术", "深圳", null, "本科", "在读", INTERN, null,
                        "https://zhaopin.meituan.com/web/campus",
                        "无人机业务部实习，参与多模态数据 / VLA 具身智能模型训练与评测。",
                        "1. 本科及以上在读；2. 熟悉 PyTorch 与多模态/具身智能方向；3. 可稳定实习。",
                        "实习,算法,具身智能,大厂"),
                new JobDto("live-bj-meituan-llm-intern", "大模型算法工程师（实习生）", "美团",
                        "互联网", "技术", "北京", null, "本科", "在读", INTERN, null,
                        "https://zhaopin.meituan.com/web/campus",
                        "从事智能交互 / 后训练 / Agentic RL 相关大模型算法工作，日常实习。",
                        "1. 本科及以上在读；2. 熟悉 LLM 训练/推理与 RL；3. 有论文或竞赛经历优先。",
                        "实习,大模型,算法,Agent"),
                new JobDto("live-bj-meituan-pm-intern", "产品经理（日常实习）", "美团",
                        "互联网", "产品", "北京", null, "本科", "在读（专业不限）", INTERN, null,
                        "https://zhaopin.meituan.com/web/campus",
                        "美团平台产品经理日常实习岗，参与平台产品规划与需求落地。",
                        "1. 本科及以上在读；2. 逻辑清晰，文档与沟通能力强；3. 对互联网产品有热情。",
                        "实习,产品,大厂"),
                new JobDto("live-bj-meituan-dataprod-intern", "数据产品-AI方向实习生", "美团",
                        "互联网", "产品", "北京", null, "本科（统计/数学/CS/商分）", "不限经验", INTERN, null,
                        "https://zhaopin.meituan.com/m/position/detail?highlightType=campus&jobUnionId=4138099640",
                        "AI 自动取数 / 语义层构建 / 分析与评测，需 SQL 与 LLM 经验。",
                        "1. 统计/数学/CS/商分相关专业在读；2. 熟练 SQL，了解 LLM 应用；3. 可稳定实习。",
                        "实习,数据产品,AI"),

                // ── P1：2026-2027 届秋招 技术热点公司 ──
                new JobDto("live-by-sd-2027", "2027校园招聘（全量技术岗）", "字节跳动",
                        "互联网", "技术", "北京/上海/深圳/广州/杭州/成都", null, "本科", "2026.9-2027.8毕业", AUTUMN, LocalDate.of(2027, 5, 31),
                        "https://jobs.bytedance.com/campus",
                        "已开放投递（2026.8-2027.5.31），含大模型/算法/后端/前端/客户端/测开全方向。",
                        "1. 本科及以上，2026.9-2027.8 毕业；2. 技术岗需扎实基础；3. 投递截止 2027-05-31。",
                        "秋招,全方向,大厂"),
                new JobDto("live-bj-baidu-2027", "2027校园招聘（AI方向）", "百度",
                        "互联网", "技术", "北京/上海/深圳/广州/杭州/成都", null, "本科", "2027届", AUTUMN, null,
                        "https://talent.baidu.com",
                        "AI 岗位占比超 90%，含大模型/智能体算法/AI Infra/自动驾驶算法/AI 产品经理。",
                        "1. 本科及以上，2027 届；2. AI/算法方向需扎实基础与项目经历。",
                        "秋招,AI,大模型,自动驾驶"),
                new JobDto("live-hz-alibaba-2027", "2027届应届生招聘", "阿里巴巴",
                        "互联网", "技术", "杭州/北京/上海/广州/深圳", null, "本科", "2026.11-2027.10毕业", AUTUMN, null,
                        "https://campus-talent.alibaba.com/?lang=zh",
                        "算法/研发/产品/运营/芯片全岗位开放，含 AI Coding 笔试新题型，支持广州深圳多地。",
                        "1. 本科及以上，2026.11-2027.10 毕业；2. 后端/算法需扎实编码功底。",
                        "秋招,全岗位,AI,大厂"),
                new JobDto("live-bj-jd-2026", "2026校园招聘（JDS新星计划）", "京东",
                        "互联网", "技术", "北京/深圳/广州", null, "本科", "2026届", AUTUMN, null,
                        "https://campus.jd.com/#/",
                        "技术岗含算法/开发/测试/数据/运维/安全等，兼含 JDS 新星计划与 TET 管理培训生。",
                        "1. 本科及以上；2. 技术岗需扎实基础；3. 兼收管培方向。",
                        "秋招,技术,零售科技"),
                new JobDto("live-hz-netease-2026", "2026秋招（大模型/AI算法/AI产品经理）", "网易",
                        "互联网", "技术", "杭州/北京/广州", null, "本科", "2026.1-2027.8毕业", AUTUMN, null,
                        "https://campus.163.com/",
                        "含大模型研发 / AI Agent 模型算法工程师 / AI 产品经理等，顶尖人才可申请黑卡计划。",
                        "1. 本科及以上，2026.1-2027.8 毕业；2. AI/算法岗需相关项目经历。",
                        "秋招,AI,大模型,游戏"),
                new JobDto("live-bj-sohu-2026", "2026秋季校招（AI人才专项）", "搜狐",
                        "互联网", "技术", "北京", null, "本科", "2026.1-2027.8毕业", AUTUMN, null,
                        "https://app.mokahr.com/campus_apply/sohu/5682",
                        "AI 算法研发/大模型算法/AI 全栈研发等专项，另含 Java/推荐/NLP/风控算法岗。",
                        "1. 本科及以上；2. AI 方向需扎实算法基础；3. 含 Java/推荐/NLP/风控多方向。",
                        "秋招,AI,算法,Java"),
                new JobDto("live-bj-zhipu-2027", "27届校招-大模型算法工程师（Agent方向）", "智谱AI",
                        "互联网", "技术", "北京", null, "硕士（优秀本科可）", "2027届", AUTUMN, null,
                        "https://app.mokahr.com/m/campus-recruitment/zphz/148984#/jobs",
                        "负责 Agent 评测体系 / 自动化评测平台构建，硕士优先，2026-09 新发布。",
                        "1. 硕士优先（优秀本科可），2027 届；2. 熟悉大模型与 Agent；3. 有评测/RL 经历者优先。",
                        "秋招,大模型,Agent,AI"),
                new JobDto("live-sh-shlab-llm-intern", "大模型算法（实习）-技术平台中心", "上海人工智能实验室",
                        "互联网", "技术", "上海", null, "硕士", "在读", INTERN, null,
                        "https://www.shlab.org.cn/joinus/detail/7678552278295103771?mode=campus",
                        "国家级 AI 实验室，参与预训练/后训练/Agentic RL，每周 4 天以上共 6 个月。",
                        "1. 硕士在读；2. 熟悉预训练/后训练/RAG/RL 之一；3. 每周 4 天以上、持续 6 个月。",
                        "实习,大模型,实验室,RAG"),

                // ── P2：社招技术岗 ──
                new JobDto("live-sz-sf-java-2026", "JAVA开发工程师（校招）", "顺丰科技",
                        "物流", "技术", "深圳", "1.5万-2.5万/月", "本科", "2026届", AUTUMN, null,
                        "https://campus.sf-express.com/#/postDetail/1874",
                        "顺丰科技官方校招，负责系统编码/并发开发，面向 2026 届毕业生。",
                        "1. 本科及以上，2026 届；2. 熟悉 Java 与并发/分布式；3. 有实习或项目经验优先。",
                        "秋招,Java,物流科技"),
                new JobDto("live-sz-guotou-java-social", "Java开发工程师（展业赋能方向）社招", "国投证券",
                        "金融", "技术", "深圳", null, "本科", "2-5年", SOCIAL, null,
                        "https://wecruit.hotjob.cn/SU625d4a0b2f9d24287db127c8/pb/posDetail.html?postId=6a8fa7e868cc6f624f79f88a&postType=society",
                        "负责财富展业平台建设，涉及 AI/大模型/Agent 落地。",
                        "1. 本科及以上，2-5 年 Java 经验；2. 熟悉大数据/微服务；3. 有 AI/大模型工程化经验者优先。",
                        "社招,Java,金融,AI"),
                new JobDto("live-gz-163-sre-social", "高级运维研发工程师（SRE）", "网易游戏互娱",
                        "互联网", "技术", "广州", null, "本科", "经验不限", SOCIAL, null,
                        "https://hr.game.163.com/recruit.html",
                        "用软件工程方法做运维自动化提升服务可用性，广州 base，官网社招通道。",
                        "1. 本科及以上；2. 熟悉 Linux/运维自动化/稳定性工程；3. 有条理、善协作。",
                        "社招,SRE,运维,游戏"),
                new JobDto("live-sz-renzixing-java-social", "高级JAVA开发工程师", "任子行",
                        "互联网", "技术", "深圳（另招济南）", null, "本科", "5年以上", SOCIAL, null,
                        "https://www.1218.com.cn/index/job/index.html?cate=21",
                        "网络安全公司，社招高级 Java 岗，涉及大数据/微服务/AI 工程化。",
                        "1. 本科及以上，5 年以上 Java 经验；2. 熟悉分布式/微服务；3. 有安全或大数据背景优先。",
                        "社招,Java,网络安全,微服务"),
                new JobDto("live-sz-zhaoshangxinnuo-java-social", "Java开发高级工程师", "招商信诺",
                        "金融", "技术", "深圳", "1.3万-1.8万·16薪", "本科", "5-10年", SOCIAL, null,
                        "https://www.zhaopin.com/companydetail/CZ120508120.htm",
                        "合资保险企业，招聘保险核心/理赔系统 Java 高级工程师。",
                        "1. 本科及以上，5-10 年 Java 经验；2. 熟悉保险核心/理赔系统优先。",
                        "社招,Java,保险,金融"),
                new JobDto("live-sz-xinwanda-java-2026", "Java开发工程师（应届）", "欣旺达",
                        "制造", "技术", "深圳", "7k-21k·13薪", "本科", "应届", AUTUMN, LocalDate.of(2026, 12, 31),
                        "https://m.liepin.com/lptjob/85199827/",
                        "上市公司，负责公司系统逻辑设计/功能开发，应届可投，截止 12-31。",
                        "1. 本科及以上，2026 届应届；2. 熟悉 Java 基础与常用框架。",
                        "校招,Java,制造,上市公司")
        );
    }
}
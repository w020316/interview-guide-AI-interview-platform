package com.example.interview.config;

import com.example.interview.service.RagHealthTracker;
import com.example.interview.service.RagSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统预置共享知识库播种器
 *
 * ── 背景（2026-09-19 真机验证发现）────────────────────────────────────
 * RAG 问答此前对**所有用户**恒返回「参考资料中没有相关内容」，AI 只能凭通用知识作答。
 *
 * 根因有两层：
 * 1. 向量库检索走 `userId = 当前用户 OR shared = true` 过滤，而新用户导入数为 0，
 *    也没有任何 `shared=true` 的文档；
 * 2. `schema.sql` 里确有 5 条预置知识（knowledge_doc 表），但**全项目没有任何代码
 *    把它写入向量库** —— 表面有"预置知识"，实际检索侧完全空转。
 *
 * 本类补齐第 2 层：应用启动后把内置的面试八股知识写入向量库，并以 `shared=true`
 * 标记为全局共享，任意用户检索时都能命中。
 *
 * ── 设计要点 ────────────────────────────────────────────────────────
 * - `@Order` 排在 SchemaInitializer 之后（后者 @Order 默认最低，vector 表由
 *   Spring AI 的 pgvector initialize-schema 建）。
 * - 幂等：单实例进程内只播种一次；`app.rag.seed-enabled=false` 可关闭（本地联调/测试用）。
 * - 失败不阻断启动：embedding 未配置/不可用时仅告警，登录等非 AI 功能不受影响
 *   （与 v1.33.1「AI 配置不阻断启动」原则一致）。
 * - 播种走 {@link RagSearchService#addToVectorStore} 统一入口，受向量库容量上限保护。
 */
@Component
@Order(100)
public class KnowledgeSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeedInitializer.class);

    /** 与 RagSearchService 约定的共享标记（metadata key=shared, value=true） */
    private static final String META_SHARED = "shared";

    private final RagSearchService ragSearchService;

    @Value("${app.rag.seed-enabled:true}")
    private boolean seedEnabled;

    /**
     * RAG 健康跟踪（v1.34.1）：把播种结果写入，供 {@code /api/health/detail} 暴露。
     *
     * <p>此前播种失败只打一条 WARN 就继续启动，外部监控看到的仍是
     * 「应用 UP」，形成「监控全绿、知识库全废」的静默降级。
     * 声明为 {@code required = false} + 空值保护，避免影响既有单测的直接构造。
     */
    @Autowired(required = false)
    private RagHealthTracker ragHealthTracker;

    /** 进程内只播种一次（应用生命周期内不会重复） */
    private volatile boolean seeded = false;

    @Autowired
    public KnowledgeSeedInitializer(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    /**
     * 内置面试知识（团队维护的通用八股，覆盖 Java / Spring / 数据库 / 中间件 / 算法 / 网络）。
     * 每条会独立向量化，内容保持自包含——分块过短或依赖上下文会显著降低检索命中率。
     */
    private static final String[][] SEED_KNOWLEDGE = {
            {"Java 基础", "HashMap 底层实现",
                    "HashMap 基于哈希表实现。JDK 8 之前是「数组 + 链表」，JDK 8 起改为「数组 + 链表 + 红黑树」："
                            + "当单个桶的链表长度达到 8 且数组容量达到 64 时，链表转为红黑树，把最坏查找复杂度从 O(n) 降到 O(log n)；"
                            + "元素数降到 6 以下时退化回链表。默认初始容量 16、负载因子 0.75，容量始终为 2 的幂，"
                            + "因此可以用 (n - 1) & hash 代替取模运算加速定位。扩容时容量翻倍并重新分布元素；"
                            + "JDK 8 借助 hash & oldCap 的结果把节点分成「原位置」和「原位置 + 旧容量」两条链，避免重新计算哈希。"
                            + "HashMap 非线程安全，多线程并发扩容可能形成环形链表（JDK 8 已缓解但仍有数据覆盖风险）。"},
            {"Java 基础", "ConcurrentHashMap 线程安全实现",
                    "JDK 8 的 ConcurrentHashMap 放弃了 JDK 7 的分段锁 Segment，改为「CAS + synchronized」："
                            + "数组初始化、桶为空时插入首节点用 CAS 无锁操作；桶非空时对桶的头节点加 synchronized 锁，"
                            + "锁粒度细化到单个桶而非整个段，并发度显著提升。"
                            + "size() 借助 baseCount 加 CounterCell 数组分散计数压力，类似 LongAdder 的分治思想。"
                            + "扩容支持多线程协同迁移（ForwardingNode 标记已迁移的桶），迁移期间读操作不阻塞。"
                            + "不允许 null 键和 null 值——因为并发场景下无法区分「键不存在」和「值为 null」。"},
            {"Java 基础", "JVM 内存结构与垃圾回收",
                    "JVM 运行时内存分五大区：程序计数器（线程私有，唯一不会 OOM 的区域）、虚拟机栈（线程私有，"
                            + "存放栈帧，栈深度超限抛 StackOverflowError）、本地方法栈、堆（线程共享，对象实例主要分配区，"
                            + "分新生代 Eden + Survivor 与老年代）、方法区（JDK 8 起由元空间 Metaspace 实现，使用本地内存）。"
                            + "垃圾判定用可达性分析（GC Roots：栈中引用、静态变量、常量、JNI 引用），不用引用计数。"
                            + "回收算法：标记-清除（碎片多）、标记-复制（新生代，Eden:Survivor = 8:1:1）、标记-整理（老年代）。"
                            + "常见收集器：G1（分区 Region，可预测停顿）、ZGC（染色指针 + 读屏障，停顿亚毫秒）。"},
            {"Java 基础", "Java 线程池核心参数与工作流程",
                    "ThreadPoolExecutor 七个核心参数：corePoolSize（核心线程数）、maximumPoolSize（最大线程数）、"
                            + "keepAliveTime（非核心线程空闲存活时间）、TimeUnit、workQueue（任务队列）、"
                            + "threadFactory（线程工厂）、handler（拒绝策略）。"
                            + "提交任务流程：① 线程数 < corePoolSize 时直接创建核心线程执行；"
                            + "② 核心线程已满则任务入 workQueue 排队；"
                            + "③ 队列已满且线程数 < maximumPoolSize 时创建非核心线程；"
                            + "④ 都满则触发拒绝策略（AbortPolicy 抛异常 / CallerRunsPolicy 调用方执行 / "
                            + "DiscardPolicy 丢弃 / DiscardOldestPolicy 丢最老的）。"
                            + "生产建议：用有界队列避免 OOM；CPU 密集型线程数约 CPU 核数 + 1，IO 密集型可适当放大；"
                            + "手动指定 ThreadFactory 便于排查。"},
            {"Spring", "Spring Bean 生命周期",
                    "Spring Bean 生命周期分四个大阶段：① 实例化（构造器创建对象）；"
                            + "② 属性赋值（依赖注入，populateBean）；"
                            + "③ 初始化（依次调用 Aware 系列接口 → BeanPostProcessor.postProcessBeforeInitialization → "
                            + "@PostConstruct → InitializingBean.afterPropertiesSet → 自定义 init-method → "
                            + "BeanPostProcessor.postProcessAfterInitialization，AOP 代理通常在此阶段生成）；"
                            + "④ 销毁（@PreDestroy → DisposableBean.destroy → 自定义 destroy-method）。"
                            + "三级缓存解决循环依赖：singletonObjects（成品）、earlySingletonObjects（半成品）、"
                            + "singletonFactories（工厂，可提前暴露 AOP 代理）。构造器注入的循环依赖无法解决，"
                            + "因为它发生在实例化阶段，此时还来不及放入缓存。"},
            {"Spring", "Spring Boot 自动配置原理",
                    "入口是 @SpringBootApplication 上的 @EnableAutoConfiguration，它通过 "
                            + "@Import(AutoConfigurationImportSelector.class) 生效。"
                            + "AutoConfigurationImportSelector 读取 classpath 下 "
                            + "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports "
                            + "（Spring Boot 2.7 之后；此前是 spring.factories）列出的候选配置类，"
                            + "再经 @Conditional 家族注解过滤：@ConditionalOnClass（类路径存在）、"
                            + "@ConditionalOnMissingBean（用户未自定义）、@ConditionalOnProperty（配置开关）等，"
                            + "最终把符合条件的 Bean 注册进容器。调试可用 --debug 打印条件评估报告，"
                            + "或在配置类上开启 debug 观察 matched/unmatched 明细。"},
            {"Spring", "Spring 事务传播行为与失效场景",
                    "七种传播行为：REQUIRED（默认，有事务则加入，无则新建）、REQUIRES_NEW（总是新建并挂起当前）、"
                            + "SUPPORTS、NOT_SUPPORTED、MANDATORY、NEVER、NESTED（嵌套，靠 savepoint 实现，"
                            + "仅 DataSourceTransactionManager 支持）。"
                            + "常见失效场景：① 同类内部方法直接调用（绕过代理）；"
                            + "② 方法非 public（CGLIB 代理无法拦截）；"
                            + "③ 异常被 catch 未抛出，或抛的是受检异常而默认只回滚 RuntimeException/Error"
                            + "（需 @Transactional(rollbackFor = Exception.class)）；"
                            + "④ 多线程中调用（事务绑定 ThreadLocal）；⑤ 类未被 Spring 管理。"
                            + "自调用失效的解法：注入自身代理、用 AopContext.currentProxy()、或拆到独立 Bean。"},
            {"数据库", "MySQL 索引原理与优化",
                    "InnoDB 使用 B+ 树索引：所有数据存于叶子节点，非叶子节点仅存键值用于导航，"
                            + "因此同样磁盘页能容纳更多索引项，树高更低（千万级数据通常 3 层），IO 次数少；"
                            + "叶子节点间用双向链表相连，天然支持范围查询与排序。"
                            + "聚簇索引（主键索引）的叶子节点直接存放整行数据，因此一张表只能有一个；"
                            + "二级索引叶子节点存主键值，查询非索引列需「回表」。"
                            + "覆盖索引指查询字段全部包含在索引中，可避免回表。"
                            + "最左前缀原则：联合索引 (a,b,c) 只能被 a、ab、abc 组合有效利用，跳过 a 直接查 b 会失效。"
                            + "索引失效常见原因：对索引列做函数运算或隐式类型转换、以 % 开头的 LIKE、"
                            + "使用 != 或 OR 连接非索引列、违反最左前缀。"},
            {"数据库", "MySQL 事务隔离级别与 MVCC",
                    "SQL 标准四种隔离级别：READ UNCOMMITTED（脏读）、READ COMMITTED（不可重复读）、"
                            + "REPEATABLE READ（幻读，MySQL 默认）、SERIALIZABLE（串行）。"
                            + "MySQL 的 RR 借助 MVCC 加间隙锁在很大程度上避免了幻读。"
                            + "MVCC 实现三要素：① 隐藏列，每行有 DB_TRX_ID（最后修改事务 ID）和 DB_ROLL_PTR（回滚指针）；"
                            + "② undo log 版本链，回滚指针把历史版本串成链；"
                            + "③ ReadView，记录生成快照时活跃的事务 ID 列表，"
                            + "通过「可见性判断」沿版本链找到对当前事务可见的版本。"
                            + "RC 在每条 SELECT 前都重新生成 ReadView（所以能读到已提交的新数据）；"
                            + "RR 只在事务首次 SELECT 时生成一次并复用（所以整个事务看到同一快照）。"},
            {"数据库", "数据库连接池与慢查询排查",
                    "连接池（HikariCP）核心参数：maximumPoolSize（最大连接数，需与数据库 max_connections 匹配）、"
                            + "minimumIdle、connectionTimeout（获取连接超时，默认 30s）、"
                            + "idleTimeout、maxLifetime（应小于数据库 wait_timeout，避免用到已被服务端关闭的连接）、"
                            + "leakDetectionThreshold（借出超时未还则告警，用于发现连接泄漏）。"
                            + "连接池过大的常见误区：连接数并非越多越好，过多会加剧数据库上下文切换与锁竞争，"
                            + "经验公式约为 CPU 核数 * 2 + 磁盘数。"
                            + "慢查询排查步骤：① 开启 slow_query_log 并设置 long_query_time；"
                            + "② 用 mysqldumpslow 或 pt-query-digest 聚合分析；"
                            + "③ 对目标 SQL 执行 EXPLAIN，重点看 type（避免 ALL 全表扫描）、key（实际使用的索引）、"
                            + "rows（预估扫描行数）、Extra（出现 Using filesort / Using temporary 需优化）。"},
            {"中间件", "Redis 持久化机制",
                    "Redis 提供两种持久化：RDB 和 AOF，4.0 起支持混合持久化。"
                            + "RDB 是某一时刻的全量快照（二进制），通过 SAVE（阻塞主线程，生产禁用）或 "
                            + "BGSAVE（fork 子进程写临时文件后原子替换）生成。优点是文件紧凑、恢复快，"
                            + "适合备份与灾备；缺点是 fork 时可能因写时复制导致内存翻倍，"
                            + "且两次快照之间宕机会丢数据。"
                            + "AOF 追加写命令，appendfsync 三档：always（每条都刷盘，最安全但性能差）、"
                            + "everysec（每秒一次，默认，最多丢 1 秒）、no（交给操作系统）。"
                            + "AOF 文件会随重写（BGREWRITEAOF）压缩。"
                            + "混合持久化让重写后的 AOF 前半段是 RDB 全量、后半段是增量命令，兼顾恢复速度与数据安全。"},
            {"中间件", "Redis 缓存穿透、击穿与雪崩",
                    "缓存穿透：查询根本不存在的数据，请求绕过缓存直击数据库。"
                            + "解法：① 缓存空值（设较短过期时间，避免占满内存）；"
                            + "② 布隆过滤器前置拦截（有假阳性无假阴性）；③ 参数合法性校验。"
                            + "缓存击穿：某个热点 key 过期瞬间，大量并发请求同时落到数据库。"
                            + "解法：① 互斥锁，只让一个线程回源重建缓存，其余等待；"
                            + "② 逻辑过期，缓存不设 TTL 而是存过期时间字段，异步刷新。"
                            + "缓存雪崩：大量 key 同一时刻集中失效，或 Redis 实例整体宕机。"
                            + "解法：① 过期时间加随机扰动打散；② 多级缓存（本地 Caffeine + Redis）；"
                            + "③ 集群高可用（哨兵/Cluster）；④ 限流降级兜底。"},
            {"中间件", "消息队列如何保证不丢消息",
                    "消息不丢需三段都保障：① 生产者到 Broker——开启确认机制（RabbitMQ 的 confirm、"
                            + "Kafka 的 acks=all），并处理确认失败的重发；"
                            + "② Broker 内部——开启持久化（队列/交换机/消息都设为持久化；Kafka 靠副本，"
                            + "replication.factor >= 3 且 min.insync.replicas >= 2）；"
                            + "③ Broker 到消费者——关闭自动确认，改为业务处理成功后手动 ack。"
                            + "重复消费几乎是必然的（网络超时导致 ack 丢失），因此消费端必须做幂等："
                            + "用唯一业务 ID 建去重表，或利用数据库唯一索引、Redis SETNX 去重。"
                            + "顺序性：同一业务键的消息路由到同一分区/队列，且单线程消费该分区。"},
            {"算法与数据结构", "常见排序算法与复杂度",
                    "快速排序：分治 + 原地分区，平均 O(n log n)，最坏 O(n²)（已有序且取首元素为基准时），"
                            + "空间 O(log n)（递归栈），不稳定。优化：随机化基准、三数取中、小区间插入排序。"
                            + "归并排序：分治 + 额外数组合并，稳定 O(n log n)，空间 O(n)，稳定排序，适合链表与外部排序。"
                            + "堆排序：建堆 O(n) + 反复调整 O(n log n)，原地、不稳定，适合求 Top K。"
                            + "计数排序/桶排序/基数排序属于非比较排序，可突破 O(n log n) 下界，但要求数据有范围约束。"
                            + "Java 的 Arrays.sort：基本类型用双轴快排（不稳定但快），对象数组用 TimSort（稳定）。"},
            {"算法与数据结构", "链表反转与环检测",
                    "单链表反转（迭代）：用 prev/curr/next 三个指针，每次把 curr.next 指向 prev 后整体右移，"
                            + "时间 O(n)、空间 O(1)。递归写法优雅但空间 O(n) 且有栈溢出风险。"
                            + "环检测（Floyd 判圈）：快慢指针同时从头部出发，慢指针每次 1 步、快指针每次 2 步，"
                            + "若存在环则必然相遇。求环入口：相遇后把一个指针放回头部，两指针均每次 1 步，"
                            + "再次相遇处即为环入口（数学推导：头到入口距离 = 相遇点到入口距离 + n 圈长）。"
                            + "相关变形：求链表中点（快慢指针）、判断两条链表是否相交（先对齐长度差再同步遍历）、"
                            + "K 个一组反转（分组处理，组内反转后接回）。"},
            {"计算机网络", "TCP 三次握手与四次挥手",
                    "三次握手：① 客户端发 SYN(seq=x)，进入 SYN_SENT；"
                            + "② 服务端回 SYN+ACK(seq=y, ack=x+1)，进入 SYN_RCVD；"
                            + "③ 客户端发 ACK(ack=y+1)，双方进入 ESTABLISHED。"
                            + "为什么是三次：需要双方都确认「自己的发送能力和对方的接收能力正常」，"
                            + "两次无法让服务端确认客户端已收到自己的 SYN，也无法避免历史失效连接请求造成资源浪费。"
                            + "四次挥手：① 客户端 FIN；② 服务端 ACK；③ 服务端处理完后 FIN；④ 客户端 ACK 并等待 2MSL。"
                            + "为什么是四次：TCP 全双工，一方关闭只代表自己不再发送，需等对方数据也发完才能关闭，"
                            + "所以 ACK 与 FIN 通常不能合并。等待 2MSL 是为了确保最后的 ACK 能到达，"
                            + "并让本次连接的旧报文在网络中彻底消散。"},
            {"计算机网络", "HTTP 与 HTTPS 的关键差异",
                    "HTTPS 在 HTTP 之下加了 TLS/SSL 层，解决三件事：机密性（对称加密传输内容）、"
                            + "完整性（HMAC 校验防篡改）、身份认证（证书链防中间人）。"
                            + "TLS 1.2 握手：客户端发 ClientHello（支持的套件、随机数）→ 服务端回 ServerHello + 证书 → "
                            + "客户端验证证书链有效性 → 用证书公钥加密预主密钥发送 → 双方由三个随机数推导会话密钥 → "
                            + "切换到对称加密通信。TLS 1.3 把握手压缩到 1-RTT（会话复用可 0-RTT），"
                            + "并移除 RSA 密钥交换，只保留前向安全的 ECDHE。"
                            + "HTTPS 常见问题：证书过期/域名不匹配导致浏览器拦截、混合内容（HTTPS 页面加载 HTTP 资源）被阻止、"
                            + "握手增加约 1-2 个 RTT（可用会话复用、TLS 1.3、OCSP Stapling 优化）。"},
            {"系统设计", "限流的常见算法与实现",
                    "固定窗口计数：最简单，但窗口边界处可能出现两倍突发流量。"
                            + "滑动窗口日志/计数：把窗口切细并滑动统计，精度更高但内存开销随粒度上升。"
                            + "漏桶：请求先入桶，以恒定速率流出，桶满则拒绝——输出速率绝对平滑，"
                            + "适合保护下游不被突发流量打垮。"
                            + "令牌桶：以恒定速率往桶里放令牌，请求需取到令牌才被放行，桶容量决定了允许的突发量——"
                            + "既能限平均速率又能容纳突发，是业界最常用的方案（Guava RateLimiter 即为此实现）。"
                            + "分布式限流需把计数放 Redis，用 Lua 脚本保证「读取-判断-写入」的原子性；"
                            + "网关层（Nginx limit_req / Sentinel / Spring Cloud Gateway）做入口粗粒度限流，"
                            + "服务层做热点接口细粒度限流。"},
            {"系统设计", "分布式 ID 生成方案",
                    "UUID：本地生成、性能好，但无序（作为 MySQL 主键会导致页分裂）、占 36 字节偏大。"
                            + "数据库自增：简单可靠，但强依赖单点、扩展困难、分库分表后需改造步长。"
                            + "号段模式：一次从数据库取一批 ID 缓存在内存，用完再取，"
                            + "兼顾性能与有序（美团 Leaf、滴滴 TinyId 均采用），缺点是服务重启会浪费一段 ID。"
                            + "雪花算法 Snowflake：64 位 = 1 位符号 + 41 位时间戳（约 69 年）+ 10 位机器 ID + 12 位序列号，"
                            + "趋势递增、性能极高；致命弱点是依赖系统时钟，回拨会产生重复 ID，"
                            + "需用「等待时钟追上」「多备几个位存回拨次数」等手段兜底。"
                            + "Redis INCR：利用原子自增，需处理持久化与集群分片问题。"}
    };

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("共享知识库播种已禁用（app.rag.seed-enabled=false）");
            markSeed(RagHealthTracker.SeedStatus.DISABLED, "app.rag.seed-enabled=false");
            return;
        }
        if (seeded) {
            return;
        }
        try {
            String[][] all = allSeeds();
            List<Document> docs = new ArrayList<>(all.length);
            for (String[] item : all) {
                String category = item[0];
                String title = item[1];
                String content = item[2];
                // 标题拼进正文：检索时用户提问通常带术语（如「HashMap」「增值税」），
                // 标题含关键词能显著提高向量命中率；category 也一并写入便于过滤与展示
                String text = "【" + category + "】" + title + "\n" + content;
                docs.add(Document.builder()
                        // 确定性 ID（category+title 派生）：向量库持久化后，
                        // 重复播种会覆盖同 ID 文档而非新增，天然幂等 ——
                        // 这是「扩充预置知识后重启不产生重复条目」的关键。
                        .id(deterministicId(category, title))
                        .text(text)
                        .metadata(Map.of(
                                "category", category,
                                "title", title,
                                META_SHARED, "true",
                                "type", "knowledge",
                                "source", "system-seed"))
                        .build());
            }
            int stored = ragSearchService.addToVectorStore(docs);
            seeded = true;
            log.info("共享知识库播种完成：{} 条预置知识已写入向量库"
                            + "（IT 基础 {} 条 + 全行业 {} 条；shared=true，全部用户可检索）",
                    stored, SEED_KNOWLEDGE.length, all.length - SEED_KNOWLEDGE.length);
            markSeed(RagHealthTracker.SeedStatus.SUCCESS,
                    stored + " 条预置知识已入库（IT 基础 " + SEED_KNOWLEDGE.length
                            + " + 全行业 " + (all.length - SEED_KNOWLEDGE.length) + "）");
        } catch (Exception e) {
            // 播种失败不阻断启动：embedding 未配置时知识库为空，但登录/面试等核心功能不受影响
            log.warn("共享知识库播种失败（已忽略，RAG 检索将为空，请检查 Embedding 配置）：{}", e.getMessage());
            // v1.34.1：把失败写入健康跟踪——否则「知识库不可用」在外部完全不可见
            markSeed(RagHealthTracker.SeedStatus.FAILED, "播种失败：" + e.getMessage());
        }
    }

    /** 写入播种状态（跟踪器可能为空：切片单测直接构造本类时未注入） */
    private void markSeed(RagHealthTracker.SeedStatus status, String detail) {
        if (ragHealthTracker != null) {
            ragHealthTracker.markSeed(status, detail);
        }
    }

    /**
     * 合并 IT 基础篇与全行业篇。
     *
     * <p>2026-09-20：本平台是「AI 智能面试辅助平台」，服务对象不限于计算机行业，
     * 因此预置知识必须覆盖财会、法律、医疗、教育、销售、制造等全行业。
     * 行业太多无法穷举，超出预置范围的主题由
     * {@code AutoKnowledgeService} 在检索落空时自动补充。
     */
    static String[][] allSeeds() {
        String[][] industry = SeedIndustryKnowledge.all();
        String[][] merged = new String[SEED_KNOWLEDGE.length + industry.length][];
        System.arraycopy(SEED_KNOWLEDGE, 0, merged, 0, SEED_KNOWLEDGE.length);
        System.arraycopy(industry, 0, merged, SEED_KNOWLEDGE.length, industry.length);
        return merged;
    }

    /** 由「分类 + 标题」派生稳定 ID，保证同一知识点多次播种得到同一 ID */
    static String deterministicId(String category, String title) {
        String key = "seed|" + category + "|" + title;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

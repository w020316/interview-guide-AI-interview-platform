<template>
  <div class="admin-page">
    <header class="admin-header">
      <div>
        <h1>管理后台</h1>
        <p class="admin-sub">数据总览 · 数据源健康 · 岗位管理 · 用户管理 · 系统指标</p>
      </div>
      <button class="btn-refresh-all" :disabled="refreshing || overview?.refreshing" @click="doRefresh">
        <span v-if="refreshing" class="spinner" />
        {{ refreshing ? '刷新中…' : '手动刷新岗位数据' }}
      </button>
    </header>

    <nav class="admin-tabs">
      <button
        v-for="t in TABS"
        :key="t.k"
        class="tab-btn"
        :class="{ active: activeTab === t.k }"
        @click="switchTab(t.k)"
      >
        {{ t.l }}
        <span v-if="t.k === 'sources' && sources.length" class="tab-badge">{{ sources.length }}</span>
      </button>
    </nav>

    <!-- ══════════ 数据总览 ══════════ -->
    <section v-show="activeTab === 'overview'" class="panel">
      <div class="cards">
        <div v-for="c in overviewCards" :key="c.label" class="stat-card" :class="c.tone">
          <div class="stat-value">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
      </div>

      <div class="overview-meta">
        <span>最近数据更新：<b>{{ fmtDate(overview?.lastRefreshedAt ?? null) }}</b></span>
        <span v-if="overview?.refreshing" class="badge-warn">刷新中…</span>
        <span v-if="refreshResult" class="badge-ok">
          本次：新增 {{ refreshResult.inserted }} · 更新 {{ refreshResult.updated }} · 下架 {{ refreshResult.expired }}
        </span>
      </div>

      <!-- 数据源告警横幅（v1.38.0）：某个源被上游停用或网络不可达时，
           此前只能靠「岗位总数慢慢变少」察觉，现在总览直接点出是哪个源、什么原因 -->
      <div v-if="sourceAlerts.length" class="alert-banner" role="alert">
        <span class="alert-icon" aria-hidden="true">!</span>
        <div class="alert-body">
          <b>{{ sourceAlerts.length }} 个数据源拉取异常</b>
          <ul class="alert-list">
            <li v-for="a in sourceAlerts" :key="a.platform">
              <span class="alert-src">{{ a.platform }}</span>
              <span class="alert-err">{{ a.lastError || '未知错误' }}</span>
              <span class="alert-times">连续失败 {{ a.consecutiveFailures }} 次 · {{ relativeTime(a.lastAttemptAt) }}</span>
            </li>
          </ul>
          <p class="alert-hint">
            海外公开 API 偶发超时会自动重试；若持续失败，可在
            <code>app.job-agent.open-api-enabled=false</code> 关闭海外源，或检查网络出口。
          </p>
        </div>
        <button class="alert-action" @click="switchTab('sources')">查看数据源</button>
      </div>

      <div class="chart-grid">
        <!-- 数据源分布 -->
        <div class="chart-card">
          <h3>数据源分布 <span class="chart-hint">按有效岗位数排序</span></h3>
          <p v-if="!sourceDist.length" class="chart-empty">暂无岗位数据，点击右上角「手动刷新岗位数据」拉取</p>
          <div v-else class="bar-list">
            <div v-for="s in sourceDist" :key="s.platform" class="bar-row">
              <span class="bar-name" :title="s.platform">{{ s.platform }}</span>
              <div class="bar-track">
                <div class="bar-fill" :style="{ width: barWidth(s.total, maxSourceTotal) }" />
                <div class="bar-fill active-part" :style="{ width: barWidth(s.active, maxSourceTotal) }" />
              </div>
              <span class="bar-value">{{ s.active }}<em v-if="s.inactive"> ({{ s.inactive }} 失效)</em></span>
            </div>
          </div>
        </div>

        <!-- 招聘类型分布 -->
        <div class="chart-card">
          <h3>招聘类型分布</h3>
          <p v-if="!recruitDist.length" class="chart-empty">暂无数据</p>
          <div v-else class="donut-wrap">
            <div class="donut" :style="donutStyle">
              <div class="donut-hole">
                <b>{{ activeJobsTotal }}</b>
                <span>有效岗位</span>
              </div>
            </div>
            <ul class="legend">
              <li v-for="(item, i) in recruitDistList" :key="item.key">
                <i :style="{ background: DONUT_COLORS[i % DONUT_COLORS.length] }" />
                <span>{{ recruitTypeLabel(item.key) }}</span>
                <b>{{ item.value }}</b>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <!-- 近 7 天趋势 -->
      <div class="chart-card full">
        <h3>近 7 天新增趋势</h3>
        <div class="trend-legend">
          <span><i class="dot jobs" />新增岗位</span>
          <span><i class="dot users" />新增用户</span>
        </div>
        <div class="trend-chart">
          <div v-for="d in trend" :key="d.date" class="trend-col">
            <div class="trend-bars">
              <div class="trend-bar jobs" :style="{ height: barHeight(d.jobs) }" :title="`新增岗位 ${d.jobs}`" />
              <div class="trend-bar users" :style="{ height: barHeight(d.users) }" :title="`新增用户 ${d.users}`" />
            </div>
            <span class="trend-label">{{ d.label }}</span>
            <span class="trend-num">{{ d.jobs }}/{{ d.users }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ 数据源 ══════════ -->
    <section v-show="activeTab === 'sources'" class="panel">
      <div class="toolbar">
        <span class="tip">
          共 {{ sources.length }} 个来源，其中 {{ enabledCount }} 个已启用。
          表内「有效 / 失效」为当前库中岗位数，「最近更新」反映该源最近一次成功写入的时间。
        </span>
        <button class="ghost-btn" @click="fetchSources">刷新</button>
      </div>
      <div class="table-wrap">
        <table class="data-table" v-loading="sourcesLoading">
          <thead>
            <tr>
              <th>数据来源</th><th>状态</th><th>最近拉取</th><th>有效</th><th>失效</th><th>总计</th><th>最近入库</th><th>说明 / 错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sources" :key="s.platform">
              <td class="strong">
                {{ s.platform }}
                <span v-if="s.overseas || isOverseas(s.platform)" class="pill accent">海外</span>
              </td>
              <td><span :class="statusOf(s).cls">{{ statusOf(s).label }}</span></td>
              <td class="mono" :class="{ 'cell-error': s.health && !s.health.healthy }">{{ healthText(s) }}</td>
              <td>{{ s.active }}</td>
              <td>{{ Math.max(0, s.total - s.active) }}</td>
              <td>{{ s.total }}</td>
              <td class="mono">{{ relativeTime(s.lastUpdatedAt) }}</td>
              <td class="note">{{ noteText(s) }}</td>
            </tr>
            <tr v-if="!sources.length"><td colspan="8" class="empty-cell">暂无数据源</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ══════════ 岗位数据 ══════════ -->
    <section v-show="activeTab === 'jobs'" class="panel">
      <div class="toolbar">
        <input v-model="jobKeyword" class="search-input" placeholder="搜索标题 / 公司 / 标签" @keyup.enter="searchJobs" />
        <select v-model="jobSource" class="select-input" @change="searchJobs">
          <option value="">全部来源</option>
          <option v-for="s in sourceDist" :key="s.platform" :value="s.platform">{{ s.platform }}</option>
        </select>
        <select v-model="jobRecruitType" class="select-input" @change="searchJobs">
          <option value="">全部类型</option>
          <option v-for="t in RECRUIT_TYPES" :key="t.v" :value="t.v">{{ t.l }}</option>
        </select>
        <select v-model="jobActive" class="select-input" @change="searchJobs">
          <option value="">全部状态</option>
          <option value="true">仅有效</option>
          <option value="false">仅失效</option>
        </select>
        <button class="ghost-btn" @click="searchJobs">搜索</button>
        <button class="ghost-btn" @click="resetJobFilters">重置</button>
      </div>
      <div class="table-wrap">
        <table class="data-table" v-loading="jobsLoading">
          <thead>
            <tr><th>ID</th><th>标题</th><th>公司</th><th>地点</th><th>类型</th><th>来源</th><th>状态</th><th>截止</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="j in jobs" :key="j.id">
              <td class="mono">{{ j.id }}</td>
              <td class="ellipsis" :title="j.title">{{ j.title }}</td>
              <td class="ellipsis" :title="j.companyName">{{ j.companyName }}</td>
              <td>{{ j.location || '—' }}</td>
              <td>{{ recruitTypeLabel(j.recruitType) }}</td>
              <td>{{ j.platform }}</td>
              <td><span :class="j.active ? 'badge-ok' : 'badge-off'">{{ j.active ? '有效' : '失效' }}</span></td>
              <td>{{ j.deadline || '—' }}</td>
              <td class="ops">
                <button class="mini-btn" @click="toggleJob(j)">{{ j.active ? '下架' : '恢复' }}</button>
                <button class="mini-btn danger" @click="deleteJob(j)">删除</button>
              </td>
            </tr>
            <tr v-if="!jobs.length"><td colspan="9" class="empty-cell">暂无岗位</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="ghost-btn" :disabled="jobPage <= 0" @click="jobPage--; fetchJobs()">上一页</button>
        <span>第 {{ jobPage + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(jobsTotal / jobSize)) }} 页（{{ jobsTotal }} 条）</span>
        <button class="ghost-btn" :disabled="(jobPage + 1) * jobSize >= jobsTotal" @click="jobPage++; fetchJobs()">下一页</button>
      </div>
    </section>

    <!-- ══════════ 用户管理 ══════════ -->
    <section v-show="activeTab === 'users'" class="panel">
      <div class="toolbar">
        <input v-model="userKeyword" class="search-input" placeholder="搜索用户名 / 邮箱" @keyup.enter="searchUsers" />
        <button class="ghost-btn" @click="searchUsers">搜索</button>
        <span class="tip">禁用为进程内生效（重启自动恢复），用于临时封禁</span>
      </div>
      <div class="table-wrap">
        <table class="data-table" v-loading="usersLoading">
          <thead>
            <tr><th>ID</th><th>用户名</th><th>邮箱</th><th>注册时间</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td class="mono">{{ u.id }}</td>
              <td class="strong">{{ u.username }}</td>
              <td>{{ u.email || '—' }}</td>
              <td class="mono">{{ fmtDate(u.createdAt) }}</td>
              <td><span :class="u.banned ? 'badge-off' : 'badge-ok'">{{ u.banned ? '已禁用' : '正常' }}</span></td>
              <td class="ops">
                <button class="mini-btn" :class="{ danger: !u.banned }" @click="toggleBan(u)">{{ u.banned ? '解禁' : '禁用' }}</button>
              </td>
            </tr>
            <tr v-if="!users.length"><td colspan="6" class="empty-cell">暂无用户</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="ghost-btn" :disabled="userPage <= 0" @click="userPage--; fetchUsers()">上一页</button>
        <span>第 {{ userPage + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(usersTotal / userSize)) }} 页（{{ usersTotal }} 条）</span>
        <button class="ghost-btn" :disabled="(userPage + 1) * userSize >= usersTotal" @click="userPage++; fetchUsers()">下一页</button>
      </div>
    </section>

    <!-- ══════════ 系统指标 ══════════ -->
    <section v-show="activeTab === 'metrics'" class="panel">
      <div class="metrics-grid" v-if="metrics">
        <div class="metric-block">
          <h3>AI 调用次数</h3>
          <div class="metric-row" v-for="(v, k) in aiCalls" :key="k">
            <span>{{ aiLabel(String(k)) }}</span><b>{{ typeof v === 'number' ? Math.round(v * 10) / 10 : v }}</b>
          </div>
        </div>
        <div class="metric-block">
          <h3>缓存</h3>
          <div class="metric-row"><span>命中</span><b>{{ metrics.cache.hits }}</b></div>
          <div class="metric-row"><span>未命中</span><b>{{ metrics.cache.misses }}</b></div>
          <div class="metric-row"><span>命中率</span><b>{{ cacheHitRate }}</b></div>
        </div>
        <div class="metric-block">
          <h3>SSE 流式连接</h3>
          <div class="metric-row"><span>并发上限</span><b>{{ metrics.sse.maxConcurrent }}</b></div>
          <div class="metric-row"><span>当前活跃</span><b>{{ metrics.sse.activeCount }}</b></div>
          <div class="metric-row"><span>水位</span><b>{{ sseUsage }}%</b></div>
        </div>
        <div class="metric-block">
          <h3>JVM 内存 (MB)</h3>
          <div class="metric-row"><span>已用</span><b>{{ metrics.jvm.usedMb }}</b></div>
          <div class="metric-row"><span>空闲</span><b>{{ metrics.jvm.freeMb }}</b></div>
          <div class="metric-row"><span>已分配</span><b>{{ metrics.jvm.totalMb }}</b></div>
          <div class="metric-row"><span>上限</span><b>{{ metrics.jvm.maxMb }}</b></div>
        </div>
      </div>
      <p v-else class="chart-empty">指标加载中…</p>
      <button class="ghost-btn" @click="fetchMetrics">刷新指标</button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { getErrMessage } from '../api'

/**
 * 管理后台（v1.37.0 视觉与信息架构重构）
 *
 * 本次优化解决的三个问题：
 * 1. 视觉与全站割裂 —— 原实现硬编码 #888/#777/#555 等色值，深色模式下文字不可读；
 *    现全部改用设计 token（--c-text-* / --brand-* / --radius-*），自动适配明暗主题。
 * 2. 信息不足 —— 原「数据总览」只有 5 个数字，看不出「岗位都来自哪些源、谁是主力、
 *    近几天有没有在涨」。现补充数据源分布、招聘类型分布、近 7 天新增趋势。
 * 3. 缺少数据源视角 —— 某个数据源静默失效（被平台停用 / 网络不可达）时此前无感知，
 *    只能看到岗位总数慢慢变少。现新增「数据源」标签页，对照展示适配器启用状态与实际入库量。
 */

interface SourceDist { platform: string; total: number; active: number; inactive: number }
interface TrendPoint { date: string; label: string; jobs: number; users: number }
interface Overview {
  totalJobs: number
  activeJobs: number
  inactiveJobs: number
  totalUsers: number
  bannedUsers: number
  lastRefreshedAt: string | null
  refreshing: boolean
  sourceDist?: SourceDist[]
  recruitDist?: Record<string, number>
  trend?: TrendPoint[]
  /** 拉取异常的数据源（v1.38.0，总览告警横幅据此渲染） */
  sourceAlerts?: SourceHealth[]
}

/** 数据源最近一次拉取的健康快照（对应后端 JobSourceHealthRegistry.Health） */
interface SourceHealth {
  platform: string
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  healthy: boolean
  lastCount: number
  lastElapsedMs: number
  lastError: string | null
  consecutiveFailures: number
}
interface RefreshResult { upserted: number; inserted: number; updated: number; expired: number; removed: number }
interface JobRow {
  id: number
  title: string
  companyName: string
  location: string
  recruitType: string
  platform: string
  active: boolean
  deadline: string | null
  updatedAt: string | null
}
interface UserRow { id: number; username: string; email: string | null; createdAt: string | null; banned: boolean }
interface SourceRow {
  platform: string
  enabled: boolean
  aggregate: boolean
  builtin: boolean
  overseas?: boolean
  total: number
  active: number
  lastUpdatedAt: string | null
  note: string | null
  /** 最近一次拉取的健康快照；从未拉取过为 null */
  health?: SourceHealth | null
}
interface Metrics {
  aiCalls: Record<string, number>
  cache: { hits: number; misses: number }
  sse: { maxConcurrent: number; activeCount: number }
  jvm: { usedMb: number; freeMb: number; totalMb: number; maxMb: number }
}

type TabKey = 'overview' | 'sources' | 'jobs' | 'users' | 'metrics'

const TABS: { k: TabKey; l: string }[] = [
  { k: 'overview', l: '数据总览' },
  { k: 'sources', l: '数据源' },
  { k: 'jobs', l: '岗位数据' },
  { k: 'users', l: '用户管理' },
  { k: 'metrics', l: '系统指标' },
]

const RECRUIT_TYPES = [
  { v: 'AUTUMN', l: '秋招' },
  { v: 'SPRING', l: '春招' },
  { v: 'INTERN', l: '实习' },
  { v: 'SOCIAL', l: '社招' },
  { v: 'TARGETED', l: '定向专项' },
]

/** 环形图配色（按招聘类型顺序取用，深色模式同样可辨） */
const DONUT_COLORS = ['#0f766e', '#c2410c', '#1d4ed8', '#b45309', '#7c3aed']

/** 海外数据源标识（与招聘广场的来源 chips 保持一致） */
const OVERSEAS_MARKERS = ['RemoteOK', 'Remotive', 'Arbeitnow']

const activeTab = ref<TabKey>('overview')
const overview = ref<Overview | null>(null)
const refreshing = ref(false)
const refreshResult = ref<RefreshResult | null>(null)

const sources = ref<SourceRow[]>([])
const sourcesLoading = ref(false)

const jobs = ref<JobRow[]>([])
const jobsTotal = ref(0)
const jobPage = ref(0)
const jobSize = 10
const jobKeyword = ref('')
const jobSource = ref('')
const jobRecruitType = ref('')
const jobActive = ref('')
const jobsLoading = ref(false)

const users = ref<UserRow[]>([])
const usersTotal = ref(0)
const userPage = ref(0)
const userSize = 10
const userKeyword = ref('')
const usersLoading = ref(false)

const metrics = ref<Metrics | null>(null)

const recruitTypeLabel = (t: string | null) =>
  ({ AUTUMN: '秋招', SPRING: '春招', SOCIAL: '社招', INTERN: '实习', TARGETED: '定向' } as Record<string, string>)[t || ''] || t || '—'

const AI_LABELS: Record<string, string> = {
  resume: '简历分析',
  question: '面试出题',
  evaluate: '回答评估',
  jobAnalysis: '岗位分析',
  rag: '知识库问答',
  totalCalls: '调用总次数',
  avgMs: '平均耗时(ms)',
  p95Ms: 'P95 耗时(ms)',
}
const aiLabel = (k: string) => AI_LABELS[k] || k

const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleString('zh-CN', { hour12: false }) : '—')

const isOverseas = (p: string) => OVERSEAS_MARKERS.some((m) => p.includes(m))

/** 空态兜底：后端老版本可能不返回新字段，统一给空数组避免模板报错 */
const sourceDist = computed<SourceDist[]>(() => overview.value?.sourceDist || [])
const trend = computed<TrendPoint[]>(() => overview.value?.trend || [])
const recruitDist = computed(() => overview.value?.recruitDist || {})
const aiCalls = computed(() => metrics.value?.aiCalls || {})

const activeJobsTotal = computed(() => overview.value?.activeJobs ?? 0)
const enabledCount = computed(() => sources.value.filter((s) => s.enabled).length)
const maxSourceTotal = computed(() => Math.max(1, ...sourceDist.value.map((s) => s.total)))
const maxTrend = computed(() => Math.max(1, ...trend.value.map((d) => Math.max(d.jobs, d.users))))

const cacheHitRate = computed(() => {
  const c = metrics.value?.cache
  if (!c) return '—'
  const total = c.hits + c.misses
  return total === 0 ? '—' : `${Math.round((c.hits / total) * 100)}%`
})

const sseUsage = computed(() => {
  const s = metrics.value?.sse
  if (!s || !s.maxConcurrent) return '0'
  return String(Math.round((s.activeCount / s.maxConcurrent) * 100))
})

const overviewCards = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    { label: '岗位总量', value: o.totalJobs, tone: '' },
    { label: '有效岗位', value: o.activeJobs, tone: 'ok' },
    { label: '已失效', value: o.inactiveJobs, tone: 'off' },
    { label: '注册用户', value: o.totalUsers, tone: '' },
    { label: '已禁用', value: o.bannedUsers, tone: 'off' },
  ]
})

/** 招聘类型分布列表（带占比，供环形图与图例共用） */
const recruitDistList = computed(() => {
  const entries = Object.entries(recruitDist.value).map(([key, value]) => ({ key, value: Number(value) || 0 }))
  entries.sort((a, b) => b.value - a.value)
  return entries
})

/** 用 conic-gradient 拼环形图，避免引入图表库 */
const donutStyle = computed(() => {
  const list = recruitDistList.value
  const total = list.reduce((sum, i) => sum + i.value, 0)
  if (total === 0) return { background: 'var(--c-bg-alt)' }
  let acc = 0
  const stops: string[] = []
  list.forEach((item, i) => {
    const start = (acc / total) * 100
    acc += item.value
    const end = (acc / total) * 100
    const color = DONUT_COLORS[i % DONUT_COLORS.length]
    stops.push(`${color} ${start}% ${end}%`)
  })
  return { background: `conic-gradient(${stops.join(',')})` }
})

const barWidth = (v: number, max: number) => `${Math.max(2, Math.round((v / max) * 100))}%`
const barHeight = (v: number) => `${Math.max(4, Math.round((v / maxTrend.value) * 100))}%`

/** 拉取异常的数据源清单（总览告警横幅） */
const sourceAlerts = computed<SourceHealth[]>(() => overview.value?.sourceAlerts || [])

/**
 * 数据源状态判定。
 *
 * 优先看**最近一次拉取是否成功**：一个源「库里有历史数据」≠「现在还能拉通」，
 * 后者才是需要运维介入的信号。此前只看 enabled + total，导致源挂掉后仍显示「正常」。
 */
function statusOf(s: SourceRow): { label: string; cls: string } {
  if (s.health && !s.health.healthy) return { label: '拉取失败', cls: 'badge-off' }
  if (s.enabled && s.total > 0) return { label: '正常', cls: 'badge-ok' }
  if (s.enabled && s.total === 0) return { label: '已启用·待产出', cls: 'badge-warn' }
  if (!s.enabled && s.total > 0) return { label: '已关闭·有存量', cls: 'badge-warn' }
  return { label: '未启用', cls: 'badge-mute' }
}

/** 相对时间：后台看板关注「多久以前」，绝对时间戳反而需要心算 */
function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const diff = Date.now() - new Date(iso).getTime()
  if (Number.isNaN(diff)) return '—'
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

/** 最近拉取结果摘要：成功给条数与耗时，失败给连续次数（错误正文单独一列） */
function healthText(s: SourceRow): string {
  const h = s.health
  if (!h) return '—'
  const when = relativeTime(h.lastAttemptAt)
  return h.healthy ? `成功 ${h.lastCount} 条 · ${h.lastElapsedMs}ms · ${when}` : `失败 ${h.consecutiveFailures} 次 · ${when}`
}

/** 说明列：失败时优先展示错误摘要（这是排查的第一手线索） */
function noteText(s: SourceRow): string {
  if (s.health && !s.health.healthy && s.health.lastError) return s.health.lastError
  return s.note || '—'
}

function switchTab(k: TabKey) {
  activeTab.value = k
  if (k === 'sources' && !sources.value.length) fetchSources()
  if (k === 'metrics' && !metrics.value) fetchMetrics()
}

async function fetchOverview() {
  try {
    overview.value = (await api.get('/api/admin/overview')) as unknown as Overview
  } catch (e) {
    ElMessage.error(getErrMessage(e, '获取总览失败'))
  }
}

async function fetchSources() {
  sourcesLoading.value = true
  try {
    const res = (await api.get('/api/admin/sources')) as unknown as { items?: SourceRow[] }
    sources.value = res?.items || []
  } catch (e) {
    ElMessage.error(getErrMessage(e, '获取数据源失败'))
  } finally {
    sourcesLoading.value = false
  }
}

async function doRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  refreshResult.value = null
  try {
    const r = (await api.post('/api/admin/jobs/refresh')) as unknown as RefreshResult
    refreshResult.value = r
    ElMessage.success(`刷新完成：新增 ${r.inserted} / 更新 ${r.updated} / 下架 ${r.expired}`)
    await Promise.all([fetchOverview(), fetchSources()])
  } catch (e) {
    ElMessage.error(getErrMessage(e, '刷新失败'))
  } finally {
    refreshing.value = false
  }
}

async function fetchJobs() {
  jobsLoading.value = true
  try {
    const params = new URLSearchParams({ page: String(jobPage.value), size: String(jobSize) })
    if (jobKeyword.value.trim()) params.set('keyword', jobKeyword.value.trim())
    if (jobSource.value) params.set('source', jobSource.value)
    if (jobRecruitType.value) params.set('recruitType', jobRecruitType.value)
    if (jobActive.value) params.set('active', jobActive.value)
    const data = (await api.get(`/api/admin/jobs?${params.toString()}`)) as unknown as { total: number; items: JobRow[] }
    jobsTotal.value = data.total
    jobs.value = data.items
  } catch (e) {
    ElMessage.error(getErrMessage(e, '加载岗位失败'))
  } finally {
    jobsLoading.value = false
  }
}

function searchJobs() {
  jobPage.value = 0
  fetchJobs()
}

function resetJobFilters() {
  jobKeyword.value = ''
  jobSource.value = ''
  jobRecruitType.value = ''
  jobActive.value = ''
  searchJobs()
}

async function toggleJob(job: JobRow) {
  const action = job.active ? 'deactivate' : 'activate'
  try {
    await api.post(`/api/admin/jobs/${job.id}/${action}`)
    ElMessage.success(job.active ? '已下架' : '已恢复')
    fetchJobs()
    fetchOverview()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '操作失败'))
  }
}

async function deleteJob(job: JobRow) {
  try {
    await ElMessageBox.confirm(`确认删除岗位「${job.title}」（${job.companyName}）？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.delete(`/api/admin/jobs/${job.id}`)
    ElMessage.success('已删除')
    fetchJobs()
    fetchOverview()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '删除失败'))
  }
}

async function fetchUsers() {
  usersLoading.value = true
  try {
    const params = new URLSearchParams({ page: String(userPage.value), size: String(userSize) })
    if (userKeyword.value.trim()) params.set('keyword', userKeyword.value.trim())
    const data = (await api.get(`/api/admin/users?${params.toString()}`)) as unknown as { total: number; items: UserRow[] }
    usersTotal.value = data.total
    users.value = data.items
  } catch (e) {
    ElMessage.error(getErrMessage(e, '加载用户失败'))
  } finally {
    usersLoading.value = false
  }
}

function searchUsers() {
  userPage.value = 0
  fetchUsers()
}

async function toggleBan(u: UserRow) {
  try {
    await api.post(`/api/admin/users/${u.id}/${u.banned ? 'unban' : 'ban'}`)
    ElMessage.success(u.banned ? '已解禁' : '已禁用（进程内生效，重启后恢复）')
    fetchUsers()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '操作失败'))
  }
}

async function fetchMetrics() {
  try {
    metrics.value = (await api.get('/api/admin/metrics')) as unknown as Metrics
  } catch (e) {
    ElMessage.error(getErrMessage(e, '获取指标失败'))
  }
}

// P2-21：定时器句柄——组件卸载时清理，此前离开页面仍每 30s 请求，
// 登出后触发 401 → 全页跳转
let overviewTimer: ReturnType<typeof setInterval> | undefined
onUnmounted(() => {
  if (overviewTimer) clearInterval(overviewTimer)
})

onMounted(() => {
  fetchOverview()
  fetchJobs()
  fetchUsers()
  fetchMetrics()
  overviewTimer = setInterval(() => {
    if (activeTab.value === 'overview') fetchOverview()
  }, 30000)
})
</script>

<style scoped>
.admin-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 4px 0 32px;
}

/* ── 头部 ── */
.admin-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.admin-header h1 {
  margin: 0 0 6px;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: -0.5px;
}

.admin-sub {
  margin: 0;
  font-size: 13px;
  color: var(--c-text-tertiary);
}

.btn-refresh-all {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-fast);
  white-space: nowrap;
}

.btn-refresh-all:hover:not(:disabled) {
  background: var(--brand-primary-hover);
}

.btn-refresh-all:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 标签页 ── */
.admin-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 18px;
  padding: 4px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-lg);
  overflow-x: auto;
  scrollbar-width: none;
}

.admin-tabs::-webkit-scrollbar {
  display: none;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.tab-btn:hover {
  color: var(--c-text);
}

.tab-btn.active {
  color: var(--c-text);
  background: var(--c-surface);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.tab-badge {
  min-width: 18px;
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border-radius: var(--radius-full);
  text-align: center;
}

/* ── 面板 ── */
.panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 统计卡片 ── */
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.stat-card {
  padding: 18px 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  text-align: center;
}

.stat-value {
  font-family: var(--font-sans);
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--brand-primary);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--c-text-tertiary);
}

.stat-card.ok .stat-value { color: var(--c-success); }
.stat-card.off .stat-value { color: var(--c-text-quaternary); }

.overview-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--c-text-secondary);
  padding: 12px 16px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
}

.overview-meta b { color: var(--c-text); font-weight: 600; }

/* ── 数据源告警横幅（v1.38.0）── */
.alert-banner {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px 18px;
  background: var(--c-danger-light);
  border: 1px solid var(--c-danger);
  border-radius: var(--radius-lg);
}

.alert-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  margin-top: 1px;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: var(--c-danger);
  border-radius: 50%;
}

.alert-body {
  flex: 1;
  min-width: 0;
}

.alert-body b {
  display: block;
  margin-bottom: 8px;
  font-size: 13.5px;
  color: var(--c-danger);
}

.alert-list {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.alert-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.6;
}

.alert-src {
  font-weight: 600;
  color: var(--c-text);
}

.alert-err {
  color: var(--c-text-secondary);
  word-break: break-all;
}

.alert-times {
  font-size: 11.5px;
  color: var(--c-text-tertiary);
}

.alert-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-tertiary);
}

.alert-hint code {
  padding: 1px 5px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border-radius: var(--radius-xs);
}

.alert-action {
  flex-shrink: 0;
  align-self: center;
  padding: 8px 16px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--c-danger);
  background: var(--c-surface);
  border: 1px solid var(--c-danger);
  border-radius: var(--radius-md);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.alert-action:hover {
  color: #fff;
  background: var(--c-danger);
}

/* 拉取失败的单元格：让异常行在表格里能一眼扫出来 */
.cell-error {
  color: var(--c-danger);
  font-weight: 600;
}

/* ── 图表区 ── */
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 16px;
}

.chart-card {
  padding: 18px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  min-width: 0;
}

.chart-card h3 {
  margin: 0 0 14px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.chart-hint {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--c-text-quaternary);
}

.chart-empty {
  margin: 0;
  padding: 20px 0;
  font-size: 13px;
  color: var(--c-text-tertiary);
  text-align: center;
}

/* 水平条形图 */
.bar-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bar-row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 92px;
  align-items: center;
  gap: 10px;
}

.bar-name {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  position: relative;
  height: 14px;
  background: var(--c-bg-alt);
  border-radius: var(--radius-full);
  overflow: hidden;
}

/* total 条（底）与 active 条（覆盖）叠放：一眼看出「有效 vs 失效」比例 */
.bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--c-border-strong);
  border-radius: var(--radius-full);
}

.bar-fill.active-part {
  background: var(--brand-primary);
}

.bar-value {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--c-text);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.bar-value em {
  font-style: normal;
  font-weight: 400;
  color: var(--c-text-quaternary);
  font-size: 11.5px;
}

/* 环形图 */
.donut-wrap {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.donut {
  position: relative;
  width: 116px;
  height: 116px;
  border-radius: 50%;
  flex-shrink: 0;
}

.donut-hole {
  position: absolute;
  inset: 22px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--c-surface);
  border-radius: 50%;
}

.donut-hole b {
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.donut-hole span {
  font-size: 10.5px;
  color: var(--c-text-tertiary);
}

.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
  min-width: 140px;
}

.legend li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--c-text-secondary);
}

.legend i {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex-shrink: 0;
}

.legend b {
  margin-left: auto;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

/* 趋势图 */
.chart-card.full { grid-column: 1 / -1; }

.trend-legend {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.trend-legend .dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-right: 6px;
}

.trend-legend .dot.jobs { background: var(--brand-primary); }
.trend-legend .dot.users { background: var(--brand-accent); }

.trend-chart {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 140px;
}

.trend-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  height: 100%;
}

.trend-bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 3px;
  width: 100%;
  flex: 1;
  min-height: 0;
}

.trend-bar {
  width: 12px;
  border-radius: 3px 3px 0 0;
  transition: height var(--transition-base);
}

.trend-bar.jobs { background: var(--brand-primary); }
.trend-bar.users { background: var(--brand-accent); }

.trend-label {
  font-size: 11px;
  color: var(--c-text-tertiary);
  white-space: nowrap;
}

.trend-num {
  font-size: 10px;
  color: var(--c-text-quaternary);
  font-variant-numeric: tabular-nums;
}

/* ── 工具栏 ── */
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  padding: 14px 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
}

.search-input,
.select-input {
  padding: 9px 12px;
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--c-text);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.search-input {
  flex: 1;
  min-width: 180px;
  background: var(--c-surface);
}

.search-input:focus,
.select-input:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
}

.ghost-btn {
  padding: 9px 16px;
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.ghost-btn:hover:not(:disabled) {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
}

.ghost-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.tip {
  font-size: 12.5px;
  color: var(--c-text-tertiary);
  line-height: 1.6;
}

/* ── 表格 ── */
.table-wrap {
  overflow-x: auto;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 11px 14px;
  text-align: left;
  border-bottom: 1px solid var(--c-border-light);
  white-space: nowrap;
}

.data-table th {
  font-weight: 600;
  font-size: 12.5px;
  color: var(--c-text-tertiary);
  background: var(--c-bg-alt);
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-table tbody tr:last-child td {
  border-bottom: none;
}

.data-table tbody tr:hover {
  background: var(--c-bg-alt);
}

.data-table td.note {
  white-space: normal;
  max-width: 300px;
  font-size: 12px;
  color: var(--c-text-tertiary);
  line-height: 1.6;
}

.strong { font-weight: 600; color: var(--c-text); }

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-secondary);
}

.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pill {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 7px;
  font-size: 10.5px;
  font-weight: 600;
  border-radius: var(--radius-full);
  vertical-align: middle;
}

.pill.accent {
  color: var(--brand-accent);
  background: var(--brand-accent-light);
}

.badge-ok { color: var(--c-success); font-weight: 600; }
.badge-off { color: var(--c-danger); font-weight: 600; }
.badge-warn { color: var(--c-warning); font-weight: 600; }
.badge-mute { color: var(--c-text-quaternary); font-weight: 600; }

.ops {
  display: flex;
  gap: 6px;
}

.mini-btn {
  padding: 5px 11px;
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.mini-btn:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}

.mini-btn.danger {
  color: var(--c-danger);
  border-color: var(--c-danger);
}

.mini-btn.danger:hover {
  background: var(--c-danger-light);
}

.empty-cell {
  text-align: center;
  color: var(--c-text-tertiary);
  padding: 32px !important;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  font-size: 13px;
  color: var(--c-text-secondary);
}

/* ── 指标 ── */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 14px;
}

.metric-block {
  padding: 16px 18px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
}

.metric-block h3 {
  margin: 0 0 12px;
  font-family: var(--font-sans);
  font-size: 13.5px;
  font-weight: 600;
  color: var(--c-text);
}

.metric-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.metric-row b {
  color: var(--brand-primary);
  font-variant-numeric: tabular-nums;
}

/* ── 响应式 ── */
@media (max-width: 900px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
  .bar-row {
    grid-template-columns: 96px minmax(0, 1fr) 78px;
  }
  .admin-header {
    flex-direction: column;
    align-items: stretch;
  }
  .btn-refresh-all {
    justify-content: center;
  }
}

@media (max-width: 600px) {
  .stat-value { font-size: 24px; }
  .trend-chart { height: 110px; }
  .trend-bar { width: 8px; }
  .trend-num { display: none; }
}
</style>

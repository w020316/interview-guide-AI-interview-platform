<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

interface Overview {
  totalJobs: number
  activeJobs: number
  inactiveJobs: number
  totalUsers: number
  bannedUsers: number
  lastRefreshedAt: string | null
  refreshing: boolean
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
interface Metrics { aiCalls: Record<string, number>; cache: { hits: number; misses: number }; sse: { maxConcurrent: number; activeCount: number }; jvm: { usedMb: number; freeMb: number; totalMb: number; maxMb: number } }

const activeTab = ref<'overview' | 'jobs' | 'users' | 'metrics'>('overview')
const overview = ref<Overview | null>(null)
const refreshing = ref(false)
const refreshResult = ref<RefreshResult | null>(null)

const jobs = ref<JobRow[]>([])
const jobsTotal = ref(0)
const jobPage = ref(0)
const jobSize = ref(10)
const jobKeyword = ref('')
const jobsLoading = ref(false)

const users = ref<UserRow[]>([])
const usersTotal = ref(0)
const userPage = ref(0)
const userSize = ref(10)
const userKeyword = ref('')
const usersLoading = ref(false)

const metrics = ref<Metrics | null>(null)

const recruitTypeLabel = (t: string) => ({ AUTUMN: '秋招', SPRING: '春招', SOCIAL: '社招', INTERN: '实习', TARGETED: '定向' } as Record<string, string>)[t] || t || '-'
const fmtDate = (d: string | null) => d ? new Date(d).toLocaleString('zh-CN', { hour12: false }) : '-'
const overviewCards = computed(() => {
  if (!overview.value) return []
  const o = overview.value
  return [
    { label: '岗位总量', value: o.totalJobs },
    { label: '有效岗位', value: o.activeJobs },
    { label: '已失效', value: o.inactiveJobs },
    { label: '注册用户', value: o.totalUsers },
    { label: '已禁用', value: o.bannedUsers },
  ]
})

async function fetchOverview() {
  try {
    overview.value = await api.get('/api/admin/overview') as unknown as Overview
  } catch (e) {
    ElMessage.error(getErr(e, '获取总览失败'))
  }
}
async function doRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  refreshResult.value = null
  try {
    const r = await api.post('/api/admin/jobs/refresh') as unknown as RefreshResult
    refreshResult.value = r
    ElMessage.success(`刷新完成：新增 ${r.inserted} / 更新 ${r.updated} / 下架 ${r.expired}`)
    await fetchOverview()
  } catch (e) {
    ElMessage.error(getErr(e, '刷新失败'))
  } finally {
    refreshing.value = false
  }
}

async function fetchJobs() {
  jobsLoading.value = true
  try {
    const params = new URLSearchParams({ page: String(jobPage.value), size: String(jobSize.value) })
    if (jobKeyword.value.trim()) params.set('keyword', jobKeyword.value.trim())
    const data = await api.get(`/api/admin/jobs?${params.toString()}`) as unknown as { total: number; items: JobRow[] }
    jobsTotal.value = data.total
    jobs.value = data.items
  } catch (e) {
    ElMessage.error(getErr(e, '加载岗位失败'))
  } finally {
    jobsLoading.value = false
  }
}
function searchJobs() { jobPage.value = 0; fetchJobs() }
async function toggleJob(job: JobRow) {
  const action = job.active ? 'deactivate' : 'activate'
  try {
    await api.post(`/api/admin/jobs/${job.id}/${action}`)
    ElMessage.success(job.active ? '已下架' : '已恢复')
    fetchJobs()
  } catch (e) {
    ElMessage.error(getErr(e, '操作失败'))
  }
}
async function deleteJob(job: JobRow) {
  try {
    await ElMessageBox.confirm(`确认删除岗位「${job.title}」（${job.companyName}）？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await api.delete(`/api/admin/jobs/${job.id}`)
    ElMessage.success('已删除')
    fetchJobs()
  } catch (e) {
    ElMessage.error(getErr(e, '删除失败'))
  }
}

async function fetchUsers() {
  usersLoading.value = true
  try {
    const params = new URLSearchParams({ page: String(userPage.value), size: String(userSize.value) })
    if (userKeyword.value.trim()) params.set('keyword', userKeyword.value.trim())
    const data = await api.get(`/api/admin/users?${params.toString()}`) as unknown as { total: number; items: UserRow[] }
    usersTotal.value = data.total
    users.value = data.items
  } catch (e) {
    ElMessage.error(getErr(e, '加载用户失败'))
  } finally {
    usersLoading.value = false
  }
}
function searchUsers() { userPage.value = 0; fetchUsers() }
async function toggleBan(u: UserRow) {
  try {
    await api.post(`/api/admin/users/${u.id}/${u.banned ? 'unban' : 'ban'}`)
    ElMessage.success(u.banned ? '已解禁' : '已禁用（进程内生效，重启后恢复）')
    fetchUsers()
  } catch (e) {
    ElMessage.error(getErr(e, '操作失败'))
  }
}

async function fetchMetrics() {
  try {
    metrics.value = await api.get('/api/admin/metrics') as unknown as Metrics
  } catch (e) {
    ElMessage.error(getErr(e, '获取指标失败'))
  }
}

function getErr(e: unknown, fallback: string): string {
  const msg = (e as Error)?.message
  return msg && msg !== 'Request failed with status code 500' ? msg : fallback
}

onMounted(() => {
  fetchOverview()
  fetchJobs()
  fetchUsers()
  fetchMetrics()
  setInterval(() => { if (activeTab.value === 'overview') fetchOverview() }, 30000)
})
</script>

<template>
  <div class="admin-page">
    <header class="admin-header">
      <h1>管理后台</h1>
      <p class="admin-sub">数据总览 · 岗位管理 · 用户管理 · 系统指标</p>
    </header>

    <div class="admin-tabs">
      <button v-for="t in ([{k:'overview',l:'数据总览'},{k:'jobs',l:'岗位数据'},{k:'users',l:'用户管理'},{k:'metrics',l:'系统指标'}] as const)" :key="t.k"
        class="tab-btn" :class="{ active: activeTab === t.k }" @click="activeTab = t.k">{{ t.l }}</button>
    </div>

    <!-- 数据总览 -->
    <section v-show="activeTab === 'overview'" class="panel">
      <div class="cards">
        <div v-for="c in overviewCards" :key="c.label" class="stat-card">
          <div class="stat-value">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
      </div>
      <div class="overview-meta">
        <span>最近刷新：{{ fmtDate(overview?.lastRefreshedAt ?? null) }}</span>
        <span v-if="overview?.refreshing" class="badge-warn">刷新中…</span>
      </div>
      <div class="refresh-box">
        <button class="primary-btn" :disabled="refreshing || overview?.refreshing" @click="doRefresh">
          {{ refreshing ? '刷新中…' : '手动刷新岗位数据' }}
        </button>
        <span v-if="refreshResult" class="refresh-result">
          新增 {{ refreshResult.inserted }} · 更新 {{ refreshResult.updated }} · 下架 {{ refreshResult.expired }} · 删除 {{ refreshResult.removed }}
        </span>
      </div>
    </section>

    <!-- 岗位数据管理 -->
    <section v-show="activeTab === 'jobs'" class="panel">
      <div class="toolbar">
        <input v-model="jobKeyword" class="search-input" placeholder="搜索标题/公司/标签" @keyup.enter="searchJobs" />
        <button class="ghost-btn" @click="searchJobs">搜索</button>
      </div>
      <table class="data-table" v-loading="jobsLoading">
        <thead>
          <tr><th>ID</th><th>标题</th><th>公司</th><th>地点</th><th>类型</th><th>来源</th><th>状态</th><th>截止</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="j in jobs" :key="j.id">
            <td>{{ j.id }}</td>
            <td class="ellipsis" :title="j.title">{{ j.title }}</td>
            <td class="ellipsis" :title="j.companyName">{{ j.companyName }}</td>
            <td>{{ j.location }}</td>
            <td>{{ recruitTypeLabel(j.recruitType) }}</td>
            <td>{{ j.platform }}</td>
            <td><span :class="j.active ? 'badge-ok' : 'badge-off'">{{ j.active ? '有效' : '失效' }}</span></td>
            <td>{{ j.deadline ? j.deadline : '-' }}</td>
            <td class="ops">
              <button class="mini-btn" @click="toggleJob(j)">{{ j.active ? '下架' : '恢复' }}</button>
              <button class="mini-btn danger" @click="deleteJob(j)">删除</button>
            </td>
          </tr>
          <tr v-if="!jobs.length"><td colspan="9" class="empty-cell">暂无岗位</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button class="ghost-btn" :disabled="jobPage <= 0" @click="jobPage--; fetchJobs()">上一页</button>
        <span>第 {{ jobPage + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(jobsTotal / jobSize)) }} 页（{{ jobsTotal }} 条）</span>
        <button class="ghost-btn" :disabled="(jobPage + 1) * jobSize >= jobsTotal" @click="jobPage++; fetchJobs()">下一页</button>
      </div>
    </section>

    <!-- 用户管理 -->
    <section v-show="activeTab === 'users'" class="panel">
      <div class="toolbar">
        <input v-model="userKeyword" class="search-input" placeholder="搜索用户名/邮箱" @keyup.enter="searchUsers" />
        <button class="ghost-btn" @click="searchUsers">搜索</button>
        <span class="tip">禁用为进程内生效（重启自动恢复），用于临时封禁</span>
      </div>
      <table class="data-table" v-loading="usersLoading">
        <thead>
          <tr><th>ID</th><th>用户名</th><th>邮箱</th><th>注册时间</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>{{ u.id }}</td>
            <td>{{ u.username }}</td>
            <td>{{ u.email || '-' }}</td>
            <td>{{ fmtDate(u.createdAt) }}</td>
            <td><span :class="u.banned ? 'badge-off' : 'badge-ok'">{{ u.banned ? '已禁用' : '正常' }}</span></td>
            <td class="ops">
              <button class="mini-btn" :class="{ danger: !u.banned }" @click="toggleBan(u)">{{ u.banned ? '解禁' : '禁用' }}</button>
            </td>
          </tr>
          <tr v-if="!users.length"><td colspan="6" class="empty-cell">暂无用户</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button class="ghost-btn" :disabled="userPage <= 0" @click="userPage--; fetchUsers()">上一页</button>
        <span>第 {{ userPage + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(usersTotal / userSize)) }} 页（{{ usersTotal }} 条）</span>
        <button class="ghost-btn" :disabled="(userPage + 1) * userSize >= usersTotal" @click="userPage++; fetchUsers()">下一页</button>
      </div>
    </section>

    <!-- 系统指标 -->
    <section v-show="activeTab === 'metrics'" class="panel">
      <div class="metrics-grid" v-if="metrics">
        <div class="metric-block">
          <h3>AI 调用次数</h3>
          <div class="metric-row" v-for="(v, k) in metrics.aiCalls" :key="k">
            <span>{{ k }}</span><b>{{ typeof v === 'number' ? Math.round(v * 10) / 10 : v }}</b>
          </div>
        </div>
        <div class="metric-block">
          <h3>缓存</h3>
          <div class="metric-row"><span>命中</span><b>{{ metrics.cache.hits }}</b></div>
          <div class="metric-row"><span>未命中</span><b>{{ metrics.cache.misses }}</b></div>
        </div>
        <div class="metric-block">
          <h3>SSE 流式连接</h3>
          <div class="metric-row"><span>并发上限</span><b>{{ metrics.sse.maxConcurrent }}</b></div>
          <div class="metric-row"><span>当前活跃</span><b>{{ metrics.sse.activeCount }}</b></div>
        </div>
        <div class="metric-block">
          <h3>JVM 内存 (MB)</h3>
          <div class="metric-row"><span>已用</span><b>{{ metrics.jvm.usedMb }}</b></div>
          <div class="metric-row"><span>空闲</span><b>{{ metrics.jvm.freeMb }}</b></div>
          <div class="metric-row"><span>总量</span><b>{{ metrics.jvm.totalMb }}</b></div>
          <div class="metric-row"><span>上限</span><b>{{ metrics.jvm.maxMb }}</b></div>
        </div>
      </div>
      <button class="ghost-btn" @click="fetchMetrics">刷新指标</button>
    </section>
  </div>
</template>

<style scoped>
.admin-page { max-width: 1080px; margin: 0 auto; padding: 24px 16px; }
.admin-header h1 { margin: 0 0 4px; font-size: 22px; }
.admin-sub { margin: 0 0 16px; color: #888; font-size: 13px; }
.admin-tabs { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.tab-btn { padding: 8px 18px; border: 1px solid #ddd; border-radius: 8px; background: #fff; cursor: pointer; font-size: 14px; }
.tab-btn.active { background: #2f6fed; color: #fff; border-color: #2f6fed; }
.panel { background: #fff; border: 1px solid #eee; border-radius: 12px; padding: 20px; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card { background: #f6f8fb; border-radius: 10px; padding: 16px; text-align: center; }
.stat-value { font-size: 26px; font-weight: 700; color: #2f6fed; }
.stat-label { margin-top: 4px; color: #777; font-size: 13px; }
.overview-meta { display: flex; gap: 16px; color: #777; font-size: 13px; margin-bottom: 12px; align-items: center; }
.badge-warn { color: #d97706; font-weight: 600; }
.badge-ok { color: #16a34a; font-weight: 600; }
.badge-off { color: #dc2626; font-weight: 600; }
.refresh-box { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.primary-btn { padding: 9px 20px; background: #2f6fed; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }
.refresh-result { color: #16a34a; font-size: 13px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 14px; align-items: center; flex-wrap: wrap; }
.search-input { flex: 1; min-width: 200px; padding: 8px 12px; border: 1px solid #ddd; border-radius: 8px; font-size: 14px; }
.ghost-btn { padding: 8px 16px; border: 1px solid #ddd; border-radius: 8px; background: #fff; cursor: pointer; font-size: 14px; }
.ghost-btn:disabled { opacity: .5; cursor: not-allowed; }
.tip { color: #999; font-size: 12px; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th, .data-table td { padding: 9px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data-table th { background: #fafbfc; color: #666; font-weight: 600; }
.ellipsis { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops { display: flex; gap: 6px; }
.mini-btn { padding: 4px 10px; font-size: 12px; border: 1px solid #ddd; border-radius: 6px; background: #fff; cursor: pointer; }
.mini-btn.danger { color: #dc2626; border-color: #fca5a5; }
.empty-cell { text-align: center; color: #999; padding: 24px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 14px; margin-top: 14px; font-size: 13px; color: #666; }
.metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-bottom: 16px; }
.metric-block { background: #f6f8fb; border-radius: 10px; padding: 14px 16px; }
.metric-block h3 { margin: 0 0 10px; font-size: 14px; color: #444; }
.metric-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 13px; color: #555; }
.metric-row b { color: #2f6fed; }
</style>
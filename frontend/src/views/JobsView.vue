<template>
  <div class="jobs-page">
    <header class="page-header">
      <h1>招聘信息广场</h1>
      <p>聚合主流招聘平台与秋招精选信息，智能分类、多条件筛选，直通官方申请入口</p>
    </header>

    <!-- 招聘类型 Tab -->
    <div class="tab-switch">
      <button
        v-for="t in recruitTabs"
        :key="t.value"
        :class="{ active: recruitType === t.value }"
        @click="switchTab(t.value)"
      >
        {{ t.label }}
        <span v-if="t.value === 'FAVORITE' && favCount" class="tab-count">{{ favCount }}</span>
        <span v-else-if="t.value !== 'FAVORITE' && recruitCounts[t.value]" class="tab-count">{{ recruitCounts[t.value] }}</span>
      </button>
    </div>

    <!-- 收藏岗位截止提醒横幅（v1.23.3：收藏岗位 7 天内截止时站内提醒） -->
    <div v-if="remindJobs.length && !favoriteMode" class="ddl-banner" role="status">
      <span class="ddl-banner-icon">⏰</span>
      <span class="ddl-banner-text">
        {{ remindJobs.length }} 个收藏岗位将于 {{ remindJobs[0].daysLeft === 0 ? '今天' : `${remindJobs[0].daysLeft} 天内` }}截止
        <template v-if="remindJobs.length > 1">（最近：{{ remindJobs[0].title }} · {{ remindJobs[0].companyName }}）</template>
        <template v-else>（{{ remindJobs[0].title }} · {{ remindJobs[0].companyName }}）</template>
      </span>
      <button class="ddl-banner-action" @click="switchTab('FAVORITE')">查看收藏</button>
    </div>

    <!-- 筛选栏（收藏模式无筛选与分页） -->
    <div v-if="!favoriteMode" class="filter-card">
      <div class="filter-row">
        <input
          v-model="keyword"
          class="filter-input search"
          placeholder="搜索岗位 / 企业 / 标签..."
          @keyup.enter="applyFilters"
        />
        <select v-model="industry" class="filter-input">
          <option value="">全部行业</option>
          <option v-for="i in meta.industries" :key="i" :value="i">{{ i }}</option>
        </select>
        <select v-model="jobType" class="filter-input">
          <option value="">全部职位类型</option>
          <option v-for="t in meta.jobTypes" :key="t" :value="t">{{ t }}</option>
        </select>
        <select v-model="source" class="filter-input">
          <option value="">全部来源</option>
          <option v-for="s in meta.sources" :key="s" :value="s">{{ s }}</option>
        </select>
        <select v-model="degree" class="filter-input">
          <option value="">学历不限</option>
          <option v-for="d in meta.degrees" :key="d" :value="d">{{ d }}</option>
        </select>
        <select v-model="experience" class="filter-input">
          <option value="">经验不限</option>
          <option v-for="e in meta.experiences" :key="e" :value="e">{{ e }}</option>
        </select>
        <BaseButton variant="gradient" :loading="loading" :disabled="loading" @click="applyFilters">
          搜索
        </BaseButton>
        <BaseButton variant="ghost" @click="matchOpen = !matchOpen">
          {{ matchOpen ? '收起匹配' : '简历匹配推荐' }}
        </BaseButton>
      </div>
      <div class="filter-row location-row">
        <input
          v-model="location"
          class="filter-input"
          placeholder="工作地点（如：深圳 / 杭州 / 北京）"
          @keyup.enter="applyFilters"
        />
        <span class="update-hint" v-if="meta.lastUpdatedAt">
          数据更新于 {{ formatTime(meta.lastUpdatedAt) }}
        </span>
        <button class="refresh-btn" :disabled="refreshing" @click="refreshData">
          {{ refreshing ? '刷新中...' : '刷新数据' }}
        </button>
      </div>

      <!-- 简历匹配推荐（v1.29.0） -->
      <div v-if="matchOpen" class="filter-row match-panel">
        <textarea v-model="matchResume" rows="3" class="filter-input ta"
          placeholder="粘贴简历核心内容（技能/项目/技术栈），自动为你推荐最吻合的岗位"></textarea>
        <div class="match-actions">
          <BaseButton variant="gradient" :loading="matching" :disabled="matching || !matchResume.trim()" @click="runMatch">
            {{ matching ? '匹配中...' : '开始匹配' }}
          </BaseButton>
          <BaseButton v-if="matchActive" variant="ghost" @click="exitMatch">返回列表</BaseButton>
        </div>
      </div>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <div class="loading-text">正在加载岗位信息...</div>
    </div>

    <!-- 错误态（与空态区分：加载失败可重试） -->
    <div v-else-if="loadError" class="empty-state">
      <div class="empty-icon">⚠️</div>
      <p>岗位数据加载失败，可能是后端正在冷启动，请稍后重试</p>
      <BaseButton variant="gradient" @click="fetchJobs">重新加载</BaseButton>
    </div>

    <!-- 空态 -->
    <div v-else-if="displayJobs.length === 0" class="empty-state">
      <div class="empty-icon">🔍</div>
      <p v-if="favoriteMode">暂无收藏岗位，在岗位卡片上点击 ♥ 收藏感兴趣的岗位，截止前会在此提醒</p>
      <p v-else>暂无匹配的岗位，试试调整筛选条件或刷新数据</p>
    </div>

    <!-- 岗位列表 -->
    <div v-else class="job-list fade-in-up">
      <div v-for="job in displayJobs" :key="job.id" class="job-card" @click="showDetail(job)">
        <div class="job-card-main">
          <div class="job-card-header">
            <span class="job-title">{{ job.title }}</span>
            <span class="job-salary">{{ job.salary || '面议' }}</span>
          </div>
          <div class="job-company">{{ job.companyName }}</div>
          <div class="job-meta">
            <span v-if="job.location" class="meta-item">📍 {{ job.location }}</span>
            <span v-if="job.degree" class="meta-item">🎓 {{ job.degree }}</span>
            <span v-if="job.experience" class="meta-item">💼 {{ job.experience }}</span>
          </div>
          <div class="job-tags">
            <span v-if="matchInfo(job)" class="tag tag-match">匹配 {{ matchInfo(job)?.matchScore ?? 0 }} 分</span>
            <span v-for="(s, si) in matchInfo(job)?.matchedSkills || []" :key="'ms' + si" class="tag tag-skill">+{{ s }}</span>
            <span class="tag tag-source">{{ job.platform }}</span>
            <span v-if="job.industry" class="tag">{{ job.industry }}</span>
            <span v-if="job.jobType" class="tag">{{ job.jobType }}</span>
            <span v-for="(t, i) in tagList(job.tags)" :key="'t' + i" class="tag">{{ t }}</span>
          </div>
        </div>
        <div class="job-card-side">
          <button
            class="fav-btn"
            :class="{ active: favIds.has(job.id) }"
            :aria-label="favIds.has(job.id) ? '取消收藏' : '收藏该岗位'"
            :title="favIds.has(job.id) ? '取消收藏' : '收藏该岗位，截止前站内提醒'"
            @click.stop="toggleFavorite(job)"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
              <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
                :fill="favIds.has(job.id) ? 'currentColor' : 'none'"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <span class="deadline" :class="deadlineClass(job.deadline)">
            {{ deadlineText(job.deadline) }}
          </span>
          <a
            v-if="job.applyUrl"
            :href="job.applyUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="apply-btn"
            @click.stop
          >
            立即申请
          </a>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="!favoriteMode && total > pageSize" class="pagination">
      <button :disabled="page === 0 || loading" @click="goPage(page - 1)">上一页</button>
      <span class="page-info">第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button :disabled="page >= totalPages - 1 || loading" @click="goPage(page + 1)">下一页</button>
    </div>

    <!-- 岗位详情弹窗 -->
    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="modal-card">
        <button class="modal-close" @click="detail = null">×</button>
        <h2 class="detail-title">{{ detail.title }}</h2>
        <div class="detail-sub">{{ detail.companyName }} · {{ detail.salary || '面议' }}</div>
        <div class="detail-meta">
          <span v-if="detail.location">📍 {{ detail.location }}</span>
          <span v-if="detail.degree">🎓 {{ detail.degree }}</span>
          <span v-if="detail.experience">💼 {{ detail.experience }}</span>
          <span v-if="detail.industry">🏢 {{ detail.industry }}</span>
          <span v-if="detail.deadline">⏰ 截止：{{ detail.deadline }}</span>
        </div>
        <div v-if="detail.description" class="detail-block">
          <h4>岗位描述</h4>
          <p class="pre-wrap">{{ detail.description }}</p>
        </div>
        <div v-if="detail.requirements" class="detail-block">
          <h4>岗位要求</h4>
          <p class="pre-wrap">{{ detail.requirements }}</p>
        </div>
        <div v-if="detail.tags" class="detail-block">
          <div class="job-tags">
            <span v-for="(t, i) in tagList(detail.tags)" :key="i" class="tag">{{ t }}</span>
          </div>
        </div>
        <div class="detail-footer">
          <span class="detail-source">数据来源：{{ detail.platform }}</span>
          <a
            v-if="detail.applyUrl"
            :href="detail.applyUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="apply-btn primary"
          >
            前往官方申请入口 →
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton } from '../components'

/**
 * 招聘信息广场
 * - 聚合内置秋招精选与第三方平台岗位（智联招聘/前程无忧/BOSS直聘，需后端配置数据服务）
 * - 支持关键词搜索与行业/职位类型/地点/来源多条件筛选
 * - 秋招 Tab 默认展示，含申请截止日期倒计时与官方申请入口
 */

interface JobPosting {
  id: number
  platform: string
  externalId: string
  title: string
  companyName: string
  industry: string | null
  jobType: string | null
  location: string | null
  salary: string | null
  degree: string | null
  experience: string | null
  recruitType: string | null
  deadline: string | null
  applyUrl: string | null
  description: string | null
  requirements: string | null
  tags: string | null
}

interface JobsMeta {
  industries: string[]
  jobTypes: string[]
  sources: string[]
  degrees?: string[]
  experiences?: string[]
  recruitCounts: Record<string, number>
  lastUpdatedAt: string | null
}

const recruitTabs = [
  { value: 'AUTUMN', label: '秋招精选' },
  { value: 'SPRING', label: '春招' },
  { value: 'INTERN', label: '实习' },
  { value: 'SOCIAL', label: '社招' },
  { value: 'TARGETED', label: '定向专项' },
  { value: 'FAVORITE', label: '我的收藏' },
  { value: '', label: '全部' },
]

/** 收藏岗位快照（后端 /api/jobs/favorite 返回） */
interface JobFavorite {
  id: number
  jobId: number
  title: string
  companyName: string
  platform: string | null
  location: string | null
  salary: string | null
  deadline: string | null
  applyUrl: string | null
  daysLeft?: number
  expired?: boolean
  remind?: boolean
}

const loading = ref(false)
const loadError = ref(false)
const refreshing = ref(false)
const jobs = ref<JobPosting[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = 10
const detail = ref<JobPosting | null>(null)

const recruitType = ref('AUTUMN')
const keyword = ref('')
const industry = ref('')
const jobType = ref('')
const location = ref('')
const source = ref('')
const degree = ref('')
const experience = ref('')

const meta = ref<JobsMeta>({ industries: [], jobTypes: [], sources: [], recruitCounts: {}, lastUpdatedAt: null })
const recruitCounts = computed(() => meta.value.recruitCounts || {})
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

// ── 岗位收藏（v1.23.3）──
const favIds = ref<Set<number>>(new Set())
const favList = ref<JobFavorite[]>([])
const favCount = ref(0)
const favoriteMode = computed(() => recruitType.value === 'FAVORITE')

// ── 简历匹配推荐（v1.29.0）──
const matchOpen = ref(false)
const matchActive = ref(false)
const matching = ref(false)
const matchResume = ref('')
interface MatchedResult { job: JobPosting; matchScore: number; matchedSkills: string[] }
const matched = ref<MatchedResult[]>([])
try {
  const pre = sessionStorage.getItem('interview_prefill_resume')
  if (pre) matchResume.value = pre
} catch { /* ignore */ }

function matchInfo(job: JobPosting) {
  return matched.value.find((m) => m.job.id === job.id) ?? null
}
async function runMatch() {
  if (!matchResume.value.trim()) return ElMessage.warning('请粘贴简历内容')
  matching.value = true
  try {
    const res = (await api.post('/api/jobs/match', { resumeText: matchResume.value })) as unknown as { items?: MatchedResult[] }
    matched.value = (res?.items || []).filter((i) => i.job && i.job.id != null)
    if (matched.value.length) {
      matchActive.value = true
      ElMessage.success(`为你推荐 ${matched.value.length} 个岗位`)
    } else {
      ElMessage.info('暂未找到与你简历匹配的岗位，可试试调整')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '匹配失败'))
  } finally {
    matching.value = false
  }
}
function exitMatch() {
  matchActive.value = false
  matched.value = []
}
/** 收藏岗位截止 7 天内的提醒列表（横幅数据源） */
const remindJobs = computed(() => favList.value.filter((f) => f.remind))

/** 收藏模式渲染快照卡片；普通模式渲染搜索结果 */
const displayJobs = computed<JobPosting[]>(() => {
  if (matchActive.value && matched.value.length) return matched.value.map((m) => m.job)
  if (!favoriteMode.value) return jobs.value
  return favList.value.map((f) => ({
    id: f.jobId,
    platform: f.platform || '',
    externalId: '',
    title: f.title,
    companyName: f.companyName,
    industry: null,
    jobType: null,
    location: f.location,
    salary: f.salary,
    degree: null,
    experience: null,
    recruitType: null,
    deadline: f.deadline,
    applyUrl: f.applyUrl,
    description: null,
    requirements: null,
    tags: null,
  }))
})

/** 拉取我的收藏（快照列表，同时得到 favIds / count / 提醒数据） */
async function loadFavorites() {
  try {
    const res = await api.get('/api/jobs/favorite') as unknown as { items?: JobFavorite[] }
    favList.value = res?.items || []
    favCount.value = favList.value.length
    favIds.value = new Set(favList.value.map((f) => f.jobId))
  } catch {
    // 收藏数据加载失败不阻断岗位列表
  }
}

/** 收藏/取消收藏（快照式：取消后该岗位的提醒同步消失） */
async function toggleFavorite(job: JobPosting) {
  try {
    const res = await api.post('/api/jobs/favorite/toggle', { jobId: job.id }) as unknown as { favorited: boolean; count: number }
    const ids = new Set(favIds.value)
    if (res.favorited) {
      ids.add(job.id)
      ElMessage.success('已收藏，截止前会提醒你')
    } else {
      ids.delete(job.id)
      ElMessage.success('已取消收藏')
    }
    favIds.value = ids
    favCount.value = res.count ?? favIds.value.size
    // 刷新快照列表（提醒横幅与收藏 Tab 数据同步）
    loadFavorites()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '收藏操作失败'))
  }
}

function tagList(tags: string | null): string[] {
  if (!tags) return []
  return tags.split(/[,，]/).map((s) => s.trim()).filter(Boolean).slice(0, 4)
}

function showDetail(job: JobPosting) {
  detail.value = job
}

async function fetchJobs() {
  loading.value = true
  loadError.value = false
  try {
    const res = await api.get('/api/jobs', {
      params: {
        keyword: keyword.value || undefined,
        industry: industry.value || undefined,
        jobType: jobType.value || undefined,
        location: location.value || undefined,
        recruitType: recruitType.value || undefined,
        source: source.value || undefined,
        degree: degree.value || undefined,
        experience: experience.value || undefined,
        page: page.value,
        size: pageSize,
      },
    })
    // axios 拦截器已解包 Result.data，此处直接取分页结构 {total,page,size,items}
    const data = res as unknown as { total: number; items: JobPosting[] }
    jobs.value = data?.items || []
    total.value = data?.total || 0
  } catch (e) {
    loadError.value = true
    ElMessage.error(getErrMessage(e, '岗位加载失败'))
  } finally {
    loading.value = false
  }
}

async function fetchMeta() {
  try {
    const res = await api.get('/api/jobs/meta')
    meta.value = (res as unknown as JobsMeta) || { industries: [], jobTypes: [], sources: [], recruitCounts: {}, lastUpdatedAt: null }
  } catch {
    // 元数据加载失败不阻断主列表
  }
}

function applyFilters() {
  page.value = 0
  fetchJobs()
}

function switchTab(value: string) {
  recruitType.value = value
  if (value === 'FAVORITE') {
    loadFavorites()
  } else {
    applyFilters()
  }
}

function goPage(p: number) {
  page.value = p
  fetchJobs()
}

async function refreshData() {
  refreshing.value = true
  try {
    await api.post('/api/jobs/refresh', null, { timeout: 120000 })
    ElMessage.success('岗位数据已刷新')
    await Promise.all([fetchJobs(), fetchMeta()])
  } catch (e) {
    ElMessage.error(getErrMessage(e, '刷新失败，请稍后重试'))
  } finally {
    refreshing.value = false
  }
}

function deadlineText(deadline: string | null): string {
  if (!deadline) return '长期有效'
  const d = new Date(deadline + 'T23:59:59')
  const diff = Math.ceil((d.getTime() - Date.now()) / 86400000)
  if (diff < 0) return '已截止'
  if (diff <= 7) return `仅剩 ${diff} 天`
  if (diff <= 30) return `${diff} 天后截止`
  return `截止 ${deadline}`
}

function deadlineClass(deadline: string | null): string {
  if (!deadline) return ''
  const d = new Date(deadline + 'T23:59:59')
  const diff = Math.ceil((d.getTime() - Date.now()) / 86400000)
  if (diff < 0) return 'closed'
  if (diff <= 7) return 'urgent'
  return ''
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  fetchJobs()
  fetchMeta()
  // 收藏数据用于提醒横幅与 Tab 计数（失败静默，不阻断岗位列表）
  loadFavorites()
})
</script>

<style scoped>
.jobs-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 32px 20px 60px;
}

.page-header h1 {
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 8px;
}

.page-header p {
  color: var(--text-secondary, #888);
  margin-bottom: 24px;
}

.tab-switch {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.tab-switch button {
  padding: 8px 18px;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-primary, #333);
  transition: all 0.2s;
}

.tab-switch button.active {
  background: var(--primary-color, #4f46e5);
  border-color: var(--primary-color, #4f46e5);
  color: #fff;
}

.tab-count {
  display: inline-block;
  margin-left: 4px;
  font-size: 12px;
  opacity: 0.8;
}

/* ── 收藏截止提醒横幅（v1.23.3）── */
.ddl-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 12px;
  flex-wrap: wrap;
}
.ddl-banner-icon {
  font-size: 16px;
  flex-shrink: 0;
}
.ddl-banner-text {
  flex: 1;
  min-width: 200px;
  font-size: 13px;
  color: #991b1b;
  line-height: 1.5;
}
.ddl-banner-action {
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: #dc2626;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.2s;
}
.ddl-banner-action:hover {
  background: #b91c1c;
}

/* ── 岗位收藏按钮（v1.23.3）── */
.fav-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary, #999);
  cursor: pointer;
  transition: all 0.2s;
}
.fav-btn:hover {
  color: #e11d48;
  border-color: #fda4af;
  background: #fff1f2;
}
.fav-btn.active {
  color: #e11d48;
  border-color: #fda4af;
  background: #fff1f2;
}

.filter-card {
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #eee);
  border-radius: 14px;
  padding: 16px;
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.filter-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.filter-input {
  flex: 1;
  min-width: 130px;
  padding: 9px 12px;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  background: var(--input-bg, #fafafa);
  color: var(--text-primary, #333);
  font-size: 14px;
  outline: none;
}

.filter-input:focus {
  border-color: var(--primary-color, #4f46e5);
}

.filter-input.search {
  flex: 2;
}

.location-row {
  justify-content: space-between;
}

.update-hint {
  font-size: 12px;
  color: var(--text-secondary, #999);
}

.refresh-btn {
  padding: 8px 14px;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary, #333);
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}

.refresh-btn:hover:not(:disabled) {
  border-color: var(--primary-color, #4f46e5);
  color: var(--primary-color, #4f46e5);
}

.job-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.job-card {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #eee);
  border-radius: 14px;
  padding: 18px 20px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.job-card:hover {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.job-card-main {
  flex: 1;
  min-width: 0;
}

.job-card-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}

.job-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #222);
}

.job-salary {
  color: #16a34a;
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
}

.job-company {
  margin-top: 4px;
  font-size: 14px;
  color: var(--text-secondary, #666);
}

.job-meta {
  margin-top: 8px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--text-secondary, #777);
}

.job-tags {
  margin-top: 10px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tag {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--tag-bg, #f1f0fb);
  color: var(--tag-text, #4f46e5);
}

.tag-source {
  background: #ecfdf5;
  color: #059669;
}

.job-card-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.deadline {
  font-size: 12px;
  color: var(--text-secondary, #999);
  white-space: nowrap;
}

.deadline.urgent {
  color: #dc2626;
  font-weight: 600;
}

.deadline.closed {
  color: #aaa;
  text-decoration: line-through;
}

.apply-btn {
  padding: 7px 16px;
  border-radius: 10px;
  border: 1px solid var(--primary-color, #4f46e5);
  color: var(--primary-color, #4f46e5);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
  transition: all 0.2s;
}

.apply-btn:hover {
  background: var(--primary-color, #4f46e5);
  color: #fff;
}

.apply-btn.primary {
  background: var(--primary-color, #4f46e5);
  color: #fff;
}

.loading-state {
  text-align: center;
  padding: 60px 0;
  color: var(--text-secondary, #999);
}

.loading-spinner {
  width: 36px;
  height: 36px;
  margin: 0 auto 12px;
  border: 3px solid var(--border-color, #eee);
  border-top-color: var(--primary-color, #4f46e5);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.empty-state {
  text-align: center;
  padding: 60px 0;
  color: var(--text-secondary, #999);
}

.empty-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.pagination {
  margin-top: 24px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
}

.pagination button {
  padding: 8px 18px;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary, #333);
  cursor: pointer;
}

.pagination button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-info {
  font-size: 13px;
  color: var(--text-secondary, #999);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-card {
  position: relative;
  background: var(--card-bg, #fff);
  border-radius: 16px;
  max-width: 640px;
  width: 100%;
  max-height: 82vh;
  overflow-y: auto;
  padding: 28px;
}

.modal-close {
  position: absolute;
  top: 14px;
  right: 16px;
  font-size: 24px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--text-secondary, #999);
}

.detail-title {
  font-size: 20px;
  font-weight: 700;
  padding-right: 30px;
}

.detail-sub {
  margin-top: 6px;
  color: #16a34a;
  font-weight: 600;
}

.detail-meta {
  margin-top: 12px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--text-secondary, #777);
}

.detail-block {
  margin-top: 18px;
}

.detail-block h4 {
  font-size: 15px;
  margin-bottom: 8px;
  color: var(--text-primary, #333);
}

.pre-wrap {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 14px;
  color: var(--text-secondary, #555);
}

.detail-footer {
  margin-top: 22px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-source {
  font-size: 12px;
  color: var(--text-secondary, #999);
}

.fade-in-up {
  animation: fadeInUp 0.3s ease;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 640px) {
  .job-card {
    flex-direction: column;
  }

  .job-card-side {
    flex-direction: row;
    align-items: center;
    width: 100%;
    justify-content: space-between;
  }
}
.match-panel { flex-direction: column; align-items: stretch; gap: 8px; }
.match-panel .filter-input.ta { resize: vertical; min-height: 68px; line-height: 1.6; font-family: inherit; }
.match-actions { display: flex; gap: 8px; align-items: center; }
.tag-match { color: var(--brand-primary, #0d7377); border-color: var(--brand-primary, #0d7377); font-weight: 600; }
.tag-skill { color: var(--c-info); border-color: var(--c-info); }
</style>

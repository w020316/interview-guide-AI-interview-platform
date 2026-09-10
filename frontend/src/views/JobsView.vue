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
        <span v-if="recruitCounts[t.value]" class="tab-count">{{ recruitCounts[t.value] }}</span>
      </button>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-card">
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
        <BaseButton variant="gradient" :loading="loading" :disabled="loading" @click="applyFilters">
          搜索
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
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <div class="loading-text">正在加载岗位信息...</div>
    </div>

    <!-- 空态 -->
    <div v-else-if="jobs.length === 0" class="empty-state">
      <div class="empty-icon">🔍</div>
      <p>暂无匹配的岗位，试试调整筛选条件或刷新数据</p>
    </div>

    <!-- 岗位列表 -->
    <div v-else class="job-list fade-in-up">
      <div v-for="job in jobs" :key="job.id" class="job-card" @click="showDetail(job)">
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
            <span class="tag tag-source">{{ job.platform }}</span>
            <span v-if="job.industry" class="tag">{{ job.industry }}</span>
            <span v-if="job.jobType" class="tag">{{ job.jobType }}</span>
            <span v-for="(t, i) in tagList(job.tags)" :key="'t' + i" class="tag">{{ t }}</span>
          </div>
        </div>
        <div class="job-card-side">
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
    <div v-if="total > pageSize" class="pagination">
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
  recruitCounts: Record<string, number>
  lastUpdatedAt: string | null
}

const recruitTabs = [
  { value: 'AUTUMN', label: '秋招精选' },
  { value: 'SPRING', label: '春招' },
  { value: 'INTERN', label: '实习' },
  { value: 'SOCIAL', label: '社招' },
  { value: '', label: '全部' },
]

const loading = ref(false)
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

const meta = ref<JobsMeta>({ industries: [], jobTypes: [], sources: [], recruitCounts: {}, lastUpdatedAt: null })
const recruitCounts = computed(() => meta.value.recruitCounts || {})
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function tagList(tags: string | null): string[] {
  if (!tags) return []
  return tags.split(/[,，]/).map((s) => s.trim()).filter(Boolean).slice(0, 4)
}

async function fetchJobs() {
  loading.value = true
  try {
    const res = await api.get('/api/jobs', {
      params: {
        keyword: keyword.value || undefined,
        industry: industry.value || undefined,
        jobType: jobType.value || undefined,
        location: location.value || undefined,
        recruitType: recruitType.value || undefined,
        source: source.value || undefined,
        page: page.value,
        size: pageSize,
      },
    })
    jobs.value = res.data?.data?.items || []
    total.value = res.data?.data?.total || 0
  } catch (e) {
    ElMessage.error(getErrMessage(e, '岗位加载失败'))
  } finally {
    loading.value = false
  }
}

async function fetchMeta() {
  try {
    const res = await api.get('/api/jobs/meta')
    meta.value = res.data?.data || { industries: [], jobTypes: [], sources: [], recruitCounts: {}, lastUpdatedAt: null }
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
  applyFilters()
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
</style>

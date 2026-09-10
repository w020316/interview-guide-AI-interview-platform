<template>
  <div class="book-page">
    <header class="page-header head-row">
      <div>
        <h1>错题本</h1>
        <p>评分低于阈值的题目，重点回顾薄弱题型</p>
      </div>
      <BaseButton v-if="items.length" variant="gradient" @click="retryWeak">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        针对错题重新练习
      </BaseButton>
    </header>

    <!-- 阈值筛选 -->
    <section class="filter-bar fade-in-up">
      <span class="filter-label">错题阈值：</span>
      <div class="radio-row">
        <button
          v-for="t in [50, 60, 70]"
          :key="t"
          class="radio-chip"
          :class="{ active: threshold === t }"
          @click="threshold = t"
        >
          低于 {{ t }} 分
        </button>
      </div>
      <span class="filter-note">共 {{ total }} 道错题</span>
    </section>

    <!-- 加载骨架 -->
    <div v-if="loading" class="list">
      <div v-for="i in 3" :key="i" class="book-card skeleton-card">
        <div class="skeleton skeleton-line w-70"></div>
        <div class="skeleton skeleton-line w-40"></div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="loadError" class="empty-state fade-in">
      <div class="empty-icon">⚠️</div>
      <div class="empty-title">加载失败</div>
      <div class="empty-desc">错题数据加载失败，请检查网络后重试</div>
      <button class="retry-btn" @click="load">重新加载</button>
    </div>
    <div v-else-if="!items.length" class="empty-state fade-in">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
            stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="empty-title">太棒了，没有错题</div>
      <div class="empty-desc">当前阈值下暂无低分题目，继续加油</div>
      <BaseButton variant="gradient" @click="$router.push('/interview')">再来一场面试</BaseButton>
    </div>

    <!-- 错题列表 -->
    <div v-else class="list">
      <div v-for="(q, idx) in items" :key="q.id" class="book-card fade-in-up">
        <div class="book-head">
          <div class="book-index">{{ idx + 1 }}</div>
          <div class="book-content">
            <div class="book-question">{{ q.question }}</div>
            <div class="book-tags">
              <BaseTag v-if="q.category" variant="info" size="sm">{{ q.category }}</BaseTag>
              <BaseTag v-if="q.difficulty" :variant="difficultyVariant(q.difficulty)" size="sm">{{ difficultyText(q.difficulty) }}</BaseTag>
              <BaseTag v-if="q.evaluationScore != null" variant="danger" size="sm">得分 {{ q.evaluationScore }}</BaseTag>
            </div>
          </div>
          <FavoriteToggle
            :question-id="q.id"
            :question="q.question"
            :category="q.category"
            :difficulty="q.difficulty"
            :reference-answer="q.referenceAnswer"
            :user-answer="q.userAnswer"
            :evaluation-score="q.evaluationScore"
            :session-id="q.sessionId"
          />
        </div>
        <div class="book-meta">
          <span>岗位：{{ q.jobDescription || '未指定' }}</span>
        </div>
        <div class="book-body">
          <div v-if="q.userAnswer" class="block">
            <div class="block-label">我的回答</div>
            <div class="block-text">{{ q.userAnswer }}</div>
          </div>
          <div v-if="q.referenceAnswer" class="block">
            <div class="block-label">参考答案</div>
            <div class="block-text ref">{{ q.referenceAnswer }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag, FavoriteToggle } from '../components'
import { extractWeakCategories, mostFrequentJob } from '../utils/weakCategories'

interface WrongQuestion {
  id: number
  question: string
  category?: string | null
  difficulty?: string | null
  userAnswer?: string | null
  referenceAnswer?: string | null
  evaluationScore?: number | null
  sessionId?: string | null
  jobDescription?: string | null
}

const router = useRouter()

const threshold = ref(60)
const items = ref<WrongQuestion[]>([])
const total = ref(0)
const loadError = ref(false)
const loading = ref(true)

onMounted(() => load())

watch(threshold, () => load())

async function load() {
  loading.value = true
  try {
    const data = (await api.get('/api/knowledge/wrong-questions', {
      params: { threshold: threshold.value },
    })) as unknown as { total?: number; questions?: WrongQuestion[] }
    items.value = data?.questions || []
    total.value = data?.total || items.value.length
  } catch (e: unknown) {
    loadError.value = true
    ElMessage.error(getErrMessage(e, '加载错题失败'))
  } finally { loading.value = false }
}

/**
 * 针对错题重新练习：
 * 聚合错题中最薄弱的前 3 个分类作为聚焦项，并带上出现最多的岗位，
 * 携带 query 跳转到面试准备页，由 InterviewView 预填岗位并聚焦薄弱分类出题。
 */
function retryWeak() {
  if (!items.value.length) {
    ElMessage.warning('当前阈值下暂无错题可重练')
    return
  }
  const focus = extractWeakCategories(items.value)
  const job = mostFrequentJob(items.value)
  if (!focus.length) {
    ElMessage.warning('错题缺少分类信息，无法聚焦薄弱项')
    return
  }
  const query: Record<string, string> = { focus: focus.join(',') }
  if (job) query.job = job
  router.push({ path: '/interview', query })
}

function difficultyVariant(d: string) {
  if (d === 'HARD') return 'danger'
  if (d === 'MEDIUM') return 'warning'
  return 'success'
}
function difficultyText(d: string) {
  if (d === 'HARD') return '困难'
  if (d === 'MEDIUM') return '中等'
  return '简单'
}
</script>

<style scoped>
.book-page { max-width: 860px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; }

.filter-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 14px 18px; margin-bottom: 18px; box-shadow: var(--shadow-sm); }
.filter-label { font-size: 13px; color: var(--c-text-secondary); }
.radio-row { display: flex; gap: 8px; }
.radio-chip { padding: 5px 12px; font-size: 12px; font-weight: 500; color: var(--c-text-tertiary); background: var(--c-bg-alt); border: 1px solid var(--c-border); border-radius: 999px; cursor: pointer; transition: all var(--transition-fast); }
.radio-chip:hover { border-color: var(--brand-primary); color: var(--brand-primary); }
.radio-chip.active { background: var(--brand-primary); border-color: var(--brand-primary); color: #fff; }
.filter-note { margin-left: auto; font-size: 12px; color: var(--c-text-tertiary); }

.list { display: flex; flex-direction: column; gap: 12px; }

.book-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 18px 20px; }
.book-head { display: flex; gap: 14px; align-items: flex-start; }
.book-index { flex-shrink: 0; width: 26px; height: 26px; background: var(--c-danger-light); color: var(--c-danger); border-radius: 50%; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.book-content { flex: 1; min-width: 0; }
.book-question { font-family: var(--font-serif); font-size: 15px; font-weight: 600; color: var(--c-text); line-height: 1.5; margin-bottom: 8px; }
.book-tags { display: flex; gap: 8px; flex-wrap: wrap; }
.book-meta { margin: 10px 0 0 40px; font-size: 12px; color: var(--c-text-tertiary); }
.book-body { margin: 12px 0 0 40px; display: flex; flex-direction: column; gap: 12px; border-top: 1px dashed var(--c-border); padding-top: 12px; }
.block-label { font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); margin-bottom: 4px; }
.block-text { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); white-space: pre-wrap; }
.block-text.ref { color: var(--c-info); }

.skeleton-card { padding: 18px 20px; }
.skeleton { background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; border-radius: var(--radius-sm); }
.skeleton-line { height: 14px; margin-bottom: 8px; }
.skeleton-line.w-70 { width: 70%; }
.skeleton-line.w-40 { width: 40%; }
.skeleton-line:last-child { margin-bottom: 0; }
@keyframes skeleton-loading { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

.empty-state { text-align: center; padding: 64px 24px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); }
.empty-icon { font-size: 56px; margin-bottom: 16px; }
.empty-title { font-size: 18px; font-weight: 600; color: var(--c-text); margin-bottom: 6px; }
.empty-desc { font-size: 14px; color: var(--c-text-tertiary); margin-bottom: 24px; }

@media (max-width: 640px) {
  .book-head { flex-wrap: wrap; }
  .book-meta, .book-body { margin-left: 0; }
}

.retry-btn { padding: 8px 20px; border: 1px solid var(--c-accent); background: transparent; color: var(--c-accent); border-radius: var(--radius-md); cursor: pointer; font-size: 14px; transition: all 0.2s; }
.retry-btn:hover { background: var(--c-accent-soft); }
</style>
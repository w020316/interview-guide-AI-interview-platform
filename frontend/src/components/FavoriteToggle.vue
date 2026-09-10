<template>
  <button
    type="button"
    class="fav-toggle"
    :class="{ 'is-active': favorited, 'is-loading': loading }"
    :aria-pressed="favorited"
    :aria-label="favorited ? '取消收藏' : '收藏该题'"
    :title="favorited ? '取消收藏' : '收藏该题，便于集中回看'"
    :disabled="loading || !questionId"
    @click="onToggle"
  >
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
      <path
        d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
        stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
      />
    </svg>
    <span v-if="showText" class="fav-text">{{ favorited ? '已收藏' : '收藏' }}</span>
  </button>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'

/**
 * 收藏按钮
 * - 依赖后端 /api/favorite 接口，按原题目 ID 持久化收藏快照
 * - 自动加载用户已收藏的题目 ID 集合并高亮状态
 */
const props = defineProps<{
  questionId?: number | null
  question?: string
  category?: string | null
  difficulty?: string | null
  referenceAnswer?: string | null
  userAnswer?: string | null
  evaluationScore?: number | null
  sessionId?: string | null
  showText?: boolean
}>()

const favorited = ref(false)
const loading = ref(false)

// 模块级共享缓存：同页 N 个实例挂载时只发 1 次请求（修复 N+1 问题）
// toggle 成功后置空，保证下次挂载拿到最新收藏集
let favIdsPromise: Promise<number[]> | null = null
function fetchFavIds(): Promise<number[]> {
  if (!favIdsPromise) {
    favIdsPromise = api.get('/api/favorite/ids') as unknown as Promise<number[]>
  }
  return favIdsPromise
}

async function loadState() {
  if (!props.questionId) return
  try {
    const ids = await fetchFavIds()
    favorited.value = Array.isArray(ids) && ids.includes(props.questionId)
  } catch {
    /* 静默：加载失败不阻塞列表展示 */
  }
}

async function onToggle() {
  if (!props.questionId || loading.value) return
  loading.value = true
  try {
    const res = (await api.post('/api/favorite/toggle', {
      questionId: props.questionId,
      sessionId: props.sessionId,
      question: props.question,
      category: props.category,
      difficulty: props.difficulty,
      referenceAnswer: props.referenceAnswer,
      userAnswer: props.userAnswer,
      evaluationScore: props.evaluationScore,
    })) as unknown as { favorited: boolean }
    favorited.value = res.favorited
    favIdsPromise = null
    ElMessage.success(res.favorited ? '已收藏' : '已取消收藏')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '收藏操作失败'))
  } finally {
    loading.value = false
  }
}

onMounted(loadState)
</script>

<style scoped>
.fav-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-tertiary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}
.fav-toggle:hover {
  color: var(--c-accent);
  border-color: var(--c-accent-line);
  background: var(--c-accent-soft);
}
.fav-toggle.is-active {
  color: var(--c-accent);
  border-color: var(--c-accent-line);
  background: var(--c-accent-soft);
}
.fav-toggle.is-active svg { fill: var(--c-accent); }
.fav-toggle:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
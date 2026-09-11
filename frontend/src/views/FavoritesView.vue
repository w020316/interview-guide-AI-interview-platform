<template>
  <div class="favorites-page">
    <header class="page-header">
      <h1>收藏夹</h1>
      <p>集中回看收藏的重点题目与薄弱点</p>
    </header>

    <!-- 工具栏：从收藏发起面试 / 手动加题 -->
    <div class="toolbar fade-in-up">
      <div class="toolbar-left">
        <BaseButton variant="gradient" :disabled="!items.length || starting" @click="startInterview">
          {{ starting ? '创建中…' : '从收藏发起面试' }}
        </BaseButton>
        <span v-if="items.length" class="toolbar-hint">将使用全部 {{ items.length }} 道收藏题直接进入模拟面试</span>
      </div>
      <BaseButton variant="ghost" @click="manualOpen = !manualOpen">
        {{ manualOpen ? '收起加题' : '＋ 手动加题' }}
      </BaseButton>
    </div>

    <!-- 手动加题表单 -->
    <div v-if="manualOpen" class="manual-card fade-in-up">
      <input v-model="manualForm.question" class="input" placeholder="题目内容（必填）" />
      <div class="manual-row">
        <input v-model="manualForm.category" class="input" placeholder="分类（如：Java 基础 / 项目深挖）" />
        <select v-model="manualForm.difficulty" class="input">
          <option value="">难度不限</option>
          <option value="EASY">简单</option>
          <option value="MEDIUM">中等</option>
          <option value="HARD">困难</option>
        </select>
      </div>
      <textarea v-model="manualForm.referenceAnswer" class="input ta" rows="3" placeholder="参考答案（可选）"></textarea>
      <div class="manual-actions">
        <BaseButton variant="gradient" :disabled="manualSaving || !manualForm.question.trim()" @click="manualAdd">
          {{ manualSaving ? '保存中…' : '保存到题库' }}
        </BaseButton>
      </div>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" class="list">
      <div v-for="i in 3" :key="i" class="fav-card skeleton-card">
        <div class="skeleton skeleton-line w-60"></div>
        <div class="skeleton skeleton-line w-40"></div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="loadError" class="empty-state fade-in">
      <div class="empty-icon">⚠️</div>
      <div class="empty-title">加载失败</div>
      <div class="empty-desc">收藏列表加载失败，请检查网络后重试</div>
      <button class="retry-btn" @click="load">重新加载</button>
    </div>
    <div v-else-if="!items.length" class="empty-state fade-in">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
          <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
            stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="empty-title">还没有收藏</div>
      <div class="empty-desc">在历史记录或模拟面试中收藏题目，就会沉淀到这里</div>
      <BaseButton variant="gradient" @click="$router.push('/history')">前往历史记录</BaseButton>
    </div>

    <!-- 收藏列表 -->
    <div v-else class="list">
      <div class="list-meta">
        共 {{ items.length }} 道收藏 · 点击卡片展开参考答案
      </div>
      <div v-for="f in items" :key="f.id" class="fav-card fade-in-up">
        <div class="fav-head" role="button" tabindex="0" @click="toggleOpen(f.id)" @keydown.enter="toggleOpen(f.id)">
          <div class="fav-title">{{ f.question }}</div>
          <div class="fav-tags">
            <BaseTag v-if="f.category" variant="info" size="sm">{{ f.category }}</BaseTag>
            <BaseTag v-if="f.difficulty" :variant="difficultyVariant(f.difficulty)" size="sm">{{ difficultyText(f.difficulty) }}</BaseTag>
            <BaseTag v-if="f.evaluationScore != null" variant="warning" size="sm">得分 {{ f.evaluationScore }}</BaseTag>
          </div>
        </div>
        <div v-if="openIds.has(f.id)" class="fav-body">
          <div v-if="f.userAnswer" class="block">
            <div class="block-label">我的回答</div>
            <div class="block-text">{{ f.userAnswer }}</div>
          </div>
          <div v-if="f.referenceAnswer" class="block">
            <div class="block-label">参考答案</div>
            <div class="block-text ref">{{ f.referenceAnswer }}</div>
          </div>
          <div v-else-if="!f.userAnswer" class="block-empty">该题未保存回答与参考答案</div>
        </div>
        <div class="fav-foot">
          <span class="fav-date">{{ fmtDate(f.createdAt) }}</span>
          <button class="remove-btn" @click="remove(f)">移除收藏</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag } from '../components'

const router = useRouter()

interface FavoriteItem {
  id: number
  sessionId?: string
  questionId?: number | null
  question: string
  category?: string | null
  difficulty?: string | null
  referenceAnswer?: string | null
  userAnswer?: string | null
  evaluationScore?: number | null
  createdAt?: string
}

const items = ref<FavoriteItem[]>([])
const openIds = ref<Set<number>>(new Set())
const loadError = ref(false)
const loading = ref(true)

// ── 从收藏发起面试 + 手动加题（v1.28.0 定制题库）──
const starting = ref(false)
const manualOpen = ref(false)
const manualSaving = ref(false)
const manualForm = reactive({ question: '', category: '', difficulty: '', referenceAnswer: '' })

onMounted(load)

async function load() {
  try {
    const data = (await api.get('/api/favorite/list')) as unknown as { items?: FavoriteItem[] }
    items.value = data?.items || []
  } catch (e: unknown) {
    loadError.value = true
    ElMessage.error(getErrMessage(e, '加载收藏失败'))
  } finally { loading.value = false }
}

/** 从全部收藏发起模拟面试：创建会话、载入题目，跳转面试页答题 */
async function startInterview() {
  if (!items.value.length) return ElMessage.warning('暂无收藏题目')
  starting.value = true
  try {
    const ids = items.value.map((f) => f.id)
    const res = (await api.post('/api/favorite/bank/start', { favoriteIds: ids })) as unknown as { sessionId: string }
    if (!res?.sessionId) throw new Error('未返回会话')
    ElMessage.success('已创建面试，进入答题')
    router.push({ path: '/interview', query: { sessionId: res.sessionId } })
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '创建面试失败'))
  } finally {
    starting.value = false
  }
}

/** 手动添加自定义题目到题库（questionId 为空即新增） */
async function manualAdd() {
  const q = manualForm.question.trim()
  if (!q) return ElMessage.warning('请填写题目内容')
  manualSaving.value = true
  try {
    await api.post('/api/favorite/toggle', {
      question: q,
      category: manualForm.category.trim() || null,
      difficulty: manualForm.difficulty || null,
      referenceAnswer: manualForm.referenceAnswer.trim() || null,
    })
    ElMessage.success('已加入自定义题库')
    manualForm.question = ''
    manualForm.category = ''
    manualForm.difficulty = ''
    manualForm.referenceAnswer = ''
    manualOpen.value = false
    load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '保存失败'))
  } finally {
    manualSaving.value = false
  }
}

async function remove(f: FavoriteItem) {
  try {
    await api.post('/api/favorite/toggle', { favoriteId: f.id, questionId: f.questionId })
    items.value = items.value.filter((x) => x.id !== f.id)
    openIds.value.delete(f.id)
    ElMessage.success('已移除收藏')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '移除失败'))
  }
}

function toggleOpen(id: number) {
  const next = new Set(openIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  openIds.value = next
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
function fmtDate(dt?: string) {
  if (!dt) return '-'
  const d = new Date(dt)
  return isNaN(d.getTime()) ? '-' : d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.favorites-page { max-width: 860px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; }

.list { display: flex; flex-direction: column; gap: 12px; }
.list-meta { font-size: 13px; color: var(--c-text-tertiary); }

.fav-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); overflow: hidden; }
.fav-head { padding: 16px 20px; cursor: pointer; transition: background var(--transition-fast); }
.fav-head:hover { background: var(--c-bg-alt); }
.fav-head:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: -2px; }
.fav-title { font-family: var(--font-serif); font-size: 15px; font-weight: 600; color: var(--c-text); margin-bottom: 8px; line-height: 1.5; }
.fav-tags { display: flex; gap: 8px; flex-wrap: wrap; }

.fav-body { padding: 0 20px 14px; border-top: 1px dashed var(--c-border); padding-top: 14px; display: flex; flex-direction: column; gap: 12px; }
.block-label { font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); margin-bottom: 4px; }
.block-text { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); white-space: pre-wrap; }
.block-text.ref { color: var(--c-info); }
.block-empty { font-size: 13px; color: var(--c-text-tertiary); font-style: italic; }

.fav-foot { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; border-top: 1px solid var(--c-border-light); }
.fav-date { font-size: 12px; color: var(--c-text-quaternary); }
.remove-btn { font-size: 12px; font-weight: 500; color: var(--c-text-tertiary); background: transparent; border: none; cursor: pointer; transition: color var(--transition-fast); }
.remove-btn:hover { color: var(--c-danger); }

.skeleton { background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; border-radius: var(--radius-sm); }
.skeleton-line { height: 14px; margin-bottom: 8px; }
.skeleton-line.w-60 { width: 60%; }
.skeleton-line.w-40 { width: 40%; }
.skeleton-line:last-child { margin-bottom: 0; }
.skeleton-card { padding: 20px; }
@keyframes skeleton-loading { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

.empty-state { text-align: center; padding: 64px 24px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); }
.empty-icon { font-size: 56px; margin-bottom: 16px; opacity: 0.5; }
.empty-title { font-size: 18px; font-weight: 600; color: var(--c-text); margin-bottom: 6px; }
.empty-desc { font-size: 14px; color: var(--c-text-tertiary); margin-bottom: 24px; }

.retry-btn { padding: 8px 20px; border: 1px solid var(--c-accent); background: transparent; color: var(--c-accent); border-radius: var(--radius-md); cursor: pointer; font-size: 14px; transition: all 0.2s; }
.retry-btn:hover { background: var(--c-accent-soft); }

.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.toolbar-hint { font-size: 12px; color: var(--c-text-tertiary); }

.manual-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 18px 20px; margin-bottom: 16px; display: flex; flex-direction: column; gap: 12px; }
.input { width: 100%; padding: 9px 12px; font-size: 14px; color: var(--c-text); background: var(--c-bg); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); transition: border-color var(--transition-fast); box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--brand-primary); }
.input.ta { resize: vertical; min-height: 72px; font-family: inherit; line-height: 1.6; }
.manual-row { display: flex; gap: 12px; }
.manual-row .input { flex: 1; }
.manual-actions { display: flex; justify-content: flex-end; }
select.input { appearance: auto; background: var(--c-bg); }
</style>
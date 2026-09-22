<template>
  <div class="app-page">
    <header class="page-header">
      <h1>投递看板</h1>
      <p>本地投递台账：人工确认投递、跟踪回复、针对岗位定制简历。平台不代替你投递，只帮你把「投了什么、谁回了、该催谁」管清楚。</p>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="card skeleton-card">
      <div class="skeleton skeleton-line w-60"></div>
      <div class="skeleton skeleton-line w-40"></div>
    </div>

    <!-- 统计 -->
    <section v-else-if="total > 0" class="stats">
      <div class="stat-card">
        <div class="stat-num">{{ total }}</div>
        <div class="stat-label">投递总数</div>
      </div>
      <div v-for="s in statusOrder" :key="s.key" class="stat-card">
        <div class="stat-num">{{ counts[s.key] || 0 }}</div>
        <div class="stat-label">{{ s.label }}</div>
      </div>
    </section>

    <!-- 转化漏斗 + 待跟进 -->
    <section v-if="!loading && total > 0" class="card">
      <div class="funnel">
        <span class="funnel-item">已投 <b>{{ funnel.submitted || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item">有回复 <b>{{ funnel.repliedOrBeyond || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item">面试 <b>{{ funnel.interviewOrBeyond || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item accent">Offer <b>{{ funnel.offer || 0 }}</b></span>
      </div>
      <div v-if="followUps.length" class="followup">
        <div class="followup-title">⚠️ {{ followUps.length }} 条需要跟进（超过 7 天无回复，或已到跟进时间）</div>
        <div v-for="f in followUps" :key="f.id" class="followup-row">
          <span class="fu-title">{{ f.title }}</span>
          <span class="fu-company">{{ f.companyName }}</span>
          <span class="fu-status">{{ statusLabel(f.status) }}</span>
          <a v-if="f.applyUrl" :href="f.applyUrl" target="_blank" rel="noopener" class="fu-link">去催一下 →</a>
        </div>
      </div>
    </section>

    <!-- 空状态 -->
    <div v-if="!loading && total === 0" class="empty-state">
      <div class="empty-icon">📮</div>
      <div class="empty-title">还没有投递记录</div>
      <div class="empty-desc">在「招聘广场」或岗位收藏里点「加入投递计划」，就能在这里跟踪进度与回复</div>
      <BaseButton variant="gradient" @click="$router.push('/jobs')">前往招聘广场</BaseButton>
    </div>

    <!-- 投递列表 -->
    <section v-if="!loading && total > 0" class="list">
      <div v-for="a in items" :key="a.id" class="app-card">
        <div class="app-main">
          <div class="app-title-row">
            <span class="app-title">{{ a.title }}</span>
            <BaseTag :variant="statusVariant(a.status)" size="sm">{{ statusLabel(a.status) }}</BaseTag>
          </div>
          <div class="app-meta">
            <span>{{ a.companyName }}</span>
            <span v-if="a.location">{{ a.location }}</span>
            <span v-if="a.salary">{{ a.salary }}</span>
            <span v-if="a.deadline">截止 {{ a.deadline }}</span>
          </div>
          <div v-if="a.note" class="app-note">备注：{{ a.note }}</div>
        </div>

        <div class="app-actions">
          <a v-if="a.applyUrl" :href="a.applyUrl" target="_blank" rel="noopener" class="mini-btn">前往投递</a>
          <button
            v-if="a.status === 'PLANNED'"
            class="mini-btn primary"
            :disabled="busyId === a.id"
            @click="confirmApply(a)"
          >确认已投递</button>

          <select
            class="mini-select"
            :value="a.status"
            :disabled="busyId === a.id"
            @change="onStatusChange(a, $event)"
          >
            <option v-for="s in statusOrder" :key="s.key" :value="s.key">{{ s.label }}</option>
          </select>

          <button class="mini-btn" :disabled="busyId === a.id" @click="openTailor(a)">定制简历</button>
          <button class="mini-btn danger" :disabled="busyId === a.id" @click="remove(a)">移除</button>
        </div>

        <div v-if="a.tailoredResume" class="tailored-hint">
          已生成定制简历 ·
          <button class="link-btn" @click="showTailored(a)">查看</button>
        </div>
      </div>
    </section>

    <!-- 定制简历弹窗 -->
    <div v-if="tailorTarget" class="modal-mask" @click.self="tailorTarget = null">
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">定制简历 · {{ tailorTarget.title }}</div>
          <button class="modal-close" @click="tailorTarget = null">✕</button>
        </div>

        <template v-if="!tailorResult">
          <div class="modal-sub">粘贴你的简历要点，AI 会针对该岗位重写条目、对齐 JD 关键词，并列出你还缺什么。</div>
          <textarea v-model="tailorResume" class="input ta" rows="6" placeholder="例：熟悉 Java/Spring Boot，做过订单中心重构，用 Redis 做缓存，QPS 峰值 3000……"></textarea>
          <input v-model="tailorDetail" class="input" placeholder="补充岗位 JD（可选）" />
          <div class="modal-actions">
            <BaseButton variant="gradient" :disabled="tailoring || !tailorResume.trim()" @click="doTailor">
              {{ tailoring ? '正在生成…' : '生成定制简历要点' }}
            </BaseButton>
          </div>
        </template>

        <template v-else>
          <div v-if="tailorResult.matchedKeywords?.length" class="mini-block">
            <div class="label">已命中关键词</div>
            <div class="tags"><BaseTag v-for="k in tailorResult.matchedKeywords" :key="k" variant="success" size="sm">{{ k }}</BaseTag></div>
          </div>
          <div v-if="tailorResult.missingKeywords?.length" class="mini-block">
            <div class="label">待补关键词（不要写进简历，先补能力）</div>
            <div class="tags"><BaseTag v-for="k in tailorResult.missingKeywords" :key="k" variant="danger" size="sm">{{ k }}</BaseTag></div>
          </div>
          <div v-if="tailorResult.rewrittenBullets?.length" class="mini-block">
            <div class="label">条目重写</div>
            <div v-for="(b, i) in tailorResult.rewrittenBullets" :key="i" class="bullet">
              <div class="bullet-orig">原：{{ b.original }}</div>
              <div class="bullet-new">改：{{ b.rewritten }}</div>
              <div v-if="b.reason" class="bullet-reason">{{ b.reason }}</div>
            </div>
          </div>
          <div v-if="tailorResult.summary" class="mini-block">
            <div class="label">定位陈述（可放简历开头）</div>
            <div class="summary-box">{{ tailorResult.summary }}</div>
          </div>
          <div v-if="tailorResult.suggestions?.length" class="mini-block">
            <div class="label">优化建议</div>
            <ul class="plain-list"><li v-for="s in tailorResult.suggestions" :key="s">{{ s }}</li></ul>
          </div>
          <div class="modal-actions">
            <BaseButton variant="ghost" @click="tailorResult = null">重新生成</BaseButton>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag } from '../components'

interface Application {
  id: number
  jobId: number
  title: string
  companyName: string
  location?: string | null
  salary?: string | null
  deadline?: string | null
  applyUrl?: string | null
  status: string
  note?: string | null
  tailoredResume?: string | null
  appliedAt?: string | null
  lastReplyAt?: string | null
  updatedAt?: string | null
}

interface TailoredResult {
  matchedKeywords?: string[]
  missingKeywords?: string[]
  rewrittenBullets?: { original?: string; rewritten?: string; reason?: string }[]
  summary?: string
  suggestions?: string[]
  raw?: string
}

const statusOrder = [
  { key: 'PLANNED', label: '待投递' },
  { key: 'APPLIED', label: '已投递' },
  { key: 'VIEWED', label: '已查看' },
  { key: 'REPLIED', label: '有回复' },
  { key: 'INTERVIEW', label: '面试中' },
  { key: 'OFFER', label: '已拿 Offer' },
  { key: 'REJECTED', label: '已淘汰' },
  { key: 'WITHDRAWN', label: '已放弃' },
]

const loading = ref(true)
const items = ref<Application[]>([])
const counts = ref<Record<string, number>>({})
const funnel = ref<Record<string, number>>({})
const followUps = ref<Application[]>([])
const busyId = ref<number | null>(null)

const tailorTarget = ref<Application | null>(null)
const tailorResume = ref('')
const tailorDetail = ref('')
const tailoring = ref(false)
const tailorResult = ref<TailoredResult | null>(null)

const total = computed(() => items.value.length)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const listData = (await api.get('/api/application/list')) as unknown as { items?: Application[] }
    items.value = listData?.items || []
    const board = (await api.get('/api/application/board')) as unknown as {
      counts?: Record<string, number>
      funnel?: Record<string, number>
      followUps?: Application[]
    }
    counts.value = board?.counts || {}
    funnel.value = board?.funnel || {}
    followUps.value = board?.followUps || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载投递台账失败'))
  } finally {
    loading.value = false
  }
}

/** 确认已投递：PLANNED → APPLIED（语义为用户已自行完成投递） */
async function confirmApply(a: Application) {
  busyId.value = a.id
  try {
    await api.post(`/api/application/${a.id}/confirm`)
    ElMessage.success('已标记为已投递')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '操作失败'))
  } finally {
    busyId.value = null
  }
}

/** 推进状态（回复监测） */
async function onStatusChange(a: Application, ev: Event) {
  const target = (ev.target as HTMLSelectElement).value
  if (target === a.status) return
  busyId.value = a.id
  try {
    await api.post(`/api/application/${a.id}/status`, { status: target })
    ElMessage.success(`已更新为「${statusLabel(target)}」`)
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '更新状态失败'))
    ;(ev.target as HTMLSelectElement).value = a.status
  } finally {
    busyId.value = null
  }
}

async function remove(a: Application) {
  busyId.value = a.id
  try {
    await api.delete(`/api/application/${a.id}`)
    items.value = items.value.filter((x) => x.id !== a.id)
    ElMessage.success('已移除')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '移除失败'))
  } finally {
    busyId.value = null
  }
}

function openTailor(a: Application) {
  tailorTarget.value = a
  tailorResult.value = null
  tailorResume.value = ''
  tailorDetail.value = ''
}

function showTailored(a: Application) {
  tailorTarget.value = a
  tailorResume.value = ''
  tailorDetail.value = ''
  try {
    tailorResult.value = a.tailoredResume ? (JSON.parse(a.tailoredResume) as TailoredResult) : null
  } catch {
    tailorResult.value = { raw: a.tailoredResume || '' }
  }
}

async function doTailor() {
  const target = tailorTarget.value
  if (!target) return
  const r = tailorResume.value.trim()
  if (!r) return ElMessage.warning('请先粘贴简历要点')
  tailoring.value = true
  try {
    const res = (await api.post(`/api/application/${target.id}/tailor`, {
      resumeText: r,
      jobDetail: tailorDetail.value.trim(),
    })) as unknown as { tailoredResume?: TailoredResult }
    tailorResult.value = res?.tailoredResume || null
    ElMessage.success('定制简历已生成')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成失败，请稍后重试'))
  } finally {
    tailoring.value = false
  }
}

function statusLabel(key?: string) {
  return statusOrder.find((s) => s.key === key)?.label || key || '-'
}

function statusVariant(key?: string) {
  switch (key) {
    case 'OFFER': return 'success'
    case 'INTERVIEW': return 'warning'
    case 'REPLIED': return 'info'
    case 'REJECTED': return 'danger'
    case 'WITHDRAWN': return 'info'
    default: return 'info'
  }
}
</script>

<style scoped>
.app-page { max-width: 960px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; line-height: 1.7; }

.card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 18px 20px; margin-bottom: 16px; }

.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(104px, 1fr)); gap: 10px; margin-bottom: 16px; }
.stat-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 14px 12px; text-align: center; }
.stat-num { font-family: var(--font-serif); font-size: 24px; font-weight: 700; color: var(--brand-primary); line-height: 1.2; }
.stat-label { font-size: 12px; color: var(--c-text-tertiary); margin-top: 4px; }

.funnel { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; color: var(--c-text-secondary); }
.funnel-item b { color: var(--c-text); font-size: 15px; }
.funnel-item.accent b { color: var(--c-accent); }
.funnel-arrow { color: var(--c-text-quaternary); }

.followup { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--c-border); }
.followup-title { font-size: 13px; font-weight: 600; color: var(--c-warning); margin-bottom: 8px; }
.followup-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 6px 0; font-size: 13px; }
.fu-title { font-weight: 500; color: var(--c-text); }
.fu-company { color: var(--c-text-tertiary); }
.fu-status { font-size: 12px; color: var(--c-warning); background: var(--c-warning-light); border-radius: var(--radius-sm); padding: 1px 6px; }
.fu-link { color: var(--brand-primary); text-decoration: none; font-size: 12px; }
.fu-link:hover { text-decoration: underline; }

.list { display: flex; flex-direction: column; gap: 12px; }
.app-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 16px 18px; }
.app-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.app-title { font-family: var(--font-serif); font-size: 16px; font-weight: 600; color: var(--c-text); }
.app-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 13px; color: var(--c-text-tertiary); }
.app-note { margin-top: 8px; font-size: 13px; color: var(--c-text-secondary); line-height: 1.6; }
.app-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--c-border-light); }

.mini-btn { padding: 6px 12px; font-size: 13px; border: 1px solid var(--c-border-strong); background: var(--c-surface); color: var(--c-text-secondary); border-radius: var(--radius-md); cursor: pointer; text-decoration: none; transition: all var(--transition-fast); }
.mini-btn:hover:not(:disabled) { border-color: var(--brand-primary); color: var(--brand-primary); }
.mini-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.mini-btn.primary { background: var(--brand-primary); border-color: var(--brand-primary); color: #fff; }
.mini-btn.primary:hover:not(:disabled) { background: var(--brand-primary-hover); color: #fff; }
.mini-btn.danger:hover:not(:disabled) { border-color: var(--c-danger); color: var(--c-danger); }
.mini-select { padding: 6px 8px; font-size: 13px; border: 1px solid var(--c-border-strong); border-radius: var(--radius-md); background: var(--c-surface); color: var(--c-text-secondary); cursor: pointer; }

.tailored-hint { margin-top: 10px; font-size: 12px; color: var(--c-text-tertiary); }
.link-btn { background: transparent; border: none; color: var(--brand-primary); font-size: 12px; cursor: pointer; padding: 0; }
.link-btn:hover { text-decoration: underline; }

.empty-state { text-align: center; padding: 64px 24px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); }
.empty-icon { font-size: 56px; margin-bottom: 16px; opacity: 0.6; }
.empty-title { font-size: 18px; font-weight: 600; color: var(--c-text); margin-bottom: 6px; }
.empty-desc { font-size: 14px; color: var(--c-text-tertiary); margin-bottom: 24px; }

.skeleton { background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; border-radius: var(--radius-sm); }
.skeleton-line { height: 14px; margin-bottom: 8px; }
.skeleton-line.w-60 { width: 60%; }
.skeleton-line.w-40 { width: 40%; }
@keyframes skeleton-loading { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

.modal-mask { position: fixed; inset: 0; background: rgba(28, 25, 23, 0.45); display: flex; align-items: center; justify-content: center; padding: 24px; z-index: 2000; }
.modal { background: var(--c-surface); border-radius: var(--radius-lg); box-shadow: var(--shadow-lg); width: 100%; max-width: 680px; max-height: 86vh; overflow-y: auto; padding: 20px 22px; }
.modal-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.modal-title { font-family: var(--font-serif); font-size: 17px; font-weight: 700; color: var(--c-text); }
.modal-close { background: transparent; border: none; font-size: 16px; color: var(--c-text-tertiary); cursor: pointer; }
.modal-sub { font-size: 13px; color: var(--c-text-tertiary); line-height: 1.6; margin-bottom: 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; }

.input { width: 100%; padding: 9px 12px; font-size: 14px; color: var(--c-text); background: var(--c-bg); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); box-sizing: border-box; margin-bottom: 10px; }
.input:focus { outline: none; border-color: var(--brand-primary); }
.input.ta { resize: vertical; min-height: 72px; font-family: inherit; line-height: 1.7; }

.label { font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); margin-bottom: 8px; }
.mini-block { margin-bottom: 16px; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.plain-list { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; color: var(--c-text-secondary); }
.bullet { border-left: 3px solid var(--brand-primary-200); padding: 6px 0 6px 10px; margin-bottom: 10px; font-size: 13px; line-height: 1.7; }
.bullet-orig { color: var(--c-text-tertiary); text-decoration: line-through; }
.bullet-new { color: var(--c-text); font-weight: 500; }
.bullet-reason { color: var(--c-accent); font-size: 12px; margin-top: 4px; }
.summary-box { background: var(--brand-primary-light); border-radius: var(--radius-md); padding: 12px 14px; font-size: 13px; line-height: 1.8; color: var(--c-text-secondary); }
</style>

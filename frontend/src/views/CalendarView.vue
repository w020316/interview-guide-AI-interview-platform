<template>
  <div class="calendar-page">
    <header class="page-header">
      <h1>面试日历</h1>
      <p>规划每一场面试与准备节点，掌控求职节奏</p>
    </header>

    <!-- 顶部统计 + 新增 -->
    <section class="cal-toolbar fade-in-up">
      <div class="stats-row">
        <div class="mini-stat">
          <span class="mini-num num-display">{{ eventCount }}</span>
          <span class="mini-label">总日程</span>
        </div>
        <div class="mini-stat">
          <span class="mini-num up num-display">{{ upcomingCount }}</span>
          <span class="mini-label">待面试</span>
        </div>
        <div class="mini-stat">
          <span class="mini-num done num-display">{{ doneCount }}</span>
          <span class="mini-label">已完成</span>
        </div>
      </div>
      <BaseButton variant="gradient" size="md" @click="openCreate">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        新增日程
      </BaseButton>
    </section>

    <!-- 月历主体 -->
    <section class="cal-card fade-in-up">
      <div class="cal-head">
        <button class="cal-nav" aria-label="上一月" @click="shiftMonth(-1)">‹</button>
        <span class="cal-title">{{ monthTitle }}</span>
        <button class="cal-nav" aria-label="下一月" @click="shiftMonth(1)">›</button>
        <button class="cal-today" @click="goToday">今天</button>
      </div>

      <div class="week-row">
        <div v-for="(w, i) in WEEKDAY_LABELS" :key="i" class="week-label" :class="{ 'is-weekend': i >= 5 }">{{ w }}</div>
      </div>

      <div class="day-grid">
        <button
          v-for="cell in days"
          :key="cell.date"
          class="day-cell"
          :class="{
            'is-out': !cell.inMonth,
            'is-today': cell.isToday,
            'is-selected': selectedDate === cell.date,
            'has-event': dayEvents(cell.date).length > 0,
          }"
          @click="selectDate(cell.date)"
        >
          <span class="day-num" :class="{ 'num-display': cell.inMonth }">{{ cell.date.slice(8) }}</span>
          <div class="day-events">
            <div
              v-for="e in dayEvents(cell.date).slice(0, 2)"
              :key="e.id"
              class="mini-event"
              :class="`ev-${statusText(e.status)}`"
              :title="e.title"
            >{{ e.title }}</div>
            <div v-if="dayEvents(cell.date).length > 2" class="mini-more">+{{ dayEvents(cell.date).length - 2 }}</div>
          </div>
        </button>
      </div>
    </section>

    <!-- 选中日期的日程明细 -->
    <section v-if="selectedEvents.length" class="day-detail fade-in-up">
      <div class="detail-head">
        <h2>{{ selectedDate }} 的日程</h2>
        <BaseButton variant="ghost" size="sm" @click="openCreate">＋ 添加</BaseButton>
      </div>
      <div class="event-list">
        <div v-for="e in selectedEvents" :key="e.id" class="event-row">
          <span class="event-dot" :class="`dot-${statusText(e.status)}`"></span>
          <div class="event-main">
            <div class="event-title-row">
              <span class="event-title">{{ e.title }}</span>
              <BaseTag :variant="statusVariant(e.status)" size="sm">{{ statusText(e.status) }}</BaseTag>
            </div>
            <div class="event-time num-display">{{ fmtDateTime(e.interviewAt) }}</div>
            <div v-if="e.location || e.interviewer" class="event-meta">
              <span v-if="e.location" class="meta-item">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                {{ e.location }}
              </span>
              <span v-if="e.interviewer" class="meta-item">· {{ e.interviewer }}</span>
            </div>
            <div v-if="e.note" class="event-note">{{ e.note }}</div>
          </div>
          <div class="event-actions">
            <button class="icon-btn" aria-label="标记完成" title="标记完成" @click="setStatus(e, 'DONE')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M20 6L9 17l-5-5" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button class="icon-btn" aria-label="编辑" title="编辑" @click="openEdit(e)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M17 3a2.83 2.83 0 0 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button class="icon-btn danger" aria-label="删除" title="删除" @click="remove(e)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </section>
    <section v-else class="day-detail empty fade-in-up">
      <div class="empty-inline">
        <p>{{ selectedDate }} 暂无安排</p>
        <BaseButton variant="primary" size="sm" @click="openCreate">安排一场面试</BaseButton>
      </div>
    </section>

    <!-- 新增/编辑弹窗 -->
    <div v-if="showDialog" class="modal-mask" @click.self="closeDialog">
      <div class="modal">
        <h3 class="modal-title">{{ editingId ? '编辑日程' : '新增日程' }}</h3>
        <form class="event-form" @submit.prevent="submit">
          <label class="field">
            <span class="field-label">面试标题 <em>*</em></span>
            <input v-model="form.title" class="field-input" placeholder="如：字节跳动 · 产品经理一面" required maxlength="60" />
          </label>
          <label class="field">
            <span class="field-label">面试时间 <em>*</em></span>
            <input v-model="form.interviewAt" type="datetime-local" class="field-input" required />
          </label>
          <div class="field-row">
            <label class="field">
              <span class="field-label">面试地点</span>
              <input v-model="form.location" class="field-input" placeholder="视频面试 / 公司地址" maxlength="60" />
            </label>
            <label class="field">
              <span class="field-label">面试官</span>
              <input v-model="form.interviewer" class="field-input" placeholder="如：HR 李女士" maxlength="30" />
            </label>
          </div>
          <label class="field">
            <span class="field-label">状态</span>
            <select v-model="form.status" class="field-input">
              <option value="UPCOMING">待面试</option>
              <option value="DONE">已完成</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </label>
          <label class="field">
            <span class="field-label">备注</span>
            <textarea v-model="form.note" class="field-input field-textarea" placeholder="准备要点、携带资料、注意事项…" rows="3" maxlength="300"></textarea>
          </label>
          <div class="modal-actions">
            <BaseButton variant="ghost" size="md" type="button" @click="closeDialog">取消</BaseButton>
            <BaseButton variant="primary" size="md" type="submit" :loading="submitting">保存</BaseButton>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag } from '../components'
import {
  WEEKDAY_LABELS, fmtDateTime, isSameDate, monthMatrix,
  statusText, statusVariant, toDatetimeLocal, toDateString,
} from '../utils/calendar'

interface CalendarEvent {
  id: number
  title: string
  interviewer?: string | null
  location?: string | null
  note?: string | null
  interviewAt: string
  status: string
}

const now = new Date()
const viewYear = ref(now.getFullYear())
const viewMonth = ref(now.getMonth())
const selectedDate = ref(toDateString(now))
const events = ref<CalendarEvent[]>([])

const showDialog = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ title: '', interviewAt: '', location: '', interviewer: '', status: 'UPCOMING', note: '' })

const days = computed(() => monthMatrix(viewYear.value, viewMonth.value))
const monthTitle = computed(() => `${viewYear.value} 年 ${viewMonth.value + 1} 月`)

const eventCount = computed(() => events.value.length)
const upcomingCount = computed(() => events.value.filter((e) => e.status === 'UPCOMING').length)
const doneCount = computed(() => events.value.filter((e) => e.status === 'DONE').length)
const selectedEvents = computed(() =>
  events.value.filter((e) => isSameDate(e.interviewAt.slice(0, 10), selectedDate.value))
    .sort((a, b) => a.interviewAt.localeCompare(b.interviewAt)),
)

function dayEvents(date: string): CalendarEvent[] {
  return events.value.filter((e) => isSameDate(e.interviewAt.slice(0, 10), date))
}

function shiftMonth(n: number) {
  const d = new Date(viewYear.value, viewMonth.value + n, 1)
  viewYear.value = d.getFullYear()
  viewMonth.value = d.getMonth()
}

function goToday() {
  const t = new Date()
  viewYear.value = t.getFullYear()
  viewMonth.value = t.getMonth()
  selectedDate.value = toDateString(t)
}

function selectDate(date: string) {
  selectedDate.value = date
}

async function load() {
  try {
    itemsReload()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载日程失败'))
  }
}
async function itemsReload() {
  const data = (await api.get('/api/calendar/event/list')) as unknown as CalendarEvent[]
  events.value = data || []
}

function openCreate() {
  editingId.value = null
  form.title = ''
  form.interviewAt = toDatetimeLocal(new Date(`${selectedDate.value}T09:00`))
  form.location = ''
  form.interviewer = ''
  form.status = 'UPCOMING'
  form.note = ''
  showDialog.value = true
}

function openEdit(e: CalendarEvent) {
  editingId.value = e.id
  form.title = e.title
  form.interviewAt = e.interviewAt.slice(0, 16)
  form.location = e.location || ''
  form.interviewer = e.interviewer || ''
  form.status = e.status || 'UPCOMING'
  form.note = e.note || ''
  showDialog.value = true
}

function closeDialog() {
  if (submitting.value) return
  showDialog.value = false
}

async function submit() {
  if (!form.title.trim() || !form.interviewAt) return
  submitting.value = true
  try {
    const payload = {
      title: form.title.trim(),
      interviewAt: form.interviewAt,
      location: form.location.trim(),
      interviewer: form.interviewer.trim(),
      status: form.status,
      note: form.note.trim(),
    }
    if (editingId.value) {
      await api.put(`/api/calendar/event/${editingId.value}`, payload)
      ElMessage.success('日程已更新')
    } else {
      await api.post('/api/calendar/event', payload)
      ElMessage.success('日程已添加')
    }
    showDialog.value = false
    await itemsReload()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '保存失败'))
  } finally {
    submitting.value = false
  }
}

function setStatus(e: CalendarEvent, status: string) {
  api.put(`/api/calendar/event/${e.id}`, { status })
    .then(async () => {
      ElMessage.success('状态已更新')
      await itemsReload()
    })
    .catch((err: unknown) => ElMessage.error(getErrMessage(err, '更新失败')))
}

function remove(e: CalendarEvent) {
  ElMessageBox.confirm(`确定删除「${e.title}」吗？此操作不可恢复。`, '删除日程', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  }).then(async () => {
    await api.delete(`/api/calendar/event/${e.id}`)
    ElMessage.success('已删除')
    await itemsReload()
  }).catch(() => { /* 取消 */ })
}

onMounted(load)
</script>

<style scoped>
.calendar-page { max-width: 980px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; }

/* 工具行 */
.cal-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 16px 20px; margin-bottom: 18px; box-shadow: var(--shadow-sm); }
.stats-row { display: flex; gap: 28px; }
.mini-stat { display: flex; flex-direction: column; }
.mini-num { font-size: 24px; font-weight: 700; color: var(--c-text); line-height: 1.1; }
.mini-num.up { color: var(--c-info); }
.mini-num.done { color: var(--c-success); }
.mini-label { font-size: 12px; color: var(--c-text-tertiary); margin-top: 2px; }

/* 月历卡片 */
.cal-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 18px 20px 20px; box-shadow: var(--shadow-sm); }
.cal-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.cal-title { font-family: var(--font-serif); font-size: 18px; font-weight: 600; color: var(--c-text); flex: 1; text-align: center; }
.cal-nav { width: 32px; height: 32px; border-radius: var(--radius-md); border: 1px solid var(--c-border); background: transparent; color: var(--c-text-secondary); font-size: 18px; line-height: 1; cursor: pointer; transition: all var(--transition-fast); }
.cal-nav:hover { color: var(--brand-primary); border-color: var(--brand-primary); background: var(--brand-primary-light); }
.cal-today { margin-left: auto; padding: 6px 14px; font-size: 12px; font-weight: 600; color: var(--brand-primary); background: var(--brand-primary-light); border: 1px solid var(--brand-primary-200); border-radius: 999px; cursor: pointer; transition: all var(--transition-fast); }
.cal-today:hover { background: var(--brand-primary); color: #fff; }

.week-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; margin-bottom: 6px; }
.week-label { text-align: center; font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); padding: 4px 0; }
.week-label.is-weekend { color: var(--c-danger); }

.day-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; }
.day-cell { min-height: 84px; padding: 6px; display: flex; flex-direction: column; align-items: stretch; gap: 3px; background: var(--c-bg-alt); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); cursor: pointer; text-align: left; transition: border-color var(--transition-fast), background-color var(--transition-fast), transform var(--transition-fast); }
.day-cell:hover { border-color: var(--brand-primary-200); }
.day-cell.is-out { opacity: 0.4; background: transparent; }
.day-cell.is-today .day-num { background: var(--brand-primary); color: #fff; }
.day-cell.is-selected { border-color: var(--brand-primary); box-shadow: 0 0 0 1px var(--brand-primary); }
.day-num { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; font-size: 12px; color: var(--c-text-secondary); flex-shrink: 0; }
.day-events { display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
.mini-event { font-size: 10px; line-height: 1.3; padding: 1px 4px; border-radius: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mini-event.ev-待面试 { background: var(--c-info-light); color: var(--c-info); }
.mini-event.ev-已完成 { background: var(--c-success-light); color: var(--c-success); }
.mini-event.ev-已取消 { background: var(--c-danger-light); color: var(--c-danger); text-decoration: line-through; }
.mini-more { font-size: 10px; color: var(--c-text-tertiary); padding-left: 4px; }

/* 当日明细 */
.day-detail { margin-top: 18px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); padding: 18px 20px; box-shadow: var(--shadow-sm); }
.detail-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.detail-head h2 { font-size: 16px; font-weight: 600; color: var(--c-text); margin: 0; }
.event-list { display: flex; flex-direction: column; gap: 10px; }
.event-row { display: flex; gap: 12px; padding: 12px 14px; border: 1px solid var(--c-border-light); border-radius: var(--radius-md); background: var(--c-bg-alt); }
.event-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; }
.dot-待面试 { background: var(--c-info); }
.dot-已完成 { background: var(--c-success); }
.dot-已取消 { background: var(--c-danger); }
.event-main { flex: 1; min-width: 0; }
.event-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.event-title { font-family: var(--font-serif); font-size: 15px; font-weight: 600; color: var(--c-text); }
.event-time { font-size: 13px; color: var(--c-info); margin-top: 4px; }
.event-meta { display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--c-text-tertiary); margin-top: 4px; }
.meta-item { display: inline-flex; align-items: center; gap: 3px; }
.event-note { font-size: 12px; color: var(--c-text-secondary); margin-top: 4px; white-space: pre-wrap; }
.event-actions { display: flex; gap: 4px; align-items: flex-start; }
.icon-btn { width: 28px; height: 28px; border: 1px solid var(--c-border); border-radius: var(--radius-sm); background: transparent; color: var(--c-text-secondary); cursor: pointer; transition: all var(--transition-fast); font-size: 13px; }
.icon-btn:hover { color: var(--brand-primary); border-color: var(--brand-primary); background: var(--brand-primary-light); }
.icon-btn.danger:hover { color: var(--c-danger); border-color: var(--c-danger); background: var(--c-danger-light); }

.day-detail.empty .empty-inline { text-align: center; padding: 24px; color: var(--c-text-tertiary); }
.empty-inline p { margin: 0 0 12px; font-size: 14px; }

/* 弹窗 */
.modal-mask { position: fixed; inset: 0; z-index: var(--z-modal); background: rgba(0, 0, 0, 0.4); display: flex; align-items: center; justify-content: center; padding: 20px; }
.modal { width: 100%; max-width: 520px; max-height: 90vh; overflow-y: auto; background: var(--c-surface); border-radius: var(--radius-lg); padding: 26px; box-shadow: var(--shadow-md); }
.modal-title { font-family: var(--font-serif); font-size: 20px; font-weight: 600; color: var(--c-text); margin: 0 0 18px; }
.event-form { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 13px; font-weight: 500; color: var(--c-text-secondary); }
.field-label em { color: var(--c-danger); font-style: normal; }
.field-input { font-family: inherit; padding: 9px 12px; font-size: 14px; color: var(--c-text); background: var(--c-bg-alt); border: 1px solid var(--c-border); border-radius: var(--radius-md); transition: border-color var(--transition-fast), box-shadow var(--transition-fast); }
.field-input:focus { outline: none; border-color: var(--brand-primary); box-shadow: 0 0 0 3px var(--brand-primary-100); }
.field-textarea { resize: vertical; min-height: 64px; }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }

@media (max-width: 640px) {
  .day-cell { min-height: 64px; }
  .mini-event { display: none; }
  .day-cell.has-event .day-num { font-weight: 700; }
  .day-cell.has-event::after { content: ''; width: 5px; height: 5px; border-radius: 50%; background: var(--brand-primary); position: absolute; bottom: 5px; left: 50%; transform: translateX(-50%); }
  .day-cell { position: relative; }
  .field-row { grid-template-columns: 1fr; }
  .stats-row { gap: 18px; }
}
</style>
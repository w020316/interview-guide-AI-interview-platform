<template>
  <div v-if="running.length || done.length" class="ai-task-center" role="status" aria-live="polite">
    <!-- 进行中：可离开本页，完成后会在这里通知 -->
    <div v-for="t in running" :key="t.id" class="ai-task ai-task-running">
      <span class="ai-task-spinner" aria-hidden="true"></span>
      <div class="ai-task-body">
        <div class="ai-task-label">{{ t.label }}中…</div>
        <div class="ai-task-sub">
          <span class="ai-task-elapsed">{{ elapsed(t) }}s</span>
          <span class="ai-task-hint">可离开本页，完成后通知你</span>
        </div>
      </div>
    </div>

    <!-- 完成通知 -->
    <div
      v-for="t in done"
      :key="t.id"
      class="ai-task ai-task-done"
      :class="t.ok ? 'is-ok' : 'is-fail'"
    >
      <div class="ai-task-body">
        <div class="ai-task-label">{{ t.label }}{{ t.ok ? '完成' : '失败' }}</div>
        <div class="ai-task-sub">
          <button v-if="t.ok && t.route" class="ai-task-link" @click="open(t.route)">去看看</button>
          <span v-else class="ai-task-err">{{ t.error || '' }}</span>
        </div>
      </div>
      <button class="ai-task-close" aria-label="关闭通知" @click="dismiss(t.id)">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M18 6L6 18 M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { AiTask } from '../utils/aiTasks'
import { dismissAiTask, doneTasks, elapsedSeconds, pruneDoneTasks, runningTasks } from '../utils/aiTasks'

/**
 * 全局 AI 任务中心（N3）
 *
 * 常驻右下角：进行中的 AI 任务显示「标签 + 已用秒数」，完成后转成一条可关闭的通知
 * （成功可一键「去看看」跳回结果页）。任务来源由 api 拦截器统一登记（见 utils/aiTasks.ts），
 * 各视图零改动。无任务时整块不渲染。
 */
const router = useRouter()
const running = runningTasks
const done = doneTasks

/** 每秒推进一次：刷新秒数 + 清理超期通知 */
const tick = ref(Date.now())
let timer: number | undefined

onMounted(() => {
  timer = window.setInterval(() => {
    tick.value = Date.now()
    pruneDoneTasks(tick.value)
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

function elapsed(t: AiTask): number {
  return elapsedSeconds(t.startedAt, tick.value)
}

function dismiss(id: string): void {
  dismissAiTask(id)
}

function open(route?: string): void {
  if (route) router.push(route)
}
</script>

<style scoped>
.ai-task-center {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: min(320px, calc(100vw - 32px));
}

.ai-task {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: var(--c-surface-elevated);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}

.ai-task.is-ok {
  border-color: var(--c-success);
}

.ai-task.is-fail {
  border-color: var(--c-danger);
}

.ai-task-body {
  flex: 1;
  min-width: 0;
}

.ai-task-label {
  font-family: var(--font-title);
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.ai-task-sub {
  margin-top: 3px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.ai-task-elapsed {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--c-text-secondary);
}

.ai-task-hint {
  font-size: 11.5px;
}

.ai-task-err {
  color: var(--c-danger);
}

.ai-task-spinner {
  flex: none;
  width: 14px;
  height: 14px;
  margin-top: 1px;
  border: 2px solid color-mix(in srgb, var(--brand-primary) 25%, transparent);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: ai-task-spin 0.7s linear infinite;
}

@keyframes ai-task-spin {
  to {
    transform: rotate(360deg);
  }
}

.ai-task-link {
  padding: 0;
  border: none;
  background: transparent;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-primary);
  cursor: pointer;
}

.ai-task-link:hover {
  text-decoration: underline;
}

.ai-task-close {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-text-tertiary);
  cursor: pointer;
}

.ai-task-close:hover {
  color: var(--c-text);
}

@media (prefers-reduced-motion: reduce) {
  .ai-task-spinner {
    animation: none;
  }
}
</style>

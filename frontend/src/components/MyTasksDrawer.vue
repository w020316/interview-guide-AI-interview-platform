<template>
  <el-drawer
    v-model="open"
    title="我的任务"
    direction="rtl"
    size="min(420px, 92vw)"
    class="task-drawer"
  >
    <div class="tk-body">
      <!-- 筛选：客户端过滤最近 20 条，避免为「进行中」再造一个后端分页口径 -->
      <div class="tk-filters" role="tablist" aria-label="任务筛选">
        <button
          v-for="f in FILTERS"
          :key="f.key"
          type="button"
          role="tab"
          :aria-selected="filter === f.key"
          class="tk-filter"
          :class="{ active: filter === f.key }"
          @click="filter = f.key"
        >{{ f.label }}</button>
        <button type="button" class="tk-refresh" aria-label="刷新任务列表" :disabled="loading" @click="refresh">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M21 12a9 9 0 1 1-3-6.7 M21 3v6h-6" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>

      <p v-if="errorMsg" class="tk-error">{{ errorMsg }}</p>

      <div v-else-if="visible.length === 0" class="tk-empty">
        <span class="empty-icon-wrap" aria-hidden="true">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M9 11l3 3 8-8 M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h9"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <p>{{ loading ? '正在读取任务…' : '暂时没有任务' }}</p>
        <p class="tk-empty-hint">在「模拟面试」里出题或评分后，长耗时请求会自动转成后台任务，出现在这里。</p>
      </div>

      <ul v-else class="tk-list">
        <li v-for="t in visible" :key="t.id" class="tk-row">
          <div class="tk-head">
            <span class="tk-title">{{ taskTypeLabel(t.type) }}</span>
            <span class="tk-tag" :class="`is-${taskStatusTone(t.status)}`">{{ taskStatusLabel(t.status) }}</span>
          </div>
          <p class="tk-meta">
            {{ formatTaskTime(t.createdAt) }}
            <span class="tk-dot" aria-hidden="true">·</span>
            第 {{ t.attempt ?? 0 }}/{{ t.maxAttempt ?? 1 }} 次
          </p>
          <p v-if="t.status === 'FAILED' && t.errorMessage" class="tk-fail">{{ t.errorMessage }}</p>
          <div v-if="canCancel(t.status) || taskRoute(t.type)" class="tk-actions">
            <button
              v-if="canCancel(t.status)"
              type="button"
              class="tk-btn"
              :disabled="canceling === t.id"
              @click="cancel(t)"
            >{{ canceling === t.id ? '取消中…' : '取消任务' }}</button>
            <button
              v-if="canRetry(t.status) && taskRoute(t.type)"
              type="button"
              class="tk-btn"
              @click="go(t)"
            >去重新发起</button>
            <button
              v-if="t.status === 'SUCCEEDED' && taskRoute(t.type)"
              type="button"
              class="tk-btn primary"
              @click="go(t)"
            >去看看</button>
          </div>
        </li>
      </ul>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  canCancel,
  canRetry,
  cancelMyTask,
  closeTaskDrawer,
  filterTasks,
  formatTaskTime,
  loadMyTasks,
  taskDrawerOpen,
  taskRoute,
  taskStatusLabel,
  taskStatusTone,
  taskTypeLabel,
  type MyTask,
  type TaskFilter,
} from '../utils/myTasks'

/**
 * 我的任务抽屉（M4 补完 M2 的列表 UI）。
 *
 * 只做「看历史 + 取消 + 跳到对应页面重新发起」：结果正文不在这里展示
 * （出题/评分的结果要回填到面试页的状态里，硬塞进抽屉只会变成一份看不懂的 JSON）。
 */
const router = useRouter()

const FILTERS: ReadonlyArray<{ key: TaskFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '进行中' },
  { key: 'failed', label: '失败' },
  { key: 'done', label: '已完成' },
]

const open = computed({
  get: () => taskDrawerOpen.value,
  set: (v: boolean) => { taskDrawerOpen.value = v },
})

const items = ref<MyTask[]>([])
const filter = ref<TaskFilter>('all')
const loading = ref(false)
const errorMsg = ref('')
const canceling = ref<number | null>(null)

/** 客户端筛选（列表只取最近 20 条，数据量很小） */
const visible = computed(() => filterTasks(items.value, filter.value))

async function refresh(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    const page = await loadMyTasks()
    items.value = page.items
  } catch (e) {
    // 503（开关关闭）会原样带出「任务中心未启用」，比空列表更诚实
    errorMsg.value = (e as Error)?.message || '任务列表加载失败'
    items.value = []
  } finally {
    loading.value = false
  }
}

async function cancel(t: MyTask): Promise<void> {
  canceling.value = t.id
  try {
    if (await cancelMyTask(t.id)) {
      ElMessage.success('已取消')
      await refresh()
    } else {
      ElMessage.warning('取消失败：任务可能已经结束')
      await refresh()
    }
  } finally {
    canceling.value = null
  }
}

function go(t: MyTask): void {
  const route = taskRoute(t.type, t.id)
  if (!route) return
  closeTaskDrawer()
  router.push(route)
}

/** 每次打开都拉一次（immediate：组件挂载时若已处于打开态也要拉） */
watch(open, (v) => {
  if (v) void refresh()
}, { immediate: true })
</script>

<style scoped>
.tk-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tk-filters {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tk-filter {
  min-height: 28px;
  padding: 2px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  background: transparent;
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-secondary);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.tk-filter.active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}

.tk-refresh {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-text-tertiary);
  cursor: pointer;
}

.tk-refresh:hover {
  color: var(--c-text);
  background: var(--c-bg-alt);
}

.tk-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--c-danger);
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--c-danger);
}

.tk-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 12px;
  text-align: center;
  color: var(--c-text-secondary);
  font-size: 13px;
}

.tk-empty-hint {
  max-width: 280px;
  margin: 0;
  font-size: 12px;
  color: var(--c-text-tertiary);
  line-height: 1.6;
}

.tk-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tk-row {
  padding: 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
}

.tk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tk-title {
  font-family: var(--font-title);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.tk-tag {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
}

.tk-tag.is-pending {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}

.tk-tag.is-ok {
  background: var(--c-success-light);
  color: var(--c-success);
}

.tk-tag.is-fail {
  background: var(--c-danger-light);
  color: var(--c-danger);
}

.tk-meta {
  margin: 6px 0 0;
  font-family: var(--font-mono);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--c-text-tertiary);
}

.tk-dot {
  margin: 0 4px;
}

.tk-fail {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--c-danger);
  line-height: 1.5;
}

.tk-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.tk-btn {
  min-height: 32px;
  padding: 4px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: transparent;
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-secondary);
  cursor: pointer;
}

.tk-btn:hover {
  color: var(--c-text);
  border-color: var(--c-text-tertiary);
}

.tk-btn.primary {
  border-color: var(--brand-primary);
  background: var(--brand-primary);
  color: var(--c-on-primary);
}

.tk-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

/* 移动端：可点目标不小于 48px（阶段五 R1 的触控约定） */
@media (max-width: 768px) {
  .tk-filter,
  .tk-btn {
    min-height: 48px;
  }

  .tk-refresh {
    width: 48px;
    height: 48px;
  }
}
</style>

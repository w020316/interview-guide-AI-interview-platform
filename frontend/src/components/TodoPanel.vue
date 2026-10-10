<template>
  <section class="todo-panel" data-todo-panel aria-label="今日待办">
    <header class="todo-head">
      <div class="todo-title-wrap">
        <h2 class="todo-title">今日待办</h2>
        <!-- 计数只在确实有待办时出现；绝不渲染「0 条」 -->
        <span v-if="view.hasAny" class="todo-total">{{ view.total }} 项待处理</span>
      </div>
      <button class="todo-refresh" type="button" :disabled="loading" @click="emit('retry')">
        刷新
      </button>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="todo-skeleton">
      <div v-for="i in 3" :key="i" class="skeleton-row">
        <div class="skeleton skeleton-line w-30"></div>
        <div class="skeleton skeleton-line w-60"></div>
      </div>
    </div>

    <!-- 整块加载失败 / 全部数据源不可用 -->
    <div v-else-if="error || (!view.anyAvailable && failures.length)" class="todo-error">
      <p class="todo-error-text">{{ error || '今日待办暂时取不到，稍后重试即可。' }}</p>
      <button class="todo-retry" type="button" @click="emit('retry')">重试</button>
    </div>

    <template v-else>
      <!-- 局部失败：某组数据源不可用（count=null），如实说明，不显示 0 -->
      <ul v-if="failures.length" class="todo-failures" data-todo-failures>
        <li v-for="f in failures" :key="f.key" class="todo-failure">
          <span class="todo-failure-dot" aria-hidden="true">!</span>
          <span class="todo-failure-text">{{ f.message }}</span>
          <button class="todo-retry-link" type="button" @click="emit('retry')">重试</button>
        </li>
      </ul>

      <!-- 有待办：分组展示 -->
      <div v-if="view.hasAny" class="todo-groups">
        <div v-for="g in view.groups" :key="g.key" class="todo-group">
          <div class="todo-group-head">
            <span class="todo-group-label">{{ g.label }}</span>
            <span class="todo-group-count">{{ groupCountText(g) }}</span>
          </div>
          <ul class="todo-items">
            <li v-for="(it, idx) in g.items" :key="g.key + '-' + (it.id ?? idx)" class="todo-item">
              <button class="todo-item-btn" type="button" @click="openItem(it)">
                <span class="todo-item-main">
                  <span class="todo-item-title">{{ it.title }}</span>
                  <span v-if="it.subtitle" class="todo-item-sub">{{ it.subtitle }}</span>
                </span>
                <span v-if="it.reason" class="todo-item-reason">{{ it.reason }}</span>
                <span class="todo-item-go" aria-hidden="true">→</span>
              </button>
            </li>
          </ul>
        </div>
      </div>

      <!-- 空态：中性提示 + 建议动作（不显示任何「0 条」计数） -->
      <div v-else class="todo-empty" data-todo-empty>
        <div class="empty-icon-wrap" aria-hidden="true">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M9 11l3 3L22 4 M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
              stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <p class="todo-empty-title">今天没有待处理事项</p>
        <p class="todo-empty-desc">可以从下面这几步开始，为下一步做好准备。</p>
        <div class="todo-empty-actions">
          <BaseButton variant="primary" size="sm" @click="go('/resume')">上传简历</BaseButton>
          <BaseButton variant="ghost" size="sm" @click="go('/jobs')">去招聘广场</BaseButton>
          <BaseButton variant="ghost" size="sm" @click="go('/interview')">模拟面试</BaseButton>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * 今日待办面板（第三批 B）。
 *
 * <p>纯展示组件：数据由父级（`HomeView`）拉取并通过 `data` 传入，组件只负责把
 * {@link buildTodoView} 的视图模型渲染出来。所有「哪组有内容 / 顺序 / 计数」判定
 * 都在 `utils/todo.ts` 的纯函数里，组件不做额外推导。
 *
 * <p>R3：空组不渲染、失败组渲染局部失败态，DOM 中**不会出现「0 条」**。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from './BaseButton.vue'
import {
  buildTodoView,
  groupCountText,
  type TodoItem,
  type TodoTodayResponse,
} from '../utils/todo'

const props = withDefaults(
  defineProps<{
    data?: TodoTodayResponse | null
    loading?: boolean
    error?: string | null
  }>(),
  {
    data: null,
    loading: false,
    error: null,
  },
)

const emit = defineEmits<{ retry: [] }>()

const router = useRouter()

const view = computed(() => buildTodoView(props.data))
const failures = computed(() => view.value.failures)

/** 跳转到待办对应的既有页面（只透传 detail 里已有的 route / routeQuery，不新增 path） */
function openItem(it: TodoItem) {
  if (!it.route) return
  if (it.routeQuery) {
    void router.push({ path: it.route, query: it.routeQuery })
  } else {
    void router.push(it.route)
  }
}

function go(path: string) {
  void router.push(path)
}
</script>

<style scoped>
.todo-panel {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 20px 22px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 28px;
}

.todo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.todo-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.todo-title {
  font-family: var(--font-title);
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0;
  letter-spacing: -0.3px;
}

.todo-total {
  font-size: 12.5px;
  color: var(--c-accent);
  background: var(--c-accent-soft);
  border-radius: var(--radius-full);
  padding: 2px 10px;
  font-weight: 600;
}

.todo-refresh {
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-family: var(--font-sans);
  font-size: 12.5px;
  padding: 5px 14px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}

.todo-refresh:hover:not(:disabled) {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.todo-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ── 骨架 / 失败态 ── */
.todo-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.skeleton-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton {
  height: 12px;
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%);
  background-size: 400% 100%;
  animation: todo-skeleton-loading 1.4s ease infinite;
}

.skeleton-line.w-30 { width: 30%; }
.skeleton-line.w-60 { width: 60%; }

@keyframes todo-skeleton-loading {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

.todo-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 18px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
}

.todo-error-text {
  margin: 0;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.todo-retry {
  border: none;
  background: var(--brand-primary);
  color: var(--c-surface);
  font-family: var(--font-sans);
  font-size: 13px;
  padding: 6px 16px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.todo-retry:hover {
  background: var(--brand-primary-hover);
}

/* ── 局部失败 ── */
.todo-failures {
  list-style: none;
  margin: 0 0 14px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.todo-failure {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--c-text-secondary);
  background: var(--c-warning-light);
  border: 1px solid var(--c-warning-border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}

.todo-failure-dot {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--c-warning);
  color: var(--c-surface);
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.todo-failure-text { flex: 1; }

.todo-retry-link {
  border: none;
  background: transparent;
  color: var(--brand-primary);
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  padding: 0 2px;
}

/* ── 分组 ── */
.todo-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.todo-group-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.todo-group-label {
  font-family: var(--font-title);
  font-size: 13.5px;
  font-weight: 700;
  color: var(--c-text);
}

.todo-group-count {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.todo-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.todo-item-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  cursor: pointer;
  font-family: var(--font-sans);
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.todo-item-btn:hover {
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.todo-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.todo-item-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-item-sub {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.todo-item-reason {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--c-text-secondary);
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-item-go {
  flex-shrink: 0;
  color: var(--brand-primary);
  font-size: 14px;
}

/* ── 空态 ── */
.todo-empty {
  text-align: center;
  padding: 26px 16px 18px;
}

.todo-empty-title {
  margin: 0 0 6px;
  font-family: var(--font-title);
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}

.todo-empty-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.todo-empty-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}

@media (max-width: 480px) {
  .todo-item-reason {
    display: none;
  }
  .todo-empty-actions {
    flex-direction: column;
    width: 100%;
  }
}
</style>

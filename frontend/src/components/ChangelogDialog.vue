<template>
  <el-dialog
    :model-value="visible"
    :title="''"
    width="560px"
    :show-close="true"
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    align-center
    class="changelog-dialog"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @close="handleClose"
  >
    <div class="changelog-content">
      <!-- 头部 -->
      <div class="changelog-head">
        <div class="head-icon">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="head-text">
          <div class="head-title">{{ latest.title }}</div>
          <div class="head-meta">
            <span class="version-badge">v{{ latest.version }}</span>
            <span class="version-date">{{ latest.date }}</span>
          </div>
        </div>
      </div>

      <!-- 更新内容列表 -->
      <div class="update-list">
        <div class="list-title">本次更新内容</div>
        <ul>
          <li v-for="(item, idx) in latest.items" :key="idx">
            <span class="item-bullet">·</span>
            <span class="item-text">{{ item }}</span>
          </li>
        </ul>
      </div>

      <!-- 历史版本 -->
      <details class="history-section" v-if="history.length">
        <summary>历史版本更新（{{ history.length }} 个）</summary>
        <div class="history-list">
          <div v-for="entry in history" :key="entry.version" class="history-item">
            <div class="history-head">
              <span class="version-badge sm">v{{ entry.version }}</span>
              <span class="version-date">{{ entry.date }}</span>
            </div>
            <ul class="history-items">
              <li v-for="(item, idx) in entry.items" :key="idx">{{ item }}</li>
            </ul>
          </div>
        </div>
      </details>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <label class="dont-show">
          <input type="checkbox" v-model="dontShowAgain" />
          <span>不再提醒此版本</span>
        </label>
        <button class="btn-confirm" @click="handleClose">我知道了</button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { CHANGELOG, CURRENT_VERSION } from '../changelog'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const STORAGE_KEY = 'interview_guide_seen_version'

const dontShowAgain = ref(false)

const latest = computed(() => CHANGELOG[0])
const history = computed(() => CHANGELOG.slice(1))

/** 检查版本：若 localStorage 中记录的版本与当前版本不同，则触发弹窗 */
function checkVersion() {
  try {
    const seen = localStorage.getItem(STORAGE_KEY)
    if (seen !== CURRENT_VERSION) {
      emit('update:visible', true)
    }
  } catch {
    // localStorage 不可用（隐私模式），默认不弹
  }
}

function handleClose() {
  if (dontShowAgain.value) {
    try {
      localStorage.setItem(STORAGE_KEY, CURRENT_VERSION)
    } catch {
      // 忽略写入失败
    }
  }
  emit('update:visible', false)
}

// 组件挂载时检查版本（仅一次）
let checked = false
onMounted(() => {
  if (!checked) {
    checked = true
    checkVersion()
  }
})
</script>

<style scoped>
.changelog-content {
  padding: 0 4px;
}

/* ── 头部 ── */
.changelog-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--c-border-light);
  margin-bottom: 20px;
}

.head-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  flex-shrink: 0;
}

.head-text {
  flex: 1;
  min-width: 0;
}

.head-title {
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 8px;
  letter-spacing: -0.2px;
  line-height: 1.4;
}

.head-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.version-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border-radius: 999px;
  letter-spacing: 0.3px;
}

.version-badge.sm {
  font-size: 11px;
  padding: 2px 8px;
}

.version-date {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

/* ── 更新列表 ── */
.update-list {
  margin-bottom: 20px;
}

.list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-secondary);
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.update-list ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

.update-list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 14px;
  color: var(--c-text);
  line-height: 1.6;
  padding: 6px 0;
}

.item-bullet {
  color: var(--brand-primary);
  font-weight: 700;
  flex-shrink: 0;
  line-height: 1.6;
}

.item-text {
  flex: 1;
}

/* ── 历史版本 ── */
.history-section {
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 12px 16px;
}

.history-section summary {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  cursor: pointer;
  user-select: none;
  padding: 4px 0;
}

.history-section summary:hover {
  color: var(--brand-primary);
}

.history-list {
  margin-top: 12px;
  max-height: 280px;
  overflow-y: auto;
}

.history-item {
  padding: 12px 0;
  border-top: 1px solid var(--c-border-light);
}

.history-item:first-child {
  border-top: none;
}

.history-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.history-items {
  list-style: none;
  padding: 0;
  margin: 0;
}

.history-items li {
  font-size: 12px;
  color: var(--c-text-secondary);
  line-height: 1.6;
  padding: 3px 0;
  padding-left: 12px;
  position: relative;
}

.history-items li::before {
  content: '·';
  position: absolute;
  left: 2px;
  color: var(--c-text-tertiary);
}

/* ── 底部 ── */
.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dont-show {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--c-text-secondary);
  cursor: pointer;
  user-select: none;
}

.dont-show input {
  cursor: pointer;
  accent-color: var(--brand-primary);
}

.btn-confirm {
  padding: 9px 24px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast);
  font-family: inherit;
}

.btn-confirm:hover {
  background: var(--brand-primary-hover);
}

/* ── 响应式 ── */
@media (max-width: 640px) {
  .changelog-dialog :deep(.el-dialog) {
    width: 92% !important;
  }
  .head-title {
    font-size: 15px;
  }
  .update-list li {
    font-size: 13px;
  }
}
</style>

<style>
/* 全局样式：弹窗遮罩 */
.changelog-dialog .el-dialog__header {
  padding: 0;
  margin: 0;
}
.changelog-dialog .el-dialog__body {
  padding: 24px 28px 8px;
}
.changelog-dialog .el-dialog__footer {
  padding: 12px 28px 24px;
}
</style>

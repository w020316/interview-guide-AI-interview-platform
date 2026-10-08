<template>
  <div class="empty-chart">
    <svg class="empty-chart__icon" width="36" height="36" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M3 3v18h18 M8 17V9 M13 17V5 M18 17v-7"
        stroke="var(--c-text-quaternary)"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <p class="empty-chart__text">{{ text }}</p>
    <button
      v-if="actionText"
      type="button"
      class="empty-chart__action"
      :disabled="actionDisabled"
      @click="emit('action')"
    >
      {{ actionText }}
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 图表空态统一组件（v1.48.0）
 *
 * 背景：管理后台三处图表空态此前不一致——数据源分布给了行动指引，
 * 招聘类型分布只有「暂无数据」，近 7 天趋势干脆是一片空白。
 * 统一为「图标 + 一句说明 + 一个行动按钮」，避免同一页出现三种空态口径。
 */
interface Props {
  /** 一句说明（如「暂无岗位数据」） */
  text: string
  /** 行动按钮文案；不传则不渲染按钮 */
  actionText?: string
  /** 行动按钮禁用态（如刷新中） */
  actionDisabled?: boolean
}
withDefaults(defineProps<Props>(), {
  actionText: '',
  actionDisabled: false,
})
const emit = defineEmits<{ (e: 'action'): void }>()
</script>

<style scoped>
.empty-chart {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px 12px;
  text-align: center;
}

.empty-chart__icon {
  opacity: 0.75;
}

.empty-chart__text {
  margin: 0;
  font-size: 13px;
  color: var(--c-text-tertiary);
}

.empty-chart__action {
  padding: 6px 14px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border: 1px solid var(--brand-primary-200);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}

.empty-chart__action:hover:not(:disabled) {
  background: var(--brand-primary);
  color: var(--c-surface);
}

.empty-chart__action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>

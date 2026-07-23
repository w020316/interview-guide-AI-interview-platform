<template>
  <span
    :class="['base-tag', `base-tag--${variant}`, `base-tag--${size}`, { 'is-closable': closable }]"
  >
    <slot v-if="$slots.icon" name="icon"></slot>
    <span class="base-tag__text"><slot /></span>
    <button
      v-if="closable"
      type="button"
      class="base-tag__close"
      aria-label="移除"
      @click.stop="emit('close')"
    >×</button>
  </span>
</template>

<script setup lang="ts">
/**
 * 通用标签组件
 * - 六种语义变体：default / primary / success / warning / danger / info
 * - 三种尺寸：sm / md / lg
 * - 可关闭（closable），关闭时触发 close 事件
 *
 * 基于 variables.css 设计系统，替代各 View 内联的 .badge / .tag / .difficulty-tag
 */
interface Props {
  variant?: 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info'
  size?: 'sm' | 'md' | 'lg'
  closable?: boolean
}
withDefaults(defineProps<Props>(), {
  variant: 'default',
  size: 'md',
  closable: false,
})
const emit = defineEmits<{ (e: 'close'): void }>()
</script>

<style scoped>
.base-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: inherit;
  font-weight: 500;
  line-height: 1.4;
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  white-space: nowrap;
  user-select: none;
  vertical-align: middle;
}

/* 尺寸 */
.base-tag--sm {
  font-size: 11px;
  padding: 2px 8px;
}
.base-tag--md {
  font-size: 12px;
  padding: 4px 10px;
}
.base-tag--lg {
  font-size: 13px;
  padding: 6px 12px;
}

/* 变体（浅底 + 强色字，编辑风克制） */
.base-tag--default {
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
  border-color: var(--c-border);
}
.base-tag--primary {
  background: var(--brand-primary-light);
  color: var(--brand-primary-active);
  border-color: var(--brand-primary-200);
}
.base-tag--success {
  background: var(--c-success-light);
  color: var(--c-success);
  border-color: #bbf7d0;
}
.base-tag--warning {
  background: var(--c-warning-light);
  color: var(--c-warning);
  border-color: #fde68a;
}
.base-tag--danger {
  background: var(--c-danger-light);
  color: var(--c-danger);
  border-color: #fecaca;
}
.base-tag--info {
  background: var(--c-info-light);
  color: var(--c-info);
  border-color: #bfdbfe;
}

/* 关闭按钮 */
.base-tag.is-closable {
  padding-right: 6px;
}
.base-tag__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  padding: 0;
  margin-left: 2px;
  font-size: 14px;
  line-height: 1;
  color: inherit;
  background: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  opacity: 0.6;
  transition: opacity var(--transition-fast), background var(--transition-fast);
}
.base-tag__close:hover {
  opacity: 1;
  background: rgba(0, 0, 0, 0.08);
}
.base-tag__close:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 1px;
  opacity: 1;
}
</style>

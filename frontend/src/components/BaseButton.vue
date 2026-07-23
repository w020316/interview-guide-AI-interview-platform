<template>
  <button
    :type="type"
    :disabled="disabled"
    :class="[
      'base-btn',
      `base-btn--${variant}`,
      `base-btn--${size}`,
      shadow !== 'none' ? `base-btn--shadow-${shadow}` : '',
      { 'is-block': block, 'is-hoverable': hoverable }
    ]"
    @click="handleClick"
  >
    <span v-if="loading" class="base-btn__spinner" aria-hidden="true"></span>
    <slot v-else name="icon"></slot>
    <span v-if="$slots.default" class="base-btn__text"><slot /></span>
  </button>
</template>

<script setup lang="ts">
/**
 * 通用按钮组件
 * - 五种变体：primary（品牌色纯色）/ gradient（品牌色渐变，原各 View .btn-primary）/ ghost（描边）/ success（绿色）/ cta（白底反色）
 * - 三种尺寸：sm / md / lg
 * - 支持 loading、disabled、block（块级宽度）
 * - v1.9 新增 shadow（阴影等级）和 hoverable（悬停上浮）prop，向后兼容
 * - v1.10 新增 cta variant（HomeView CTA）和 gradient variant（替代各 View 渐变 .btn-primary）
 *
 * 基于 variables.css 设计系统，替代各 View 内联的 .btn-primary/.btn-ghost
 */
interface Props {
  variant?: 'primary' | 'gradient' | 'ghost' | 'success' | 'cta'
  size?: 'sm' | 'md' | 'lg'
  type?: 'button' | 'submit'
  disabled?: boolean
  loading?: boolean
  block?: boolean
  shadow?: 'none' | 'sm' | 'md'
  hoverable?: boolean
}
const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  disabled: false,
  loading: false,
  block: false,
  shadow: 'none',
  hoverable: false,
})
const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()
function handleClick(ev: MouseEvent) {
  if (props.disabled || props.loading) return
  emit('click', ev)
}
</script>

<style scoped>
.base-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-family: inherit;
  font-weight: 500;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
  user-select: none;
  line-height: 1.5;
}
.base-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}
.base-btn:disabled,
.base-btn[aria-disabled='true'] {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 尺寸 */
.base-btn--sm {
  font-size: 13px;
  padding: 6px 12px;
}
.base-btn--md {
  font-size: 14px;
  padding: 9px 18px;
}
.base-btn--lg {
  font-size: 16px;
  padding: 12px 24px;
}

/* 变体 */
.base-btn--primary {
  background: var(--brand-primary);
  color: #fff;
}
.base-btn--primary:hover:not(:disabled) {
  background: var(--brand-primary-hover);
}
.base-btn--primary:active:not(:disabled) {
  background: var(--brand-primary-active);
}

.base-btn--ghost {
  background: transparent;
  color: var(--c-text-secondary);
  border-color: var(--c-border-strong);
}
.base-btn--ghost:hover:not(:disabled) {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.base-btn--success {
  background: var(--c-success);
  color: #fff;
}
.base-btn--success:hover:not(:disabled) {
  filter: brightness(1.1);
}

/* CTA：白底反色，用于品牌色背景上的行动召唤（HomeView CTA 卡片） */
.base-btn--cta {
  background: #fff;
  color: var(--brand-primary);
  box-shadow: var(--shadow-md);
}
.base-btn--cta:hover:not(:disabled) {
  background: var(--brand-primary-50);
}
.base-btn--cta:active:not(:disabled) {
  background: var(--brand-primary-100);
}

/* gradient：品牌色渐变，替代各 View 原 .btn-primary（渐变 + 品牌阴影 + 上浮） */
.base-btn--gradient {
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: var(--shadow-brand);
}
.base-btn--gradient:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.05);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.4);
}
.base-btn--gradient:active:not(:disabled) {
  transform: translateY(0);
  filter: brightness(0.98);
}

/* 块级宽度 */
.is-block {
  display: flex;
  width: 100%;
}

/* 阴影等级 */
.base-btn--shadow-sm {
  box-shadow: var(--shadow-sm);
}
.base-btn--shadow-md {
  box-shadow: var(--shadow-md);
}

/* 悬停上浮：translateY + 阴影升级（sm→md） */
.base-btn.is-hoverable:hover:not(:disabled) {
  transform: translateY(-1px);
}
.base-btn.is-hoverable:active:not(:disabled) {
  transform: translateY(0);
}
.base-btn--shadow-sm.is-hoverable:hover:not(:disabled) {
  box-shadow: var(--shadow-md);
}

/* loading spinner */
.base-btn__spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>

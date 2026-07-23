<template>
  <component
    :is="tag"
    :class="['base-card', `base-card--${variant}`, { 'is-hoverable': hoverable, 'is-flat': flat }]"
  >
    <header v-if="$slots.header || title" class="base-card__header">
      <slot name="header">
        <h3 class="base-card__title">{{ title }}</h3>
        <p v-if="subtitle" class="base-card__subtitle">{{ subtitle }}</p>
      </slot>
    </header>
    <div class="base-card__body" :class="{ 'is-padded': padded }">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="base-card__footer">
      <slot name="footer" />
    </footer>
  </component>
</template>

<script setup lang="ts">
/**
 * 通用卡片组件
 * - 三种变体：default（白底+细边框）/ outlined（仅边框）/ elevated（带阴影）
 * - 支持 header/footer 插槽、title/subtitle 快捷属性
 * - hoverable 启用悬停上浮反馈，flat 去除阴影
 *
 * 基于 variables.css 设计系统，替代各 View 内联的 .card / .panel
 */
interface Props {
  variant?: 'default' | 'outlined' | 'elevated'
  tag?: string
  title?: string
  subtitle?: string
  hoverable?: boolean
  flat?: boolean
  padded?: boolean
}
withDefaults(defineProps<Props>(), {
  variant: 'default',
  tag: 'div',
  title: '',
  subtitle: '',
  hoverable: false,
  flat: false,
  padded: true,
})
</script>

<style scoped>
.base-card {
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: box-shadow var(--transition-base), transform var(--transition-base),
    border-color var(--transition-base);
}

/* 变体 */
.base-card--default {
  box-shadow: var(--shadow-sm);
}
.base-card--outlined {
  box-shadow: none;
  border-color: var(--c-border-strong);
}
.base-card--elevated {
  box-shadow: var(--shadow-lg);
  border-color: transparent;
}
.base-card--elevated.is-flat {
  box-shadow: none;
}

/* 悬停反馈 */
.base-card.is-hoverable:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lg);
  border-color: var(--brand-primary-200);
}

/* 头部 */
.base-card__header {
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--c-border-light);
  background: var(--c-bg-soft);
}
.base-card__title {
  margin: 0;
  font-family: var(--font-serif);
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: -0.01em;
}
.base-card__subtitle {
  margin: 4px 0 0;
  font-size: var(--font-size-sm);
  color: var(--c-text-tertiary);
}

/* 主体 */
.base-card__body {
  flex: 1 1 auto;
}
.base-card__body.is-padded {
  padding: var(--space-lg);
}

/* 底部 */
.base-card__footer {
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--c-border-light);
  background: var(--c-bg-soft);
}
</style>

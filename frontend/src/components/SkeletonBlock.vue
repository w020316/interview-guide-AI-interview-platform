<template>
  <span
    class="skeleton-block"
    :class="`is-${rounded}`"
    :style="{ width, height }"
    aria-hidden="true"
  />
</template>

<script setup lang="ts">
/**
 * 骨架屏基础块（v1.80.0）
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * `DESIGN.md` §5 明确要求「加载态用**骨架屏**而非通用转圈」。
 * 但此前只有 `TodoPanel` 自带一套骨架样式，招聘广场等主要页面还在用转圈 spinner ——
 * 转圈只告诉用户「在等」，骨架屏还额外告诉用户「等的是什么形状」，
 * 首屏感知速度明显更快（内容位置不跳动）。
 *
 * ── 用法 ────────────────────────────────────────────────────────────
 * 只提供**形状**，不提供布局。页面自己用 flex/grid 拼出与真实内容同构的骨架，
 * 这样骨架与内容的位置一致，加载完成时不会发生跳动。
 *
 * ── 扫光 ────────────────────────────────────────────────────────────
 * 用 `::after` 伪元素做一道从右向左扫过的高光，只动 `transform`（GPU 加速）。
 * 命中 `prefers-reduced-motion` 时**完全关掉动画**，保留静态灰块 ——
 * 骨架屏的信息在形状里，不依赖动画。
 */
withDefaults(
  defineProps<{
    /** CSS 宽度，如 '100%' / '60px' */
    width?: string
    /** CSS 高度，如 '14px' */
    height?: string
    /** 圆角档位，走全局 radius 令牌 */
    rounded?: 'xs' | 'sm' | 'md' | 'lg' | 'full'
  }>(),
  { width: '100%', height: '14px', rounded: 'sm' }
)
</script>

<style scoped>
.skeleton-block {
  display: block;
  position: relative;
  overflow: hidden;
  background: var(--c-bg-alt);
  /* 不用 transition：这是常驻动画，不是状态切换 */
}

.is-xs {
  border-radius: var(--radius-xs);
}

.is-sm {
  border-radius: var(--radius-sm);
}

.is-md {
  border-radius: var(--radius-md);
}

.is-lg {
  border-radius: var(--radius-lg);
}

.is-full {
  border-radius: var(--radius-full);
}

/* 扫光：一道半透明高光从左侧滑到右侧 */
.skeleton-block::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(
    90deg,
    transparent 0%,
    color-mix(in srgb, var(--c-text) 7%, transparent) 50%,
    transparent 100%
  );
  animation: skeleton-shimmer 1.5s cubic-bezier(0.22, 1, 0.36, 1) infinite;
}

@keyframes skeleton-shimmer {
  100% {
    transform: translateX(100%);
  }
}

/* 尊重「减少动态效果」：关掉扫光，静态灰块仍表达「正在加载」 */
@media (prefers-reduced-motion: reduce) {
  .skeleton-block::after {
    animation: none;
    display: none;
  }
}
</style>

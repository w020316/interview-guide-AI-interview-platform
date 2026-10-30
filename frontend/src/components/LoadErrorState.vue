/**
 * 加载失败态（P2-19，2026-10-30 质量循环）。
 *
 * ── 要解决的缺陷 ────────────────────────────────────────────────────
 * 多个视图在接口失败时直接把数据当成空数组/0 渲染：用户看到「还没有投递记录」
 * 「0 次练习」「暂无数据」，误以为**数据丢了**。这违反了本项目「无数据 ≠ 0」的契约：
 * 「没有数据」是确定性结论，「请求失败」是未知状态，两者必须能区分。
 *
 * ── 使用约定 ────────────────────────────────────────────────────────
 * 视图维护一个 `loadError` 标记：加载成功置 false，catch 置 true。
 * 模板中把它放在**空态之前**：失败时先渲染本组件，绝不落到空态分支。
 * 文案固定为「加载失败」而不是「暂无数据」——错误归因必须准确。
 */
<template>
  <div class="load-error-state" role="alert" data-load-error>
    <div class="le-icon" aria-hidden="true">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
        <path d="M12 7.5v5.5 M12 16.5v.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      </svg>
    </div>
    <p class="le-title">{{ title }}</p>
    <p class="le-desc">{{ description }}</p>
    <BaseButton v-if="retryable" variant="primary" size="sm" @click="$emit('retry')">重新加载</BaseButton>
  </div>
</template>

<script setup lang="ts">
import BaseButton from './BaseButton.vue'

withDefaults(defineProps<{
  /** 主文案（默认「加载失败」） */
  title?: string
  /** 说明文案，需明确「不是没有数据，而是没取到」 */
  description?: string
  /** 是否显示「重新加载」按钮 */
  retryable?: boolean
}>(), {
  title: '加载失败',
  description: '没能取到数据，这不代表没有数据。请检查网络后重试。',
  retryable: true,
})

defineEmits<{ (e: 'retry'): void }>()
</script>

<style scoped>
.load-error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 16px;
  text-align: center;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface);
}

.le-icon {
  color: var(--c-danger);
}

.le-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.le-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--c-text-secondary);
  max-width: 32em;
}
</style>

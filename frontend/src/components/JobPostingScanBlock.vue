<template>
  <section class="scan-block" :class="`is-${report.level}`">
    <div class="scan-head">
      <span class="scan-icon" aria-hidden="true">
        <svg
          v-if="report.level === 'risk'"
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
          <path d="M12 9v4M12 17h.01" />
        </svg>
        <svg
          v-else-if="report.level === 'attention'"
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8v5M12 16h.01" />
        </svg>
        <svg
          v-else
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M8.5 12.5l2.5 2.5 4.5-5" />
        </svg>
      </span>
      <span class="scan-title">内容体检</span>
      <span class="scan-badge">{{ levelLabel }}</span>
      <span class="scan-summary">{{ report.summary }}</span>
    </div>

    <ul v-if="report.signals.length" class="scan-list">
      <li v-for="s in report.signals" :key="s.id" class="scan-item" :class="`is-${s.severity}`">
        <span class="scan-item-dot" aria-hidden="true"></span>
        <div class="scan-item-body">
          <div class="scan-item-head">
            <span class="scan-item-title">{{ s.title }}</span>
            <span class="scan-item-severity">{{ s.severity === 'risk' ? '风险' : '留意' }}</span>
          </div>
          <p class="scan-item-detail">{{ s.detail }}</p>
          <code class="scan-item-evidence">{{ s.evidence }}</code>
        </div>
      </li>
    </ul>

    <p class="scan-disclaimer">{{ SCAN_DISCLAIMER }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 岗位内容体检结果块（v1.86.0）
 *
 * 纯展示组件：报告由 `scanJobPosting()` 在调用方算好传进来，这里零网络、零判断。
 * 它存在的意义是让「展示」与「规则」分开 —— 规则用单测锁住证据长什么样，
 * 这里只负责把证据摆到用户眼前。
 *
 * ── 刻意不做的三件事 ────────────────────────────────────────────
 * 1. **不给结论**：没有「该岗位可信 / 不可信」，只有「命中了哪条规则、原文是什么」；
 * 2. **不拦截**：不隐藏投递入口、不弹确认框，用户仍可直接点「去投递」；
 * 3. **不隐藏 clean 态**：命中 0 条时也照常渲染一行「未命中规则」+ 免责说明，
 *    否则用户会把「没看到提示」误读成「这个岗位已验证通过」。
 */
import { computed } from 'vue'
import { SCAN_DISCLAIMER, type ScanReport } from '../utils/jobPostingScan'

const props = defineProps<{ report: ScanReport }>()

/** 与 `summary` 的分工：这里只标等级，具体数量交给 summary 说 */
const levelLabel = computed(() => {
  switch (props.report.level) {
    case 'risk':
      return '需谨慎核实'
    case 'attention':
      return '有几处要留意'
    default:
      return '未命中规则'
  }
})
</script>

<style scoped>
.scan-block {
  margin-top: 18px;
  padding: var(--space-md) var(--space-lg);
  border: 1px solid var(--c-border);
  border-left: 3px solid var(--c-border-strong);
  border-radius: var(--radius-md);
  background: var(--c-bg-alt);
}

.scan-block.is-attention {
  border-left-color: var(--c-warning);
  background: var(--c-warning-light);
}

.scan-block.is-risk {
  border-left-color: var(--c-danger);
  background: var(--c-danger-light);
}

.scan-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--font-size-sm);
}

.scan-icon {
  display: inline-flex;
  align-items: center;
  color: var(--c-text-quaternary);
}

.is-attention .scan-icon {
  color: var(--c-warning);
}

.is-risk .scan-icon {
  color: var(--c-danger);
}

.scan-title {
  font-weight: 600;
  color: var(--c-text);
}

.scan-badge {
  padding: 0 var(--space-xs);
  font-size: var(--font-size-xs);
  line-height: 1.7;
  border-radius: var(--radius-xs);
  color: var(--c-text-secondary);
  background: var(--c-bg-soft);
}

.is-attention .scan-badge {
  color: var(--c-warning);
  background: transparent;
  border: 1px solid var(--c-warning-border);
}

.is-risk .scan-badge {
  color: var(--c-danger);
  background: transparent;
  border: 1px solid var(--c-danger-border);
}

.scan-summary {
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

.scan-list {
  margin: var(--space-sm) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.scan-item {
  display: flex;
  gap: var(--space-sm);
}

.scan-item-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--c-warning);
}

.scan-item.is-risk .scan-item-dot {
  background: var(--c-danger);
}

.scan-item-body {
  min-width: 0;
  flex: 1;
}

.scan-item-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--font-size-sm);
}

.scan-item-title {
  font-weight: 600;
  color: var(--c-text);
}

.scan-item-severity {
  font-size: var(--font-size-xs);
  color: var(--c-warning);
}

.scan-item.is-risk .scan-item-severity {
  color: var(--c-danger);
}

.scan-item-detail {
  margin: 2px 0 0;
  font-size: var(--font-size-xs);
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.scan-item-evidence {
  display: block;
  margin-top: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  font-family: var(--font-mono);
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-xs);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.scan-disclaimer {
  margin: var(--space-sm) 0 0;
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--c-text-quaternary);
}
</style>

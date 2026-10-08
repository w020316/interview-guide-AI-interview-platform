<template>
  <div class="report-mask">
    <div
      class="report-modal"
      role="dialog"
      aria-modal="true"
      aria-label="面试复盘报告"
      :data-report-degraded="view.degraded ? 'true' : 'false'"
    >
      <div class="report-head">
        <div>
          <h3 class="report-title">模拟面试复盘报告</h3>
          <!-- 岗位名取不到时不渲染（报告是转发产物，不印占位行） -->
          <p v-if="jobTitle" class="report-job">{{ jobTitle }}</p>
          <p class="report-sub">
            <span class="report-star" aria-hidden="true">★</span> {{ scopeText }} · 灵感参考 AI 面试工具
          </p>
        </div>
        <button class="report-close" aria-label="关闭" @click="emit('close')">✕</button>
      </div>

      <!-- 综合得分：来自 evaluationScore，旧会话亦有，故降级时仍展示 -->
      <div class="report-overall">
        <div class="overall-score" :style="{ color: scoreColor(view.overall) }">
          {{ view.overall === null ? EMPTY : view.overall }}
          <span v-if="view.overall !== null" class="overall-unit">分</span>
        </div>
        <div class="overall-summary">{{ view.summary }}</div>
      </div>

      <!-- RK1 降级：旧会话无维度明细 → 不渲染维度条（.report-dim 数 = 0），也不补 0 -->
      <p v-if="view.degraded" class="report-degraded-note" data-report-degraded-note="true">
        本场未记录分维度明细，仅展示综合得分（旧数据不补 0）。
      </p>
      <div v-else class="report-dims">
        <div v-for="d in view.dims" :key="d.name" class="report-dim">
          <div class="dim-bar">
            <div
              class="dim-fill"
              :style="{ width: clampPct(d.value) + '%', background: scoreColor(d.value) }"
            ></div>
          </div>
          <div class="dim-row">
            <span class="dim-name">{{ d.name }}</span>
            <span class="dim-value" :style="{ color: scoreColor(d.value) }">{{ d.value }}</span>
          </div>
        </div>
      </div>

      <!-- 多轮成绩对比（仅实时报告传入 compare） -->
      <div v-if="compareLoaded && compare" class="report-block report-compare">
        <div class="report-block-title">与历史成绩对比</div>
        <div class="compare-line">
          <span class="compare-badge" :class="compare.status">{{ compare.label }}</span>
          <span class="compare-text">{{ compare.hint }}</span>
        </div>
        <div class="compare-target">
          下一轮目标：综合
          <b :style="{ color: scoreColor(compare.nextTarget) }">{{ compare.nextTarget }} 分</b>
          。可在准备页提高难度或聚焦薄弱分类，逐步达成。
        </div>
      </div>

      <!-- 逐题得分回顾 -->
      <div v-if="view.questions.length" class="report-block">
        <div class="report-block-title">逐题得分</div>
        <div class="report-questions">
          <div v-for="(q, idx) in view.questions" :key="idx" class="report-question">
            <div class="rq-head">
              <span class="rq-index">{{ idx + 1 }}</span>
              <span class="rq-cat">{{ q.category || EMPTY }}</span>
              <span class="rq-score" :style="{ color: scoreColor(q.overallScore) }">
                {{ q.overallScore === null ? EMPTY : q.overallScore + ' 分' }}
              </span>
            </div>
            <div class="rq-text">{{ q.question }}</div>
          </div>
        </div>
      </div>

      <!-- 高频改进建议 -->
      <div v-if="view.improvements.length" class="report-block">
        <div class="report-block-title">建议提升的要点</div>
        <ul class="report-improve">
          <li v-for="(imp, idx) in view.improvements" :key="idx">
            <span class="imp-text">{{ imp.text }}</span>
            <span v-if="imp.times > 1" class="imp-times">×{{ imp.times }}</span>
          </li>
        </ul>
      </div>

      <div class="report-actions">
        <!-- 分享卡片 / 导出 PDF 对**所有**会话可见：
             两个导出模块已做降级感知（复用 reportView 的 canRenderDimensions），
             降级场次不印维度条、不补 0，故不必再靠隐藏入口来兜底。 -->
        <BaseButton variant="ghost" :loading="sharing" @click="emit('share')">分享卡片</BaseButton>
        <BaseButton variant="ghost" @click="emit('export-pdf')">导出 PDF</BaseButton>
        <BaseButton v-if="showHistory" variant="ghost" @click="emit('go-history')">查看历史记录</BaseButton>
        <BaseButton variant="gradient" @click="emit('close')">完成，继续练习</BaseButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 面试复盘报告面板（第三批 A）。
 *
 * <p>从 `InterviewView.vue` 抽出，供**实时面试结束**与**历史回看**两处复用。
 * 所有派生数据（维度条 / 摘要 / 改进建议 / 降级判定）都来自纯函数
 * {@link buildReportView}，组件本身只负责渲染——降级逻辑因此可被单测锁死。
 */
import { computed } from 'vue'
import BaseButton from './BaseButton.vue'
import { EMPTY } from '../utils/format'
import { getScoreColor } from '../utils/score'
import {
  buildReportView,
  type ReportCompareView,
  type ReportEvalInput,
} from '../utils/reportView'

const props = withDefaults(
  defineProps<{
    evals: ReportEvalInput[]
    jobTitle?: string
    scopeText?: string
    compare?: ReportCompareView | null
    compareLoaded?: boolean
    sharing?: boolean
    /** 是否展示「查看历史记录」入口（历史页自身无需该入口，传 false） */
    showHistory?: boolean
  }>(),
  {
    jobTitle: '',
    scopeText: '',
    compare: null,
    compareLoaded: false,
    sharing: false,
    showHistory: true,
  },
)

const emit = defineEmits<{
  close: []
  'go-history': []
  'export-pdf': []
  share: []
}>()

/** 唯一派生入口：降级判定与维度条都在纯函数里完成，组件不做 `?? 0`。 */
const view = computed(() => buildReportView(props.evals))

function scoreColor(s?: number | null) {
  return getScoreColor(s)
}

function clampPct(v: number) {
  return Math.max(0, Math.min(100, v))
}
</script>

<style scoped>
.report-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  backdrop-filter: blur(2px);
}
.report-modal {
  width: 720px;
  max-width: 100%;
  max-height: 88vh;
  overflow-y: auto;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.25);
  padding: 28px 30px;
  font-family: var(--font-sans);
}
.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.report-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 4px;
  letter-spacing: -0.4px;
}
.report-job {
  font-family: var(--font-title);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.2px;
  color: var(--brand-primary);
  margin: 0 0 4px;
  word-break: break-word;
}
.report-sub {
  font-size: 12.5px;
  color: var(--c-text-tertiary);
  margin: 0;
}
.report-star {
  color: var(--c-accent);
  margin-right: 3px;
}
.report-close {
  border: none;
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
  width: 32px;
  height: 32px;
  border-radius: 999px;
  font-size: 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast), opacity var(--transition-fast);
}
.report-close:hover {
  background: var(--c-border);
  color: var(--c-text);
}
.report-overall {
  display: flex;
  align-items: center;
  gap: 22px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 20px 22px;
  margin-bottom: 18px;
}
.overall-score {
  font-size: 52px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -2px;
  min-width: 96px;
  text-align: center;
}
.overall-unit {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  margin-left: 2px;
}
.overall-summary {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--c-text-secondary);
}
/* 降级提示：中性底色，强调「未记录」而非「得分为 0」 */
.report-degraded-note {
  margin: 0 0 18px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-left: 3px solid var(--c-border);
  border-radius: var(--radius-sm);
}
.report-dims {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.report-dim {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  padding: 14px;
}
.dim-bar {
  height: 6px;
  background: var(--brand-primary-50);
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 10px;
}
.dim-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.6s ease;
}
.dim-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.dim-name {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  font-weight: 500;
}
.dim-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}
.report-block {
  margin-bottom: 20px;
}
.report-block-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 10px;
}
.compare-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  line-height: 1.6;
  margin-bottom: 8px;
}
.compare-badge {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
  color: #fff;
}
.compare-badge.improved { background: var(--score-excellent); }
.compare-badge.steady { background: var(--score-good); }
.compare-badge.declined { background: var(--score-pass); }
.compare-badge.unknown { background: var(--c-text-tertiary); }
.compare-text {
  font-size: 13px;
  color: var(--c-text-secondary);
}
.compare-target {
  font-size: 13px;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  line-height: 1.6;
}
.report-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
  padding-right: 4px;
}
.report-question {
  background: var(--c-bg-alt);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
}
.rq-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.rq-index {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.rq-cat {
  font-size: 12px;
  color: var(--c-text-tertiary);
}
.rq-score {
  margin-left: auto;
  font-size: 13px;
  font-weight: 700;
}
.rq-text {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}
.report-improve {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.report-improve li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--c-accent-soft);
  border-left: 3px solid var(--c-accent);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}
.imp-times {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-warning);
}
.report-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 6px;
  flex-wrap: wrap;
}
/* 过渡类：面板被父级 <Transition name="report-fade"> 包裹，
   根元素带本组件的 scope 属性，故此处 scoped 规则可命中 */
.report-fade-enter-active,
.report-fade-leave-active {
  transition: opacity 0.25s ease;
}
.report-fade-enter-from,
.report-fade-leave-to {
  opacity: 0;
}
@media (max-width: 640px) {
  .report-modal {
    padding: 20px;
  }
  .report-overall {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .report-dims {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>

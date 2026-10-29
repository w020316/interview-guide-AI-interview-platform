<template>
  <section class="insights-card">
    <div class="ins-head">
      <h2 class="ins-title">漏斗洞察</h2>
      <span class="ins-hint">每一步的转化率都以「上一步的人数」为分母，看得到流失发生在哪一步</span>
    </div>

    <div class="ins-grid">
      <div class="ins-funnel">
        <div v-for="s in insights.stages" :key="s.id" class="ins-stage">
          <div class="ins-stage-head">
            <span class="ins-stage-label">{{ s.label }}</span>
            <span class="ins-stage-count">{{ s.count }}</span>
            <span class="ins-stage-rate" :class="{ 'is-na': s.conversionFromPrev == null }">
              {{ formatRate(s.conversionFromPrev) }}
            </span>
          </div>
          <div class="ins-bar" aria-hidden="true">
            <span class="ins-bar-fill" :style="{ width: `${barPct(s.count)}%` }"></span>
          </div>
        </div>
      </div>

      <div class="ins-side">
        <div class="ins-metric">
          <div class="ins-metric-label">投出 → 拿 Offer</div>
          <div class="ins-metric-value" :class="{ 'is-na': overallRate == null }">{{ formatRate(overallRate) }}</div>
        </div>
        <div class="ins-metric">
          <div class="ins-metric-label">平均首次回音</div>
          <div class="ins-metric-value" :class="{ 'is-na': insights.response.avgDays == null }">
            {{ formatDays(insights.response.avgDays) }}
          </div>
          <div v-if="insights.response.sampleSize > 0" class="ins-metric-hint">
            中位数 {{ formatDays(insights.response.medianDays) }} · 样本 {{ insights.response.sampleSize }} 条
          </div>
        </div>
        <div class="ins-metric">
          <div class="ins-metric-label">静默投递</div>
          <div class="ins-metric-value">{{ insights.silentCount }} 条</div>
          <div class="ins-metric-hint">已投出超过 7 天仍无回音</div>
        </div>
      </div>
    </div>

    <p v-if="drop" class="ins-drop">
      流失最多的一步：<b>{{ drop.from.label }} → {{ drop.to.label }}</b>
      （{{ drop.from.count }} → {{ drop.to.count }}，少 {{ drop.lost }} 条）
    </p>

    <p v-if="insights.response.sampleSize === 0" class="ins-note">
      还没有可用的回音时长数据：首次回音时间从本版本起开始记录，此前的投递不回填——「最近一次回复」不等于「第一次回复」。
    </p>
  </section>
</template>

<script setup lang="ts">
/**
 * 投递漏斗洞察卡片（v1.86.0）
 *
 * 补上「统计卡片 + 一行漏斗」看不到的三件事：
 *   1. **流失在哪一步**（逐段转化率 + 流失人数最多的一步）；
 *   2. **对方多久回你**（平均/中位数，附样本量）；
 *   3. **有多少投递石沉大海**（超过 7 天无回音）。
 *
 * ── 显示纪律 ────────────────────────────────────────────────
 * · 转化率用 `formatRate`：后端给 `null`（分母为 0）就显示 `—`，绝不写成 `0%`；
 * · 回音时长没有样本就显示 `—` 并附一句说明，而不是「0 天」；
 * · 流失提示只在样本 ≥ {@link MIN_SAMPLE_FOR_DROP} 条时出现 —— 3 条投递里「流失 2 条」
 *   说明不了任何事，只会让用户误以为流程有系统性问题。
 */
import { computed } from 'vue'
import { formatRate } from '../utils/channelStats'
import { biggestDrop, formatDays, type ApplicationInsights } from '../utils/applicationInsights'

const props = defineProps<{ insights: ApplicationInsights }>()

/** 少于这个投出量时不给「流失最多的一步」—— 小样本的百分比全是噪声 */
const MIN_SAMPLE_FOR_DROP = 3

const submitted = computed(() => props.insights.stages[0]?.count ?? 0)

const overallRate = computed<number | null>(() => {
  const stages = props.insights.stages
  return stages.length ? stages[stages.length - 1].conversionFromSubmitted : null
})

const drop = computed(() =>
  submitted.value >= MIN_SAMPLE_FOR_DROP ? biggestDrop(props.insights.stages) : null,
)

/** 条宽 = 本段人数 / 已投出；已投出为 0 时整条为 0（没有数据就没有长度） */
function barPct(count: number): number {
  if (submitted.value <= 0) return 0
  return Math.round((count / submitted.value) * 100)
}
</script>

<style scoped>
.insights-card {
  /* 与看板其它区块同款卡片外观（各视图的 `.card` 都是 scoped，组件不能借用，故自带一份） */
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 18px 20px;
  margin-bottom: 16px;
}

.ins-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.ins-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--c-text);
}

.ins-hint {
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

.ins-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-lg);
}

.ins-funnel {
  flex: 1 1 320px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.ins-stage-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  font-size: var(--font-size-sm);
}

.ins-stage-label {
  color: var(--c-text-secondary);
}

.ins-stage-count {
  font-weight: 700;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.ins-stage-rate {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.ins-stage-rate.is-na {
  color: var(--c-text-quaternary);
}

.ins-bar {
  margin-top: 4px;
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--c-bg-alt);
  overflow: hidden;
}

.ins-bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--radius-xs);
  background: var(--brand-primary);
}

.ins-side {
  flex: 0 1 220px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.ins-metric-label {
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

.ins-metric-value {
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.ins-metric-value.is-na {
  color: var(--c-text-quaternary);
}

.ins-metric-hint {
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--c-text-quaternary);
}

.ins-drop {
  margin: var(--space-md) 0 0;
  font-size: var(--font-size-sm);
  color: var(--c-text-secondary);
}

.ins-drop b {
  color: var(--c-text);
}

.ins-note {
  margin: var(--space-sm) 0 0;
  font-size: var(--font-size-xs);
  line-height: 1.7;
  color: var(--c-text-quaternary);
}

@media (max-width: 768px) {
  .insights-card {
    padding: var(--space-md);
  }

  .ins-side {
    flex: 1 1 100%;
    flex-direction: row;
    flex-wrap: wrap;
    gap: var(--space-lg);
  }

  .ins-metric {
    flex: 1 1 100px;
  }
}
</style>

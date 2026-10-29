<template>
  <div class="salary-page">
    <header class="page-header">
      <h1>谈薪助手</h1>
      <p>
        把「该报多少、offer 值不值、被压价怎么回」拆成能照着做的下一步。金额只按你自己填的数字换算，
        没填就留空 —— 不编造任何市场行情。
      </p>
    </header>

    <!-- ── 输入 ── -->
    <section class="card">
      <div class="mode-tabs" role="tablist" aria-label="谈薪场景">
        <button
          v-for="m in SALARY_MODES"
          :key="m.value"
          type="button"
          role="tab"
          class="mode-tab"
          :class="{ 'is-active': form.mode === m.value }"
          :aria-selected="form.mode === m.value"
          @click="setMode(m.value)"
        >
          <span class="mode-label">{{ m.label }}</span>
          <span class="mode-hint">{{ m.hint }}</span>
        </button>
      </div>

      <div class="field">
        <label class="label" for="salary-job">目标岗位</label>
        <input
          id="salary-job"
          v-model="form.targetJob"
          class="input"
          maxlength="1200"
          placeholder="例：Java 后端开发（可以把 JD 要点一起贴上）"
        />
      </div>

      <div class="field-grid">
        <div class="field">
          <label class="label" for="salary-city">城市（可选）</label>
          <input id="salary-city" v-model="form.city" class="input" maxlength="50" placeholder="例：杭州" />
        </div>
        <div class="field">
          <label class="label" for="salary-years">工作年限（可选）</label>
          <input id="salary-years" v-model="form.years" class="input" inputmode="numeric" placeholder="例：3" />
        </div>
      </div>

      <div class="field">
        <div class="salary-head">
          <span class="label">薪资数字（都按「税前，元/月」，不填就是空）</span>
          <span class="field-note">{{ currentMode.fieldNote }}</span>
        </div>
        <div class="field-grid">
          <div class="field">
            <label class="label label-sm" for="salary-current">当前月薪</label>
            <input id="salary-current" v-model="form.currentMonthly" class="input" inputmode="numeric" placeholder="例：18000" />
          </div>
          <div class="field">
            <label class="label label-sm" for="salary-expected">期望月薪</label>
            <input id="salary-expected" v-model="form.expectedMonthly" class="input" inputmode="numeric" placeholder="例：25000" />
          </div>
          <div class="field">
            <label class="label label-sm" for="salary-offer">offer 月薪</label>
            <input id="salary-offer" v-model="form.offerMonthly" class="input" inputmode="numeric" placeholder="例：22000" />
          </div>
          <div class="field">
            <label class="label label-sm" for="salary-months">年薪月数</label>
            <input id="salary-months" v-model="form.offerMonths" class="input" inputmode="numeric" placeholder="例：15（即 15 薪）" />
          </div>
        </div>
      </div>

      <div class="field">
        <label class="label" for="salary-competing">其他 offer / 竞争情况（可选）</label>
        <input
          id="salary-competing"
          v-model="form.competing"
          class="input"
          maxlength="300"
          placeholder="例：还有一家在终面，HR 说本周给结果"
        />
      </div>

      <div class="field">
        <label class="label" for="salary-situation">补充情况（可选）</label>
        <textarea
          id="salary-situation"
          v-model="form.situation"
          class="input ta"
          rows="3"
          maxlength="1000"
          placeholder="例：HR 说 22k 是上限，让我当场答复；现在是 18k，13 薪"
        ></textarea>
      </div>

      <div class="actions">
        <BaseButton variant="gradient" :disabled="generating || !form.targetJob.trim()" @click="doCoach">
          {{ generating ? '正在生成…' : '生成谈薪建议' }}
        </BaseButton>
        <span class="hint">AI 生成，首次调用可能因后端冷启动需要 1-2 分钟；它不掌握实时行情，也不会替你决定接或不接。</span>
      </div>
    </section>

    <!-- ── 结果 ── -->
    <section v-if="report" class="card fade-in-up">
      <div class="headline">
        <div class="label">一句话结论</div>
        <p class="headline-text">{{ report.headline }}</p>
      </div>

      <div v-if="hasNumbers" class="numbers">
        <div class="num-card">
          <span class="num-label">涨幅（月薪）</span>
          <span class="num-value" :class="toneOf(report.numbers.raiseAmount)">
            {{ formatSignedMoney(report.numbers.raiseAmount) }}
          </span>
          <span v-if="report.numbers.raisePct !== null" class="num-note">
            相对当前月薪 {{ formatPercent(report.numbers.raisePct) }}
          </span>
        </div>
        <div class="num-card">
          <span class="num-label">offer 年薪</span>
          <span class="num-value">{{ formatMoney(report.numbers.offerAnnual) }}</span>
          <span v-if="report.numbers.offerAnnual !== null" class="num-note">月薪 × 薪数，税前</span>
        </div>
        <div class="num-card">
          <span class="num-label">与期望差（月薪）</span>
          <span class="num-value" :class="toneOf(report.numbers.expectedGapMonthly)">
            {{ formatSignedMoney(report.numbers.expectedGapMonthly) }}
          </span>
          <span v-if="report.numbers.expectedGapPct !== null" class="num-note">
            相对期望月薪 {{ formatPercent(report.numbers.expectedGapPct) }}
          </span>
        </div>
      </div>
      <p v-else class="numbers-hint">
        填上当前月薪 / offer 月薪 / 期望月薪 / 年薪月数后，这里会算出涨幅与年薪折算 —— 缺失的数字不会被当成 0。
      </p>

      <div v-if="report.warnings.length" class="warn-block">
        <div class="warn-title">要先核对清楚的风险</div>
        <ul class="plain-list">
          <li v-for="(w, i) in report.warnings" :key="i">{{ w }}</li>
        </ul>
      </div>

      <div v-if="report.analysis.length" class="block">
        <div class="block-title">判断与依据</div>
        <div v-for="(a, i) in report.analysis" :key="i" class="analysis-row">
          <div class="analysis-point">{{ a.point }}</div>
          <div v-if="a.basis" class="analysis-basis">依据：{{ a.basis }}</div>
        </div>
      </div>

      <div v-if="report.scripts.length" class="block">
        <div class="block-title">照着说就行</div>
        <div v-for="(s, i) in report.scripts" :key="i" class="script-card">
          <div v-if="s.scene" class="script-scene">{{ s.scene }}</div>
          <p v-if="s.say" class="script-say">{{ s.say }}</p>
        </div>
      </div>

      <div v-if="report.qa.length" class="block">
        <div class="block-title">对方这样说，你可以这样回</div>
        <div v-for="(item, i) in report.qa" :key="i" class="qa-row">
          <p v-if="item.q" class="qa-q">{{ item.q }}</p>
          <p v-if="item.a" class="qa-a">{{ item.a }}</p>
        </div>
      </div>

      <div v-if="report.actions.length" class="block">
        <div class="block-title">下一步动作</div>
        <ol class="plain-list">
          <li v-for="(a, i) in report.actions" :key="i">{{ a }}</li>
        </ol>
      </div>

      <p class="disclaimer">
        建议基于你填写的信息与你自己的判断。AI 不掌握任何实时薪资行情；接或不接、让不让步，都由你决定。
      </p>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 谈薪助手（v1.85.0）
 *
 * 三种场景共用一套输入与结果结构（EXPECTATION / OFFER / NEGOTIATION），
 * 只有场景侧重不同 —— 因此用 Tab 切换而不是三个独立表单，用户不必重复填写。
 *
 * 两个刻意的产品取舍：
 * 1. **金额在服务端算**：前端只负责展示服务端返回的 numbers，缺数据就是 null 显示「—」，
 *    绝不在这里补 0（「涨幅 0%」看起来像事实，实际是假信息）。
 * 2. **切换场景即清空上一份结果**：不同场景的建议不可混用，留着会误导。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton } from '../components'
import {
  SALARY_MODES,
  buildSalaryPayload,
  emptySalaryForm,
  formatMoney,
  formatPercent,
  formatSignedMoney,
  hasSalaryNumbers,
  modeMeta,
  normalizeSalaryReport,
  type SalaryForm,
  type SalaryMode,
  type SalaryReport,
} from '../utils/salary'

const form = reactive<SalaryForm>(emptySalaryForm())
const generating = ref(false)
const report = ref<SalaryReport | null>(null)

const currentMode = computed(() => modeMeta(form.mode))
const hasNumbers = computed(() => (report.value ? hasSalaryNumbers(report.value.numbers) : false))

function setMode(mode: SalaryMode): void {
  if (form.mode === mode) return
  form.mode = mode
  report.value = null
}

/** 金额配色：只有「有数字」才谈得上涨/跌；null 不加类，保持中性 */
function toneOf(v: number | null): string {
  if (v === null || v === 0) return ''
  return v > 0 ? 'is-up' : 'is-down'
}

async function doCoach(): Promise<void> {
  const { payload, error } = buildSalaryPayload(form)
  if (error) {
    ElMessage.warning(error)
    return
  }
  generating.value = true
  try {
    const res = await api.post('/api/career/salary', payload)
    const normalized = normalizeSalaryReport(res)
    if (!normalized) {
      ElMessage.warning('没有生成有效建议，可以把情况写得更具体后重试一次')
      return
    }
    report.value = normalized
    ElMessage.success('建议已生成')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成失败，请稍后重试'))
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.salary-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.page-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
  line-height: 1.7;
}

.card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px 22px;
  margin-bottom: 16px;
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 18px;
}

.mode-tab {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  background: var(--c-bg);
  color: var(--c-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--transition-fast), background-color var(--transition-fast);
}

.mode-tab:hover {
  border-color: var(--brand-primary);
}

.mode-tab.is-active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.mode-label {
  font-size: 14px;
  font-weight: 600;
}

.mode-hint {
  font-size: 12px;
  color: var(--c-text-tertiary);
  line-height: 1.5;
}

.field {
  margin-bottom: 14px;
  min-width: 0;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 0 12px;
}

.salary-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 12px;
}

.field-note {
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-accent);
  margin-bottom: 8px;
}

.label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  margin-bottom: 8px;
  letter-spacing: 0.3px;
}

.label-sm {
  font-weight: 500;
}

.input {
  width: 100%;
  padding: 9px 12px;
  font-size: 14px;
  color: var(--c-text);
  background: var(--c-bg);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
  box-sizing: border-box;
}

.input:focus {
  outline: none;
  border-color: var(--brand-primary);
}

.input.ta {
  resize: vertical;
  min-height: 72px;
  font-family: inherit;
  line-height: 1.7;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.hint {
  flex: 1;
  min-width: 220px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-quaternary);
}

.headline {
  background: var(--brand-primary-light);
  border-left: 3px solid var(--brand-primary);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  margin-bottom: 16px;
}

.headline-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: var(--c-text);
}

.numbers {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}

.num-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  background: var(--c-bg-soft);
}

.num-label {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.num-value {
  font-family: var(--font-mono);
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
}

.num-value.is-up {
  color: var(--c-success);
}

.num-value.is-down {
  color: var(--c-danger);
}

.num-note {
  font-size: 12px;
  color: var(--c-text-quaternary);
}

.numbers-hint {
  margin: 0 0 18px;
  padding: 10px 12px;
  border: 1px dashed var(--c-border-light);
  border-radius: var(--radius-md);
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-tertiary);
}

.warn-block {
  background: var(--c-warning-light);
  border: 1px solid var(--c-warning-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  margin-bottom: 18px;
}

.warn-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--c-warning);
  margin-bottom: 6px;
}

.block {
  margin-bottom: 18px;
}

.block-title {
  font-family: var(--font-title);
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
  margin-bottom: 10px;
}

.plain-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--c-text-secondary);
}

.analysis-row {
  padding: 10px 0;
  border-bottom: 1px dashed var(--c-border-light);
}

.analysis-row:last-child {
  border-bottom: none;
}

.analysis-point {
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text);
}

.analysis-basis {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.script-card {
  padding: 12px 14px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  background: var(--c-bg-soft);
  margin-bottom: 10px;
}

.script-card:last-child {
  margin-bottom: 0;
}

.script-scene {
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-primary);
  margin-bottom: 6px;
}

.script-say {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: var(--c-text);
}

.qa-row {
  padding: 10px 0;
  border-bottom: 1px dashed var(--c-border-light);
}

.qa-row:last-child {
  border-bottom: none;
}

.qa-q {
  margin: 0 0 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-tertiary);
}

.qa-a {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text);
}

.disclaimer {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-quaternary);
}

@media (max-width: 768px) {
  .mode-tabs {
    grid-template-columns: 1fr;
  }

  .card {
    padding: 16px;
  }
}
</style>

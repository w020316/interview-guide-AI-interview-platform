<template>
  <div class="career-page">
    <header class="page-header">
      <h1>求职诊断</h1>
      <p>求职的第一步不是写简历，而是挖出自己的长处与特质 —— 用「证据 → 行为 → 能力 → 可投岗位信号」四层结构，把说不清的经历变成可投递的能力</p>
    </header>

    <!-- ── 第一步：经历挖掘 ── -->
    <section class="card">
      <div class="step-head">
        <span class="step-no">1</span>
        <div>
          <div class="step-title">讲讲你真实做过的事</div>
          <div class="step-sub">越具体越好：你遇到什么问题、做了什么、结果怎样、有没有数字。口语化就行，不用写成简历。</div>
        </div>
      </div>
      <textarea
        v-model="narrative"
        class="input ta"
        rows="7"
        placeholder="例：我在学校做过一个二手交易平台，一开始没人用，后来我发现是搜索太慢，就把 MySQL 的模糊查询改成了 ES，还加了缓存，日活从 30 涨到 200 多。另外我还带过 3 个人的小组做课程设计……"
      ></textarea>
      <div class="row">
        <input v-model="targetTrack" class="input" placeholder="期望方向（可选），如：Java 后端 / 数据分析 / 产品" />
        <BaseButton variant="gradient" :disabled="mining || !narrative.trim()" @click="doMine">
          {{ mining ? '正在挖掘…' : '挖掘我的职业资产' }}
        </BaseButton>
      </div>
      <div class="hint">提示：这是 AI 分析，首次调用可能因后端冷启动需要 1-2 分钟，请耐心等待。</div>
    </section>

    <!-- 挖掘结果 -->
    <section v-if="mineResult" class="card fade-in-up">
      <div class="result-head">
        <h2>职业资产分析</h2>
        <button v-if="mineResult.assets?.length" class="link-btn" @click="useMineForPlan">用这份分析生成求职节奏计划 →</button>
      </div>

      <div v-if="mineResult.positioning" class="positioning">
        <div class="label">职业定位</div>
        <div class="positioning-text">{{ mineResult.positioning }}</div>
      </div>

      <div v-if="mineResult.assets?.length" class="assets">
        <div class="label">职业资产（{{ mineResult.assets.length }} 条）</div>
        <div v-for="(a, i) in mineResult.assets" :key="i" class="asset">
          <div class="asset-head">资产 {{ i + 1 }}</div>
          <div class="layer">
            <span class="layer-tag l1">证据</span><span class="layer-text">{{ a.evidence }}</span>
          </div>
          <div class="layer">
            <span class="layer-tag l2">行为</span><span class="layer-text">{{ a.behavior }}</span>
          </div>
          <div class="layer">
            <span class="layer-tag l3">能力</span><span class="layer-text">{{ a.capability }}</span>
          </div>
          <div class="layer">
            <span class="layer-tag l4">岗位信号</span>
            <span class="layer-text">
              <BaseTag v-for="s in a.jobSignals || []" :key="s" variant="success" size="sm">{{ s }}</BaseTag>
            </span>
          </div>
        </div>
      </div>

      <div class="two-col">
        <div v-if="mineResult.strengthTags?.length" class="mini-block">
          <div class="label">优势标签</div>
          <div class="tags"><BaseTag v-for="t in mineResult.strengthTags" :key="t" variant="info" size="sm">{{ t }}</BaseTag></div>
        </div>
        <div v-if="mineResult.blindSpots?.length" class="mini-block">
          <div class="label">信息缺口（建议补充）</div>
          <ul class="plain-list"><li v-for="t in mineResult.blindSpots" :key="t">{{ t }}</li></ul>
        </div>
      </div>

      <div v-if="mineResult.nextSteps?.length" class="mini-block">
        <div class="label">下一步行动</div>
        <ol class="plain-list"><li v-for="t in mineResult.nextSteps" :key="t">{{ t }}</li></ol>
      </div>

      <div v-if="mineResult.raw" class="warn-block">AI 返回内容未能解析为结构化结果，已原样展示。可重试一次。</div>
    </section>

    <!-- ── 第二步：岗位节奏计划 ── -->
    <section class="card">
      <div class="step-head">
        <span class="step-no">2</span>
        <div>
          <div class="step-title">有节奏地找岗位并投递</div>
          <div class="step-sub">给出目标岗位，得到「为什么适合 / 差距在哪 / 30 天补什么 case / 适合什么赛道」。</div>
        </div>
      </div>
      <textarea v-model="targetJob" class="input ta" rows="3" placeholder="目标岗位，可粘贴 JD，例：Java 后端开发（要求熟悉 Spring Boot、MySQL、Redis，有分布式经验优先）"></textarea>
      <textarea v-model="resumeText" class="input ta" rows="4" placeholder="你的简历要点 / 项目经历（可与上面一致）"></textarea>
      <div class="row">
        <BaseButton variant="gradient" :disabled="planning || !targetJob.trim()" @click="doPlan">
          {{ planning ? '正在生成…' : '生成 30 天求职节奏计划' }}
        </BaseButton>
      </div>
    </section>

    <!-- 计划结果 -->
    <section v-if="planResult" class="card fade-in-up">
      <h2>求职节奏计划</h2>

      <div v-if="planResult.whyFit" class="positioning">
        <div class="label">为什么适合</div>
        <div class="positioning-text">{{ planResult.whyFit }}</div>
      </div>

      <div v-if="planResult.gaps?.length" class="mini-block">
        <div class="label">差距分析</div>
        <div v-for="(g, i) in planResult.gaps" :key="i" class="gap-row">
          <BaseTag :variant="gapVariant(g.level)" size="sm">{{ g.level || 'MEDIUM' }}</BaseTag>
          <span class="gap-item">{{ g.item }}</span>
          <span class="gap-action">→ {{ g.action }}</span>
        </div>
      </div>

      <div v-if="planResult.thirtyDayPlan?.length" class="mini-block">
        <div class="label">30 天计划</div>
        <div class="week-grid">
          <div v-for="(w, i) in planResult.thirtyDayPlan" :key="i" class="week-card">
            <div class="week-title">{{ w.week || ('第 ' + (i + 1) + ' 周') }}</div>
            <div class="week-focus">{{ w.focus }}</div>
            <ul class="plain-list tight"><li v-for="t in w.tasks || []" :key="t">{{ t }}</li></ul>
            <div v-if="w.deliverable" class="week-deliverable">产出：{{ w.deliverable }}</div>
          </div>
        </div>
      </div>

      <div v-if="planResult.tracks?.length" class="mini-block">
        <div class="label">适合的赛道</div>
        <div v-for="(t, i) in planResult.tracks" :key="i" class="track-row">
          <div class="track-name">{{ t.track }}</div>
          <div class="track-companies">
            <BaseTag v-for="c in t.companies || []" :key="c" variant="warning" size="sm">{{ c }}</BaseTag>
          </div>
          <div class="track-reason">{{ t.reason }}</div>
        </div>
      </div>

      <div v-if="planResult.cadence?.length" class="mini-block">
        <div class="label">投递节奏</div>
        <ul class="plain-list"><li v-for="c in planResult.cadence" :key="c">{{ c }}</li></ul>
      </div>

      <!-- 岗位假设卡：不是推荐岗位，而是可证伪的假设（低于 60 分进观察池） -->
      <div v-if="planResult.applyAdvice" class="apply-advice">
        <div class="advice-head">
          <div class="label" style="margin-bottom: 0">岗位假设卡</div>
          <span class="advice-score" :class="adviceClass(planResult.applyAdvice)">
            {{ planResult.applyAdvice.score ?? 0 }} 分
          </span>
          <BaseTag :variant="planResult.applyAdvice.verdict === 'APPLY' ? 'success' : 'warning'" size="sm">
            {{ planResult.applyAdvice.verdict === 'APPLY' ? '建议投递' : '观察池' }}
          </BaseTag>
        </div>
        <div v-if="planResult.applyAdvice.matchedEvidence?.length" class="advice-section">
          <span class="advice-label">匹配证据：</span>
          <ul class="plain-list tight"><li v-for="e in planResult.applyAdvice.matchedEvidence" :key="e">{{ e }}</li></ul>
        </div>
        <div v-if="planResult.applyAdvice.reason" class="advice-section">
          <span class="advice-label">结论：</span>{{ planResult.applyAdvice.reason }}
        </div>
        <div class="advice-note">低于 60 分不建议投递，只进入观察池；60-74 分先补齐关键差距再投。</div>
      </div>

      <div v-if="planResult.raw" class="warn-block">AI 返回内容未能解析为结构化结果，已原样展示。可重试一次。</div>
    </section>

    <!-- ── 第三步：面试故事库 ── -->
    <section class="card">
      <div class="step-head">
        <span class="step-no">3</span>
        <div>
          <div class="step-title">面试故事库：面试不是背答案，是经得起追问</div>
          <div class="step-sub">把真实经历整理成 STAR 故事存入故事库；练习时「先回答，再追问，再复盘」，并用六项质检自查。</div>
        </div>
      </div>
      <textarea
        v-model="storyNarrative"
        class="input ta"
        rows="5"
        placeholder="把你想练的经历写出来（可与第一步相同）：做了什么、遇到什么问题、结果怎样、有没有数字。AI 将提炼成 STAR 故事候选。"
      ></textarea>
      <div class="row">
        <input v-model="storyTrack" class="input" placeholder="目标岗位/赛道（可选，质检时用于判断「是否贴合岗位」）" />
        <BaseButton variant="gradient" :disabled="extracting || !storyNarrative.trim()" @click="doExtractStories">
          {{ extracting ? '正在提炼…' : '提炼 STAR 故事候选' }}
        </BaseButton>
      </div>
    </section>

    <!-- 提炼候选 -->
    <section v-if="candidates.length" class="card fade-in-up">
      <h2>故事候选（{{ candidates.length }} 条）</h2>
      <div v-for="(c, i) in candidates" :key="i" class="story-card">
        <div class="story-head">
          <div class="story-title">{{ c.title }}</div>
          <BaseButton size="sm" variant="gradient" @click="saveStory(c)">存入故事库</BaseButton>
        </div>
        <div class="story-grid">
          <div class="story-item"><span class="s-tag">情境</span>{{ c.situation || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">任务</span>{{ c.task || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">行动</span>{{ c.action || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">结果</span>{{ c.result || '待补充' }}</div>
          <div v-if="c.evidence" class="story-item"><span class="s-tag">证据</span>{{ c.evidence }}</div>
        </div>
        <div v-if="c.capabilities?.length" class="tags">
          <BaseTag v-for="t in c.capabilities" :key="t" variant="info" size="sm">{{ t }}</BaseTag>
        </div>
      </div>
    </section>

    <!-- 我的故事库 -->
    <section class="card">
      <div class="result-head">
        <h2 style="margin-bottom: 0">我的故事库（{{ stories.length }} 条）</h2>
        <button class="link-btn" @click="loadStories">刷新</button>
      </div>
      <div v-if="!stories.length" class="hint">还没有存入故事。先在上面的输入框提炼故事候选，再点「存入故事库」。</div>
      <div v-for="s in stories" :key="s.id" class="story-card">
        <div class="story-head">
          <div class="story-title">{{ s.title }}</div>
          <div class="story-actions">
            <button class="link-btn" @click="togglePractice(s.id)">{{ practicingId === s.id ? '收起练习' : '练习 / 质检' }}</button>
            <button class="link-btn danger" @click="removeStory(s.id)">删除</button>
          </div>
        </div>
        <div class="story-grid">
          <div class="story-item"><span class="s-tag">情境</span>{{ s.situation || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">任务</span>{{ s.task || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">行动</span>{{ s.action || '待补充' }}</div>
          <div class="story-item"><span class="s-tag">结果</span>{{ s.result || '待补充' }}</div>
        </div>
        <div v-if="s.capabilityTags" class="tags">
          <BaseTag v-for="t in s.capabilityTags.split(',').filter(Boolean)" :key="t" variant="info" size="sm">{{ t.trim() }}</BaseTag>
        </div>

        <!-- 练习面板：先回答，再追问，再复盘 -->
        <div v-if="practicingId === s.id" class="practice">
          <textarea v-model="practiceAnswer" class="input ta" rows="4"
            placeholder="用口语把这段经历讲一遍（就像面试现场回答「介绍一个你最有成就感的项目」），再点「六项质检」或「生成追问」。"></textarea>
          <div class="row">
            <BaseButton size="sm" variant="gradient" :disabled="checking || !practiceAnswer.trim()" @click="doCheck(s)">
              {{ checking ? '质检中…' : '六项质检' }}
            </BaseButton>
            <BaseButton size="sm" :disabled="following || !practiceAnswer.trim()" @click="doFollowUp(s)">
              {{ following ? '生成中…' : '生成追问' }}
            </BaseButton>
          </div>

          <div v-if="checkResult" class="check-result">
            <div class="check-score">质检得分：<b>{{ checkResult.overallScore }}</b> / 100</div>
            <div v-for="item in checkResult.items || []" :key="item.name" class="check-item">
              <BaseTag :variant="item.pass ? 'success' : 'danger'" size="sm">{{ item.pass ? '通过' : '待改进' }}</BaseTag>
              <span class="check-name">{{ item.name }}</span>
              <span class="check-comment">{{ item.comment }}</span>
            </div>
            <div v-if="checkResult.suggestion" class="check-suggestion">复盘建议：{{ checkResult.suggestion }}</div>
          </div>

          <div v-if="followups.length" class="check-result">
            <div class="check-score">追问（逐条回答，检验是否经得起下钻）</div>
            <ol class="plain-list"><li v-for="f in followups" :key="f">{{ f }}</li></ol>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag } from '../components'

interface Asset {
  evidence?: string
  behavior?: string
  capability?: string
  jobSignals?: string[]
}
interface MineResult {
  positioning?: string
  assets?: Asset[]
  strengthTags?: string[]
  blindSpots?: string[]
  nextSteps?: string[]
  raw?: string
}
interface Gap { item?: string; level?: string; action?: string }
interface Week { week?: string; focus?: string; tasks?: string[]; deliverable?: string }
interface Track { track?: string; companies?: string[]; reason?: string }
interface ApplyAdvice {
  score?: number
  matchedEvidence?: string[]
  verdict?: string
  reason?: string
}
interface PlanResult {
  whyFit?: string
  gaps?: Gap[]
  thirtyDayPlan?: Week[]
  tracks?: Track[]
  cadence?: string[]
  applyAdvice?: ApplyAdvice
  raw?: string
}
// ── v1.36.0 面试故事库 ──
interface StoryCandidate {
  title?: string
  situation?: string
  task?: string
  action?: string
  result?: string
  evidence?: string
  capabilities?: string[]
}
interface StoryItem {
  id: number
  title: string
  situation?: string
  task?: string
  action?: string
  result?: string
  evidence?: string
  capabilityTags?: string
  targetTrack?: string
}
interface CheckItem { name?: string; pass?: boolean; comment?: string }
interface CheckResult { items?: CheckItem[]; overallScore?: number; suggestion?: string }

const narrative = ref('')
const targetTrack = ref('')
const mining = ref(false)
const mineResult = ref<MineResult | null>(null)

const targetJob = ref('')
const resumeText = ref('')
const planning = ref(false)
const planResult = ref<PlanResult | null>(null)

// ── v1.36.0 面试故事库状态 ──
const storyNarrative = ref('')
const storyTrack = ref('')
const extracting = ref(false)
const candidates = ref<StoryCandidate[]>([])
const stories = ref<StoryItem[]>([])
const practicingId = ref<number | null>(null)
const practiceAnswer = ref('')
const checking = ref(false)
const following = ref(false)
const checkResult = ref<CheckResult | null>(null)
const followups = ref<string[]>([])

onMounted(() => {
  loadStories()
})

/** 职业资产四层挖掘 */
async function doMine() {
  const n = narrative.value.trim()
  if (!n) return ElMessage.warning('请先描述你的真实经历')
  mining.value = true
  try {
    mineResult.value = (await api.post('/api/career/mine', {
      narrative: n,
      targetTrack: targetTrack.value.trim() || null,
    })) as unknown as MineResult
    if (!mineResult.value?.assets?.length && !mineResult.value?.raw) {
      ElMessage.warning('没有挖到足够具体的证据，试着补充「做了什么、结果是什么、有没有数字」')
    } else {
      ElMessage.success('挖掘完成')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '挖掘失败，请稍后重试'))
  } finally {
    mining.value = false
  }
}

/** 把挖掘结果回填到计划输入，保持两步分析对齐 */
function useMineForPlan() {
  const r = mineResult.value
  if (!r) return
  const lines: string[] = []
  if (r.positioning) lines.push(`定位：${r.positioning}`)
  for (const a of r.assets || []) {
    lines.push(`- 证据：${a.evidence || ''}；行为：${a.behavior || ''}；能力：${a.capability || ''}；岗位信号：${(a.jobSignals || []).join('/')}`)
  }
  resumeText.value = lines.join('\n')
  ElMessage.success('已回填挖掘结果，请填写目标岗位后生成计划')
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
}

/** 岗位节奏计划 */
async function doPlan() {
  const job = targetJob.value.trim()
  if (!job) return ElMessage.warning('请填写目标岗位')
  planning.value = true
  try {
    planResult.value = (await api.post('/api/career/plan', {
      targetJob: job,
      resumeText: resumeText.value.trim(),
      mineSummary: mineResult.value ? JSON.stringify(mineResult.value).slice(0, 1500) : '',
    })) as unknown as PlanResult
    ElMessage.success('计划已生成')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成失败，请稍后重试'))
  } finally {
    planning.value = false
  }
}

function gapVariant(level?: string) {
  if (level === 'HIGH') return 'danger'
  if (level === 'LOW') return 'success'
  return 'warning'
}

// ── v1.36.0 面试故事库 ──

/** 岗位假设卡样式（分数颜色） */
function adviceClass(a: ApplyAdvice) {
  const score = a.score ?? 0
  if (score >= 75 && a.verdict === 'APPLY') return 'good'
  if (score >= 60) return 'mid'
  return 'low'
}

/** 加载我的故事库 */
async function loadStories() {
  try {
    stories.value = (await api.get('/api/story-bank')) as unknown as StoryItem[]
  } catch {
    // 静默：故事库加载失败不打断页面
  }
}

/** 提炼 STAR 故事候选（不入库，由用户确认后保存） */
async function doExtractStories() {
  const n = storyNarrative.value.trim()
  if (!n) return ElMessage.warning('请先描述你的真实经历')
  extracting.value = true
  try {
    const r = (await api.post('/api/story-bank/extract', {
      narrative: n,
      targetTrack: storyTrack.value.trim() || null,
    })) as unknown as { stories?: StoryCandidate[]; raw?: string }
    candidates.value = r.stories || []
    if (!candidates.value.length) {
      ElMessage.warning(r.raw ? 'AI 返回内容未能解析，可重试一次' : '这段描述还提炼不出完整故事，试着补充具体动作和结果')
    } else {
      ElMessage.success(`提炼出 ${candidates.value.length} 条故事候选`)
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '提炼失败，请稍后重试'))
  } finally {
    extracting.value = false
  }
}

/** 把候选存入故事库 */
async function saveStory(c: StoryCandidate) {
  if (!c.title?.trim()) return ElMessage.warning('该候选缺少标题，无法保存')
  try {
    await api.post('/api/story-bank', {
      title: c.title.trim(),
      situation: c.situation || null,
      task: c.task || null,
      action: c.action || null,
      result: c.result || null,
      evidence: c.evidence || null,
      capabilityTags: (c.capabilities || []).join(',') || null,
      targetTrack: storyTrack.value.trim() || null,
    })
    ElMessage.success('已存入故事库')
    candidates.value = candidates.value.filter(x => x !== c)
    loadStories()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '保存失败，请稍后重试'))
  }
}

/** 删除故事 */
async function removeStory(id: number) {
  try {
    await api.delete(`/api/story-bank/${id}`)
    ElMessage.success('已删除')
    if (practicingId.value === id) {
      practicingId.value = null
    }
    loadStories()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '删除失败，请稍后重试'))
  }
}

/** 展开/收起练习面板 */
function togglePractice(id: number) {
  if (practicingId.value === id) {
    practicingId.value = null
    return
  }
  practicingId.value = id
  practiceAnswer.value = ''
  checkResult.value = null
  followups.value = []
}

/** 六项质检：结构/证据/贴合岗位/废话/风险表达/经得起追问 */
async function doCheck(s: StoryItem) {
  const a = practiceAnswer.value.trim()
  if (!a) return ElMessage.warning('请先输入你的模拟回答')
  checking.value = true
  try {
    checkResult.value = (await api.post(`/api/story-bank/${s.id}/check`, { answer: a })) as unknown as CheckResult
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '质检失败，请稍后重试'))
  } finally {
    checking.value = false
  }
}

/** 追问链：先回答，再追问，再复盘 */
async function doFollowUp(s: StoryItem) {
  const a = practiceAnswer.value.trim()
  if (!a) return ElMessage.warning('请先输入你的模拟回答')
  following.value = true
  try {
    const r = (await api.post(`/api/story-bank/${s.id}/followup`, { answer: a })) as unknown as { followups?: string[] }
    followups.value = r.followups || []
    if (!followups.value.length) ElMessage.warning('没有生成追问，可重试一次')
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成追问失败，请稍后重试'))
  } finally {
    following.value = false
  }
}
</script>

<style scoped>
.career-page { max-width: 900px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; line-height: 1.7; }

.card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 20px 22px; margin-bottom: 16px; }
.card h2 { font-family: var(--font-title); font-size: 19px; font-weight: 700; color: var(--c-text); margin: 0 0 16px; }

.step-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 14px; }
.step-no { flex: none; width: 24px; height: 24px; border-radius: 50%; background: var(--brand-primary); color: #fff; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.step-title { font-size: 16px; font-weight: 600; color: var(--c-text); }
.step-sub { font-size: 13px; color: var(--c-text-tertiary); line-height: 1.6; margin-top: 2px; }

.input { width: 100%; padding: 9px 12px; font-size: 14px; color: var(--c-text); background: var(--c-bg); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); transition: border-color var(--transition-fast); box-sizing: border-box; }
.input:focus { outline: none; border-color: var(--brand-primary); }
.input.ta { resize: vertical; min-height: 72px; font-family: inherit; line-height: 1.7; margin-bottom: 10px; }
.row { display: flex; gap: 12px; align-items: center; }
.row .input { flex: 1; }
.hint { font-size: 12px; color: var(--c-text-quaternary); margin-top: 10px; }

.result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.link-btn { background: transparent; border: none; color: var(--brand-primary); font-size: 13px; font-weight: 500; cursor: pointer; padding: 0; }
.link-btn:hover { text-decoration: underline; }

.label { font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); margin-bottom: 8px; letter-spacing: 0.3px; }
.positioning { background: var(--brand-primary-light); border-left: 3px solid var(--brand-primary); border-radius: var(--radius-sm); padding: 12px 14px; margin-bottom: 18px; }
.positioning-text { font-size: 14px; line-height: 1.8; color: var(--c-text-secondary); }

.assets { margin-bottom: 18px; }
.asset { border: 1px solid var(--c-border-light); border-radius: var(--radius-md); padding: 14px 16px; margin-bottom: 10px; background: var(--c-bg-soft); }
.asset-head { font-size: 13px; font-weight: 700; color: var(--brand-primary); margin-bottom: 10px; }
.layer { display: flex; gap: 10px; margin-bottom: 8px; align-items: flex-start; }
.layer:last-child { margin-bottom: 0; }
.layer-tag { flex: none; width: 62px; font-size: 11px; font-weight: 600; padding: 2px 0; text-align: center; border-radius: var(--radius-sm); }
.layer-tag.l1 { background: var(--brand-primary-50); color: var(--brand-primary); }
.layer-tag.l2 { background: var(--brand-primary-100); color: var(--brand-primary); }
.layer-tag.l3 { background: var(--brand-primary-100); color: var(--brand-primary-hover); }
.layer-tag.l4 { background: var(--brand-primary-200); color: var(--brand-primary-active); }
.layer-text { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); display: flex; flex-wrap: wrap; gap: 6px; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 720px) { .two-col { grid-template-columns: 1fr; } }
.mini-block { margin-bottom: 16px; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.plain-list { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; color: var(--c-text-secondary); }
.plain-list.tight { line-height: 1.6; padding-left: 18px; }

.gap-row { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; padding: 8px 0; border-bottom: 1px dashed var(--c-border-light); font-size: 13px; }
.gap-row:last-child { border-bottom: none; }
.gap-item { color: var(--c-text); font-weight: 500; }
.gap-action { color: var(--c-text-tertiary); }

.week-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
@media (max-width: 720px) { .week-grid { grid-template-columns: 1fr; } }
.week-card { border: 1px solid var(--c-border-light); border-radius: var(--radius-md); padding: 12px 14px; background: var(--c-bg-soft); }
.week-title { font-size: 13px; font-weight: 700; color: var(--brand-primary); margin-bottom: 4px; }
.week-focus { font-size: 13px; color: var(--c-text); font-weight: 500; margin-bottom: 6px; }
.week-deliverable { margin-top: 8px; font-size: 12px; color: var(--c-accent); background: var(--c-accent-soft); border-radius: var(--radius-sm); padding: 6px 8px; line-height: 1.6; }

.track-row { border-bottom: 1px dashed var(--c-border-light); padding: 10px 0; }
.track-row:last-child { border-bottom: none; }
.track-name { font-size: 14px; font-weight: 600; color: var(--c-text); margin-bottom: 6px; }
.track-companies { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.track-reason { font-size: 13px; color: var(--c-text-tertiary); line-height: 1.6; }

.warn-block { background: var(--c-warning-light); border: 1px solid var(--c-warning); border-radius: var(--radius-md); padding: 12px 14px; font-size: 13px; color: var(--c-warning); }

/* ── v1.36.0：岗位假设卡 ── */
.apply-advice { background: var(--brand-primary-light); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); padding: 14px 16px; margin-bottom: 16px; }
.advice-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.advice-score { font-family: var(--font-mono); font-size: 22px; font-weight: 700; }
.advice-score.good { color: var(--c-success, #15803d); }
.advice-score.mid { color: #b45309; }
.advice-score.low { color: var(--c-danger, #dc2626); }
.advice-section { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); margin-bottom: 6px; }
.advice-label { font-weight: 600; color: var(--c-text); }
.advice-note { font-size: 12px; color: var(--c-text-quaternary); margin-top: 6px; }

/* ── v1.36.0：面试故事库 ── */
.story-card { border: 1px solid var(--c-border-light); border-radius: var(--radius-md); padding: 14px 16px; margin-bottom: 12px; background: var(--c-bg-soft); }
.story-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.story-title { font-size: 15px; font-weight: 700; color: var(--c-text); }
.story-actions { display: flex; gap: 14px; }
.link-btn.danger { color: var(--c-danger, #dc2626); }
.story-grid { display: grid; grid-template-columns: 1fr; gap: 6px; margin-bottom: 8px; }
.story-item { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); display: flex; gap: 8px; align-items: baseline; }
.s-tag { flex: none; width: 34px; font-size: 11px; font-weight: 600; text-align: center; padding: 1px 0; border-radius: var(--radius-sm); background: var(--brand-primary-50); color: var(--brand-primary); }

.practice { margin-top: 12px; border-top: 1px dashed var(--c-border-light); padding-top: 12px; }
.check-result { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); padding: 12px 14px; margin-top: 10px; }
.check-score { font-size: 13px; font-weight: 600; color: var(--c-text); margin-bottom: 8px; }
.check-score b { color: var(--brand-primary); font-size: 16px; }
.check-item { display: flex; gap: 8px; align-items: baseline; font-size: 13px; padding: 4px 0; flex-wrap: wrap; }
.check-name { font-weight: 600; color: var(--c-text); flex: none; }
.check-comment { color: var(--c-text-tertiary); }
.check-suggestion { font-size: 13px; line-height: 1.7; color: var(--c-text-secondary); background: var(--brand-primary-light); border-radius: var(--radius-sm); padding: 8px 10px; margin-top: 8px; }
</style>

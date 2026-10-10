<template>
  <div class="home">
    <!-- 今日待办（第三批 B）：已登录时置顶；Hero 区整体下移但**不删**
         （未登录用户仍以 Hero 为主，不请求 /api/todo/today） -->
    <TodoPanel
      v-if="loggedIn"
      class="home-todo"
      :data="todoData"
      :loading="todoLoading"
      :error="todoError"
      @retry="loadTodo"
    />

    <!-- Hero 区：左文右卡，不对称编辑式构图；ambient-glow 给平面背景一层极轻的径向光 -->
    <section class="hero ambient-glow">
      <div class="hero-grid">
        <div class="hero-copy">
          <div class="hero-badge fade-in-up">
            <span>求职工作台 · 全时段全行业</span>
          </div>
          <h1 class="hero-title fade-in-up" style="animation-delay: 80ms">
            求职<br />
            <span class="hero-em">一个工作台就够了</span>
          </h1>
          <p class="hero-subtitle fade-in-up" style="animation-delay: 160ms">
            招聘广场聚合 4000+ 岗位，投递看板盯住每一次投递与回复，求职诊断帮你挖出长处——
            从找岗位、投递到模拟面试与复盘，求职全流程都在这里完成。
          </p>
          <div class="hero-actions fade-in-up" style="animation-delay: 240ms">
            <BaseButton variant="primary" size="lg" shadow="sm" hoverable @click="goTo('/jobs')">
              <span>进入招聘广场</span>
              <svg class="arrow-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </BaseButton>
            <BaseButton variant="ghost" size="lg" hoverable @click="goTo('/interview')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              <span>模拟面试</span>
            </BaseButton>
          </div>

          <!-- 首屏求职工具入口（v1.48.0）：把「投递看板 / 求职诊断」等
               此前埋在二级下拉的高频能力提到首屏，并点名 6 个工具 -->
          <nav class="hero-tools fade-in-up" style="animation-delay: 280ms" aria-label="求职工具">
            <span class="hero-tools-label">求职工具</span>
            <ul class="tool-chips">
              <li v-for="t in toolLinks" :key="t.to">
                <router-link class="tool-chip" :to="t.to">{{ t.label }}</router-link>
              </li>
            </ul>
          </nav>

          <!-- Hero 数据展示：等宽琥珀金数字，评分面板质感。
               v-count-up 让数字从 0 滚到目标值（后缀 `+` 保留），只在进入视口时播一次 -->
          <div class="hero-stats fade-in-up" style="animation-delay: 320ms">
            <div class="stat">
              <div class="stat-num num-display" v-count-up>4</div>
              <div class="stat-label">评分维度</div>
            </div>
            <div class="stat-divider"></div>
            <div class="stat">
              <div class="stat-num num-display" v-count-up="1400">4000+</div>
              <div class="stat-label">招聘岗位</div>
            </div>
            <div class="stat-divider"></div>
            <div class="stat">
              <div class="stat-num num-display">∞</div>
              <div class="stat-label">无限次面试</div>
            </div>
          </div>
        </div>

        <!-- 视觉锚点：准备度评分卡 -->
        <div class="hero-visual fade-in-up" style="animation-delay: 200ms" aria-hidden="true">
          <div class="ring"></div>
          <!-- v1.39.0 修复：浮动标签此前相对 .hero-visual（整列宽）定位，
               而卡片只有 300px 且居中，导致 900~1250px 视口下 chip-a 直接压住
               卡片左上角的「本轮准备度」标题（真机截图确认）。
               现在包一层与卡片等宽的 .visual-stage，标签改为相对**卡片**定位，
               任何视口宽度下都只会挂在卡片外侧。 -->
          <div class="visual-stage">
            <div class="visual-card">
              <div class="visual-head">
                <span class="visual-title">本轮准备度<span v-if="mode === 'demo'" class="demo-badge">示例</span></span>
                <span class="visual-ready">{{ readyText }}</span>
              </div>
              <div class="visual-score">
                <div class="score-big num-display">{{ displayScore }}</div>
                <div class="score-meta">{{ scoreMeta }}</div>
              </div>
              <!-- P2-B：维度条只在「有真实数据」或「未登录示例卡」时渲染；
                   已登录但零数据的用户看到 '—' 空态，不再展示虚构分数 -->
              <div v-if="rowsVisible" class="visual-rows">
                <div class="v-row">
                  <span class="v-label">技术匹配</span>
                  <div class="v-bar"><i class="v-fill" :style="{ '--w': dim.tech + '%' }"></i></div>
                  <span class="v-val num-display">{{ dim.tech }}</span>
                </div>
                <div class="v-row">
                  <span class="v-label">表述清晰</span>
                  <div class="v-bar"><i class="v-fill" :style="{ '--w': dim.clarity + '%' }"></i></div>
                  <span class="v-val num-display">{{ dim.clarity }}</span>
                </div>
                <div class="v-row">
                  <span class="v-label">项目含金</span>
                  <div class="v-bar"><i class="v-fill" :style="{ '--w': dim.project + '%' }"></i></div>
                  <span class="v-val num-display">{{ dim.project }}</span>
                </div>
              </div>
              <div class="visual-cta">
                <span class="visual-star">★</span>
                <span>{{ ctaText }}</span>
              </div>
            </div>
            <div class="visual-chip chip-a">面试题 <b>{{ questionChip }}</b></div>
            <div class="visual-chip chip-b">复盘 <b>自动报告</b></div>
          </div>
        </div>
      </div>
    </section>

    <!-- 特性卡片：**不对称编辑式网格** —— 首张为「主卡」占左列整高，另两张在右列上下排列。
         ⚠️ 刻意不用「三等分卡片行」：那是 AI 生成版式最典型的特征（redesign 反模式清单点名项）。 -->
    <section class="section">
      <div class="section-header" v-reveal>
        <h2 class="section-title">三大核心能力</h2>
        <p class="section-subtitle">从简历到面试，全链路 AI 辅助</p>
      </div>
      <div class="features">
        <div v-for="(f, i) in features" :key="f.title"
             class="feature-slot"
             :class="{ 'is-lead': i === 0 }"
             v-reveal="i * 90">
          <BaseCard variant="feature" class="feature-card">
            <div class="feature-icon-wrap">
              <svg class="feature-icon" viewBox="0 0 24 24" fill="none">
                <path :d="f.iconPath" stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
            <ul v-if="f.points?.length" class="feature-points">
              <li v-for="p in f.points" :key="p">{{ p }}</li>
            </ul>
            <div class="feature-tags">
              <BaseTag v-for="t in f.tags" :key="t">{{ t }}</BaseTag>
            </div>
          </BaseCard>
        </div>
      </div>
    </section>

    <!-- 工作流程：编辑式编号（顶部长细线 + 等宽序号），不用填充圆形徽章。
         ⚠️ 序号在这里承载**真实的顺序语义**，故保留；被去掉的是「装饰性圆形数字」
         这种通用版式（redesign 反模式清单点名项）。 -->
    <section class="section">
      <div class="section-header" v-reveal>
        <h2 class="section-title">三步完成面试准备</h2>
        <p class="section-subtitle">简洁流程，快速上手</p>
      </div>
      <div class="steps">
        <div v-for="(s, i) in steps" :key="s.title" class="step" v-reveal="i * 110">
          <div class="step-index">{{ String(i + 1).padStart(2, '0') }}</div>
          <h4>{{ s.title }}</h4>
          <p>{{ s.desc }}</p>
        </div>
      </div>
    </section>

    <!-- CTA 区 -->
    <section class="cta-section" v-reveal>
      <div class="cta-card">
        <div class="cta-content">
          <h2 class="cta-title">准备好开启下一段职业旅程了吗？</h2>
          <p class="cta-desc">免费使用，无需信用卡，从找岗位到模拟面试一站搞定</p>
          <BaseButton variant="cta" size="lg" hoverable @click="goTo('/login')">
            立即开始
            <svg class="arrow-icon" width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </BaseButton>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { isLoggedIn } from '../auth'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseCard, BaseTag } from '../components'
import { vCountUp, vReveal } from '../utils/reveal'
import TodoPanel from '../components/TodoPanel.vue'
import type { TodoTodayResponse } from '../utils/todo'

const router = useRouter()

/** 登录态在进入首页时一次性判定（决定是否挂载今日待办） */
const loggedIn = isLoggedIn()

/* ── 今日待办（第三批 B）：已登录时置顶聚合视图，零 AI ── */
const todoData = ref<TodoTodayResponse | null>(null)
const todoLoading = ref(false)
const todoError = ref<string | null>(null)

async function loadTodo() {
  if (!loggedIn) return
  todoLoading.value = true
  todoError.value = null
  try {
    todoData.value = (await api.get('/api/todo/today', { params: { days: 3 } })) as unknown as TodoTodayResponse
  } catch (e: unknown) {
    todoError.value = getErrMessage(e, '加载今日待办失败，请稍后重试')
  } finally {
    todoLoading.value = false
  }
}

function goTo(path: string) {
  const requiresAuth = ['/resume', '/job', '/jobs', '/applications', '/career', '/interview', '/history', '/profile'].includes(path)
  if (requiresAuth && !isLoggedIn()) {
    router.push({ path: '/login', query: { redirect: path } })
    return
  }
  router.push(path)
}

/** 首屏「求职工具」入口：与 App.vue 主导航/工具下拉保持一致（6 个工具） */
const toolLinks = [
  { label: '智能体', to: '/agent' },
  { label: '求职诊断', to: '/career' },
  { label: '投递看板', to: '/applications' },
  { label: '学习中心', to: '/learning' },
  { label: '历史记录', to: '/history' },
  { label: '知识库', to: '/knowledge' },
]

/* ── P2-B：准备度卡片三态 ──────────────────────────────────────────────
 * demo  : 未登录 / 已登录但数据获取失败 → 保留示例卡并标注「示例」
 * real  : 已登录且有简历评估 → 大数字与维度条用真实数据
 * empty : 已登录但还没有简历评估 → '—' 空态 + 引导文案，不展示虚构分数
 * （缺陷背景：此前对所有访客硬编码 86/78%/92/88/78 与「面试题已就绪」，
 *   对零数据用户是事实性错误。）
 */
interface HomeStats {
  overallScore: number
  dims: { tech: number; clarity: number; project: number } | null
  sessionCount: number
  /** true=有真实简历评估；false=已登录但还没有评估记录（空态） */
  real: boolean
}
const realStats = ref<HomeStats | null>(null)

const mode = computed<'real' | 'empty' | 'demo'>(() => {
  if (realStats.value === null) return 'demo'
  return realStats.value.real ? 'real' : 'empty'
})

const DEMO_DIMS = { tech: 92, clarity: 88, project: 78 }

const dim = computed(() => {
  if (mode.value === 'real' && realStats.value?.dims) return realStats.value.dims
  return DEMO_DIMS
})

const rowsVisible = computed(() => mode.value === 'demo' || (mode.value === 'real' && !!realStats.value?.dims))

const displayScore = computed(() => {
  if (mode.value === 'real') return realStats.value?.overallScore ?? '—'
  if (mode.value === 'empty') return '—'
  return '86'
})

const readyText = computed(() =>
  mode.value === 'real' ? '已就绪' : mode.value === 'empty' ? '未开始' : '示例')

const scoreMeta = computed(() => {
  if (mode.value === 'real') return '最新简历评估 · 真实数据'
  if (mode.value === 'empty') return '完成简历分析后生成'
  return '综合评估 · 击败 78% 求职者'
})

const ctaText = computed(() =>
  mode.value === 'empty' ? '上传简历，开启真实评估' : '双向奔赴的岗位在等你')

const questionChip = computed(() => {
  if (mode.value === 'demo') return 'AI 生成'
  return realStats.value && realStats.value.sessionCount > 0
    ? `已练 ${realStats.value.sessionCount} 场`
    : '待开启'
})

onMounted(async () => {
  // 未登录不做真实数据请求：直接保留示例卡（带「示例」标注）
  if (!isLoggedIn()) return
  // 今日待办与首页统计并行拉取（互不阻塞）
  void loadTodo()
  try {
    const resumes = (await api.get('/api/resume/history')) as unknown as Array<{
      overallScore?: number | null
      analysisResult?: string
    }>
    const latest = (resumes || []).find((r) => r && r.overallScore != null)
    if (!latest) {
      realStats.value = { overallScore: 0, dims: null, sessionCount: 0, real: false }
      return
    }
    // 维度分在 analysisResult 的 AI JSON 里，容错解析；解析不出则不渲染维度条
    let dims: HomeStats['dims'] = null
    if (latest.analysisResult) {
      try {
        const obj = JSON.parse(latest.analysisResult) as {
          dimensions?: Array<{ name?: unknown; score?: unknown }>
        }
        const list = Array.isArray(obj.dimensions) ? obj.dimensions : []
        const pick = (kw: string) => list.find((d) => String(d?.name ?? '').includes(kw))
        const num = (d: { score?: unknown } | undefined) => {
          const n = typeof d?.score === 'number' ? d.score : Number(d?.score)
          return Number.isFinite(n as number) ? (n as number) : null
        }
        const tech = pick('技术')
        const clarity = pick('表述')
        const project = pick('项目')
        const t = num(tech)
        const c = num(clarity)
        const p = num(project)
        if (t != null && c != null && p != null) dims = { tech: t, clarity: c, project: p }
      } catch {
        // AI JSON 不可解析：维度条按不可用处理，不影响大数字的真实性
      }
    }
    let sessionCount = 0
    try {
      const sessions = (await api.get('/api/session/list')) as unknown as unknown[]
      sessionCount = Array.isArray(sessions) ? sessions.length : 0
    } catch {
      // 会话数取不到不影响主指标；chip 显示「待开启」也不构成事实错误
    }
    realStats.value = {
      overallScore: Number(latest.overallScore),
      dims,
      sessionCount,
      real: true,
    }
  } catch {
    // 冷启动/网络失败：回退示例卡（带「示例」标注），不阻塞首屏
  }
})

/** 首页特性卡数据。`points` 只有主卡用：它跨两行，需要真实内容撑起高度 */
interface FeatureCard {
  title: string
  desc: string
  tags: string[]
  iconPath: string
  points?: string[]
}

const features: FeatureCard[] = [
  {
    title: '简历智能分析',
    desc: 'AI 从技术匹配度、项目含金量、表述清晰度等四个维度评分，给出可执行的改进建议',
    tags: ['PDF 解析', '多维度评分', '改进建议'],
    iconPath: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M9 13h6 M9 17h6 M9 9h1',
    // 主卡（跨两行）需要真实内容撑起高度 —— 不用装饰元素填空。
    // 三条都对应实现里的事实：4 维度评分（hero 数据条同源）、多格式上传、
    // v1.46.0 的截图视觉识别通道。
    points: [
      '技术匹配、项目含金量、表述清晰度等 4 个维度逐条打分',
      '支持 PDF / HTML / Markdown / TXT 与纯文本粘贴',
      '也可以直接传截图，由视觉模型识别成文字',
    ],
  },
  {
    title: '岗位深度分析',
    desc: '拆解 JD 核心职责、硬技能、软技能、隐性条件，诊断简历匹配度，一键生成求职信',
    tags: ['JD 拆解', '差距诊断', '求职信生成'],
    iconPath: 'M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  },
  {
    title: '个性化面试题',
    desc: '根据简历内容与目标岗位生成定制化面试题，覆盖基础、框架、数据库、中间件等方向',
    tags: ['岗位匹配', '难度分级', '参考答案'],
    iconPath: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z M8 10h.01 M12 10h.01 M16 10h.01',
  },
]

const steps = [
  { title: '上传简历', desc: '粘贴文本或上传 PDF/HTML/MD/TXT 简历，支持多格式' },
  { title: '岗位分析', desc: '拆解 JD 要求，诊断简历匹配度，生成求职信' },
  { title: '模拟面试', desc: '答题获得实时流式提示与自动评估' },
]
</script>

<style scoped>
.home {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
}

/* ── Hero：左文右卡不对称网格 ── */
.hero {
  position: relative;
  padding: 64px 0 48px;
  overflow: hidden;
}

.hero-grid {
  display: grid;
  grid-template-columns: 1.06fr 0.94fr;
  gap: 48px;
  align-items: center;
}

.hero-copy {
  position: relative;
  z-index: 1;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  border-radius: var(--radius-full);
  margin-bottom: 22px;
}

/* v1.39.0：移除装饰性状态圆点（badge-dot）。
   它不表达任何真实状态，属于 taste-skill 明确列为「AI Tell」的装饰性 status dot，
   徽标本身已足够传达信息。 */

.hero-title {
  font-size: clamp(34px, 5vw, 52px);
  font-weight: 800;
  line-height: 1.14;
  letter-spacing: -1.5px;
  color: var(--c-text);
  margin: 0 0 18px;
}

/* 强调短语：琥珀金 + 衬线，斩获感 */
.hero-em {
  color: var(--c-accent);
  position: relative;
  white-space: nowrap;
}

.hero-em::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 4px;
  height: 8px;
  background: var(--c-accent-line);
  opacity: 0.5;
  z-index: -1;
  border-radius: var(--radius-xs);
}

.hero-subtitle {
  font-size: 16px;
  line-height: 1.7;
  color: var(--c-text-secondary);
  max-width: 520px;
  margin: 0 0 28px;
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 36px;
}

.arrow-icon {
  transition: transform var(--transition-fast);
}

.hero-actions :deep(.base-btn):hover .arrow-icon,
.cta-content :deep(.base-btn):hover .arrow-icon {
  transform: translateX(4px);
}

/* Hero 数据展示 */
.hero-stats {
  display: inline-flex;
  align-items: center;
  gap: 26px;
  padding: 16px 28px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.stat {
  text-align: center;
}

.stat-num {
  font-size: 26px;
  margin-bottom: 4px;
}

.stat-unit {
  font-size: 15px;
  color: var(--c-text-tertiary);
  font-weight: 600;
}

.stat-label {
  font-size: 12px;
  color: var(--c-text-secondary);
  font-weight: 500;
}

.stat-divider {
  width: 1px;
  height: 28px;
  background: var(--c-border);
}

/* ── 首屏求职工具入口（v1.48.0）── */
.hero-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  margin: 0 0 26px;
}

.hero-tools-label {
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-tertiary);
  letter-spacing: 0.4px;
}

.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.tool-chip {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 4px 12px;
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  text-decoration: none;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.tool-chip:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}

/* ── 视觉锚点：准备度评分卡 ── */
.hero-visual {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 360px;
}

.ring {
  position: absolute;
  width: 320px;
  height: 320px;
  border: 1px dashed var(--brand-primary-200);
  border-radius: 50%;
  animation: spin 26s linear infinite;
}

.ring::before {
  content: '';
  position: absolute;
  top: -4px;
  left: 50%;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-accent);
  box-shadow: 0 4px 10px rgba(180, 83, 9, 0.4);
}

/* 与卡片等宽的定位容器：让浮动标签相对「卡片」而不是「整列」定位（v1.39.0 修复遮挡） */
.visual-stage {
  position: relative;
  width: 300px;
}

.visual-card {
  position: relative;
  z-index: 1;
  width: 100%;
  padding: 22px 22px 18px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xl);
}

.visual-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.visual-title {
  font-family: var(--font-title);
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

/* P2-B：示例卡标注——未登录/数据不可用时明示这是演示数据，不是用户真实状态 */
.demo-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 7px;
  font-size: 10.5px;
  font-weight: 600;
  font-family: var(--font-sans);
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  vertical-align: 1px;
}

.visual-ready {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-accent);
  background: var(--c-accent-soft);
  border-radius: var(--radius-full);
}

.visual-score {
  text-align: center;
  padding: 12px 0 16px;
  border-bottom: 1px solid var(--c-border-light);
}

.score-big {
  font-size: 52px;
  font-weight: 700;
}

.score-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.visual-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 0 12px;
}

.v-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.v-label {
  width: 52px;
  font-size: 12px;
  color: var(--c-text-secondary);
  flex-shrink: 0;
}

.v-bar {
  flex: 1;
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--brand-primary-50);
  overflow: hidden;
}

.v-fill {
  display: block;
  height: 100%;
  width: var(--w, 80%);
  border-radius: var(--radius-xs);
  background: var(--brand-primary);
}

.v-val {
  font-size: 13px;
  width: 26px;
  text-align: right;
  color: var(--c-accent);
}

.visual-cta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 0 0 0;
  border-top: 1px solid var(--c-border-light);
}

.visual-star {
  color: var(--c-accent);
  font-size: 14px;
}

.visual-cta span:last-child {
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-secondary);
}

/* 浮动小标 */
.visual-chip {
  position: absolute;
  z-index: 2;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  box-shadow: var(--shadow-sm);
}

.visual-chip b {
  color: var(--brand-primary);
  font-weight: 600;
}

/* 浮动标签挂在卡片外侧：负偏移保证不侵入卡片内容区（此前是 top/left 百分比，
   在窄列下会直接压住卡片标题） */
.chip-a {
  top: -14px;
  left: -36px;
  animation: fadeInUp 0.5s var(--transition-bounce) both 0.5s;
}

.chip-b {
  bottom: 30px;
  right: -44px;
  animation: fadeInUp 0.5s var(--transition-bounce) both 0.7s;
}

/* ── 通用 Section ──
 * 留白刻意**不对称**：下边距略大于上边距，视觉重心更稳，也避免各区块「等距堆叠」的均质感。
 */
.section {
  padding: 68px 0 76px;
}

/* 区块头**左对齐**（v1.63.0）：与 Hero 的左对齐编辑式构图连成一条阅读轴线。
 * 此前居中对齐会让整页变成「每段都居中」的模板感。 */
.section-header {
  text-align: left;
  max-width: 60ch;
  margin-bottom: 40px;
}

.section-title {
  font-size: 30px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 10px;
  letter-spacing: -0.5px;
  text-wrap: balance;
}

.section-subtitle {
  font-size: 15px;
  color: var(--c-text-secondary);
  margin: 0;
  text-wrap: pretty;
}

/* ── 特性卡片：不对称编辑式网格 ──
 * 左列主卡跨两行（1.45fr），右列两张上下排列（1fr）。
 * 这是刻意的**非对称**：打破「三等分卡片行」这种 AI 版式指纹。 */
.features {
  display: grid;
  grid-template-columns: 1.45fr 1fr;
  gap: 18px;
}

.feature-slot {
  display: flex;
}

.feature-slot :deep(.base-card) {
  width: 100%;
  height: 100%;
}

/* 主卡跨两行时高度由右列决定，内容短就会在底部留一大片空白，看着像没做完。
 * 让卡片内容成为纵向弹性容器、把标签锚到底部，空白就成了刻意的呼吸而非空缺。
 * （同类问题见 redesign 清单「卡片 CTA 未对齐底部」。） */
.features :deep(.base-card--feature .base-card__body) {
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 主卡内的要点列表：用发丝分隔线组织，替代「用装饰元素填空白」的做法 */
.feature-points {
  list-style: none;
  margin: 4px 0 22px;
  padding: 0;
}

.feature-points li {
  padding: 11px 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  border-top: 1px solid var(--c-border-light);
}

.feature-points li:last-child {
  border-bottom: 1px solid var(--c-border-light);
}

/* 主卡跨两行 —— 右列两张自然上下堆叠，形成左重右轻的编辑式平衡 */
.feature-slot.is-lead {
  grid-row: span 2;
}

/* 主卡给更宽的留白与更大的字号，让「主次」一眼可辨 */
.feature-slot.is-lead :deep(.base-card--feature .base-card__body) {
  padding: 40px 36px;
}

.feature-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  margin-bottom: 16px;
}

.feature-icon {
  width: 22px;
  height: 22px;
}

.feature-slot.is-lead .feature-icon-wrap {
  width: 56px;
  height: 56px;
  margin-bottom: 20px;
}

.feature-slot.is-lead .feature-icon {
  width: 28px;
  height: 28px;
}

.features :deep(.base-card--feature h3) {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}

.feature-slot.is-lead :deep(.base-card--feature h3) {
  font-size: 22px;
  letter-spacing: -0.4px;
}

.features :deep(.base-card--feature p) {
  font-size: 14px;
  line-height: 1.65;
  color: var(--c-text-secondary);
  margin: 0 0 16px;
}

.feature-slot.is-lead :deep(.base-card--feature p) {
  font-size: 15px;
  line-height: 1.7;
}

.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  /* 锚到底部：主卡跨两行时高度由右列决定，标签贴着底边才不会飘在中间 */
  margin-top: auto;
}

.features :deep(.base-card--feature:hover .base-tag) {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border-color: var(--brand-primary-100);
}

/* ── 工作流程：编辑式编号 ──
 * 顶部长细线连成一条贯穿整行的规则线；序号用等宽小字 + 字距，放在内容之上。
 * 不用填充圆形徽章 —— 那是通用模板版式。 */
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0;
}

.step {
  padding: 24px 32px 0 0;
  border-top: 1px solid var(--c-border-strong);
}

.step-index {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.16em;
  color: var(--c-text-quaternary);
  margin-bottom: 14px;
}

.step h4 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}

.step p {
  font-size: 14px;
  line-height: 1.65;
  color: var(--c-text-secondary);
  margin: 0;
  text-wrap: pretty;
}

/* ── CTA 区 ── */
.cta-section {
  padding: 40px 0 80px;
}

.cta-card {
  position: relative;
  background: var(--brand-gradient);
  border-radius: var(--radius-xl);
  padding: 56px 40px;
  text-align: center;
  box-shadow: var(--shadow-lg);
}

.cta-content {
  position: relative;
  z-index: 1;
}

.cta-title {
  font-size: clamp(24px, 4vw, 30px);
  font-weight: 700;
  color: #fff;
  margin: 0 0 10px;
  letter-spacing: -0.5px;
}

.cta-desc {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.9);
  margin: 0 0 28px;
}

/* 桌面端 */
@media (min-width: 1024px) {
  .hero-grid {
    grid-template-columns: 1.06fr 0.94fr;
  }
}

@media (max-width: 1023px) {
  .hero-grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  /* v1.34.1 修复（UX P3-8）：此前这里用 `.hero-visual { order: -1 }` 把演示卡提到最前，
     单列布局下会把主标题与主 CTA 整块推到首屏之外——375×667 实测 h1 顶端在 582px，
     几乎贴底，移动端首访看不到"这个产品是干什么的"。
     DOM 顺序本就是「文案在前、演示卡在后」，直接去掉 order 覆盖即可让价值主张先出现；
     演示卡紧随其后，仍能在首屏下沿露出，引导继续下滑。 */
  .hero-visual {
    order: 0;
  }
  /* 平板及以下：不对称网格收成单列。
     主卡的 `grid-row: span 2` 必须一并复位，否则单列下它会凭空占两行高度。 */
  .features {
    grid-template-columns: 1fr;
  }
  .feature-slot.is-lead {
    grid-row: auto;
  }
  .feature-slot.is-lead :deep(.base-card--feature .base-card__body) {
    padding: 30px 26px;
  }
}

@media (max-width: 768px) {
  .steps {
    grid-template-columns: 1fr;
    gap: 22px;
  }
  .step {
    padding: 20px 0 0;
  }
  /* 移动端隐藏浮动小标：卡片仅 260px，标签外挂会在 320px 视口溢出。
     （遮挡问题已由 .visual-stage 从结构上修掉，这里只是窄屏空间取舍） */
  .visual-chip {
    display: none;
  }
  .hero {
    padding-top: 40px;
  }
  .hero-stats {
    gap: 16px;
    padding: 14px 20px;
  }
  .stat-num {
    font-size: 22px;
  }
}

@media (max-width: 480px) {
  .hero-actions {
    flex-direction: column;
    width: 100%;
  }
  .hero-actions :deep(.base-btn),
  .cta-content :deep(.base-btn) {
    width: 100%;
    justify-content: center;
  }
  .ring {
    width: 240px;
    height: 240px;
  }
  .visual-card {
    width: 100%;
  }
  .visual-stage {
    width: 260px;
  }
}
</style>
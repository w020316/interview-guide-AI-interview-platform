<template>
  <div class="home">
    <!-- Hero 区：左文右卡，不对称编辑式构图 -->
    <section class="hero">
      <div class="hero-grid">
        <div class="hero-copy">
          <div class="hero-badge fade-in-up">
            <span class="badge-dot"></span>
            <span>AI 驱动 · 智能面试准备平台</span>
          </div>
          <h1 class="hero-title fade-in-up" style="animation-delay: 80ms">
            让每一次面试<br />
            <span class="hero-em">都有备而来</span>
          </h1>
          <p class="hero-subtitle fade-in-up" style="animation-delay: 160ms">
            上传简历获得 AI 多维度评分，生成个性化面试题，
            实时流式提示与自动评估，助你高效备战求职季。
          </p>
          <div class="hero-actions fade-in-up" style="animation-delay: 240ms">
            <BaseButton variant="primary" size="lg" shadow="sm" hoverable @click="goTo('/resume')">
              <span>开始简历分析</span>
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

          <!-- Hero 数据展示：等宽琥珀金数字，评分面板质感 -->
          <div class="hero-stats fade-in-up" style="animation-delay: 320ms">
            <div class="stat">
              <div class="stat-num num-display">4</div>
              <div class="stat-label">评分维度</div>
            </div>
            <div class="stat-divider"></div>
            <div class="stat">
              <div class="stat-num num-display">100<span class="stat-unit">%</span></div>
              <div class="stat-label">岗位匹配分析</div>
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
          <div class="visual-card">
            <div class="visual-head">
              <span class="visual-title">本轮准备度</span>
              <span class="visual-ready">
                <span class="ready-dot"></span>已就绪
              </span>
            </div>
            <div class="visual-score">
              <div class="score-big num-display">86</div>
              <div class="score-meta">综合评估 · 击败 78% 求职者</div>
            </div>
            <div class="visual-rows">
              <div class="v-row">
                <span class="v-label">技术匹配</span>
                <div class="v-bar"><i class="v-fill" style="--w: 92%"></i></div>
                <span class="v-val num-display">92</span>
              </div>
              <div class="v-row">
                <span class="v-label">表述清晰</span>
                <div class="v-bar"><i class="v-fill" style="--w: 88%"></i></div>
                <span class="v-val num-display">88</span>
              </div>
              <div class="v-row">
                <span class="v-label">项目含金</span>
                <div class="v-bar"><i class="v-fill" style="--w: 78%"></i></div>
                <span class="v-val num-display">78</span>
              </div>
            </div>
            <div class="visual-cta">
              <span class="visual-star">★</span>
              <span>双向奔赴的岗位在等你</span>
            </div>
          </div>
          <div class="visual-chip chip-a">面试题 <b>已就绪</b></div>
          <div class="visual-chip chip-b">复盘 <b>有报告</b></div>
        </div>
      </div>
    </section>

    <!-- 特性卡片 -->
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">三大核心能力</h2>
        <p class="section-subtitle">从简历到面试，全链路 AI 辅助</p>
      </div>
      <div class="features">
        <BaseCard v-for="(f, i) in features" :key="f.title"
                  variant="feature"
                  class="fade-in-up"
                  :style="{ animationDelay: (i * 100) + 'ms' }">
          <div class="feature-icon-wrap">
            <svg class="feature-icon" viewBox="0 0 24 24" fill="none">
              <path :d="f.iconPath" stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <h3>{{ f.title }}</h3>
          <p>{{ f.desc }}</p>
          <div class="feature-tags">
            <BaseTag v-for="t in f.tags" :key="t">{{ t }}</BaseTag>
          </div>
        </BaseCard>
      </div>
    </section>

    <!-- 工作流程 -->
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">三步完成面试准备</h2>
        <p class="section-subtitle">简洁流程，快速上手</p>
      </div>
      <div class="steps">
        <div v-for="(s, i) in steps" :key="s.title" class="step fade-in-up"
             :style="{ animationDelay: (i * 120) + 'ms' }">
          <div class="step-num-wrap">
            <div class="step-num">{{ i + 1 }}</div>
            <div v-if="i < steps.length - 1" class="step-line"></div>
          </div>
          <div class="step-content">
            <h4>{{ s.title }}</h4>
            <p>{{ s.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- CTA 区 -->
    <section class="cta-section fade-in-up">
      <div class="cta-card">
        <div class="cta-content">
          <h2 class="cta-title">准备好开始你的面试之旅了吗？</h2>
          <p class="cta-desc">免费使用，无需信用卡，立即获得 AI 智能评估</p>
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
import { useRouter } from 'vue-router'
import { isLoggedIn } from '../auth'
import { BaseButton, BaseCard, BaseTag } from '../components'

const router = useRouter()

function goTo(path: string) {
  const requiresAuth = ['/resume', '/job', '/interview', '/history', '/profile'].includes(path)
  if (requiresAuth && !isLoggedIn()) {
    router.push({ path: '/login', query: { redirect: path } })
    return
  }
  router.push(path)
}

const features = [
  {
    title: '简历智能分析',
    desc: 'AI 从技术匹配度、项目含金量、表述清晰度等四个维度评分，给出可执行的改进建议',
    tags: ['PDF 解析', '多维度评分', '改进建议'],
    iconPath: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M9 13h6 M9 17h6 M9 9h1',
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

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-primary);
}

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
  border-radius: 2px;
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

.visual-card {
  position: relative;
  z-index: 1;
  width: 300px;
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
  font-family: var(--font-serif);
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.visual-ready {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-accent);
  background: var(--c-accent-soft);
  border-radius: var(--radius-full);
}

.ready-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-accent);
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
  border-radius: 3px;
  background: var(--brand-primary-50);
  overflow: hidden;
}

.v-fill {
  display: block;
  height: 100%;
  width: var(--w, 80%);
  border-radius: 3px;
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

.chip-a {
  top: 12%;
  left: 4%;
  animation: fadeInUp 0.5s var(--transition-bounce) both 0.5s;
}

.chip-b {
  bottom: 14%;
  right: 2%;
  animation: fadeInUp 0.5s var(--transition-bounce) both 0.7s;
}

/* ── 通用 Section ── */
.section {
  padding: 56px 0;
}

.section-header {
  text-align: center;
  margin-bottom: 40px;
}

.section-title {
  font-size: 30px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 10px;
  letter-spacing: -0.5px;
}

.section-subtitle {
  font-size: 15px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── 特性卡片 ── */
.features {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.features :deep(.base-card--feature .base-card__body) {
  padding: 28px 24px;
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

.features :deep(.base-card--feature h3) {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}

.features :deep(.base-card--feature p) {
  font-size: 14px;
  line-height: 1.65;
  color: var(--c-text-secondary);
  margin: 0 0 16px;
}

.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.features :deep(.base-card--feature:hover .base-tag) {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  border-color: var(--brand-primary-100);
}

/* ── 工作流程 ── */
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  position: relative;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  position: relative;
}

.step-num-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.step-line {
  position: absolute;
  top: 50%;
  left: 100%;
  width: 100%;
  height: 1px;
  background: var(--c-border-strong);
  background-image: linear-gradient(to right, var(--c-border-strong) 50%, transparent 50%);
  background-size: 10px 1px;
  transform: translateY(-50%);
}

.step-content h4 {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin: 6px 0 4px;
}

.step-content p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
  line-height: 1.6;
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
  .hero-visual {
    order: -1;
  }
}

@media (max-width: 768px) {
  .features, .steps {
    grid-template-columns: 1fr;
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
    width: 260px;
  }
  .chip-a { left: 0; }
  .chip-b { right: 0; }
}
</style>
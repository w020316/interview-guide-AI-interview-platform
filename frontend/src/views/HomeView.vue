<template>
  <div class="home">
    <!-- Hero 区 -->
    <section class="hero">
      <div class="hero-content fade-in-up">
        <div class="hero-badge">
          <span class="badge-dot"></span>
          <span>AI 驱动 · 智能面试准备平台</span>
        </div>
        <h1 class="hero-title">
          让每一次面试<br />
          <span class="text-gradient">都有备而来</span>
        </h1>
        <p class="hero-subtitle">
          上传简历获得 AI 多维度评分，生成个性化面试题，
          <br />
          实时流式提示与自动评估，助你高效备战求职季
        </p>
        <div class="hero-actions">
          <button class="btn-primary" @click="goTo('/resume')">
            <span>开始简历分析</span>
            <svg class="arrow-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <button class="btn-secondary" @click="goTo('/interview')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <span>模拟面试</span>
          </button>
        </div>

        <!-- Hero 数据展示 -->
        <div class="hero-stats">
          <div class="stat">
            <div class="stat-num">4</div>
            <div class="stat-label">评分维度</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <div class="stat-num">3</div>
            <div class="stat-label">岗位分析</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <div class="stat-num">∞</div>
            <div class="stat-label">面试次数</div>
          </div>
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
        <div v-for="(f, i) in features" :key="f.title"
             class="feature-card fade-in-up"
             :style="{ animationDelay: (i * 100) + 'ms' }">
          <div class="feature-icon-wrap">
            <svg class="feature-icon" viewBox="0 0 24 24" fill="none">
              <path :d="f.iconPath" stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <h3>{{ f.title }}</h3>
          <p>{{ f.desc }}</p>
          <div class="feature-tags">
            <span v-for="t in f.tags" :key="t" class="tag">{{ t }}</span>
          </div>
        </div>
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
          <button class="btn-primary btn-lg" @click="goTo('/login')">
            立即开始
            <svg class="arrow-icon" width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { isLoggedIn } from '../auth'

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

/* ── Hero（去除光晕/网格/玻璃态，纯色 + 排版） ── */
.hero {
  position: relative;
  text-align: center;
  padding: 72px 0 56px;
  overflow: hidden;
}

.hero-content {
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
  margin-bottom: 24px;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-primary);
}

.hero-title {
  font-size: clamp(36px, 6vw, 56px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -1.5px;
  color: var(--c-text);
  margin: 0 0 20px;
}

.hero-subtitle {
  font-size: 16px;
  line-height: 1.7;
  color: var(--c-text-secondary);
  max-width: 580px;
  margin: 0 auto 32px;
}

.hero-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
  margin-bottom: 48px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 26px;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-sm);
}

.btn-primary:hover {
  background: var(--brand-primary-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.btn-primary:active {
  transform: translateY(0);
}

.btn-primary .arrow-icon {
  transition: transform var(--transition-fast);
}

.btn-primary:hover .arrow-icon {
  transform: translateX(4px);
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 26px;
  font-size: 15px;
  font-weight: 500;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-secondary:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.btn-lg {
  padding: 15px 32px;
  font-size: 16px;
}

/* ── Hero 数据展示（实色卡片，无玻璃态） ── */
.hero-stats {
  display: inline-flex;
  align-items: center;
  gap: 28px;
  padding: 18px 32px;
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
  font-weight: 700;
  color: var(--brand-primary);
  line-height: 1;
  margin-bottom: 4px;
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

/* ── 特性卡片（克制 hover，无图标旋转） ── */
.features {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.feature-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 28px 24px;
  transition: all var(--transition-base);
  position: relative;
  overflow: hidden;
}

.feature-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--brand-primary);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--transition-base);
}

.feature-card:hover {
  border-color: var(--c-border);
  box-shadow: var(--shadow-md);
}

.feature-card:hover::before {
  transform: scaleX(1);
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

.feature-card h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}

.feature-card p {
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

.tag {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border-radius: var(--radius-full);
}

.feature-card:hover .tag {
  background: var(--brand-primary-50);
  color: var(--brand-primary);
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

/* ── CTA 区（品牌色实色，无紫青渐变） ── */
.cta-section {
  padding: 56px 0 80px;
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

.cta-card .btn-primary {
  background: #fff;
  color: var(--brand-primary);
  box-shadow: var(--shadow-md);
}

.cta-card .btn-primary:hover {
  background: var(--brand-primary-50);
    transform: translateY(-1px);
}

@media (max-width: 768px) {
  .features, .steps {
    grid-template-columns: 1fr;
  }
  .hero-stats {
    gap: 16px;
    padding: 14px 20px;
  }
  .stat-num { font-size: 22px; }
}

@media (max-width: 480px) {
  .hero-actions { flex-direction: column; width: 100%; }
  .btn-primary, .btn-secondary { width: 100%; justify-content: center; }
}
</style>

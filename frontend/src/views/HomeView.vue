<template>
  <div class="home">
    <!-- Hero 区 -->
    <section class="hero fade-in-up">
      <div class="hero-badge">
        <span class="badge-dot"></span>
        AI 驱动 · 智能面试准备
      </div>
      <h1 class="hero-title">
        让每一次面试<br />
        <span class="text-gradient">都有备而来</span>
      </h1>
      <p class="hero-subtitle">
        上传简历获得 AI 多维度评分，生成个性化面试题，实时流式提示助你高效备战
      </p>
      <div class="hero-actions">
        <button class="btn-primary" @click="$router.push('/resume')">
          开始简历分析
          <span class="arrow">→</span>
        </button>
        <button class="btn-secondary" @click="$router.push('/interview')">
          模拟面试
        </button>
      </div>
    </section>

    <!-- 特性卡片 -->
    <section class="features">
      <div v-for="(f, i) in features" :key="f.title" class="feature-card fade-in-up"
           :style="{ animationDelay: (i * 80) + 'ms' }">
        <div class="feature-icon" :style="{ background: f.bg }">{{ f.icon }}</div>
        <h3>{{ f.title }}</h3>
        <p>{{ f.desc }}</p>
        <div class="feature-tags">
          <span v-for="t in f.tags" :key="t" class="tag">{{ t }}</span>
        </div>
      </div>
    </section>

    <!-- 工作流程 -->
    <section class="workflow">
      <h2 class="section-title">三步完成面试准备</h2>
      <div class="steps">
        <div v-for="(s, i) in steps" :key="s.title" class="step">
          <div class="step-num">{{ i + 1 }}</div>
          <h4>{{ s.title }}</h4>
          <p>{{ s.desc }}</p>
          <div v-if="i < steps.length - 1" class="step-line"></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
const features = [
  {
    icon: '📄',
    title: '简历智能分析',
    desc: 'AI 从技术匹配度、项目含金量、表述清晰度等四个维度评分，给出可执行的改进建议',
    tags: ['PDF 解析', '多维度评分', '改进建议'],
    bg: 'linear-gradient(135deg, #dbeafe 0%, #ede9fe 100%)',
  },
  {
    icon: '🤖',
    title: '个性化面试题',
    desc: '根据简历内容与目标岗位生成定制化面试题，覆盖基础、框架、数据库、中间件等方向',
    tags: ['岗位匹配', '难度分级', '参考答案'],
    bg: 'linear-gradient(135deg, #fce7f3 0%, #fbcfe8 100%)',
  },
  {
    icon: '📊',
    title: '答题实时评估',
    desc: '提交回答后 AI 从完整性、准确性、表达力三个维度评分，并给出针对性的改进建议',
    tags: ['三维度评分', '流式提示', '历史回看'],
    bg: 'linear-gradient(135deg, #d1fae5 0%, #ccfbf1 100%)',
  },
]

const steps = [
  { title: '上传简历', desc: '粘贴文本或上传 PDF/TXT 简历' },
  { title: 'AI 分析', desc: '获得评分与个性化面试题' },
  { title: '模拟面试', desc: '答题获得实时评估与建议' },
]
</script>

<style scoped>
.home {
  max-width: 1100px;
  margin: 0 auto;
}

/* ── Hero ── */
.hero {
  text-align: center;
  padding: 56px 24px 48px;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border-radius: 999px;
  margin-bottom: 24px;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: pulse 2s ease-in-out infinite;
}

.hero-title {
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -1px;
  color: var(--c-text);
  margin: 0 0 20px;
}

.hero-subtitle {
  font-size: 16px;
  line-height: 1.7;
  color: var(--c-text-secondary);
  max-width: 560px;
  margin: 0 auto 36px;
}

.hero-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 28px;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: 0 4px 14px rgba(79, 70, 229, 0.3);
}

.btn-primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(79, 70, 229, 0.4);
}

.btn-primary .arrow {
  transition: transform var(--transition-fast);
}

.btn-primary:hover .arrow {
  transform: translateX(4px);
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  padding: 13px 28px;
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
  background: var(--brand-primary-light);
}

/* ── 特性卡片 ── */
.features {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 64px;
}

.feature-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  padding: 28px 24px;
  transition: all var(--transition-base);
}

.feature-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
  border-color: var(--c-border);
}

.feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: var(--radius-md);
  font-size: 26px;
  margin-bottom: 16px;
}

.feature-card h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}

.feature-card p {
  font-size: 14px;
  line-height: 1.6;
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
  border-radius: 999px;
}

/* ── 工作流程 ── */
.workflow {
  padding: 32px 0;
}

.section-title {
  text-align: center;
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 40px;
  letter-spacing: -0.3px;
}

.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 32px;
  position: relative;
}

.step {
  text-align: center;
  position: relative;
}

.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 16px;
}

.step h4 {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 6px;
}

.step p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

.step-line {
  position: absolute;
  top: 20px;
  left: calc(50% + 28px);
  width: calc(100% - 56px);
  height: 2px;
  background: linear-gradient(to right, var(--c-border) 50%, transparent 50%);
  background-size: 12px 2px;
  background-repeat: repeat-x;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .features {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .steps {
    grid-template-columns: 1fr;
    gap: 24px;
  }
  .step-line {
    display: none;
  }
  .hero {
    padding: 32px 16px 32px;
  }
}
</style>

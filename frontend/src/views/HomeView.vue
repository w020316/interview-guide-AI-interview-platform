<template>
  <div class="home">
    <el-row justify="center">
      <el-col :xs="24" :sm="20" :md="16">
        <el-card shadow="hover" class="hero-card">
          <h1>🎯 AI 智能面试辅助平台</h1>
          <p class="subtitle">上传简历、生成个性化面试题、AI 实时评估</p>
          <div class="action-row">
            <el-button type="primary" size="large" @click="$router.push('/resume')">📄 上传简历</el-button>
            <el-button size="large" @click="$router.push('/interview')">🤖 模拟面试</el-button>
            <el-button size="large" @click="$router.push('/history')">📋 历史记录</el-button>
          </div>
        </el-card>
        <el-row :gutter="16" class="feature-row">
          <el-col :xs="24" :sm="8" v-for="f in features" :key="f.title">
            <el-card shadow="hover" class="feature-card">
              <div class="feature-icon">{{ f.icon }}</div>
              <h3>{{ f.title }}</h3>
              <p>{{ f.desc }}</p>
            </el-card>
          </el-col>
        </el-row>
      </el-col>
    </el-row>
  </div>
</template>
<script setup lang="ts">
const features = [
  { icon: '📄', title: 'PDF 简历解析', desc: '自动提取 PDF/Word 简历内容，AI 多维度评分' },
  { icon: '🤖', title: 'AI 面试模拟', desc: '根据岗位生成个性化面试题，SSE 流式回答' },
  { icon: '📊', title: '答题评估', desc: '完整性、准确性、表达力三维度改进建议' },
]
</script>
<style scoped>
.hero-card { text-align:center; padding:40px 20px; }
h1 { font-size:clamp(22px,4vw,32px); margin-bottom:12px; }
.subtitle { color:#666; margin-bottom:28px; }
.action-row { display:flex; flex-wrap:wrap; gap:12px; justify-content:center; }
.feature-row { margin-top:20px; }
.feature-card { text-align:center; padding:20px 10px; }
.feature-icon { font-size:36px; margin-bottom:8px; }
</style>

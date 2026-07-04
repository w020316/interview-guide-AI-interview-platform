<template>
  <div class="home-container">
    <el-row :gutter="20" class="hero-section">
      <el-col :span="14">
        <h1 class="hero-title">
          <el-icon :size="36"><ChatDotRound /></el-icon>
          AI 智能面试辅助平台
        </h1>
        <p class="hero-desc">
          基于 Spring Boot 3.3 + Spring AI 1.0 + Java 21 构建的智能面试辅助系统，
          集成简历分析、AI 模拟面试、RAG 知识库问答三大核心能力，
          帮助 Java 后端开发者高效备战校招与社招。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="goTo('/resume')">
            <el-icon><EditPen /></el-icon>开始分析简历
          </el-button>
          <el-button size="large" @click="goTo('/interview')">
            <el-icon><VideoCamera /></el-icon>AI 模拟面试
          </el-button>
          <el-button size="large" @click="goTo('/history')">
            <el-icon><Clock /></el-icon>历史记录
          </el-button>
        </div>
      </el-col>
      <el-col :span="10">
        <el-card shadow="hover" class="tech-card">
          <h3>技术栈</h3>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="后端">Spring Boot 3.3.6</el-descriptions-item>
            <el-descriptions-item label="AI 框架">Spring AI 1.0 GA</el-descriptions-item>
            <el-descriptions-item label="JDK">Java 21（虚拟线程）</el-descriptions-item>
            <el-descriptions-item label="数据库">PostgreSQL + pgvector</el-descriptions-item>
            <el-descriptions-item label="缓存">Redis</el-descriptions-item>
            <el-descriptions-item label="AI 模型">Agnes AI（OpenAI 兼容）</el-descriptions-item>
            <el-descriptions-item label="认证">JWT + Spring Security</el-descriptions-item>
            <el-descriptions-item label="部署">Vercel + Render + Supabase</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="feature-section">
      <el-col :span="8" v-for="f in features" :key="f.title">
        <el-card shadow="hover" class="feature-card" @click="goTo(f.path)">
          <el-icon :size="32" :color="f.color"><component :is="f.icon" /></el-icon>
          <h4>{{ f.title }}</h4>
          <p>{{ f.desc }}</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ChatDotRound, EditPen, VideoCamera, Clock, Document, Reading, DataAnalysis } from '@element-plus/icons-vue'

const router = useRouter()
const goTo = (path: string) => router.push(path)

const features = [
  { title: '智能简历分析', desc: '4 维度评分 + 改进建议，支持 PDF/Word 上传', icon: Document, color: '#409EFF', path: '/resume' },
  { title: 'AI 模拟面试', desc: '基于简历生成定制题，SSE 流式回答', icon: VideoCamera, color: '#67C23A', path: '/interview' },
  { title: 'RAG 知识问答', desc: 'Java 八股文向量检索，精准回答', icon: Reading, color: '#E6A23C', path: '/interview' },
  { title: '面试会话管理', desc: '会话 CRUD + 历史回顾', icon: Clock, color: '#F56C6C', path: '/history' },
  { title: '回答评估', desc: 'AI 打分 + 标准答案对比', icon: DataAnalysis, color: '#909399', path: '/interview' },
  { title: '限流保护', desc: 'Bucket4j 10 req/IP/min', icon: ChatDotRound, color: '#9C27B0', path: '/' }
]
</script>

<style scoped>
.home-container { padding: 20px; max-width: 1200px; margin: 0 auto; }
.hero-section { margin-bottom: 40px; }
.hero-title { font-size: 28px; color: #303133; display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.hero-desc { color: #606266; line-height: 1.8; font-size: 14px; margin-bottom: 24px; }
.hero-actions { display: flex; gap: 12px; flex-wrap: wrap; }
.tech-card h3 { margin: 0 0 16px; color: #303133; }
.feature-card { text-align: center; cursor: pointer; transition: transform 0.2s; }
.feature-card:hover { transform: translateY(-4px); }
.feature-card h4 { margin: 12px 0 8px; color: #303133; }
.feature-card p { color: #909399; font-size: 13px; margin: 0; }
</style>

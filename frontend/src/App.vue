<template>
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-title">
        <el-icon :size="28"><ChatDotRound /></el-icon>
        <span>AI 智能面试辅助平台</span>
      </div>
      <el-tag type="success" effect="plain">Spring Boot 3.3 + Spring AI 1.0 + Java 21</el-tag>
    </el-header>

    <el-main class="app-main">
      <el-tabs v-model="activeTab" class="custom-tabs">
        <!-- 简历分析 -->
        <el-tab-pane label="简历分析" name="resume">
          <div class="tab-content">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-card shadow="hover">
                  <template #header>
                    <span class="card-title">输入简历与目标岗位</span>
                  </template>
                  <el-form label-position="top">
                    <el-form-item label="目标岗位">
                      <el-input v-model="resumeForm.targetJob" placeholder="例如：Java 后端开发" />
                    </el-form-item>
                    <el-form-item label="简历内容">
                      <el-input
                        v-model="resumeForm.resumeText"
                        type="textarea"
                        :rows="12"
                        placeholder="粘贴你的简历文本..."
                      />
                    </el-form-item>
                    <el-button type="primary" :loading="loading.resume" @click="analyzeResume">
                      AI 分析简历
                    </el-button>
                  </el-form>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="hover" class="result-card">
                  <template #header>
                    <span class="card-title">分析结果</span>
                  </template>
                  <div v-if="resumeResult" class="result-content" v-html="renderMarkdown(resumeResult)"></div>
                  <el-empty v-else description="提交简历后查看分析结果" />
                </el-card>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- 模拟面试 -->
        <el-tab-pane label="模拟面试" name="interview">
          <div class="tab-content">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-card shadow="hover">
                  <template #header>
                    <span class="card-title">生成面试题</span>
                  </template>
                  <el-form label-position="top">
                    <el-form-item label="简历内容">
                      <el-input
                        v-model="interviewForm.resumeText"
                        type="textarea"
                        :rows="6"
                        placeholder="粘贴简历文本..."
                      />
                    </el-form-item>
                    <el-form-item label="岗位描述">
                      <el-input
                        v-model="interviewForm.jobDescription"
                        type="textarea"
                        :rows="6"
                        placeholder="粘贴目标岗位 JD..."
                      />
                    </el-form-item>
                    <el-form-item label="题目数量">
                      <el-slider v-model="interviewForm.count" :min="3" :max="10" show-input />
                    </el-form-item>
                    <el-button type="primary" :loading="loading.interview" @click="generateQuestions">
                      生成面试题
                    </el-button>
                  </el-form>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="hover" class="result-card">
                  <template #header>
                    <span class="card-title">面试题列表</span>
                  </template>
                  <div v-if="interviewResult" class="result-content" v-html="renderMarkdown(interviewResult)"></div>
                  <el-empty v-else description="生成后查看面试题" />
                </el-card>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- 知识库问答 -->
        <el-tab-pane label="知识问答" name="knowledge">
          <div class="tab-content">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-card shadow="hover">
                  <template #header>
                    <span class="card-title">提问</span>
                  </template>
                  <el-form label-position="top">
                    <el-form-item label="你的问题">
                      <el-input
                        v-model="knowledgeForm.question"
                        type="textarea"
                        :rows="6"
                        placeholder="例如：HashMap 和 ConcurrentHashMap 的区别？"
                      />
                    </el-form-item>
                    <el-button type="primary" :loading="loading.knowledge" @click="askQuestion">
                      RAG 智能问答
                    </el-button>
                  </el-form>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="hover" class="result-card">
                  <template #header>
                    <span class="card-title">AI 回答</span>
                  </template>
                  <div v-if="knowledgeResult" class="result-content" v-html="renderMarkdown(knowledgeResult)"></div>
                  <el-empty v-else description="提问后查看 AI 回答" />
                </el-card>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-main>

    <el-footer class="app-footer">
      <span>© 2026 AI 智能面试辅助平台 ｜ Powered by Spring AI 1.0 + 通义千问</span>
    </el-footer>
  </el-container>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { marked } from 'marked'
import api from './api'

const activeTab = ref('resume')

const loading = reactive({
  resume: false,
  interview: false,
  knowledge: false
})

const resumeForm = reactive({
  resumeText: '',
  targetJob: 'Java 后端开发'
})
const resumeResult = ref('')

const interviewForm = reactive({
  resumeText: '',
  jobDescription: '',
  count: 5
})
const interviewResult = ref('')

const knowledgeForm = reactive({
  question: ''
})
const knowledgeResult = ref('')

const renderMarkdown = (text: string) => marked.parse(text)

const analyzeResume = async () => {
  if (!resumeForm.resumeText.trim()) {
    ElMessage.warning('请输入简历内容')
    return
  }
  loading.resume = true
  try {
    const res: any = await api.post('/api/resume/analyze', resumeForm)
    if (res.code === 200) {
      // 尝试格式化 JSON 显示
      try {
        const parsed = JSON.parse(res.data)
        resumeResult.value = '```json\n' + JSON.stringify(parsed, null, 2) + '\n```'
      } catch {
        resumeResult.value = res.data
      }
    } else {
      ElMessage.error(res.message)
    }
  } catch (e: any) {
    ElMessage.error('请求失败：' + e.message)
  } finally {
    loading.resume = false
  }
}

const generateQuestions = async () => {
  if (!interviewForm.resumeText.trim() || !interviewForm.jobDescription.trim()) {
    ElMessage.warning('请填写简历和岗位描述')
    return
  }
  loading.interview = true
  try {
    const res: any = await api.post('/api/interview/questions', interviewForm)
    if (res.code === 200) {
      try {
        const parsed = JSON.parse(res.data)
        interviewResult.value = '```json\n' + JSON.stringify(parsed, null, 2) + '\n```'
      } catch {
        interviewResult.value = res.data
      }
    } else {
      ElMessage.error(res.message)
    }
  } catch (e: any) {
    ElMessage.error('请求失败：' + e.message)
  } finally {
    loading.interview = false
  }
}

const askQuestion = async () => {
  if (!knowledgeForm.question.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  loading.knowledge = true
  try {
    const res: any = await api.post('/api/knowledge/ask', knowledgeForm)
    if (res.code === 200) {
      knowledgeResult.value = res.data
    } else {
      ElMessage.error(res.message)
    }
  } catch (e: any) {
    ElMessage.error('请求失败：' + e.message)
  } finally {
    loading.knowledge = false
  }
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; }

.app-container { min-height: 100vh; background: #f5f7fa; }

.app-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 64px;
}
.header-title { display: flex; align-items: center; gap: 12px; font-size: 20px; font-weight: 600; }

.app-main { padding: 24px; max-width: 1400px; margin: 0 auto; width: 100%; }

.custom-tabs { background: white; border-radius: 8px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.tab-content { padding: 16px 0; }
.card-title { font-weight: 600; color: #303133; }
.result-card { min-height: 400px; }
.result-content { line-height: 1.8; word-break: break-word; }
.result-content pre { background: #f5f7fa; padding: 12px; border-radius: 6px; overflow-x: auto; }

.app-footer {
  text-align: center;
  color: #909399;
  font-size: 12px;
  padding: 16px;
  background: white;
  border-top: 1px solid #ebeef5;
}
</style>

<template>
  <div class="interview-container">
    <el-row :gutter="20">
      <!-- 左侧：面试题生成 -->
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header><span class="card-title">面试题生成</span></template>
          <el-form label-position="top">
            <el-form-item label="岗位描述">
              <el-input v-model="genForm.jobDescription" type="textarea" :rows="4" placeholder="例如：Java 后端，熟悉 Spring Boot、MySQL、Redis" />
            </el-form-item>
            <el-form-item label="简历摘要">
              <el-input v-model="genForm.resumeText" type="textarea" :rows="4" placeholder="简要描述你的项目经验与技术栈" />
            </el-form-item>
            <el-form-item label="题目数量">
              <el-slider v-model="genForm.count" :min="3" :max="10" show-input />
            </el-form-item>
            <el-button type="primary" :loading="genLoading" @click="generate" style="width:100%">
              <el-icon><MagicStick /></el-icon>生成面试题
            </el-button>
          </el-form>
        </el-card>

        <el-card shadow="hover" style="margin-top:16px">
          <template #header><span class="card-title">题目列表</span></template>
          <div v-if="questions.length">
            <div v-for="(q, i) in questions" :key="i" class="question-item" @click="selectQuestion(q)">
              <el-tag size="small" type="info">Q{{ i + 1 }}</el-tag>
              <span class="q-text">{{ q }}</span>
            </div>
          </div>
          <el-empty v-else description="尚未生成题目" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 中间：AI 流式回答 -->
      <el-col :span="10">
        <el-card shadow="hover" class="chat-card">
          <template #header>
            <div class="chat-header">
              <span class="card-title">AI 流式回答</span>
              <el-tag v-if="streaming" type="success" size="small">回答中...</el-tag>
            </div>
          </template>
          <div v-if="!currentQuestion && !streaming" class="empty-chat">
            <el-icon :size="48" color="#dcdfe6"><ChatLineSquare /></el-icon>
            <p>点击左侧题目或输入问题，AI 将流式回答</p>
          </div>
          <div v-else class="chat-body">
            <div class="msg user-msg" v-if="currentQuestion">
              <el-avatar :size="28" style="background:#409EFF">我</el-avatar>
              <div class="msg-content">{{ currentQuestion }}</div>
            </div>
            <div class="msg ai-msg">
              <el-avatar :size="28" style="background:#67C23A">AI</el-avatar>
              <div class="msg-content markdown-body" v-html="renderedAnswer"></div>
              <span v-if="streaming" class="cursor">▊</span>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" style="margin-top:16px">
          <el-input v-model="customQuestion" placeholder="输入自定义问题，回车发送" @keyup.enter="askCustom">
            <template #append>
              <el-button :loading="streaming" @click="askCustom">发送</el-button>
            </template>
          </el-input>
        </el-card>
      </el-col>

      <!-- 右侧：回答评估 -->
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header><span class="card-title">回答评估</span></template>
          <el-form label-position="top">
            <el-form-item label="问题">
              <el-input v-model="evalForm.question" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item label="你的回答">
              <el-input v-model="evalForm.userAnswer" type="textarea" :rows="6" placeholder="输入你的回答，AI 将评分" />
            </el-form-item>
            <el-button type="warning" :loading="evalLoading" @click="evaluate" style="width:100%">
              <el-icon><DataAnalysis /></el-icon>评估打分
            </el-button>
          </el-form>
        </el-card>

        <el-card v-if="evalResult" shadow="hover" style="margin-top:16px">
          <template #header><span class="card-title">评估结果</span></template>
          <div class="markdown-body" v-html="renderedEval"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, ChatLineSquare, DataAnalysis } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import api from '../api'

const md = new MarkdownIt({ html: true, breaks: true, linkify: true })

const genForm = reactive({ jobDescription: '', resumeText: '', count: 5 })
const genLoading = ref(false)
const questions = ref<string[]>([])
const currentQuestion = ref('')
const answer = ref('')
const streaming = ref(false)
const customQuestion = ref('')

const evalForm = reactive({ question: '', userAnswer: '' })
const evalLoading = ref(false)
const evalResult = ref('')

const renderedAnswer = computed(() => md.render(answer.value))
const renderedEval = computed(() => md.render(evalResult.value))

const generate = async () => {
  if (!genForm.jobDescription) { ElMessage.warning('请填写岗位描述'); return }
  genLoading.value = true
  try {
    const res = await api.post('/interview/questions', genForm)
    // 后端返回字符串，按换行或数字编号切分
    const text = res as string
    questions.value = text.split(/\n(?=\d+[.、])/).map(s => s.trim()).filter(Boolean)
    if (questions.value.length === 0) questions.value = [text]
    ElMessage.success(`生成 ${questions.value.length} 道题目`)
  } catch (e: any) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    genLoading.value = false
  }
}

const selectQuestion = (q: string) => {
  currentQuestion.value = q
  evalForm.question = q
  streamAnswer(q)
}

const askCustom = () => {
  if (!customQuestion.value.trim()) return
  currentQuestion.value = customQuestion.value
  evalForm.question = customQuestion.value
  streamAnswer(customQuestion.value)
  customQuestion.value = ''
}

const streamAnswer = async (question: string) => {
  answer.value = ''
  streaming.value = true
  try {
    // POST SSE：用 fetch + ReadableStream 接收
    const resp = await fetch(`${import.meta.env.VITE_API_BASE || '/api'}/interview/ask/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(localStorage.getItem('token') ? { Authorization: `Bearer ${localStorage.getItem('token')}` } : {})
      },
      body: JSON.stringify({ question })
    })
    if (!resp.ok || !resp.body) throw new Error('SSE 连接失败')

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 格式：event: token\ndata: xxx\n\n
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const token = line.slice(5).trim()
          if (token) answer.value += token
        }
      }
    }
  } catch (e: any) {
    ElMessage.error(e.message || '流式回答失败')
  } finally {
    streaming.value = false
  }
}

const evaluate = async () => {
  if (!evalForm.userAnswer) { ElMessage.warning('请输入你的回答'); return }
  evalLoading.value = true
  try {
    const res = await api.post('/interview/evaluate', evalForm)
    evalResult.value = res as string
    ElMessage.success('评估完成')
  } catch (e: any) {
    ElMessage.error(e.message || '评估失败')
  } finally {
    evalLoading.value = false
  }
}
</script>

<style scoped>
.interview-container { padding: 20px; }
.card-title { font-weight: 600; }
.question-item { padding: 8px; border-radius: 6px; cursor: pointer; margin-bottom: 6px; display: flex; gap: 8px; align-items: flex-start; }
.question-item:hover { background: #f5f7fa; }
.q-text { font-size: 13px; color: #606266; flex: 1; }
.chat-card { height: 500px; display: flex; flex-direction: column; }
.chat-header { display: flex; justify-content: space-between; align-items: center; }
.empty-chat { text-align: center; padding: 60px 0; color: #c0c4cc; }
.chat-body { display: flex; flex-direction: column; gap: 16px; height: 420px; overflow-y: auto; }
.msg { display: flex; gap: 8px; }
.msg-content { flex: 1; padding: 8px 12px; border-radius: 8px; font-size: 14px; line-height: 1.6; }
.user-msg .msg-content { background: #ecf5ff; }
.ai-msg .msg-content { background: #f0f9eb; }
.cursor { color: #67C23A; animation: blink 1s infinite; }
@keyframes blink { 0%,50%{opacity:1} 51%,100%{opacity:0} }
.markdown-body :deep(code) { background:#f5f7fa; padding:2px 6px; border-radius:4px; }
</style>

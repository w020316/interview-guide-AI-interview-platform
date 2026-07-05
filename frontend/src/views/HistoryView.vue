<template>
  <div>
    <h2>📋 历史面试记录</h2>

    <el-empty v-if="!sessions.length" description="暂无记录" />

    <el-collapse v-else>
      <el-collapse-item v-for="s in sessions" :key="s.sessionId"
        :title="`${s.jobDescription}  ·  ${fmtDate(s.createdAt)}  ·  ${s.status}`">
        <el-button size="small" @click="loadQuestions(s.sessionId)" :loading="loadingId===s.sessionId">
          查看题目
        </el-button>
        <div v-if="qMap[s.sessionId]" style="margin-top:12px">
          <el-card v-for="q in qMap[s.sessionId]" :key="q.id" style="margin-bottom:8px">
            <p><strong>Q：</strong>{{ q.question }}</p>
            <p v-if="q.userAnswer"><strong>我的回答：</strong>{{ q.userAnswer }}</p>
            <el-tag v-if="q.evaluationScore != null">评分：{{ q.evaluationScore }} 分</el-tag>
          </el-card>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'

interface Session {
  sessionId: string
  jobDescription: string
  status: string
  createdAt: string
}

interface Question {
  id: number
  question: string
  userAnswer?: string
  evaluationScore?: number | null
}

const sessions = ref<Session[]>([])
const qMap = ref<Record<string, Question[]>>({})
const loadingId = ref('')

onMounted(() => loadHistory())

async function loadHistory() {
  try {
    // userId 由后端从 token 提取，前端不传
    const data = await api.get('/api/session/list') as unknown as Session[]
    sessions.value = data || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载历史失败'))
  }
}

async function loadQuestions(sessionId: string) {
  loadingId.value = sessionId
  try {
    const data = await api.get(`/api/session/${sessionId}/questions`) as unknown as Question[]
    qMap.value = { ...qMap.value, [sessionId]: data || [] }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载题目失败'))
  } finally { loadingId.value = '' }
}

function fmtDate(dt: string) {
  if (!dt) return '-'
  const d = new Date(dt)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false })
}
</script>

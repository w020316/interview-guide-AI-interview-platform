<template>
  <div>
    <h2>📋 历史面试记录</h2>
    <el-input v-model="userId" placeholder="用户 ID（默认 user1）"
      style="max-width:300px;margin-bottom:16px" @change="loadHistory" />

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
            <el-tag v-if="q.evaluationScore !== null">评分：{{ q.evaluationScore }} 分</el-tag>
          </el-card>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const API = import.meta.env.VITE_API_BASE_URL || ''
const headers = () => ({ Authorization: `Bearer ${localStorage.getItem('token')}` })

const userId = ref('user1')
const sessions = ref<any[]>([])
const qMap = ref<Record<string, any[]>>({})
const loadingId = ref('')

onMounted(() => loadHistory())

async function loadHistory() {
  try {
    const { data } = await axios.get(`${API}/api/session/list`,
      { params: { userId: userId.value }, headers: headers() })
    sessions.value = data.code === 200 ? data.data : []
  } catch { ElMessage.error('加载历史失败') }
}

async function loadQuestions(sessionId: string) {
  loadingId.value = sessionId
  try {
    const { data } = await axios.get(`${API}/api/session/${sessionId}/questions`,
      { headers: headers() })
    qMap.value[sessionId] = data.data
  } catch { ElMessage.error('加载题目失败') }
  finally { loadingId.value = '' }
}

function fmtDate(dt: string) {
  return dt ? new Date(dt).toLocaleString('zh-CN', { hour12: false }) : ''
}
</script>

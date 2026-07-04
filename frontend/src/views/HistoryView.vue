<template>
  <div class="history-container">
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span class="card-title">面试会话历史</span>
          <el-button type="primary" @click="loadList">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </template>

      <el-table :data="sessions" v-loading="loading" stripe>
        <el-table-column prop="sessionId" label="会话 ID" width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="viewDetail(row.sessionId)">{{ row.sessionId.substring(0, 8) }}...</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="userId" label="用户" width="120" />
        <el-table-column prop="jobDescription" label="岗位" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'FINISHED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'FINISHED' ? '已结束' : '进行中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewDetail(row.sessionId)">查看</el-button>
            <el-button size="small" type="success" :disabled="row.status === 'FINISHED'" @click="finish(row.sessionId)">结束</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 会话详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="60%" :title="`会话详情：${currentSessionId?.substring(0, 8)}`">
      <div v-if="currentSession" class="session-info">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="会话 ID">{{ currentSession.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ currentSession.userId }}</el-descriptions-item>
          <el-descriptions-item label="岗位">{{ currentSession.jobDescription }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ currentSession.status }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ currentSession.createdAt }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <h4 style="margin: 20px 0 12px">题目与回答</h4>
      <el-empty v-if="!currentQuestions.length" description="该会话暂无题目" />
      <div v-else class="question-list">
        <div v-for="(q, i) in currentQuestions" :key="q.id" class="question-block">
          <div class="q-header">
            <el-tag type="info">Q{{ i + 1 }}</el-tag>
            <el-tag v-if="q.evaluationScore" type="success" size="small">{{ q.evaluationScore }} 分</el-tag>
          </div>
          <p class="q-content"><strong>题目：</strong>{{ q.questionContent }}</p>
          <p v-if="q.userAnswer" class="q-content"><strong>你的回答：</strong>{{ q.userAnswer }}</p>
          <el-empty v-else description="未作答" :image-size="40" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import api from '../api'

interface Session {
  id: number; sessionId: string; userId: string
  jobDescription: string; status: string; createdAt: string
}
interface Question {
  id: number; questionContent: string; userAnswer: string | null
  evaluationScore: number | null
}

const loading = ref(false)
const sessions = ref<Session[]>([])
const drawerVisible = ref(false)
const currentSessionId = ref('')
const currentSession = ref<Session | null>(null)
const currentQuestions = ref<Question[]>([])

const loadList = async () => {
  const username = localStorage.getItem('username')
  if (!username) { ElMessage.warning('请先登录'); return }
  loading.value = true
  try {
    const res = await api.get('/session/list', { params: { userId: username } })
    sessions.value = res as Session[]
  } catch (e: any) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const viewDetail = async (sessionId: string) => {
  currentSessionId.value = sessionId
  drawerVisible.value = true
  try {
    const [session, questions] = await Promise.all([
      api.get(`/session/${sessionId}`),
      api.get(`/session/${sessionId}/questions`)
    ])
    currentSession.value = session as Session
    currentQuestions.value = questions as Question[]
  } catch (e: any) {
    ElMessage.error(e.message || '加载详情失败')
  }
}

const finish = async (sessionId: string) => {
  try {
    await api.put(`/session/${sessionId}/finish`)
    ElMessage.success('会话已结束')
    loadList()
  } catch (e: any) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.history-container { padding: 20px; }
.card-title { font-weight: 600; }
.header-bar { display: flex; justify-content: space-between; align-items: center; }
.session-info { margin-bottom: 16px; }
.question-block { padding: 12px; background: #f5f7fa; border-radius: 6px; margin-bottom: 12px; }
.q-header { display: flex; gap: 8px; margin-bottom: 8px; }
.q-content { margin: 4px 0; font-size: 13px; color: #606266; line-height: 1.6; }
</style>

<template>
  <div>
    <h2>🤖 AI 模拟面试</h2>

    <!-- Step 1: 创建会话 -->
    <el-card v-if="!sessionId" style="max-width:600px">
      <el-form label-width="100px">
        <el-form-item label="目标岗位">
          <el-input v-model="jobDesc" placeholder="Java 后端开发工程师" />
        </el-form-item>
        <el-form-item label="简历摘要">
          <el-input v-model="resumeText" type="textarea" :rows="4" placeholder="粘贴简历核心内容（可选）" />
        </el-form-item>
        <el-form-item label="题目数量">
          <el-input-number v-model="count" :min="3" :max="10" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="startInterview">开始面试</el-button>
      </el-form>
    </el-card>

    <!-- Step 2: 面试进行中 -->
    <div v-else>
      <el-progress :percentage="progress" :stroke-width="12" style="margin-bottom:20px" />

      <el-card v-if="currentQ" class="question-card">
        <div class="q-meta">
          <el-tag>{{ currentQ.category }}</el-tag>
          <el-tag :type="diffColor(currentQ.difficulty)">{{ currentQ.difficulty }}</el-tag>
          <span style="color:#999;margin-left:8px">第 {{ qIndex+1 }} / {{ questions.length }} 题</span>
        </div>
        <h3 style="margin:16px 0">{{ currentQ.question }}</h3>

        <!-- SSE 流式 AI 提示 -->
        <el-collapse style="margin-bottom:16px">
          <el-collapse-item title="💡 AI 实时提示（流式）">
            <div class="stream-box" v-html="streamHtml" />
            <el-button size="small" @click="streamHint" :loading="streaming">获取 AI 提示</el-button>
          </el-collapse-item>
        </el-collapse>

        <el-input v-model="userAnswer" type="textarea" :rows="6" placeholder="请输入你的回答..." />
        <div style="margin-top:12px;display:flex;gap:8px">
          <el-button type="primary" :loading="evalLoading" @click="submitAnswer">提交回答</el-button>
          <el-button @click="nextQuestion" v-if="qIndex < questions.length-1">跳过</el-button>
          <el-button type="success" @click="finishSession" v-if="qIndex === questions.length-1">结束面试</el-button>
        </div>
      </el-card>

      <el-card v-if="evalResult" class="eval-card">
        <h4>📊 AI 评估</h4>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="综合">{{ evalResult.overallScore }}分</el-descriptions-item>
          <el-descriptions-item label="完整性">{{ evalResult.completeness }}分</el-descriptions-item>
          <el-descriptions-item label="准确性">{{ evalResult.accuracy }}分</el-descriptions-item>
        </el-descriptions>
        <ul style="margin-top:12px">
          <li v-for="i in evalResult.improvements" :key="i" style="color:#e6a23c">{{ i }}</li>
        </ul>
        <el-button type="primary" style="margin-top:12px" @click="nextQuestion"
          v-if="qIndex < questions.length-1">下一题</el-button>
        <el-button type="success" style="margin-top:12px" @click="finishSession"
          v-else>结束面试</el-button>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt()
const API = import.meta.env.VITE_API_BASE_URL || ''
const headers = () => ({ Authorization: `Bearer ${localStorage.getItem('token')}` })

const jobDesc = ref('Java 后端开发工程师')
const resumeText = ref('')
const count = ref(5)
const loading = ref(false)
const sessionId = ref('')
const questions = ref<any[]>([])
const qIndex = ref(0)
const userAnswer = ref('')
const evalLoading = ref(false)
const evalResult = ref<any>(null)
const streaming = ref(false)
const streamContent = ref('')

const currentQ = computed(() => questions.value[qIndex.value])
const progress = computed(() => Math.round((qIndex.value / questions.value.length) * 100))
const streamHtml = computed(() => md.render(streamContent.value))

function diffColor(d: string) {
  return d === 'HARD' ? 'danger' : d === 'MEDIUM' ? 'warning' : 'success'
}

async function startInterview() {
  if (!jobDesc.value.trim()) return ElMessage.warning('请填写目标岗位')
  loading.value = true
  try {
    // 创建会话
    const { data: sess } = await axios.post(`${API}/api/session/create`,
      { userId: 'user1', jobDescription: jobDesc.value }, { headers: headers() })
    sessionId.value = sess.data.sessionId

    // 生成面试题
    const { data: qs } = await axios.post(`${API}/api/interview/questions`,
      { resumeText: resumeText.value || jobDesc.value, jobDescription: jobDesc.value, count: count.value },
      { headers: headers() })
    questions.value = JSON.parse(qs.data)
    ElMessage.success(`已生成 ${questions.value.length} 道题目，开始面试！`)
  } catch (e: any) {
    ElMessage.error('创建面试失败')
  } finally { loading.value = false }
}

async function streamHint() {
  if (!currentQ.value) return
  streaming.value = true
  streamContent.value = ''
  try {
    const resp = await fetch(`${API}/api/interview/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers() },
      body: JSON.stringify({ question: currentQ.value.question })
    })
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder()
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const text = decoder.decode(value)
      // 解析 SSE data: <token> 格式
      text.split('\n').forEach(line => {
        if (line.startsWith('data:')) streamContent.value += line.slice(5)
      })
    }
  } catch (e) { ElMessage.error('流式请求失败') }
  finally { streaming.value = false }
}

async function submitAnswer() {
  if (!userAnswer.value.trim()) return ElMessage.warning('请输入回答')
  evalLoading.value = true
  evalResult.value = null
  try {
    const { data } = await axios.post(`${API}/api/interview/evaluate`, {
      question: currentQ.value.question,
      userAnswer: userAnswer.value,
      referenceAnswer: currentQ.value.referenceAnswer
    }, { headers: headers() })
    evalResult.value = JSON.parse(data.data)
  } catch { ElMessage.error('评估失败') }
  finally { evalLoading.value = false }
}

function nextQuestion() {
  qIndex.value++
  userAnswer.value = ''
  evalResult.value = null
  streamContent.value = ''
}

async function finishSession() {
  try {
    await axios.put(`${API}/api/session/${sessionId.value}/finish`, {}, { headers: headers() })
    ElMessage.success('面试结束，结果已保存！')
    sessionId.value = ''
    qIndex.value = 0
  } catch { ElMessage.error('结束失败') }
}
</script>

<style scoped>
.question-card { margin-bottom:16px; }
.q-meta { display:flex; gap:8px; flex-wrap:wrap; }
.stream-box { font-size:14px; line-height:1.6; max-height:200px; overflow-y:auto;
  background:#f8f8f8; padding:8px; border-radius:4px; margin-bottom:8px; }
.eval-card { background:#fdf6ec; }
</style>

<template>
  <div>
    <h2>📄 简历分析</h2>
    <el-tabs v-model="tab">
      <el-tab-pane label="上传 PDF" name="upload">
        <el-upload drag accept=".pdf,.txt"
          :before-upload="handleUpload" :show-file-list="false" :http-request="() => {}">
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div>拖拽文件到此处或 <em>点击上传</em></div>
          <template #tip><div style="color:#999">支持 PDF / TXT，≤10MB</div></template>
        </el-upload>
        <el-form-item label="目标岗位" style="margin-top:16px;max-width:400px">
          <el-input v-model="targetJob" placeholder="Java 后端开发" />
        </el-form-item>
      </el-tab-pane>
      <el-tab-pane label="粘贴文本" name="text">
        <el-input v-model="resumeText" type="textarea" :rows="10" placeholder="粘贴简历文本..." />
        <el-form-item label="目标岗位" style="margin-top:12px">
          <el-input v-model="targetJob" placeholder="Java 后端开发" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="analyzeText" style="margin-top:8px">
          开始分析
        </el-button>
      </el-tab-pane>
    </el-tabs>

    <div v-if="loading" class="loading-tip">
      <el-icon class="is-loading"><loading /></el-icon> AI 分析中，请稍候（首次调用需冷启动，最长约 1-2 分钟）…
    </div>

    <div v-if="result" class="result-section">
      <el-divider>分析结果</el-divider>
      <el-alert v-if="parseError" type="warning" :title="parseError" show-icon :closable="false" style="margin-bottom:12px" />
      <el-descriptions :column="2" border>
        <el-descriptions-item label="综合评分">
          <el-tag type="success" size="large">{{ parsed.overallScore }} 分</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-collapse v-if="parsed.dimensions?.length" style="margin-top:16px">
        <el-collapse-item v-for="(d, idx) in parsed.dimensions" :key="idx"
          :title="`${d.name}  ${d.score}分`">
          {{ d.suggestion }}
        </el-collapse-item>
      </el-collapse>
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="12">
          <el-card header="✅ 优势">
            <ul v-if="parsed.strengths?.length">
              <li v-for="(s, idx) in parsed.strengths" :key="idx">{{ s }}</li>
            </ul>
            <el-empty v-else description="无" :image-size="40" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card header="💡 改进建议">
            <ul v-if="parsed.improvements?.length">
              <li v-for="(i, idx) in parsed.improvements" :key="idx">{{ i }}</li>
            </ul>
            <el-empty v-else description="无" :image-size="40" />
          </el-card>
        </el-col>
      </el-row>
      <el-collapse style="margin-top:16px">
        <el-collapse-item title="查看 AI 原始返回">
          <pre class="raw-output">{{ result }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT } from '../api'

interface AnalysisResult {
  overallScore: number
  dimensions: Array<{ name: string; score: number; suggestion: string }>
  strengths: string[]
  improvements: string[]
}

const tab = ref('upload')
const resumeText = ref('')
const targetJob = ref('Java 后端开发')
const loading = ref(false)
const result = ref('')
const parseError = ref('')

const parsed = computed<AnalysisResult>(() => {
  if (!result.value) {
    return { overallScore: 0, dimensions: [], strengths: [], improvements: [] }
  }
  try {
    const obj = JSON.parse(result.value)
    return {
      overallScore: obj.overallScore ?? 0,
      dimensions: obj.dimensions ?? [],
      strengths: obj.strengths ?? [],
      improvements: obj.improvements ?? [],
    }
  } catch {
    return { overallScore: 0, dimensions: [], strengths: [], improvements: [] }
  }
})

// 监听 result 变化，更新 parseError（避免在 computed 中产生副作用）
watch(result, (val) => {
  if (!val) {
    parseError.value = ''
  } else {
    try {
      JSON.parse(val)
      parseError.value = ''
    } catch (e) {
      parseError.value = 'AI 返回内容无法解析为标准 JSON，可在下方查看原始返回。错误：' + (e as Error).message
    }
  }
})

function handleResult(data: unknown) {
  if (data == null || (typeof data === 'string' && !data.trim())) {
    ElMessage.error('AI 返回为空，请重试')
    result.value = ''
    return false
  }
  result.value = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
  return true
}

async function handleUpload(file: File) {
  // 文件大小校验
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10MB')
    return false
  }
  // 文件类型校验
  const allowed = ['.pdf', '.txt']
  const ext = file.name.toLowerCase().match(/\.[^.]+$/)?.[0] || ''
  if (!allowed.includes(ext)) {
    ElMessage.error('仅支持 PDF、TXT 格式')
    return false
  }

  loading.value = true
  result.value = ''
  const form = new FormData()
  form.append('file', file)
  form.append('targetJob', targetJob.value)
  try {
    const data = await api.post('/api/resume/upload', form, { timeout: AI_TIMEOUT }) as unknown as string
    if (handleResult(data)) ElMessage.success('分析完成')
  } catch (e: unknown) {
    const err = e as { code?: string; message?: string; response?: { data?: { message?: string } } }
    const msg = err?.response?.data?.message || err?.message || '上传失败'
    ElMessage.error(msg)
  } finally { loading.value = false }
  return false
}

async function analyzeText() {
  if (!resumeText.value.trim()) return ElMessage.warning('请输入简历内容')
  loading.value = true
  result.value = ''
  try {
    const data = await api.post('/api/resume/analyze',
      { resumeText: resumeText.value, targetJob: targetJob.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    if (handleResult(data)) ElMessage.success('分析完成')
  } catch (e: unknown) {
    const err = e as { code?: string; message?: string; response?: { data?: { message?: string } } }
    const msg = err?.response?.data?.message || err?.message || '分析失败'
    ElMessage.error(msg)
  } finally { loading.value = false }
}
</script>
<style scoped>
.loading-tip { text-align:center; padding:20px; color:#409eff; font-size:16px; }
.result-section { margin-top:24px; }
.raw-output { white-space: pre-wrap; word-break: break-word; background:#f5f7fa; padding:12px; border-radius:4px; font-size:13px; max-height:400px; overflow:auto; }
</style>

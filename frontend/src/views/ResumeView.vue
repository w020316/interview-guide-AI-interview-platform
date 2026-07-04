<template>
  <div>
    <h2>📄 简历分析</h2>
    <el-tabs v-model="tab">
      <el-tab-pane label="上传 PDF" name="upload">
        <el-upload drag accept=".pdf,.docx,.doc,.txt"
          :before-upload="handleUpload" :show-file-list="false" :http-request="() => {}">
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div>拖拽文件到此处或 <em>点击上传</em></div>
          <template #tip><div style="color:#999">支持 PDF / Word / TXT，≤10MB</div></template>
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
      <el-icon class="is-loading"><loading /></el-icon> AI 分析中，请稍候…
    </div>

    <div v-if="result" class="result-section">
      <el-divider>分析结果</el-divider>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="综合评分">
          <el-tag type="success" size="large">{{ parsed.overallScore }} 分</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-collapse style="margin-top:16px">
        <el-collapse-item v-for="d in parsed.dimensions" :key="d.name"
          :title="`${d.name}  ${d.score}分`">
          {{ d.suggestion }}
        </el-collapse-item>
      </el-collapse>
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="12">
          <el-card header="✅ 优势">
            <li v-for="s in parsed.strengths" :key="s">{{ s }}</li>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card header="💡 改进建议">
            <li v-for="i in parsed.improvements" :key="i">{{ i }}</li>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
const tab = ref('upload')
const resumeText = ref('')
const targetJob = ref('Java 后端开发')
const loading = ref(false)
const result = ref('')
const parsed = computed(() => { try { return JSON.parse(result.value) } catch { return {} } })
const API = import.meta.env.VITE_API_BASE_URL || ''
const token = () => ({ Authorization: `Bearer ${localStorage.getItem('token')}` })

async function handleUpload(file: File) {
  loading.value = true
  const form = new FormData()
  form.append('file', file)
  form.append('targetJob', targetJob.value)
  try {
    const { data } = await axios.post(`${API}/api/resume/upload`, form, { headers: token() })
    if (data.code === 200) { result.value = data.data; ElMessage.success('分析完成') }
    else ElMessage.error(data.message)
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '上传失败') }
  finally { loading.value = false }
  return false
}
async function analyzeText() {
  if (!resumeText.value.trim()) return ElMessage.warning('请输入简历内容')
  loading.value = true
  try {
    const { data } = await axios.post(`${API}/api/resume/analyze`,
      { resumeText: resumeText.value, targetJob: targetJob.value }, { headers: token() })
    if (data.code === 200) { result.value = data.data; ElMessage.success('分析完成') }
    else ElMessage.error(data.message)
  } catch (e: any) { ElMessage.error('分析失败') }
  finally { loading.value = false }
}
</script>
<style scoped>
.loading-tip { text-align:center; padding:20px; color:#409eff; font-size:16px; }
.result-section { margin-top:24px; }
</style>

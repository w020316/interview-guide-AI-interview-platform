<template>
  <div class="resume-container">
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <span class="card-title">输入简历与目标岗位</span>
          </template>
          <el-form label-position="top">
            <el-form-item label="目标岗位">
              <el-input v-model="form.targetJob" placeholder="例如：Java 后端开发" />
            </el-form-item>
            <el-form-item label="简历内容">
              <el-input v-model="form.resumeText" type="textarea" :rows="10" placeholder="粘贴简历文本或上传 PDF 自动填充" />
            </el-form-item>
            <el-form-item label="上传简历文件（可选）">
              <el-upload
                :http-request="handleUpload"
                :show-file-list="false"
                accept=".pdf,.docx,.doc,.txt"
                :before-upload="beforeUpload"
              >
                <el-button type="primary" plain :loading="uploading">
                  <el-icon><Upload /></el-icon>
                  {{ uploading ? '解析中...' : '上传 PDF / Word' }}
                </el-button>
                <template #tip>
                  <div class="upload-tip">支持 PDF / DOCX / DOC / TXT，上传后自动解析并填充到文本框</div>
                </template>
              </el-upload>
            </el-form-item>
            <el-button type="primary" :loading="loading" @click="analyze">
              <el-icon><MagicStick /></el-icon>开始分析
            </el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="hover" class="result-card">
          <template #header>
            <div class="result-header">
              <span class="card-title">AI 分析结果</span>
              <el-button v-if="result" text type="primary" @click="copyResult">
                <el-icon><CopyDocument /></el-icon>复制
              </el-button>
            </div>
          </template>
          <div v-if="loading" class="loading-box">
            <el-skeleton :rows="8" animated />
          </div>
          <div v-else-if="result" class="markdown-body" v-html="renderedHtml"></div>
          <el-empty v-else description="尚未分析，请填写简历后点击开始分析" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, MagicStick, CopyDocument } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import api from '../api'

const md = new MarkdownIt({ html: true, breaks: true, linkify: true })
const loading = ref(false)
const uploading = ref(false)
const result = ref('')
const form = reactive({ targetJob: 'Java 后端开发', resumeText: '' })

const renderedHtml = computed(() => md.render(result.value))

const beforeUpload = (file: File) => {
  const ok = /\.(pdf|docx?|txt)$/i.test(file.name)
  if (!ok) ElMessage.warning('仅支持 PDF / Word / TXT')
  return ok
}

const handleUpload = async (options: any) => {
  const file = options.file as File
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('targetJob', form.targetJob)
    // 直接走 /api/resume/upload，返回的是 AI 分析结果
    const res = await api.post('/resume/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    result.value = res as string
    // 如果文本框为空，把解析出的简历文本提示给用户（这里后端返回的是分析结果，不做填充）
    ElMessage.success('简历解析 + 分析完成')
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const analyze = async () => {
  if (!form.resumeText.trim()) {
    ElMessage.warning('请输入简历内容或上传文件')
    return
  }
  loading.value = true
  try {
    const res = await api.post('/resume/analyze', form)
    result.value = res as string
    ElMessage.success('分析完成')
  } catch (e: any) {
    ElMessage.error(e.message || '分析失败')
  } finally {
    loading.value = false
  }
}

const copyResult = () => {
  navigator.clipboard.writeText(result.value)
  ElMessage.success('已复制到剪贴板')
}
</script>

<style scoped>
.resume-container { padding: 20px; }
.card-title { font-weight: 600; }
.upload-tip { font-size: 12px; color: #909399; margin-top: 4px; }
.result-header { display: flex; justify-content: space-between; align-items: center; }
.loading-box { padding: 20px 0; }
.markdown-body { line-height: 1.8; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) { margin: 16px 0 8px; color: #303133; }
.markdown-body :deep(code) { background: #f5f7fa; padding: 2px 6px; border-radius: 4px; font-family: Consolas, monospace; }
.markdown-body :deep(pre) { background: #2d2d2d; color: #ccc; padding: 12px; border-radius: 6px; overflow-x: auto; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 24px; }
</style>

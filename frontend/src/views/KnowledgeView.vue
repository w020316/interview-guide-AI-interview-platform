<template>
  <div class="knowledge-page">
    <header class="page-header">
      <h1>知识库</h1>
      <p>导入面试知识点 / 八股文，AI 将基于知识库增强问答</p>
    </header>

    <!-- Tab 切换 -->
    <div class="tab-switch">
      <button :class="{ active: tab === 'ask' }" @click="tab = 'ask'">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
            stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        RAG 问答
      </button>
      <button :class="{ active: tab === 'import' }" @click="tab = 'import'">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        导入知识
      </button>
    </div>

    <!-- RAG 问答 -->
    <div v-if="tab === 'ask'" class="ask-section fade-in">
      <div class="field-row">
        <label>你的问题</label>
        <textarea v-model="question" rows="3" placeholder="例如：HashMap 的底层原理是什么？"></textarea>
      </div>
      <button class="btn-primary" :disabled="loading" @click="ask">
        <span v-if="loading" class="spinner"></span>
        {{ loading ? '查询中…' : 'AI 知识问答' }}
      </button>

      <div v-if="answer" class="answer-card fade-in-up">
        <div class="answer-head">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2z M12 16v-4 M12 8h.01"
              stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <h4>AI 回答</h4>
        </div>
        <div class="answer-body" v-html="renderedAnswer"></div>
      </div>
    </div>

    <!-- 导入知识 -->
    <div v-else class="import-section fade-in">
      <div class="field-row">
        <label>分类 <span class="hint">可选</span></label>
        <input v-model="category" type="text" placeholder="例如：Spring、Java 基础" />
      </div>
      <div class="field-row">
        <label>知识点内容 <span class="hint">每行一条，建议 200 字以内</span></label>
        <textarea v-model="importText" rows="10"
          placeholder="例如：&#10;HashMap 基于哈希表实现，JDK 8 后采用数组+链表+红黑树结构。&#10;ConcurrentHashMap 在 JDK 8 中使用 CAS + synchronized 实现。"></textarea>
      </div>
      <button class="btn-primary" :disabled="importing" @click="importKnowledge">
        <span v-if="importing" class="spinner"></span>
        {{ importing ? '导入中…' : '批量导入' }}
      </button>

      <div v-if="importResult" class="result-card fade-in-up" :class="importResult.success ? 'success' : 'error'">
        <span>{{ importResult.message }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import api, { AI_TIMEOUT, getErrMessage } from '../api'

const md = new MarkdownIt({ html: false, linkify: true })

const tab = ref<'ask' | 'import'>('ask')
const question = ref('')
const answer = ref('')
const loading = ref(false)

const category = ref('')
const importText = ref('')
const importing = ref(false)
const importResult = ref<{ success: boolean; message: string } | null>(null)

const renderedAnswer = computed(() => md.render(answer.value || '*等待提问...*'))

async function ask() {
  if (!question.value.trim()) return ElMessage.warning('请输入问题')
  loading.value = true
  answer.value = ''
  try {
    const data = await api.post('/api/knowledge/ask',
      { question: question.value },
      { timeout: AI_TIMEOUT }) as unknown as string
    answer.value = data || '(空回答)'
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '问答失败'))
  } finally {
    loading.value = false
  }
}

async function importKnowledge() {
  if (!importText.value.trim()) return ElMessage.warning('请输入知识内容')
  const chunks = importText.value
    .split('\n')
    .map(s => s.trim())
    .filter(s => s.length > 0)
  if (!chunks.length) return ElMessage.warning('未识别到有效知识内容')

  importing.value = true
  importResult.value = null
  try {
    const data = await api.post('/api/knowledge/import/batch',
      { category: category.value || '通用', chunks },
      { timeout: AI_TIMEOUT }) as unknown as { imported: number; category: string }
    importResult.value = {
      success: true,
      message: `成功导入 ${data.imported} 条知识（分类：${data.category}）`,
    }
    ElMessage.success('导入成功')
    importText.value = ''
  } catch (e: unknown) {
    importResult.value = {
      success: false,
      message: getErrMessage(e, '导入失败'),
    }
    ElMessage.error('导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.knowledge-page {
  max-width: 900px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: var(--c-text);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.page-header p {
  font-size: 14px;
  color: var(--c-text-secondary);
  margin: 0;
}

/* ── Tab ── */
.tab-switch {
  display: inline-flex;
  background: var(--c-bg-alt);
  border-radius: var(--radius-md);
  padding: 4px;
  margin-bottom: 24px;
  gap: 4px;
}

.tab-switch button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  font-family: inherit;
}

.tab-switch button.active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--shadow-sm);
}

/* ── 表单 ── */
.field-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.field-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.hint {
  color: var(--c-text-tertiary);
  font-weight: 400;
  font-size: 12px;
}

.field-row input,
.field-row textarea {
  padding: 11px 14px;
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: all var(--transition-fast);
  resize: vertical;
}

.field-row input:focus,
.field-row textarea:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
}

/* ── 按钮 ── */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 24px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-brand);
  font-family: inherit;
}

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.4);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 答案卡片 ── */
.answer-card {
  margin-top: 24px;
  padding: 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-border-light);
}

.answer-head h4 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
  color: var(--c-text);
}

.answer-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text);
  word-break: break-word;
}

.answer-body :deep(h1),
.answer-body :deep(h2),
.answer-body :deep(h3) {
  font-size: 16px;
  margin: 16px 0 8px;
  color: var(--c-text);
}

.answer-body :deep(code) {
  background: var(--c-bg-alt);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--brand-primary);
}

.answer-body :deep(pre) {
  background: var(--c-bg-alt);
  padding: 12px;
  border-radius: var(--radius-md);
  overflow-x: auto;
  margin: 8px 0;
}

.answer-body :deep(p) {
  margin: 8px 0;
}

/* ── 结果卡片 ── */
.result-card {
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: var(--radius-md);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-card.success {
  background: var(--c-success-light);
  color: var(--c-success);
  border: 1px solid var(--c-success);
}

.result-card.error {
  background: var(--c-danger-light);
  color: var(--c-danger);
  border: 1px solid var(--c-danger);
}
</style>

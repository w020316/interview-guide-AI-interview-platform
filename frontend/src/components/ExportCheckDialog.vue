<template>
  <el-dialog
    :model-value="visible"
    title="导出体检"
    width="min(640px, 92vw)"
    :close-on-click-modal="false"
    align-center
    class="export-check-dialog"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <div class="export-check-body">
      <p class="intro">
        先在编辑器点「导出 PDF」，在系统打印对话框里选「另存为 PDF」，再上传<strong>刚刚保存的那份文件</strong>——体检的是你真正要投出去的那一份，而不是页面里的预览。
      </p>

      <input
        ref="fileRef"
        class="file-input"
        type="file"
        accept=".pdf,application/pdf"
        tabindex="-1"
        @change="onFileChange"
      />

      <button type="button" class="pick-zone" :disabled="busy" @click="openPicker">
        <span class="pick-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
            <path d="M14 2v6h6" />
          </svg>
        </span>
        <span class="pick-main">{{ fileName || '选择要体检的 PDF' }}</span>
        <span class="pick-sub">
          {{ busy ? '正在解析 PDF…' : fileName ? '换一份文件再体检' : '只支持 PDF，最大 10MB' }}
        </span>
      </button>

      <!-- 隐私说明放在上传入口下方、结果之前：用户决定「要不要上传」时就要看到它 -->
      <p class="privacy-note">只在本站服务端解析：不调用 AI，也不保存你的文件。换一份文件即重新体检。</p>

      <p v-if="errorMsg" class="error-block">{{ errorMsg }}</p>

      <template v-if="report">
        <div class="verdict" :class="`is-${report.verdict}`">
          <span class="verdict-icon" aria-hidden="true">
            <svg v-if="report.verdict === 'pass'" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M8.5 12.5l2.5 2.5 4.5-5" />
            </svg>
            <svg v-else-if="report.verdict === 'warn'" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
              <path d="M12 9v4M12 17h.01" />
            </svg>
            <svg v-else viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M15 9l-6 6M9 9l6 6" />
            </svg>
          </span>
          <div class="verdict-text">
            <div class="verdict-title">{{ verdictLabel(report.verdict) }}</div>
            <p class="verdict-summary">{{ report.summary }}</p>
          </div>
        </div>

        <div class="stats">
          <div class="stat">
            <span class="stat-label">页数</span>
            <span class="stat-value">{{ formatCount(report.pageCount) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">可提取文字</span>
            <span class="stat-value">{{ formatCount(report.charCount) }} 字</span>
          </div>
        </div>

        <div class="checks">
          <div v-for="c in report.checks" :key="c.id" class="check" :class="`is-${c.status}`">
            <span class="check-icon" aria-hidden="true">
              <svg v-if="c.status === 'pass'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M8.5 12.5l2.5 2.5 4.5-5" />
              </svg>
              <svg v-else-if="c.status === 'warn'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
                <path d="M12 9v4M12 17h.01" />
              </svg>
              <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M15 9l-6 6M9 9l6 6" />
              </svg>
            </span>
            <div class="check-body">
              <div class="check-head">
                <span class="check-label">{{ c.label }}</span>
                <span class="check-status">{{ statusLabel(c.status) }}</span>
              </div>
              <p v-if="c.detail" class="check-detail">{{ c.detail }}</p>
            </div>
          </div>
        </div>
      </template>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <BaseButton variant="ghost" size="sm" @click="emit('update:visible', false)">关闭</BaseButton>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 导出体检对话框（v1.85.0）
 *
 * ── 为什么必须「先导出、再上传」────────────────────────────────────
 * 体检对象是**浏览器打印出来的那份 PDF**，而不是页面预览：字号、分页、字体嵌入
 * 都只在打印产物里才最终确定。因此引导文案必须把「另存为 PDF」这一步写死，
 * 否则用户会拿 Word/docx 或截图来体检，得到一份「只支持 PDF」的错误提示而非结论。
 *
 * ── 为什么不上传就去调 AI ──────────────────────────────────────────
 * 判定全部是 PDFBox 的本地规则（有无文字层、页数、可疑字符、板块关键词），
 * 简历含手机号/邮箱属 PII，不做任何 AI 调用、不落盘；报告也从不回显这些值。
 */
import { ref, watch } from 'vue'
import BaseButton from './BaseButton.vue'
import api, { getErrMessage } from '../api'
import {
  formatCount,
  normalizeExportReport,
  statusLabel,
  validatePdfFile,
  verdictLabel,
  type ExportCheckReport,
} from '../utils/exportCheck'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const fileRef = ref<HTMLInputElement | null>(null)
const busy = ref(false)
const report = ref<ExportCheckReport | null>(null)
const errorMsg = ref('')
const fileName = ref('')

// 每次打开都从干净状态开始：上一轮的报告留着会让人误以为是这次文件的结果
watch(
  () => props.visible,
  (v) => {
    if (!v) return
    busy.value = false
    report.value = null
    errorMsg.value = ''
    fileName.value = ''
    if (fileRef.value) fileRef.value.value = ''
  }
)

function openPicker(): void {
  if (busy.value) return
  fileRef.value?.click()
}

async function onFileChange(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空 input，否则「同一份文件修改后重选」不会触发 change
  input.value = ''
  if (!file) return

  fileName.value = file.name
  report.value = null
  errorMsg.value = ''

  const invalid = validatePdfFile(file.name, file.size)
  if (invalid) {
    errorMsg.value = invalid
    return
  }

  busy.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await api.post('/api/resume/export-check', form)
    const normalized = normalizeExportReport(res)
    report.value = normalized
    if (!normalized) errorMsg.value = '没有拿到有效的体检结果，请稍后重试一次'
  } catch (err) {
    errorMsg.value = getErrMessage(err, '体检失败，请稍后重试')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.export-check-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.intro {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.intro strong {
  color: var(--c-text);
}

.file-input {
  display: none;
}

.pick-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 20px 16px;
  border: 1px dashed var(--c-border-strong);
  border-radius: var(--radius-lg);
  background: var(--c-bg-alt);
  color: var(--c-text);
  font: inherit;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.pick-zone:hover:not(:disabled) {
  border-color: var(--brand-primary);
  background: var(--brand-primary-light);
}

.pick-zone:disabled {
  cursor: progress;
  opacity: 0.7;
}

.pick-icon {
  display: inline-flex;
  color: var(--brand-primary);
}

.pick-main {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
}

.pick-sub {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.error-block {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--c-danger-border);
  border-radius: var(--radius-md);
  background: var(--c-danger-light);
  color: var(--c-danger);
  font-size: 13px;
  line-height: 1.6;
}

.verdict {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  background: var(--c-bg-alt);
}

.verdict.is-pass {
  border-color: var(--c-success-border);
  background: var(--c-success-light);
}

.verdict.is-warn {
  border-color: var(--c-warning-border);
  background: var(--c-warning-light);
}

.verdict.is-fail {
  border-color: var(--c-danger-border);
  background: var(--c-danger-light);
}

.verdict-icon {
  display: inline-flex;
  flex-shrink: 0;
  margin-top: 1px;
}

.verdict.is-pass .verdict-icon {
  color: var(--c-success);
}

.verdict.is-warn .verdict-icon {
  color: var(--c-warning);
}

.verdict.is-fail .verdict-icon {
  color: var(--c-danger);
}

.verdict-text {
  min-width: 0;
}

.verdict-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}

.verdict-summary {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.stats {
  display: flex;
  gap: 12px;
}

.stat {
  display: flex;
  flex: 1;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  background: var(--c-bg-alt);
}

.stat-label {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.stat-value {
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.checks {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.check {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-md);
  background: var(--c-surface);
}

.check-icon {
  display: inline-flex;
  flex-shrink: 0;
  margin-top: 1px;
}

.check.is-pass .check-icon {
  color: var(--c-success);
}

.check.is-warn .check-icon {
  color: var(--c-warning);
}

.check.is-fail .check-icon {
  color: var(--c-danger);
}

.check-body {
  min-width: 0;
}

.check-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.check-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.check-status {
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--c-bg-alt);
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.check.is-pass .check-status {
  background: var(--c-success-light);
  color: var(--c-success);
}

.check.is-warn .check-status {
  background: var(--c-warning-light);
  color: var(--c-warning);
}

.check.is-fail .check-status {
  background: var(--c-danger-light);
  color: var(--c-danger);
}

.check-detail {
  margin: 3px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.privacy-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 640px) {
  .stats {
    flex-direction: column;
    gap: 8px;
  }
}
</style>

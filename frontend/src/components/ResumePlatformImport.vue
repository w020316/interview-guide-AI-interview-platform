<template>
  <section class="pi-block" aria-labelledby="pi-title">
    <div class="pi-head">
      <h4 id="pi-title" class="pi-title">从第三方平台导入</h4>
      <p class="pi-sub">
        在招聘 App 里截图或导出简历，再回来上传。手机端可直接唤起对应 App；
        截屏自动进相册，不用下载保存，回来选中就能识别。
      </p>
    </div>

    <div class="pi-grid">
      <article v-for="platform in platforms" :key="platform.key" class="pi-card">
        <div class="pi-card-head">
          <span class="pi-badge" aria-hidden="true">{{ platform.badge }}</span>
          <span class="pi-name">{{ platform.name }}</span>
        </div>
        <p class="pi-hint">{{ platform.exportHint }}</p>
        <div class="pi-actions">
          <button
            v-if="isMobile"
            type="button"
            class="pi-btn pi-btn-primary"
            :disabled="!!launchingKey || disabled"
            @click="launch(platform)"
          >
            {{ launchingKey === platform.key ? '正在唤起…' : '在 App 中打开' }}
          </button>
          <button
            type="button"
            class="pi-btn"
            :class="{ 'pi-btn-primary': !isMobile }"
            :disabled="disabled"
            @click="openWeb(platform)"
          >
            打开网页版
          </button>
          <button type="button" class="pi-btn pi-btn-quiet" :disabled="disabled" @click="$emit('pick-file')">
            选择 PDF 文件
          </button>
        </div>
      </article>
    </div>

    <p v-if="launchNotice" class="pi-notice" :class="{ 'is-warn': launchNoticeWarn }" role="status">
      {{ launchNotice }}
      <button v-if="showContinue" type="button" class="pi-inline-link" @click="$emit('pick-image')">
        选择截图提取
      </button>
      <button
        v-if="sheetPlatform && !sheetOpen"
        type="button"
        class="pi-inline-link"
        @click="reopenSheet"
      >
        查看取件步骤
      </button>
    </p>

    <div class="pi-link">
      <div class="pi-link-head">
        <h5 class="pi-link-title">已拿到平台分享链接？直接导入 PDF</h5>
        <span class="pi-link-tag">授权导入</span>
      </div>
      <div class="pi-field">
        <label class="pi-label" for="pi-link-url">PDF 分享链接</label>
        <BaseInput
          id="pi-link-url"
          v-model="linkUrl"
          type="url"
          block
          placeholder="https://example.com/resume.pdf"
          :disabled="busy || disabled"
          autocomplete="off"
        />
      </div>
      <label class="pi-consent">
        <input v-model="authorized" type="checkbox" :disabled="busy || disabled" />
        <span>{{ consentText }}</span>
      </label>
      <button
        type="button"
        class="pi-btn pi-btn-primary pi-submit"
        :disabled="!canSubmit"
        @click="submitLink"
      >
        {{ busy ? '导入中…' : '授权并导入' }}
      </button>

      <div v-if="busy" class="pi-progress" role="status" aria-live="polite">
        <div class="pi-progress-bar"><span class="pi-progress-indeterminate"></span></div>
        <p class="pi-progress-text">{{ stageText }}</p>
      </div>
      <p v-if="linkError" class="pi-error" role="alert">{{ linkError }}</p>
      <p class="pi-note">{{ boundaryNote }}</p>
    </div>

    <!-- v1.90.1：唤起 App 后不再把用户丢在 App 里。面板在唤起的同时打开，
         切回来时给出继续入口；v1.90.2 起主路径为截图提取（不用下载保存），
         PDF 选择保留为次路径；位置只是提示，真正取文件仍走系统选择器。 -->
    <ResumeIntakeSheet
      :open="sheetOpen"
      :phase="sheetPhase"
      :platform="sheetPlatform"
      :handed-off="sheetHandedOff"
      :busy="!!launchingKey"
      @close="closeSheet"
      @pick-file="onSheetPickFile"
      @pick-image="onSheetPickImage"
      @retry="onSheetRetry"
      @open-web="onSheetOpenWeb"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import api, { AI_TIMEOUT, getErrMessage } from '../api'
import BaseInput from './BaseInput.vue'
import ResumeIntakeSheet from './ResumeIntakeSheet.vue'
import {
  HANDOFF_WINDOW_MS,
  createHandoffWatcher,
  detectInAppBrowser,
  detectMobile,
  inAppBrowserHint,
  launchFallbackHint,
  openFailedHint,
  type HandoffWatcher,
} from '../utils/appLaunch'
import {
  RESUME_IMPORT_PLATFORMS,
  THIRD_PARTY_BOUNDARY_NOTE,
  THIRD_PARTY_CONSENT_TEXT,
  decideLaunchMode,
  isHttpUrl,
  returnFromAppHint,
  type IntakeSheetPhase,
  type ResumeImportPlatform,
} from '../utils/resumeIntake'

interface ImportedPayload {
  analysis: string
  resumeText: string
  fileName?: string
}

const props = defineProps<{
  targetJob: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'pick-file'): void
  (e: 'pick-image'): void
  (e: 'imported', payload: ImportedPayload): void
}>()

const platforms = RESUME_IMPORT_PLATFORMS
const consentText = THIRD_PARTY_CONSENT_TEXT
const boundaryNote = THIRD_PARTY_BOUNDARY_NOTE

/** 环境在 setup 时求值一次：首帧就按真实端渲染，避免手机先看到桌面分支 */
const isMobile = detectMobile()
const inAppBrowser = detectInAppBrowser()

const launchingKey = ref('')
const launchNotice = ref('')
const launchNoticeWarn = ref(false)
const showContinue = ref(false)

/* 取件向导：唤起即打开，交棒/返回后切到「选 PDF 位置」 */
const sheetOpen = ref(false)
const sheetPhase = ref<IntakeSheetPhase>('launching')
const sheetPlatform = ref<ResumeImportPlatform | null>(null)
const sheetHandedOff = ref(false)
let returnHandler: (() => void) | null = null

const linkUrl = ref('')
const authorized = ref(false)
const busy = ref(false)
const stage = ref<'idle' | 'checking' | 'fetching'>('idle')
const linkError = ref('')

const watcher = shallowRef<HandoffWatcher | null>(null)
const timers = new Set<number>()

/**
 * 只以「授权勾选」为门槛：链接格式由 submitLink 校验并给出内联原因。
 * 若把 isHttpUrl 也算进 disabled，用户粘错地址时按钮只是变灰、不给任何解释。
 */
const canSubmit = computed(() => (
  !busy.value
  && !props.disabled
  && authorized.value
))

const stageText = computed(() => (
  stage.value === 'checking'
    ? '正在校验链接…'
    : '正在下载 PDF 并交给 AI 分析，稍等片刻…'
))

function clearTimers() {
  for (const id of timers) window.clearTimeout(id)
  timers.clear()
}

function setNotice(text: string, warn: boolean) {
  launchNotice.value = text
  launchNoticeWarn.value = warn
}

function openSheet(platform: ResumeImportPlatform) {
  sheetPlatform.value = platform
  sheetPhase.value = 'launching'
  sheetHandedOff.value = false
  sheetOpen.value = true
}

function closeSheet() {
  sheetOpen.value = false
}

/** 关掉面板后仍可从提示里找回步骤，不必再触发一次 App 唤起 */
function reopenSheet() {
  if (!sheetPlatform.value) return
  sheetPhase.value = 'ready'
  sheetOpen.value = true
}

/**
 * 从 App 切回页面时立刻把面板切到就绪态。
 * 2.5s 的判定定时器在后台标签页会被节流，单靠它会让用户回来后仍看到「正在打开」。
 */
function watchAppReturn() {
  stopWatchingAppReturn()
  returnHandler = () => {
    if (document.visibilityState !== 'visible') return
    if (!watcher.value?.handedOff) return
    sheetHandedOff.value = true
    sheetPhase.value = 'ready'
  }
  document.addEventListener('visibilitychange', returnHandler)
}

function stopWatchingAppReturn() {
  if (returnHandler) document.removeEventListener('visibilitychange', returnHandler)
  returnHandler = null
}

function onSheetPickFile() {
  sheetOpen.value = false
  emit('pick-file')
}

function onSheetPickImage() {
  sheetOpen.value = false
  emit('pick-image')
}

function onSheetRetry() {
  if (sheetPlatform.value && !launchingKey.value) launch(sheetPlatform.value)
}

function onSheetOpenWeb() {
  sheetOpen.value = false
  if (sheetPlatform.value) openWeb(sheetPlatform.value)
}

function openWeb(platform: ResumeImportPlatform) {
  const win = window.open(platform.webUrl, '_blank', 'noopener,noreferrer')
  if (!win) {
    setNotice(openFailedHint(platform.name), true)
  }
}

function launch(platform: ResumeImportPlatform) {
  const mode = decideLaunchMode({
    mobile: isMobile,
    inAppBrowser,
    hasScheme: !!platform.scheme,
  })

  if (mode === 'web' || !platform.scheme) {
    openWeb(platform)
    return
  }
  if (mode === 'blocked') {
    setNotice(inAppBrowserHint(platform.name, inAppBrowser), true)
    showContinue.value = true
    openWeb(platform)
    return
  }

  launchingKey.value = platform.key
  showContinue.value = true
  setNotice(`正在尝试打开「${platform.name}」…`, false)
  openSheet(platform)

  const current = createHandoffWatcher()
  watcher.value = current
  watchAppReturn()
  try {
    window.location.href = platform.scheme
  } catch {
    // 某些浏览器会直接把无效 scheme 当普通地址处理；统一等判定窗口给出兜底
  }

  const timer = window.setTimeout(() => {
    timers.delete(timer)
    if (current.handedOff) {
      setNotice(returnFromAppHint(platform.name), false)
    } else {
      setNotice(launchFallbackHint(platform.name), true)
    }
    sheetHandedOff.value = current.handedOff
    sheetPhase.value = 'ready'
    stopWatchingAppReturn()
    current.dispose()
    if (watcher.value === current) watcher.value = null
    launchingKey.value = ''
  }, HANDOFF_WINDOW_MS)
  timers.add(timer)
}

async function submitLink() {
  linkError.value = ''
  if (!authorized.value) {
    linkError.value = '请先勾选授权确认'
    return
  }
  if (!isHttpUrl(linkUrl.value)) {
    linkError.value = '请输入以 http:// 或 https:// 开头的链接'
    return
  }

  busy.value = true
  stage.value = 'checking'
  // 单一请求内后端会先校验链接再下载；这里只切换阶段文案，不伪造百分比
  const stageTimer = window.setTimeout(() => { stage.value = 'fetching' }, 500)
  timers.add(stageTimer)
  try {
    const data = await api.post('/api/resume/import-pdf-url', {
      url: linkUrl.value.trim(),
      targetJob: props.targetJob || '通用岗位',
      authorized: true,
    }, { timeout: AI_TIMEOUT }) as unknown as {
      analysis?: string
      resumeText?: string
      fileName?: string
    }

    if (!data?.analysis?.trim()) {
      throw new Error('导入结果为空，请重试')
    }
    emit('imported', {
      analysis: data.analysis,
      resumeText: data.resumeText || '',
      fileName: data.fileName,
    })
    ElMessage.success(data.fileName ? `已导入「${data.fileName}」并完成分析` : '导入完成')
    linkUrl.value = ''
    authorized.value = false
  } catch (e: unknown) {
    linkError.value = getErrMessage(e, '导入失败，请确认链接指向可公开访问的 PDF')
  } finally {
    window.clearTimeout(stageTimer)
    timers.delete(stageTimer)
    busy.value = false
    stage.value = 'idle'
  }
}

onUnmounted(() => {
  clearTimers()
  stopWatchingAppReturn()
  sheetOpen.value = false
  watcher.value?.dispose()
  watcher.value = null
})
</script>

<style scoped>
.pi-block {
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
  padding: 20px;
}

.pi-head {
  margin-bottom: 16px;
}

.pi-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.pi-sub {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.pi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.pi-card {
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-bg-soft);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pi-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pi-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 34px;
  height: 24px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--brand-primary-light);
  color: var(--brand-primary-active);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.pi-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.pi-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
  min-height: 38px;
}

.pi-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
}

.pi-btn {
  min-height: 36px;
  padding: 6px 12px;
  border: 1px solid var(--c-border-strong);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.pi-btn:hover:not(:disabled) {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}

.pi-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.pi-btn-primary {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: var(--c-on-primary);
}

.pi-btn-primary:hover:not(:disabled) {
  background: var(--brand-primary-hover);
  border-color: var(--brand-primary-hover);
  color: var(--c-on-primary);
}

.pi-btn-quiet {
  background: transparent;
  border-color: transparent;
  color: var(--c-text-tertiary);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.pi-notice {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--brand-primary-light);
  color: var(--brand-primary-active);
  font-size: 12px;
  line-height: 1.6;
}

.pi-notice.is-warn {
  background: var(--c-warning-light);
  color: var(--c-warning);
}

.pi-inline-link {
  margin-left: 8px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.pi-link {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--c-border);
}

.pi-link-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.pi-link-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.pi-link-tag {
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  background: var(--c-accent-soft);
  color: var(--c-accent);
  font-size: 11px;
  font-weight: 600;
}

.pi-field {
  margin-bottom: 10px;
}

.pi-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-secondary);
}

.pi-consent {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  cursor: pointer;
}

.pi-consent input {
  margin-top: 3px;
  accent-color: var(--brand-primary);
}

.pi-submit {
  min-height: 40px;
  padding: 8px 18px;
  font-size: 13px;
}

.pi-progress {
  margin-top: 12px;
}

.pi-progress-bar {
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--c-bg-alt);
  overflow: hidden;
}

.pi-progress-indeterminate {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: var(--radius-full);
  background: var(--brand-primary);
  animation: pi-slide 1.2s ease-in-out infinite;
}

@keyframes pi-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(250%); }
}

.pi-progress-text {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.pi-error {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-danger);
}

.pi-note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-quaternary);
}

@media (max-width: 640px) {
  .pi-block {
    padding: 16px;
  }

  .pi-grid {
    grid-template-columns: 1fr;
  }

  .pi-btn {
    min-height: 44px;
  }

  .pi-submit {
    width: 100%;
  }
}
</style>

<template>
  <Teleport to="body">
    <div v-if="open && platform" class="ri-layer">
      <div class="ri-mask" aria-hidden="true" @click="emit('close')"></div>
      <section
        ref="sheetEl"
        class="ri-sheet"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="leadId"
      >
        <div class="ri-grab" aria-hidden="true"></div>

        <header class="ri-head">
          <div class="ri-head-text">
            <p class="ri-kicker">取件向导 · {{ platform.name }}</p>
            <h3 :id="titleId" class="ri-title" aria-live="polite">{{ title }}</h3>
            <p :id="leadId" class="ri-lead">{{ lead }}</p>
          </div>
          <button type="button" class="ri-close" aria-label="关闭取件向导" @click="emit('close')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
        </header>

        <div class="ri-scroll">
          <ol class="ri-steps" aria-label="在 App 里的取件步骤">
            <li v-for="(step, i) in steps" :key="i" class="ri-step">
              <span class="ri-step-no" aria-hidden="true">{{ i + 1 }}</span>
              <span class="ri-step-text">{{ step }}</span>
            </li>
          </ol>

          <!-- v1.90.3：微信平台的简历常在聊天里，用户要的是「选那个 PDF」。
               网页拿不到聊天记录，所以这里把「存一次再选」做成主按钮，
               截图退为「不想保存」的备选。 -->
          <div v-if="chatSource" class="ri-chat">
            <div class="ri-chat-head">
              <p class="ri-chat-title">{{ chatSource.label }}</p>
              <span class="ri-chat-badge">推荐</span>
            </div>
            <p class="ri-chat-desc">{{ chatSource.where }}</p>
            <button type="button" class="ri-chat-btn" @click="emit('pick-file', chatSource)">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
                  stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
                <path d="M14 3v5h5" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
              </svg>
              {{ chatPickCta }}
            </button>
            <p class="ri-chat-note">{{ chatPickNote }}</p>
          </div>

          <div class="ri-shot" :class="{ 'is-secondary': !!chatSource }">
            <div class="ri-shot-head">
              <p class="ri-shot-title">{{ shotTitle }}</p>
              <span class="ri-shot-badge">{{ shotBadge }}</span>
            </div>
            <p class="ri-shot-desc">{{ shotDesc }}</p>
            <button type="button" class="ri-shot-btn" @click="emit('pick-image')">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="2" />
                <circle cx="9" cy="10" r="1.6" stroke="currentColor" stroke-width="2" />
                <path d="M4 17l5-5 4 4 3-3 4 4" stroke="currentColor" stroke-width="2"
                  stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              {{ shotCta }}
            </button>
          </div>

          <div class="ri-pick">
            <p class="ri-pick-title">{{ pickTitle }}</p>
            <div class="ri-sources">
              <button
                v-for="source in sources"
                :key="source.key"
                type="button"
                class="ri-source"
                @click="emit('pick-file', source)"
              >
                <span class="ri-source-badge" aria-hidden="true">{{ source.badge }}</span>
                <span class="ri-source-main">
                  <span class="ri-source-label">{{ source.label }}</span>
                  <span class="ri-source-where">{{ source.where }}</span>
                </span>
                <span class="ri-source-arrow" aria-hidden="true">›</span>
              </button>
            </div>
            <p class="ri-pick-note">{{ pickNote }}</p>
          </div>
        </div>

        <div class="ri-foot">
          <button type="button" class="ri-foot-btn" :disabled="busy" @click="emit('retry')">
            {{ busy ? '正在唤起…' : '重新打开 App' }}
          </button>
          <button type="button" class="ri-foot-btn" @click="emit('open-web')">打开网页版</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import {
  INTAKE_CHAT_PICK_CTA,
  INTAKE_PICK_TITLE_OTHER,
  INTAKE_SHEET_NOTE,
  INTAKE_SHOT_CTA,
  INTAKE_SHOT_DESC,
  INTAKE_SHOT_TITLE,
  INTAKE_SHOT_TITLE_NO_SAVE,
  RESUME_PICK_SOURCES,
  WECHAT_FILE_PICK_NOTE,
  intakeSheetLead,
  intakeSheetTitle,
  resolvePickWhere,
  resolvePlatformSteps,
  wechatIntakeLead,
  type IntakeSheetPhase,
  type MobileOs,
  type ResumeImportPlatform,
  type ResumePickSource,
} from '../utils/resumeIntake'

const props = defineProps<{
  open: boolean
  phase: IntakeSheetPhase
  platform: ResumeImportPlatform | null
  handedOff: boolean
  /** 正在唤起时禁用重试，避免重复触发 scheme */
  busy?: boolean
  /** iOS / Android 的存文件菜单名不同；缺省时用通用说法 */
  os?: MobileOs
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'pick-file', source: ResumePickSource): void
  (e: 'pick-image'): void
  (e: 'retry'): void
  (e: 'open-web'): void
}>()

let seq = 0
const idBase = `ri-${Date.now().toString(36)}-${++seq}`
const titleId = `${idBase}-title`
const leadId = `${idBase}-lead`

const pickNote = INTAKE_SHEET_NOTE
const shotDesc = INTAKE_SHOT_DESC
const shotCta = INTAKE_SHOT_CTA
const chatPickCta = INTAKE_CHAT_PICK_CTA
const chatPickNote = WECHAT_FILE_PICK_NOTE
const sheetEl = ref<HTMLElement | null>(null)

const osClass = computed<MobileOs>(() => props.os ?? 'other')
const steps = computed(() => (
  props.platform ? resolvePlatformSteps(props.platform, osClass.value) : []
))

/** 微信等「聊天里收文件」的平台：把该位置提为主按钮，并从下方列表里移除避免重复 */
const chatSource = computed<ResumePickSource | null>(() => {
  const key = props.platform?.chatPickSource
  if (!key) return null
  const source = RESUME_PICK_SOURCES.find((s) => s.key === key)
  return source ? { ...source, where: resolvePickWhere(source, osClass.value) } : null
})

const sources = computed<ResumePickSource[]>(() => (
  RESUME_PICK_SOURCES
    .filter((source) => source.key !== props.platform?.chatPickSource)
    .map((source) => ({ ...source, where: resolvePickWhere(source, osClass.value) }))
))

const shotTitle = computed(() => (
  chatSource.value ? INTAKE_SHOT_TITLE_NO_SAVE : INTAKE_SHOT_TITLE
))
const shotBadge = computed(() => (chatSource.value ? '备用 · 不用保存' : '推荐 · 不用下载'))
const pickTitle = computed(() => (
  chatSource.value ? INTAKE_PICK_TITLE_OTHER : '已有 PDF？选它现在的位置'
))

const title = computed(() => (
  props.platform ? intakeSheetTitle(props.phase, props.platform.name) : ''
))
const lead = computed(() => (
  chatSource.value
    ? wechatIntakeLead(props.phase, props.handedOff)
    : intakeSheetLead(props.phase, props.handedOff)
))

let lastFocused: HTMLElement | null = null
let prevOverflow = ''

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
  }
}

function release() {
  document.body.style.overflow = prevOverflow
  document.removeEventListener('keydown', onKeydown)
}

watch(() => props.open, async (open) => {
  if (open) {
    lastFocused = document.activeElement as HTMLElement | null
    prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', onKeydown)
    await nextTick()
    // 焦点落在对话框本身：键盘用户可直接 Tab，触摸端也不会出现一个突兀的按钮焦点环
    sheetEl.value?.focus()
    return
  }
  release()
  lastFocused?.focus?.()
  lastFocused = null
}, { immediate: true })

onUnmounted(release)
</script>

<style scoped>
.ri-layer {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.ri-mask {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  animation: ri-fade 0.18s ease-out;
}

.ri-sheet:focus {
  outline: none;
}

.ri-sheet {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 560px;
  max-height: 88vh;
  border-radius: var(--radius-2xl) var(--radius-2xl) 0 0;
  background: var(--c-surface);
  box-shadow: 0 -12px 40px rgba(15, 23, 42, 0.2);
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
  animation: ri-rise 0.24s ease-out;
}

.ri-grab {
  width: 36px;
  height: 4px;
  margin: 8px auto 2px;
  border-radius: var(--radius-full);
  background: var(--c-border-strong);
}

.ri-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 18px 12px;
  border-bottom: 1px solid var(--c-border-light);
}

.ri-head-text {
  flex: 1;
  min-width: 0;
}

.ri-kicker {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--brand-primary-active);
}

.ri-title {
  margin: 0;
  font-size: 17px;
  line-height: 1.4;
  color: var(--c-text);
}

.ri-lead {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.ri-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-top: 2px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  background: var(--c-surface);
  color: var(--c-text-tertiary);
  cursor: pointer;
}

.ri-close:hover {
  color: var(--c-text);
  border-color: var(--c-border-strong);
}

.ri-scroll {
  overflow-y: auto;
  padding: 14px 18px 4px;
  overscroll-behavior: contain;
}

.ri-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ri-step {
  display: flex;
  gap: 10px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.ri-step-no {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  border-radius: var(--radius-full);
  background: var(--brand-primary-light);
  color: var(--brand-primary-active);
  font-size: 11px;
  font-weight: 700;
}

.ri-step-text {
  flex: 1;
  min-width: 0;
}

.ri-chat {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-lg);
  background: var(--brand-primary-light);
}

.ri-chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ri-chat-title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--brand-primary-active);
}

.ri-chat-badge {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  background: var(--brand-primary);
  color: var(--c-on-primary);
  font-size: 10px;
  font-weight: 600;
}

.ri-chat-desc {
  margin: 6px 0 10px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--c-text-secondary);
  word-break: break-word;
}

.ri-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  min-height: 48px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--brand-primary);
  color: var(--c-on-primary);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}

.ri-chat-btn:hover {
  background: var(--brand-primary-hover);
}

.ri-chat-note {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-tertiary);
}

.ri-chat + .ri-shot {
  margin-top: 10px;
}

.ri-shot.is-secondary {
  border-color: var(--c-border);
  background: var(--c-bg-soft);
}

.ri-shot.is-secondary .ri-shot-title {
  color: var(--c-text);
}

.ri-shot.is-secondary .ri-shot-badge {
  background: var(--c-surface);
  color: var(--c-text-tertiary);
}

.ri-shot.is-secondary .ri-shot-btn {
  border: 1px solid var(--brand-primary);
  background: var(--c-surface);
  color: var(--brand-primary);
}

.ri-shot.is-secondary .ri-shot-btn:hover {
  background: var(--brand-primary-light);
}

.ri-shot {
  margin-top: 14px;
  padding: 12px 14px 14px;
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-lg);
  background: var(--brand-primary-light);
}

.ri-shot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ri-shot-title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--brand-primary-active);
}

.ri-shot-badge {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  background: var(--c-surface);
  color: var(--brand-primary-active);
  font-size: 10px;
  font-weight: 600;
}

.ri-shot-desc {
  margin: 6px 0 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-secondary);
}

.ri-shot-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  min-height: 46px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--brand-primary);
  color: var(--c-on-primary);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.ri-shot-btn:hover {
  background: var(--brand-primary-hover);
}

.ri-pick {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--c-border);
}

.ri-pick-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.ri-sources {
  display: flex;
  flex-direction: column;
}

.ri-source {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 60px;
  padding: 10px 4px;
  border: none;
  border-bottom: 1px solid var(--c-border-light);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.ri-source:last-child {
  border-bottom: none;
}

.ri-source:hover,
.ri-source:active {
  background: var(--c-bg-soft);
}

.ri-source-badge {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-light);
  color: var(--brand-primary-active);
  font-size: 12px;
  font-weight: 700;
}

.ri-source-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ri-source-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.ri-source-where {
  font-size: 11px;
  line-height: 1.5;
  color: var(--c-text-tertiary);
}

.ri-source-arrow {
  flex: none;
  font-size: 18px;
  color: var(--c-text-quaternary);
}

.ri-pick-note {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-quaternary);
}

.ri-foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 12px 18px 0;
  border-top: 1px solid var(--c-border-light);
}

.ri-foot-btn {
  min-height: 42px;
  padding: 6px 10px;
  border: 1px solid var(--c-border-strong);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-size: 13px;
  cursor: pointer;
}

.ri-foot-btn:hover:not(:disabled) {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}

.ri-foot-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

@keyframes ri-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes ri-rise {
  from { transform: translateY(24px); opacity: 0.6; }
  to { transform: translateY(0); opacity: 1; }
}

@media (min-width: 1024px) {
  .ri-sheet {
    border-radius: var(--radius-2xl);
    margin-bottom: 24px;
  }
}
</style>

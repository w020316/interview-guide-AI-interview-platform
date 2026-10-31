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
          <ol class="ri-steps" aria-label="在 App 里的导出步骤">
            <li v-for="(step, i) in platform.steps" :key="i" class="ri-step">
              <span class="ri-step-no" aria-hidden="true">{{ i + 1 }}</span>
              <span class="ri-step-text">{{ step }}</span>
            </li>
          </ol>

          <div class="ri-pick">
            <p class="ri-pick-title">PDF 现在在哪？选一个位置继续</p>
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
          <button type="button" class="ri-foot-btn ri-foot-wide" @click="emit('pick-image')">
            没有 PDF？用截图识别
          </button>
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
  INTAKE_SHEET_NOTE,
  RESUME_PICK_SOURCES,
  intakeSheetLead,
  intakeSheetTitle,
  type IntakeSheetPhase,
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

const sources = RESUME_PICK_SOURCES
const pickNote = INTAKE_SHEET_NOTE
const sheetEl = ref<HTMLElement | null>(null)

const title = computed(() => (
  props.platform ? intakeSheetTitle(props.phase, props.platform.name) : ''
))
const lead = computed(() => intakeSheetLead(props.phase, props.handedOff))

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

.ri-foot-wide {
  grid-column: 1 / -1;
  border-color: var(--brand-primary);
  color: var(--brand-primary-active);
  font-weight: 600;
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

<template>
  <div class="studio-page">
    <!-- ══════════════ 浏览态：我的简历 + 模板库 ══════════════ -->
    <template v-if="mode === 'browse'">
      <header class="page-head">
        <div>
          <h1 class="page-title">简历模板</h1>
          <p class="page-desc">
            选一套版式，把内容填进去，导出成可直接上传的 PDF。所有内容都存在你自己的账号里。
          </p>
        </div>
        <BaseButton v-if="documents.length" variant="primary" @click="showPicker = true">
          新建简历
        </BaseButton>
      </header>

      <!-- 我的简历 -->
      <section v-if="documents.length" class="section">
        <h2 class="section-title">我的简历</h2>
        <div class="doc-list">
          <article
            v-for="d in documents"
            :key="d.id"
            class="doc-item"
            role="button"
            tabindex="0"
            @click="openDocument(d.id)"
            @keydown.enter="openDocument(d.id)"
            @keydown.space.prevent="openDocument(d.id)"
          >
            <div class="doc-item-main">
              <h3 class="doc-item-title">{{ d.title }}</h3>
              <p class="doc-item-meta">
                <span>{{ templateName(d.templateId) }}</span>
                <span class="dot-sep" aria-hidden="true"></span>
                <span>更新于 {{ formatDate(d.updatedAt) }}</span>
              </p>
            </div>
            <button
              type="button"
              class="icon-btn"
              aria-label="删除该简历"
              @click.stop="confirmDelete(d)"
            >
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3 6h18M8 6V4a1 1 0 011-1h6a1 1 0 011 1v2M19 6l-1 14a1 1 0 01-1 1H7a1 1 0 01-1-1L5 6" />
              </svg>
            </button>
          </article>
        </div>
      </section>

      <!-- 模板库 -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">{{ documents.length ? '换一套版式' : '选一套模板开始' }}</h2>
          <p class="section-hint">同一份内容可随时换模板，内容不会丢。</p>
        </div>

        <div class="tpl-grid">
          <article
            v-for="t in templates"
            :key="t.id"
            class="tpl-card"
            :class="{ 'is-active': activeTemplateId === t.id }"
            role="button"
            tabindex="0"
            @click="createFromTemplate(t.id)"
            @keydown.enter="createFromTemplate(t.id)"
            @keydown.space.prevent="createFromTemplate(t.id)"
          >
            <!-- 缩略图：用 CSS 灰条示意版式，不用真截图（避免加载与失真） -->
            <div class="tpl-thumb" :class="`thumb-${t.layout}`" aria-hidden="true">
              <div class="thumb-head">
                <span class="thumb-name"></span>
                <span class="thumb-sub"></span>
              </div>
              <div class="thumb-body">
                <div class="thumb-col">
                  <span class="thumb-bar is-title"></span>
                  <span class="thumb-bar"></span>
                  <span class="thumb-bar is-short"></span>
                  <span class="thumb-bar is-title"></span>
                  <span class="thumb-bar"></span>
                  <span class="thumb-bar is-mid"></span>
                  <span class="thumb-bar is-title"></span>
                  <span class="thumb-bar is-short"></span>
                </div>
                <div v-if="t.layout !== 'one-column'" class="thumb-side">
                  <span class="thumb-bar is-title"></span>
                  <span class="thumb-bar"></span>
                  <span class="thumb-bar is-short"></span>
                  <span class="thumb-bar is-title"></span>
                  <span class="thumb-bar"></span>
                </div>
              </div>
            </div>

            <div class="tpl-info">
              <div class="tpl-name-row">
                <h3 class="tpl-name">{{ t.name }}</h3>
                <span v-if="t.recommended" class="chip chip-accent">推荐</span>
              </div>
              <p class="tpl-scene">{{ t.scene }}</p>
              <div class="tpl-meta">
                <span class="chip" :class="t.atsLevel === 'HIGH' ? 'chip-ok' : 'chip-warn'">
                  ATS {{ t.atsLevel === 'HIGH' ? '友好' : '中等' }}
                </span>
                <span class="chip">{{ layoutLabel(t.layout) }}</span>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>

    <!-- ══════════════ 编辑态 ══════════════ -->
    <template v-else>
      <header class="editor-head">
        <div class="editor-head-left">
          <button type="button" class="back-btn" @click="backToBrowse">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M19 12H5M12 19l-7-7 7-7" />
            </svg>
            返回
          </button>
          <div class="title-wrap">
            <input
              v-model="docTitle"
              class="title-input"
              maxlength="200"
              aria-label="简历标题"
              placeholder="未命名简历"
            />
            <span v-if="dirty" class="dirty-flag">未保存</span>
          </div>
        </div>

        <div class="editor-actions">
          <select v-model="templateId" class="tpl-select" aria-label="切换模板">
            <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option>
          </select>
          <BaseButton variant="ghost" size="sm" :disabled="autofilling" @click="onAutofill">
            {{ autofilling ? '正在读取…' : '从已有简历填充' }}
          </BaseButton>
          <BaseButton variant="ghost" size="sm" :disabled="saving" @click="save">保存</BaseButton>
          <BaseButton variant="primary" size="sm" @click="onExportPdf">导出 PDF</BaseButton>
        </div>
      </header>

      <!-- 移动端：编辑 / 预览 切换（分段控件；桌面端两栏并排，此控件隐藏） -->
      <div class="mobile-switch" role="tablist" aria-label="编辑与预览切换">
        <button
          type="button"
          role="tab"
          :aria-selected="mobilePane === 'edit'"
          :class="{ active: mobilePane === 'edit' }"
          @click="mobilePane = 'edit'"
        >
          编辑
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mobilePane === 'preview'"
          :class="{ active: mobilePane === 'preview' }"
          @click="mobilePane = 'preview'"
        >
          预览
        </button>
      </div>

      <div class="editor-body">
        <div class="editor-pane" :class="{ 'is-hidden-mobile': mobilePane !== 'edit' }">
          <ResumeDocForm :doc="doc" />
        </div>

        <div class="preview-pane" :class="{ 'is-hidden-mobile': mobilePane !== 'preview' }">
          <div class="preview-toolbar">
            <span class="preview-label">预览 · {{ activeTemplateName }}</span>
            <div class="preview-tools">
              <button type="button" class="text-btn" @click="onDownloadHtml">下载 HTML</button>
              <button type="button" class="text-btn" @click="onDownloadMd">下载 MD</button>
              <button type="button" class="text-btn" @click="onCopyMd">复制内容</button>
            </div>
          </div>

          <!-- 提示：字号偏小的模板给出可见提醒，不做静默处理 -->
          <p v-if="templateId === 'compact-one-page'" class="preview-note">
            这套模板正文字号小于常规阶梯，用于把内容压进一页。正式投递前请先看预览确认可读性。
          </p>

          <div ref="viewportRef" class="preview-viewport" :style="{ height: `${scaledHeight}px` }">
            <div class="preview-scaler" :style="{ transform: `scale(${scale})` }">
              <ResumeDocPreview :doc="doc" :template-id="templateId" />
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 简历工作台（v1.67.0）
 *
 * 三个视图：浏览（我的简历 + 模板库）/ 编辑（表单 + 实时预览）/ 导出。
 *
 * ── 关键实现说明 ──────────────────────────────────────────────────
 * 1. **预览用 transform: scale() 缩放**，不缩放内部字号 —— 保证「预览看到的字号」
 *    与「打印出来的字号」完全一致。缩放容器高度需要补偿，否则下方留白/裁切。
 * 2. **导出走浏览器打印**（`utils/resumeExport.ts`），产出真文本层 PDF，
 *    ATS 可解析；不用 html2canvas/jsPDF（那会产出图片，等于把简历做废）。
 * 3. **模板切换不丢数据**：模板只是渲染参数，数据始终是同一份 `doc`。
 * 4. 保存是**手动**的（不自动保存），因此有 `dirty` 标记与离开提醒 ——
 *    简历编辑中途被静默覆盖的代价太高，宁可让用户明确点保存。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import BaseButton from '../components/BaseButton.vue'
import ResumeDocForm from '../components/ResumeDocForm.vue'
import ResumeDocPreview from '../components/ResumeDocPreview.vue'
import api, { getErrMessage } from '../api'
import {
  emptyResumeDoc,
  findTemplate,
  isDocEmpty,
  normalizeResumeDoc,
  RESUME_TEMPLATES,
  toMarkdown,
  type ResumeDoc,
  type ResumeTemplateMeta,
} from '../utils/resumeDoc'
import {
  downloadResumeHtml,
  downloadResumeMarkdown,
  exportResumeToPdf,
} from '../utils/resumeExport'

interface DocSummary {
  id: number
  title: string
  templateId: string
  updatedAt?: string
}

/** A4 纸面宽度（px @96dpi），与 ResumeDocPreview 的 .resume-doc 保持一致 */
const A4_WIDTH = 794
const A4_HEIGHT = 1123

const mode = ref<'browse' | 'editor'>('browse')
const showPicker = ref(false)
const templates = ref<ResumeTemplateMeta[]>([...RESUME_TEMPLATES])
const documents = ref<DocSummary[]>([])

const docId = ref<number | null>(null)
const docTitle = ref('未命名简历')
const templateId = ref('classic-single')
const doc = ref<ResumeDoc>(emptyResumeDoc())

const dirty = ref(false)
const saving = ref(false)
/** AI 自动填充进行中：这是耗时操作（接近一次对话），必须有明确加载态 */
const autofilling = ref(false)
const mobilePane = ref<'edit' | 'preview'>('edit')
const viewportRef = ref<HTMLElement | null>(null)
const viewportWidth = ref(A4_WIDTH)

const activeTemplateId = computed(() => templateId.value)
const activeTemplateName = computed(() => findTemplate(templateId.value).name)

/** 预览缩放比：容器比纸面窄时才缩，不放大（放大会糊） */
const scale = computed(() => Math.min(1, viewportWidth.value / A4_WIDTH))
const scaledHeight = computed(() => Math.round(A4_HEIGHT * scale.value))

function templateName(id: string): string {
  return findTemplate(id).name
}

/** 布局标签：三态（v1.79.0 增反向双栏），避免在模板里写三元嵌套 */
function layoutLabel(layout: string): string {
  if (layout === 'one-column') return '单栏'
  if (layout === 'two-column-reverse') return '双栏·侧栏在左'
  return '双栏'
}

function formatDate(v?: string): string {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/* ─────────────────── 数据加载 ─────────────────── */

async function loadDocuments() {
  try {
    const list = (await api.get('/api/resume/documents')) as unknown as DocSummary[]
    documents.value = Array.isArray(list) ? list : []
  } catch (e) {
    ElMessage.error(getErrMessage(e, '加载简历列表失败'))
  }
}

async function loadTemplates() {
  try {
    const list = (await api.get('/api/resume/templates')) as unknown as ResumeTemplateMeta[]
    // 服务端是模板元数据的权威来源；接口异常时保留本地常量，页面不至于空掉
    if (Array.isArray(list) && list.length) {
      templates.value = list.map((t) => ({
        ...t,
        cssClass: findTemplate(t.id).cssClass,
        locale: findTemplate(t.id).locale,
        sections: findTemplate(t.id).sections,
      }))
    }
  } catch {
    // 静默回落本地常量：模板列表不是关键路径，不该因此打断用户
  }
}

/* ─────────────────── 新建 / 打开 ─────────────────── */

async function createFromTemplate(id: string) {
  try {
    const created = (await api.post('/api/resume/documents', {
      templateId: id,
      title: '未命名简历',
      docData: JSON.stringify(emptyResumeDoc()),
    })) as unknown as { id: number; title: string; templateId: string; docData?: string }

    docId.value = created.id
    docTitle.value = created.title
    templateId.value = created.templateId
    doc.value = normalizeResumeDoc(created.docData ? JSON.parse(created.docData) : null)
    dirty.value = false
    mode.value = 'editor'
    showPicker.value = false
    mobilePane.value = 'edit'
    await loadDocuments()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '创建简历失败'))
  }
}

async function openDocument(id: number) {
  try {
    const detail = (await api.get(`/api/resume/documents/${id}`)) as unknown as {
      id: number
      title: string
      templateId: string
      docData?: string
    }
    docId.value = detail.id
    docTitle.value = detail.title
    templateId.value = detail.templateId
    doc.value = normalizeResumeDoc(parseDocData(detail.docData))
    dirty.value = false
    mode.value = 'editor'
    mobilePane.value = 'edit'
  } catch (e) {
    ElMessage.error(getErrMessage(e, '打开简历失败'))
  }
}

/** 后端把 JSON 存在文本列里，可能已是对象也可能仍是字符串 */
function parseDocData(raw: unknown): unknown {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  if (typeof raw !== 'string') return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/* ─────────────────── 保存 ─────────────────── */

async function save() {
  if (docId.value == null) return
  saving.value = true
  try {
    await api.put(`/api/resume/documents/${docId.value}`, {
      templateId: templateId.value,
      title: docTitle.value,
      docData: JSON.stringify(doc.value),
    })
    dirty.value = false
    ElMessage.success('已保存')
    await loadDocuments()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

/* ─────────────────── 从已有简历填充 ─────────────────── */

/**
 * 从已上传的简历自动填充。
 *
 * 只调接口取抽取结果、**不自动保存**：填进来的内容用户多半还要改，
 * 保存动作始终由用户显式点击。AI 只负责「少打字」，不替用户决定内容。
 *
 * 当前已填有内容时先确认——自动填充是整体替换，静默覆盖掉用户刚敲的东西
 * 是不可接受的（与「保存必须手动」是同一条原则）。
 */
async function onAutofill() {
  if (docId.value == null) return
  if (!isDocEmpty(doc.value)) {
    try {
      await ElMessageBox.confirm(
        '自动填充会用你上传的简历内容覆盖当前已填写的部分，确定继续吗？',
        '从已有简历填充',
        { confirmButtonText: '覆盖并填充', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }
  autofilling.value = true
  try {
    const res = (await api.post(`/api/resume/documents/${docId.value}/autofill`, {})) as unknown as {
      docData?: string
      sourceResumeId?: number | null
    }
    doc.value = normalizeResumeDoc(parseDocData(res.docData))
    // deep watch 也会捕获到这次替换，这里显式置一次作为双保险
    dirty.value = true
    ElMessage.success('已填充，请检查内容后点「保存」')
  } catch (e) {
    ElMessage.error(getErrMessage(e, '自动填充失败，可以先手动填写'))
  } finally {
    autofilling.value = false
  }
}

/* ─────────────────── 导出 ─────────────────── */

function onExportPdf() {
  exportResumeToPdf(doc.value, templateId.value)
}

function onDownloadHtml() {
  downloadResumeHtml(doc.value, templateId.value)
}

function onDownloadMd() {
  downloadResumeMarkdown(doc.value, toMarkdown(doc.value))
}

async function onCopyMd() {
  const text = toMarkdown(doc.value)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制 Markdown 内容')
  } catch {
    // 剪贴板权限被拒（非 HTTPS 或用户拒绝）时给出可操作提示，不假装成功
    ElMessage.warning('复制失败：浏览器未授予剪贴板权限，请改用「下载 MD」')
  }
}

/* ─────────────────── 删除 / 返回 ─────────────────── */

async function confirmDelete(d: DocSummary) {
  try {
    await ElMessageBox.confirm(
      `删除后无法恢复，确定删除「${d.title}」吗？`,
      '删除简历',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.delete(`/api/resume/documents/${d.id}`)
    ElMessage.success('已删除')
    if (docId.value === d.id) backToBrowse()
    await loadDocuments()
  } catch (e) {
    ElMessage.error(getErrMessage(e, '删除失败'))
  }
}

async function backToBrowse() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前有未保存的修改，返回后将丢失。确定返回吗？', '未保存的修改', {
        confirmButtonText: '放弃修改并返回',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  mode.value = 'browse'
  docId.value = null
  dirty.value = false
}

/* ─────────────────── 脏标记与离开提醒 ─────────────────── */

/** 初次装载数据时不应标脏：用 loadingGuard 挡住装载期间的 watch 回调 */
let loadingGuard = false

watch(
  doc,
  () => {
    if (loadingGuard || mode.value !== 'editor') return
    dirty.value = true
  },
  { deep: true }
)

watch([docTitle, templateId], () => {
  if (loadingGuard || mode.value !== 'editor') return
  dirty.value = true
})

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (!dirty.value) return
  e.preventDefault()
  // 部分浏览器仍要求设置 returnValue 才弹确认
  e.returnValue = ''
}

/* ─────────────────── 预览缩放 ─────────────────── */

let ro: ResizeObserver | null = null

function measure() {
  const el = viewportRef.value
  if (!el) return
  // 减去内边距，避免纸面被容器 padding 挤出滚动条
  viewportWidth.value = Math.max(240, el.clientWidth - 2)
}

onMounted(async () => {
  loadingGuard = true
  await Promise.all([loadDocuments(), loadTemplates()])
  await nextTick()
  loadingGuard = false

  ro = new ResizeObserver(measure)
  if (viewportRef.value) ro.observe(viewportRef.value)
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  window.removeEventListener('beforeunload', onBeforeUnload)
})

// 进入编辑态后容器才存在，需要重新挂观察器
watch(mode, async () => {
  await nextTick()
  measure()
  if (ro && viewportRef.value) ro.observe(viewportRef.value)
})
</script>

<style scoped>
.studio-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 32px;
}

.page-title {
  margin: 0 0 8px;
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.page-desc {
  margin: 0;
  max-width: 60ch;
  font-size: var(--font-size-sm);
  line-height: 1.65;
  color: var(--c-text-secondary);
}

.section {
  margin-bottom: 40px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-title {
  margin: 0 0 16px;
  font-family: var(--font-title);
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--c-text);
}

.section-head .section-title {
  margin-bottom: 0;
}

.section-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

/* ── 我的简历列表 ── */
.doc-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.doc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 64px;
  padding: 14px 16px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
  cursor: pointer;
  transition: border-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.doc-item:hover {
  border-color: var(--brand-primary);
}

.doc-item:active {
  transform: scale(0.98);
}

.doc-item:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.doc-item-title {
  margin: 0 0 4px;
  font-family: var(--font-title);
  font-size: var(--font-size-base);
  font-weight: 600;
  color: var(--c-text);
}

.doc-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

.dot-sep {
  width: 3px;
  height: 3px;
  border-radius: var(--radius-full);
  background: var(--c-text-quaternary);
}

/* ── 模板卡片 ── */
.tpl-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
}

.tpl-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-xl);
  background: var(--c-surface);
  overflow: hidden;
  cursor: pointer;
  transition: border-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.tpl-card:hover {
  border-color: var(--brand-primary);
}

.tpl-card:active {
  transform: scale(0.98);
}

.tpl-card:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.tpl-card.is-active {
  border-color: var(--brand-primary);
}

/* 缩略图：固定 248px 高，用灰条示意版式（不加载真截图） */
.tpl-thumb {
  height: 248px;
  padding: 20px 18px;
  background: var(--c-paper);
  border-bottom: 1px solid var(--c-border);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.thumb-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-paper-rule);
}

.thumb-name {
  display: block;
  width: 42%;
  height: 11px;
  border-radius: var(--radius-xs);
  background: color-mix(in srgb, var(--c-paper-ink) 42%, transparent);
}

.thumb-sub {
  display: block;
  width: 26%;
  height: 6px;
  border-radius: var(--radius-xs);
  background: color-mix(in srgb, var(--c-paper-ink) 14%, transparent);
}

.thumb-body {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

/* 反向双栏（v1.79.0）：缩略图里侧栏画在左边，与真实版式一致 */
.thumb-two-column-reverse .thumb-body {
  flex-direction: row-reverse;
}

.thumb-two-column-reverse .thumb-side {
  padding-left: 0;
  padding-right: 14px;
  border-left: none;
  border-right: 1px solid var(--c-paper-rule);
}

.thumb-col {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
  min-width: 0;
}

.thumb-side {
  display: flex;
  flex-direction: column;
  gap: 7px;
  width: 34%;
  padding-left: 14px;
  border-left: 1px solid var(--c-paper-rule);
}

.thumb-bar {
  display: block;
  height: 5px;
  border-radius: var(--radius-xs);
  background: color-mix(in srgb, var(--c-paper-ink) 12%, transparent);
}

.thumb-bar.is-title {
  height: 6px;
  width: 38%;
  background: color-mix(in srgb, var(--c-brand-ink) 72%, transparent);
}

.thumb-bar.is-short {
  width: 62%;
}

.thumb-bar.is-mid {
  width: 84%;
}

.tpl-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px 16px;
}

.tpl-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tpl-name {
  margin: 0;
  font-family: var(--font-title);
  font-size: var(--font-size-base);
  font-weight: 700;
  color: var(--c-text);
}

.tpl-scene {
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: 1.55;
  color: var(--c-text-tertiary);
}

.tpl-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
  padding-top: 4px;
}

.chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
}

.chip-ok {
  border-color: var(--c-success-border);
  color: var(--c-success);
  background: var(--c-success-light);
}

.chip-warn {
  border-color: var(--c-warning-border);
  color: var(--c-warning);
  background: var(--c-warning-light);
}

.chip-accent {
  border-color: var(--c-accent-line);
  color: var(--c-accent);
  background: var(--c-accent-soft);
}

/* ── 编辑态头部 ── */
.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--c-border);
}

.editor-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  padding: 0 8px;
  border: none;
  border-radius: var(--radius-md);
  background: none;
  font-size: var(--font-size-sm);
  color: var(--c-text-secondary);
  cursor: pointer;
  transition: color 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.back-btn:hover {
  color: var(--brand-primary);
}

.back-btn:active {
  transform: scale(0.98);
}

.back-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.title-input {
  min-width: 0;
  width: 260px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  font-family: var(--font-title);
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--c-text);
  transition: border-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
    background 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.title-input:hover {
  border-color: var(--c-border);
}

.title-input:focus {
  outline: none;
  border-color: var(--brand-primary);
  background: var(--c-surface);
}

.dirty-flag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-warning);
}

.editor-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.tpl-select {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface);
  font-size: var(--font-size-sm);
  color: var(--c-text);
  cursor: pointer;
}

.tpl-select:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

/* ── 编辑主体 ── */
.editor-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 32px;
  align-items: start;
}

.editor-pane {
  min-width: 0;
}

.preview-pane {
  position: sticky;
  top: 88px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.preview-label {
  font-size: var(--font-size-xs);
  font-weight: 600;
  color: var(--c-text-tertiary);
}

.preview-tools {
  display: flex;
  gap: 4px;
}

.text-btn {
  min-height: 32px;
  padding: 0 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  font-size: var(--font-size-xs);
  color: var(--brand-primary);
  cursor: pointer;
  transition: background 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.text-btn:hover {
  background: var(--brand-primary-50);
}

.text-btn:active {
  transform: scale(0.98);
}

.text-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.preview-note {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--c-warning-border);
  border-radius: var(--radius-md);
  background: var(--c-warning-light);
  font-size: var(--font-size-xs);
  line-height: 1.55;
  color: var(--c-warning);
}

.preview-viewport {
  position: relative;
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-bg-alt);
}

.preview-scaler {
  transform-origin: top left;
  width: 794px;
}

/* 分段控件（DESIGN.md §5.5「模式切换」idiom）：
   底槽 + 4px padding + 选中项浮起。移动端才出现，桌面端两栏并排。 */
.mobile-switch {
  display: none;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-bg-alt);
}

.mobile-switch button {
  flex: 1;
  min-height: 44px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: none;
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--c-text-secondary);
  cursor: pointer;
  transition: background 200ms cubic-bezier(0.22, 1, 0.36, 1),
    color 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.mobile-switch button.active {
  border-color: var(--c-border);
  background: var(--c-surface);
  color: var(--c-text);
}

.mobile-switch button:active {
  transform: scale(0.98);
}

.mobile-switch button:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

/* ── 响应式 ── */
@media (max-width: 1023px) {
  .tpl-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .editor-body {
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .preview-pane {
    position: static;
  }
}

@media (max-width: 768px) {
  .studio-page {
    padding: 20px 16px 48px;
  }

  .page-head {
    flex-direction: column;
    gap: 16px;
  }

  .tpl-grid {
    grid-template-columns: 1fr;
  }

  .editor-head {
    flex-direction: column;
    align-items: stretch;
  }

  .editor-actions {
    justify-content: space-between;
  }

  .title-input {
    width: 100%;
  }

  .mobile-switch {
    display: flex;
    margin-bottom: 16px;
  }

  .is-hidden-mobile {
    display: none;
  }
}
</style>

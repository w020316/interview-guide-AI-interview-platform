<template>
  <article class="resume-doc" :class="[template.cssClass, { 'is-two-col': isTwoCol }]">
    <header class="doc-head">
      <h1 class="doc-name">{{ doc.basics.name || '你的姓名' }}</h1>
      <p v-if="doc.basics.title" class="doc-title">{{ doc.basics.title }}</p>

      <ul v-if="contacts.length" class="doc-contact">
        <li v-for="(c, i) in contacts" :key="`c-${i}`">{{ c }}</li>
      </ul>

      <ul v-if="doc.basics.links.length" class="doc-links">
        <li v-for="(l, i) in doc.basics.links" :key="`l-${i}`">
          {{ l.label || l.url }}
        </li>
      </ul>
    </header>

    <div class="doc-body">
      <div class="doc-main">
        <ResumeSectionBlock
          v-for="key in mainSections"
          :key="`main-${key}`"
          :section-key="key"
          :doc="doc"
          :locale="template.locale"
        />
      </div>

      <aside v-if="sideSections.length" class="doc-side">
        <ResumeSectionBlock
          v-for="key in sideSections"
          :key="`side-${key}`"
          :section-key="key"
          :doc="doc"
          :locale="template.locale"
        />
      </aside>
    </div>
  </article>
</template>

<script setup lang="ts">
/**
 * 简历纸面渲染组件（v1.67.0）
 *
 * ── 关键设计 ──────────────────────────────────────────────────────
 * 1. **一份 DOM，五套版式**：5 个模板共用同一套结构，差异全部由 CSS 变量
 *    （`--doc-fs-*` / `--doc-gap-*` / 栏宽）表达。好处是改一处内容结构，
 *    五套模板同时受益，不会出现「某套模板漏了某个字段」。
 * 2. **纸面固定 A4 宽度 794px**（210mm @96dpi），缩放由父容器用
 *    `transform: scale()` 完成 —— **不要**在纸面内部用响应式字号，
 *    否则预览与打印出来的字号不一致，用户会以为「导出错了」。
 * 3. **双栏的 DOM 顺序是「Header → 左栏全部 → 右栏全部」**，
 *    用 CSS Grid 而非 float/absolute 排布。这样 ATS 解析器逐段读取时
 *    不会把左右栏的文字交错串行（见 ResumeTemplateCatalog 的 ATS 分级说明）。
 * 4. 空区块整段不渲染（`visibleSections()` 已剔除），不留空标题。
 */
import { computed } from 'vue'
import ResumeSectionBlock from './ResumeSectionBlock.vue'
import type { ResumeDoc, ResumeSectionKey, ResumeTemplateMeta } from '../utils/resumeDoc'
import { findTemplate, visibleSections } from '../utils/resumeDoc'

const props = defineProps<{
  doc: ResumeDoc
  templateId: string
}>()

const template = computed<ResumeTemplateMeta>(() => findTemplate(props.templateId))
const isTwoCol = computed(() => template.value.layout === 'two-column')

/** 双栏模板的右侧栏区块：只放无序列表型内容，被解析器追加到文末也不失真 */
const SIDE_SECTIONS: ResumeSectionKey[] = ['skills', 'extras']

const visible = computed(() => visibleSections(props.doc, template.value))

const mainSections = computed(() =>
  isTwoCol.value ? visible.value.filter((k) => !SIDE_SECTIONS.includes(k)) : visible.value
)

const sideSections = computed(() =>
  isTwoCol.value ? visible.value.filter((k) => SIDE_SECTIONS.includes(k)) : []
)

/** 联系方式行：空字段直接不出现，不留多余分隔符 */
const contacts = computed(() => {
  const b = props.doc.basics
  return [b.phone, b.email, b.location].map((s) => s.trim()).filter(Boolean)
})
</script>

<style scoped>
/* ── 纸面：恒为亮底深字，令牌不随主题翻转（见 variables.css 的 --c-paper* 注释） ── */
.resume-doc {
  width: 794px;
  min-height: 1123px;
  padding: 40px 48px;
  box-sizing: border-box;
  background: var(--c-paper);
  color: var(--c-paper-ink);
  font-family: -apple-system, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei',
    'Source Han Sans SC', 'Noto Sans CJK SC', sans-serif;
  /* 版式变量默认值 = T1 单栏通用；各模板类覆盖 */
  --doc-fs-name: 26px;
  --doc-fs-sec: 13px;
  --doc-fs-body: 12.5px;
  --doc-fs-date: 12px;
  --doc-lh: 1.58;
  --doc-gap-sec: 24px;
  --doc-gap-title: 10px;
  --doc-gap-entry: 16px;
  --doc-gap-list: 8px;
  --doc-gap-li: 4px;
}

/* ── 页眉 ── */
.doc-head {
  margin-bottom: 20px;
  break-inside: avoid;
}

.doc-name {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--doc-fs-name);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: 0.01em;
  color: var(--c-paper-ink);
  /* 姓名是页面级标题（≥24px），用展示级衬线符合 DESIGN.md §2 */
  text-wrap: balance;
}

.doc-title {
  margin: 4px 0 0;
  font-size: var(--doc-fs-body);
  color: var(--c-brand-ink);
  font-weight: 600;
}

.doc-contact,
.doc-links {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  font-size: var(--doc-fs-date);
  color: var(--c-paper-ink-soft);
}

/* 联系方式之间用发丝线分隔，比「·」更克制，也避免与正文标点混淆 */
.doc-contact li + li::before {
  content: '';
  display: inline-block;
  width: 1px;
  height: 10px;
  margin-right: 14px;
  vertical-align: -1px;
  background: var(--c-paper-rule);
}

.doc-links li {
  color: var(--c-brand-ink);
}

/* ── 正文区 ── */
.doc-body {
  display: block;
}

.is-two-col .doc-body {
  display: grid;
  /* 左主栏 : 右侧栏 = 1fr : 216px，gap 32（与设计规格一致） */
  grid-template-columns: 1fr 216px;
  gap: 32px;
  align-items: start;
}

.doc-main {
  min-width: 0;
}

.doc-side {
  min-width: 0;
  padding-left: 20px;
  border-left: 1px solid var(--c-paper-rule);
}

/* ══════════ 模板 T1 单栏·通用（默认值，无需覆盖） ══════════ */

/* ══════════ 模板 T2 单栏·应届：教育背景前置，条目更密 ══════════ */
.tpl-campus {
  --doc-gap-sec: 22px;
}

/* ══════════ 模板 T3 双栏·技术：字号略收，侧栏更紧凑 ══════════ */
.tpl-tech {
  --doc-fs-name: 24px;
  --doc-fs-sec: 12.5px;
  --doc-fs-body: 12.5px;
  --doc-fs-date: 11.5px;
  --doc-lh: 1.55;
  --doc-gap-sec: 20px;
  --doc-gap-title: 8px;
  --doc-gap-entry: 14px;
  --doc-gap-list: 6px;
}

.tpl-tech .doc-side :deep(.sec-title) {
  font-size: 11.5px;
}

/* ══════════ 模板 T4 单栏·海外英文：页眉居中、章节名大写 ══════════ */
.tpl-intl {
  --doc-fs-name: 25px;
  --doc-fs-sec: 11.5px;
  --doc-fs-body: 13px;
  --doc-fs-date: 12px;
  --doc-lh: 1.55;
  --doc-gap-sec: 24px;
  padding: 48px 52px;
}

.tpl-intl .doc-head {
  text-align: center;
  margin-bottom: 24px;
}

.tpl-intl .doc-name {
  /* 纯拉丁姓名用 Georgia：无中文回退问题，且是英文简历的常见选择 */
  font-family: Georgia, 'Times New Roman', serif;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.tpl-intl .doc-contact,
.tpl-intl .doc-links {
  justify-content: center;
}

.tpl-intl .doc-contact li + li::before {
  display: none;
}

.tpl-intl .doc-contact li + li {
  margin-left: 6px;
}

.tpl-intl :deep(.sec-title) {
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

/* ══════════ 模板 T5 紧凑·一页：字号与间距同步压缩 ══════════ */
.tpl-compact {
  --doc-fs-name: 22px;
  --doc-fs-sec: 12.5px;
  --doc-fs-body: 11.5px;
  --doc-fs-date: 11px;
  --doc-lh: 1.45;
  --doc-gap-sec: 16px;
  --doc-gap-title: 6px;
  --doc-gap-entry: 12px;
  --doc-gap-list: 4px;
  --doc-gap-li: 2px;
  padding: 36px 40px;
}

/* ── 移动端（≤767）：纸面不缩放字体，只由父容器整体缩放；
     这里仅把页边距收窄，避免窄屏下内容可用宽度过小 ── */
@media (max-width: 768px) {
  .resume-doc {
    padding: 28px 24px;
  }

  .is-two-col .doc-body {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .doc-side {
    padding-left: 0;
    border-left: none;
  }
}
</style>

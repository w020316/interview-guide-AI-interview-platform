<template>
  <section class="doc-sec">
    <h2 class="sec-title">{{ label }}</h2>

    <!-- 个人简介 -->
    <p v-if="sectionKey === 'summary'" class="sec-summary">{{ doc.summary }}</p>

    <!-- 工作经历 -->
    <template v-else-if="sectionKey === 'experience'">
      <div v-for="(e, i) in doc.experience" :key="`exp-${i}`" class="entry">
        <div class="entry-head">
          <span class="entry-main">{{ e.role || e.org }}</span>
          <span class="entry-date">{{ formatPeriod(e.start, e.end) }}</span>
        </div>
        <div class="entry-sub">
          <span v-if="e.role && e.org">{{ e.org }}</span>
          <span v-if="e.location" class="entry-loc">{{ e.location }}</span>
        </div>
        <ul v-if="e.bullets.length" class="entry-bullets">
          <li v-for="(b, bi) in e.bullets" :key="`exp-b-${bi}`">{{ b }}</li>
        </ul>
      </div>
    </template>

    <!-- 项目经历 -->
    <template v-else-if="sectionKey === 'projects'">
      <div v-for="(p, i) in doc.projects" :key="`prj-${i}`" class="entry">
        <div class="entry-head">
          <span class="entry-main">{{ p.name }}{{ p.role ? ` · ${p.role}` : '' }}</span>
          <span class="entry-date">{{ formatPeriod(p.start, p.end) }}</span>
        </div>
        <div v-if="p.tech.length" class="entry-sub">
          <span class="entry-tech">{{ p.tech.join(' / ') }}</span>
        </div>
        <ul v-if="p.bullets.length" class="entry-bullets">
          <li v-for="(b, bi) in p.bullets" :key="`prj-b-${bi}`">{{ b }}</li>
        </ul>
      </div>
    </template>

    <!-- 教育背景 -->
    <template v-else-if="sectionKey === 'education'">
      <div v-for="(e, i) in doc.education" :key="`edu-${i}`" class="entry">
        <div class="entry-head">
          <span class="entry-main">{{ e.school }}</span>
          <span class="entry-date">{{ formatPeriod(e.start, e.end) }}</span>
        </div>
        <div class="entry-sub">
          <span v-if="eduLine(e)">{{ eduLine(e) }}</span>
          <span v-if="e.gpa" class="entry-loc">GPA {{ e.gpa }}</span>
        </div>
        <ul v-if="e.highlights.length" class="entry-bullets">
          <li v-for="(b, bi) in e.highlights" :key="`edu-b-${bi}`">{{ b }}</li>
        </ul>
      </div>
    </template>

    <!-- 技能专长 -->
    <template v-else-if="sectionKey === 'skills'">
      <div v-for="(s, i) in doc.skills" :key="`sk-${i}`" class="skill-row">
        <!-- 分类为空时不输出空标签：否则会在 grid 里占掉一列，留下莫名留白 -->
        <span v-if="s.category" class="skill-cat">{{ s.category }}</span>
        <span class="skill-items" :class="{ 'is-full': !s.category }">{{ s.items.join(' / ') }}</span>
      </div>
    </template>

    <!-- 证书与荣誉 -->
    <template v-else-if="sectionKey === 'extras'">
      <ul class="entry-bullets extras-list">
        <li v-for="(x, i) in doc.extras" :key="`ex-${i}`">
          <span class="extra-title">{{ x.title }}</span>
          <span v-if="x.date" class="entry-date">{{ x.date }}</span>
          <span v-if="x.desc" class="extra-desc">{{ x.desc }}</span>
        </li>
      </ul>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * 简历区块渲染组件（v1.67.0）
 *
 * 只负责「一个区块内部怎么排」，不关心纸面尺寸与栏位 —— 那些由
 * `ResumeDocPreview` 通过 CSS 变量（`--doc-fs-*` / `--doc-gap-*`）下发。
 * 这样 5 套模板共用同一份 DOM 与同一套区块样式，换模板 = 换变量，不会漂移。
 *
 * 无数据时**整段不渲染**由父组件的 `hasSection()` 保证，本组件只做展示。
 */
import type { ResumeSectionKey, ResumeDoc, ResumeEducation } from '../utils/resumeDoc'
import { formatPeriod, sectionLabel } from '../utils/resumeDoc'
import { computed } from 'vue'

const props = defineProps<{
  sectionKey: ResumeSectionKey
  doc: ResumeDoc
  locale: 'zh' | 'en'
}>()

const label = computed(() => sectionLabel(props.sectionKey, props.locale))

/** 「学历 · 专业」拼装：任一端为空都不留多余分隔符 */
function eduLine(e: ResumeEducation): string {
  return [e.degree, e.major].map((s) => s.trim()).filter(Boolean).join(' · ')
}
</script>

<style scoped>
.doc-sec {
  margin-bottom: var(--doc-gap-sec, 24px);
  /* 打印时避免一个区块被从中间切断 */
  break-inside: avoid;
  page-break-inside: avoid;
}

.sec-title {
  margin: 0 0 var(--doc-gap-title, 10px);
  font-family: var(--font-title);
  font-size: var(--doc-fs-sec, 13px);
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: 0.04em;
  color: var(--c-brand-ink);
  /* 区块标题后面必须跟内容，不能孤零零留在页底 */
  break-after: avoid;
  page-break-after: avoid;
}

.sec-title::after {
  content: '';
  display: block;
  height: 1px;
  margin-top: 6px;
  background: var(--c-paper-rule);
}

.sec-summary {
  margin: 0;
  font-size: var(--doc-fs-body, 12.5px);
  line-height: var(--doc-lh, 1.58);
  color: var(--c-paper-ink);
}

.entry {
  margin-bottom: var(--doc-gap-entry, 16px);
  break-inside: avoid;
  page-break-inside: avoid;
}

.entry:last-child {
  margin-bottom: 0;
}

.entry-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
}

.entry-main {
  font-size: var(--doc-fs-body, 12.5px);
  font-weight: 700;
  color: var(--c-paper-ink);
}

.entry-date {
  flex-shrink: 0;
  font-size: var(--doc-fs-date, 12px);
  /* 日期用等宽数字但**不用** --font-mono：简历日期与中英混排同行，
   * mono 字族会让数字与汉字基线错位（DESIGN.md §2 的 mono 规则针对分数/统计，
   * 文档域已登记为例外）。 */
  font-variant-numeric: tabular-nums;
  color: var(--c-paper-ink-soft);
}

.entry-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-top: 2px;
  font-size: var(--doc-fs-date, 12px);
  color: var(--c-paper-ink-soft);
}

.entry-loc,
.entry-tech {
  color: var(--c-paper-ink-soft);
}

.entry-bullets {
  margin: var(--doc-gap-list, 8px) 0 0;
  padding-left: 16px;
  font-size: var(--doc-fs-body, 12.5px);
  line-height: var(--doc-lh, 1.58);
  color: var(--c-paper-ink);
}

.entry-bullets li {
  margin-bottom: var(--doc-gap-li, 4px);
}

.entry-bullets li:last-child {
  margin-bottom: 0;
}

/* 打印时的孤行控制：段首/段尾至少留 2 行 */
.entry-bullets li,
.sec-summary {
  orphans: 2;
  widows: 2;
}

.skill-row {
  display: grid;
  grid-template-columns: minmax(64px, auto) 1fr;
  gap: 10px;
  margin-bottom: var(--doc-gap-li, 4px);
  font-size: var(--doc-fs-body, 12.5px);
  line-height: var(--doc-lh, 1.58);
}

.skill-cat {
  font-weight: 700;
  color: var(--c-paper-ink);
}

.skill-items {
  color: var(--c-paper-ink);
}

/* 无分类时横跨整行，避免左侧留出一条空白列 */
.skill-items.is-full {
  grid-column: 1 / -1;
}

.extras-list {
  list-style: none;
  padding-left: 0;
}

.extras-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  align-items: baseline;
}

.extra-title {
  font-weight: 700;
}

.extra-desc {
  color: var(--c-paper-ink-soft);
}
</style>

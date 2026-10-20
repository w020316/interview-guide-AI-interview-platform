<template>
  <div class="doc-form">
    <!-- ── 基本信息 ── -->
    <section class="form-group">
      <h3 class="group-title">基本信息</h3>
      <div class="field-grid">
        <label class="field">
          <span class="field-label">姓名</span>
          <BaseInput v-model="doc.basics.name" placeholder="张三" />
        </label>
        <label class="field">
          <span class="field-label">求职意向 / 职位</span>
          <BaseInput v-model="doc.basics.title" placeholder="后端开发工程师" />
        </label>
        <label class="field">
          <span class="field-label">手机</span>
          <BaseInput v-model="doc.basics.phone" placeholder="138 0000 0000" />
        </label>
        <label class="field">
          <span class="field-label">邮箱</span>
          <BaseInput v-model="doc.basics.email" placeholder="you@example.com" />
        </label>
        <label class="field field-full">
          <span class="field-label">所在城市</span>
          <BaseInput v-model="doc.basics.location" placeholder="上海" />
        </label>
      </div>

      <div class="sub-block">
        <div class="sub-head">
          <span class="field-label">个人链接（GitHub / 博客 / 作品集）</span>
          <button type="button" class="link-btn" @click="addLink">+ 添加链接</button>
        </div>
        <div v-for="(l, i) in doc.basics.links" :key="`link-${i}`" class="inline-row">
          <BaseInput v-model="l.label" placeholder="GitHub" class="grow-sm" />
          <BaseInput v-model="l.url" placeholder="https://github.com/you" class="grow" />
          <button type="button" class="icon-btn" aria-label="删除该链接" @click="doc.basics.links.splice(i, 1)">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
        <p v-if="!doc.basics.links.length" class="hint">还没有链接。技术岗建议至少放一个代码仓库地址。</p>
      </div>
    </section>

    <!-- ── 个人简介 ── -->
    <section class="form-group">
      <h3 class="group-title">个人简介</h3>
      <BaseTextarea
        v-model="doc.summary"
        :rows="3"
        placeholder="一句话说清你的方向、年限与最拿得出手的成果。不超过 3 行。"
      />
    </section>

    <!-- ── 工作经历 ── -->
    <section class="form-group">
      <div class="sub-head">
        <h3 class="group-title">工作经历</h3>
        <button type="button" class="link-btn" @click="addExperience">+ 添加一段</button>
      </div>
      <div v-for="(e, i) in doc.experience" :key="`exp-${i}`" class="entry-card">
        <div class="entry-card-head">
          <span class="entry-index">经历 {{ i + 1 }}</span>
          <button type="button" class="icon-btn" aria-label="删除该段经历" @click="doc.experience.splice(i, 1)">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div class="field-grid">
          <label class="field">
            <span class="field-label">公司</span>
            <BaseInput v-model="e.org" placeholder="某某科技有限公司" />
          </label>
          <label class="field">
            <span class="field-label">职位</span>
            <BaseInput v-model="e.role" placeholder="后端开发工程师" />
          </label>
          <label class="field">
            <span class="field-label">开始时间</span>
            <BaseInput v-model="e.start" placeholder="2023.07" />
          </label>
          <label class="field">
            <span class="field-label">结束时间</span>
            <BaseInput v-model="e.end" placeholder="至今" />
          </label>
          <label class="field field-full">
            <span class="field-label">城市（可选）</span>
            <BaseInput v-model="e.location" placeholder="上海" />
          </label>
        </div>
        <label class="field field-full">
          <span class="field-label">工作内容（每行一条，建议以动词开头并带数字结果）</span>
          <BaseTextarea
            :model-value="e.bullets.join('\n')"
            :rows="4"
            placeholder="主导订单服务重构，接口 P99 从 820ms 降到 190ms&#10;搭建灰度发布流程，线上故障回滚时间由 30 分钟缩短到 3 分钟"
            @update:model-value="(v: string) => (e.bullets = toLines(v))"
          />
        </label>
      </div>
      <p v-if="!doc.experience.length" class="hint">还没有工作经历。应届生可只填项目经历。</p>
    </section>

    <!-- ── 项目经历 ── -->
    <section class="form-group">
      <div class="sub-head">
        <h3 class="group-title">项目经历</h3>
        <button type="button" class="link-btn" @click="addProject">+ 添加一个项目</button>
      </div>
      <div v-for="(p, i) in doc.projects" :key="`prj-${i}`" class="entry-card">
        <div class="entry-card-head">
          <span class="entry-index">项目 {{ i + 1 }}</span>
          <button type="button" class="icon-btn" aria-label="删除该项目" @click="doc.projects.splice(i, 1)">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div class="field-grid">
          <label class="field">
            <span class="field-label">项目名</span>
            <BaseInput v-model="p.name" placeholder="智能面试辅助平台" />
          </label>
          <label class="field">
            <span class="field-label">你的角色</span>
            <BaseInput v-model="p.role" placeholder="核心开发" />
          </label>
          <label class="field">
            <span class="field-label">开始时间</span>
            <BaseInput v-model="p.start" placeholder="2024.03" />
          </label>
          <label class="field">
            <span class="field-label">结束时间</span>
            <BaseInput v-model="p.end" placeholder="2024.09" />
          </label>
          <label class="field field-full">
            <span class="field-label">技术栈（用逗号或顿号分隔）</span>
            <BaseInput
              :model-value="p.tech.join(', ')"
              placeholder="Spring Boot, PostgreSQL, Vue3"
              @update:model-value="(v: string) => (p.tech = toTags(v))"
            />
          </label>
        </div>
        <label class="field field-full">
          <span class="field-label">项目内容（每行一条）</span>
          <BaseTextarea
            :model-value="p.bullets.join('\n')"
            :rows="4"
            placeholder="设计多源岗位采集链路，覆盖 14 个数据源&#10;实现 AI 结构化输出校验与重试，脏数据下发率降为 0"
            @update:model-value="(v: string) => (p.bullets = toLines(v))"
          />
        </label>
      </div>
    </section>

    <!-- ── 教育背景 ── -->
    <section class="form-group">
      <div class="sub-head">
        <h3 class="group-title">教育背景</h3>
        <button type="button" class="link-btn" @click="addEducation">+ 添加一段</button>
      </div>
      <div v-for="(e, i) in doc.education" :key="`edu-${i}`" class="entry-card">
        <div class="entry-card-head">
          <span class="entry-index">教育 {{ i + 1 }}</span>
          <button type="button" class="icon-btn" aria-label="删除该段教育经历" @click="doc.education.splice(i, 1)">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div class="field-grid">
          <label class="field">
            <span class="field-label">学校</span>
            <BaseInput v-model="e.school" placeholder="某某大学" />
          </label>
          <label class="field">
            <span class="field-label">学历</span>
            <BaseInput v-model="e.degree" placeholder="本科" />
          </label>
          <label class="field">
            <span class="field-label">专业</span>
            <BaseInput v-model="e.major" placeholder="计算机科学与技术" />
          </label>
          <label class="field">
            <span class="field-label">GPA（可选）</span>
            <BaseInput v-model="e.gpa" placeholder="3.8 / 4.0" />
          </label>
          <label class="field">
            <span class="field-label">开始时间</span>
            <BaseInput v-model="e.start" placeholder="2020.09" />
          </label>
          <label class="field">
            <span class="field-label">结束时间</span>
            <BaseInput v-model="e.end" placeholder="2024.06" />
          </label>
        </div>
        <label class="field field-full">
          <span class="field-label">主修课程 / 荣誉（每行一条，可选）</span>
          <BaseTextarea
            :model-value="e.highlights.join('\n')"
            :rows="2"
            placeholder="主修：数据结构、操作系统、数据库原理&#10;校级一等奖学金（2022）"
            @update:model-value="(v: string) => (e.highlights = toLines(v))"
          />
        </label>
      </div>
    </section>

    <!-- ── 技能 ── -->
    <section class="form-group">
      <div class="sub-head">
        <h3 class="group-title">技能专长</h3>
        <button type="button" class="link-btn" @click="addSkill">+ 添加一组</button>
      </div>
      <div v-for="(s, i) in doc.skills" :key="`sk-${i}`" class="inline-row">
        <BaseInput v-model="s.category" placeholder="后端" class="grow-sm" />
        <BaseInput
          :model-value="s.items.join(', ')"
          placeholder="Java, Spring Boot, PostgreSQL"
          class="grow"
          @update:model-value="(v: string) => (s.items = toTags(v))"
        />
        <button type="button" class="icon-btn" aria-label="删除该组技能" @click="doc.skills.splice(i, 1)">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
      <p v-if="!doc.skills.length" class="hint">建议按「语言 / 框架 / 工具」分组，别把所有词堆成一行。</p>
    </section>

    <!-- ── 证书与荣誉 ── -->
    <section class="form-group">
      <div class="sub-head">
        <h3 class="group-title">证书与荣誉</h3>
        <button type="button" class="link-btn" @click="addExtra">+ 添加一条</button>
      </div>
      <div v-for="(x, i) in doc.extras" :key="`ex-${i}`" class="inline-row">
        <BaseInput v-model="x.title" placeholder="CET-6" class="grow-sm" />
        <BaseInput v-model="x.date" placeholder="2023.06" class="grow-xs" />
        <BaseInput v-model="x.desc" placeholder="分数 / 说明（可选）" class="grow" />
        <button type="button" class="icon-btn" aria-label="删除该条" @click="doc.extras.splice(i, 1)">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 结构化简历编辑表单（v1.67.0）
 *
 * ── 为什么直接修改 props.doc 的属性 ──────────────────────────────────
 * `doc` 是父组件持有的 reactive 对象，本组件是它的「就地编辑器」。
 * 表单有 40+ 个字段，若每个字段都走 `emit('update', newDoc)` 全量替换，
 * 每敲一个字都会重建整棵对象树，中文输入法下会明显卡顿。
 * 因此这里**刻意**就地修改（Vue 只对「重新赋值整个 prop」告警，修改对象属性是合法的）。
 * 脏标记由父组件 `watch(doc, { deep: true })` 统一负责。
 *
 * ── 范围约束（产品评审「明确不做」）──────────────────────────────────
 * 没有富文本工具栏、没有字号/颜色、没有表格、没有图片、没有拖拽排序。
 * 要点一律是「一行一条」的纯文本，这是为了保住 ATS 可解析性。
 */
import BaseInput from './BaseInput.vue'
import BaseTextarea from './BaseTextarea.vue'
import type { ResumeDoc } from '../utils/resumeDoc'

const props = defineProps<{ doc: ResumeDoc }>()

/** textarea ↔ string[] 的互转：空行剔除，保留用户输入顺序 */
function toLines(v: string): string[] {
  return v
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s !== '')
}

/** 逗号 / 顿号 / 分号 / 竖线 都当作分隔符，容错用户的中文输入习惯 */
function toTags(v: string): string[] {
  return v
    .split(/[,，、;；|]/)
    .map((s) => s.trim())
    .filter((s) => s !== '')
}

/**
 * 新增函数统一走 `props.doc.xxx.push()`：模板里 `doc` 是只读解构，
 * `doc.basics.links.push(...)` 无法在模板表达式中完成，必须回到脚本层。
 */
function addLink() {
  props.doc.basics.links.push({ label: '', url: '' })
}

function addExperience() {
  props.doc.experience.push({
    org: '',
    role: '',
    start: '',
    end: '',
    location: '',
    bullets: [],
  })
}

function addProject() {
  props.doc.projects.push({
    name: '',
    role: '',
    start: '',
    end: '',
    tech: [],
    bullets: [],
  })
}

function addEducation() {
  props.doc.education.push({
    school: '',
    degree: '',
    major: '',
    start: '',
    end: '',
    gpa: '',
    highlights: [],
  })
}

function addSkill() {
  props.doc.skills.push({ category: '', items: [] })
}

function addExtra() {
  props.doc.extras.push({ title: '', date: '', desc: '' })
}
</script>

<style scoped>
.doc-form {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.group-title {
  margin: 0;
  font-family: var(--font-title);
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--c-text);
}

.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sub-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.field-full {
  grid-column: 1 / -1;
}

.field-label {
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

.entry-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-bg-alt);
}

.entry-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.entry-index {
  font-size: var(--font-size-xs);
  font-weight: 600;
  color: var(--c-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.grow {
  flex: 1 1 auto;
  min-width: 0;
}

.grow-sm {
  flex: 0 0 128px;
  min-width: 0;
}

.grow-xs {
  flex: 0 0 96px;
  min-width: 0;
}

.link-btn {
  min-height: 32px;
  padding: 0 4px;
  border: none;
  background: none;
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--brand-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: color 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.link-btn:hover {
  color: var(--brand-primary-hover);
}

.link-btn:active {
  transform: scale(0.98);
}

.link-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: none;
  color: var(--c-text-tertiary);
  cursor: pointer;
  transition: color 200ms cubic-bezier(0.22, 1, 0.36, 1),
    border-color 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.icon-btn:hover {
  color: var(--c-danger);
  border-color: var(--c-danger-border);
}

.icon-btn:active {
  transform: scale(0.98);
}

.icon-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--c-text-tertiary);
}

@media (max-width: 640px) {
  .field-grid {
    grid-template-columns: 1fr;
  }

  .inline-row {
    flex-wrap: wrap;
  }

  .grow-sm,
  .grow-xs {
    flex: 1 1 100%;
  }
}
</style>

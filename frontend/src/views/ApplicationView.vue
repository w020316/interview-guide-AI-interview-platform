<template>
  <div class="app-page">
    <header class="page-header">
      <h1>投递看板</h1>
      <p>本地投递台账：人工确认投递、跟踪回复、针对岗位定制简历。平台不代替你投递，只帮你把「投了什么、谁回了、该催谁」管清楚。</p>
      <div class="page-actions">
        <BaseButton variant="ghost" @click="openNotice">粘贴通知识别</BaseButton>
        <BaseButton variant="ghost" @click="openImport">从表格导入</BaseButton>
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="card skeleton-card">
      <div class="skeleton skeleton-line w-60"></div>
      <div class="skeleton skeleton-line w-40"></div>
    </div>

    <!-- 统计 -->
    <section v-else-if="total > 0" class="stats">
      <div class="stat-card">
        <div class="stat-num">{{ total }}</div>
        <div class="stat-label">投递总数</div>
      </div>
      <div v-for="s in statusOrder" :key="s.key" class="stat-card">
        <div class="stat-num">{{ counts[s.key] || 0 }}</div>
        <div class="stat-label">{{ s.label }}</div>
      </div>
    </section>

    <!-- 转化漏斗 + 待跟进 -->
    <section v-if="!loading && total > 0" class="card">
      <div class="funnel">
        <span class="funnel-item">已投 <b>{{ funnel.submitted || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item">有回复 <b>{{ funnel.repliedOrBeyond || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item">面试 <b>{{ funnel.interviewOrBeyond || 0 }}</b></span>
        <span class="funnel-arrow">→</span>
        <span class="funnel-item accent">Offer <b>{{ funnel.offer || 0 }}</b></span>
      </div>
      <div v-if="followUps.length" class="followup">
        <div class="followup-title">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 9v4 M12 17h.01 M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"
              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          {{ followUps.length }} 条需要跟进（超过 7 天无回复，或已到跟进时间）
        </div>
        <div v-for="f in followUps" :key="f.id" class="followup-row">
          <span class="fu-title">{{ f.title }}</span>
          <span class="fu-company">{{ f.companyName }}</span>
          <span class="fu-status">{{ statusLabel(f.status) }}</span>
          <a v-if="f.applyUrl" :href="f.applyUrl" target="_blank" rel="noopener" class="fu-link">去催一下 →</a>
        </div>
      </div>
    </section>

    <!-- 投递 ↔ 面试时序（v1.61.0，竞品清单 #4）：把「投了什么」和「什么时候面」串成一条线 -->
    <section v-if="!loading && timeline && timeline.nodes.length" class="card" data-timeline>
      <div class="channel-head">
        <h2 class="channel-title">投递 → 面试时序</h2>
        <span class="channel-hint">按时间先后串起每一次投递与每一场面试</span>
      </div>
      <p class="tl-summary">{{ summaryText(timeline.stats) }}</p>
      <div class="tl-legend">
        <span>共 {{ timeline.stats.appliedCount }} 次投递</span>
        <span>·</span>
        <span>{{ timeline.stats.interviewCount }} 场面试</span>
        <template v-if="timeline.stats.unlinkedInterviewCount > 0">
          <span>·</span>
          <span class="tl-unlinked">{{ timeline.stats.unlinkedInterviewCount }} 场未关联投递</span>
        </template>
      </div>

      <!-- 状态滞后提示（v1.65.0）：已安排面试，但投递状态还停在面试之前。
           ⚠️ 只**提示 + 给一键操作**，绝不擅自替用户改状态 —— 状态是用户的数据。
           不给提示的后果：看板漏斗里的「面试率」偏低，而用户通常不会回头手动改。 -->
      <div v-if="timeline.statusLagging?.length" class="tl-lagging">
        <p class="tl-lagging-head">
          有 <b>{{ timeline.statusLagging.length }}</b> 条投递已安排面试，状态还停在面试之前 ——
          不更新的话，上面的「面试率」会偏低
        </p>
        <ul class="tl-lagging-list">
          <li v-for="it in timeline.statusLagging" :key="it.applicationId" class="tl-lagging-item">
            <span class="tl-lagging-text">{{ laggingHintText(it) }}</span>
            <button
              type="button"
              class="mini-btn primary"
              :disabled="busyId === it.applicationId"
              @click="advanceToInterview(it)"
            >
              标记为面试中
            </button>
          </li>
        </ul>
      </div>

      <ol class="tl-list">
        <li v-for="(n, i) in timeline.nodes" :key="`${n.kind}-${n.eventId ?? n.applicationId}-${i}`" class="tl-node">
          <div class="tl-rail" :class="`tl-rail-${n.kind.toLowerCase()}`" aria-hidden="true"></div>
          <div class="tl-body">
            <div class="tl-head">
              <BaseTag :variant="n.kind === 'INTERVIEW' ? 'warning' : 'info'" size="sm">{{ kindLabel(n.kind) }}</BaseTag>
              <span class="tl-at num-display">{{ timelineAtText(n.at) }}</span>
              <span v-if="n.kind === 'APPLIED' && n.stageLabel" class="tl-stage">{{ n.stageLabel }}</span>
              <span v-if="n.kind === 'INTERVIEW'" class="tl-event-status">{{ eventStatusText(n.eventStatus) }}</span>
            </div>
            <div class="tl-title">{{ n.companyName ? `${n.companyName} · ` : '' }}{{ n.title || '—' }}</div>
            <div v-if="n.kind === 'INTERVIEW'" class="tl-meta">
              <!-- 未关联投递时如实说明，不硬塞一个公司名 -->
              <span v-if="n.applicationId == null" class="tl-nolink">未关联投递记录</span>
              <span v-else class="tl-gap">
                投出后 <b>{{ daysText(n.daysSinceApplied) }}</b>
              </span>
              <span v-if="n.location">· {{ n.location }}</span>
              <span v-if="n.interviewer">· {{ n.interviewer }}</span>
            </div>
            <div v-else-if="n.channel" class="tl-meta">
              <span>渠道：{{ n.channel }}</span>
              <a v-if="n.applyUrl" :href="n.applyUrl" target="_blank" rel="noopener" class="tl-link">前往投递 →</a>
            </div>
            <div v-if="n.note" class="tl-note">{{ n.note }}</div>
          </div>
        </li>
      </ol>
    </section>

    <!-- 渠道效果（竞品清单 #6）：哪个渠道回音率更高，精力该往哪放 -->
    <section v-if="!loading && channels.length" class="card" data-channel-stats>
      <div class="channel-head">
        <h2 class="channel-title">渠道效果</h2>
        <span class="channel-hint">按来源统计回复情况——回复率高的渠道，值得多投一些</span>
      </div>
      <div class="channel-scroll">
        <table class="channel-table">
          <thead>
            <tr>
              <th>渠道</th>
              <th>投递</th>
              <th>已投出</th>
              <th>有回复</th>
              <th>面试</th>
              <th>Offer</th>
              <th>回复率</th>
              <th>面试率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in channels" :key="c.channel">
              <td class="ch-name">{{ c.channel }}</td>
              <td>{{ c.total }}</td>
              <td>{{ c.submitted }}</td>
              <td>{{ c.repliedOrBeyond }}</td>
              <td>{{ c.interviewOrBeyond }}</td>
              <td>{{ c.offer }}</td>
              <!-- 后端在「已投出为 0」时返回 null（比率不可定义）→ 显示占位符而非 0% -->
              <td :class="{ 'ch-na': c.replyRate == null }">{{ formatRate(c.replyRate) }}</td>
              <td :class="{ 'ch-na': c.interviewRate == null }">{{ formatRate(c.interviewRate) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 空状态 -->
    <div v-if="!loading && total === 0" class="empty-state">
      <div class="empty-icon" aria-hidden="true">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
          <path d="M22 12h-6l-2 3h-4l-2-3H2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"
                stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="empty-title">还没有投递记录</div>
      <div class="empty-desc">在「招聘广场」或岗位收藏里点「加入投递计划」，就能在这里跟踪进度与回复</div>
      <BaseButton variant="gradient" @click="$router.push('/jobs')">前往招聘广场</BaseButton>
    </div>

    <!-- 投递列表 -->
    <section v-if="!loading && total > 0" class="list">
      <div v-for="a in items" :key="a.id" class="app-card">
        <div class="app-main">
          <div class="app-title-row">
            <span class="app-title">{{ a.title }}</span>
            <BaseTag :variant="statusVariant(a.status)" size="sm">{{ statusLabel(a.status) }}</BaseTag>
            <!-- 导入行无真实岗位（后端以负值哨兵 jobId 标记），不得渲染 /jobs/{id} 跳转，仅给文本标记 -->
            <BaseTag v-if="a.jobId < 0" variant="info" size="sm">自定义导入</BaseTag>
          </div>
          <div class="app-meta">
            <span>{{ a.companyName }}</span>
            <span v-if="a.location">{{ a.location }}</span>
            <span v-if="a.salary">{{ a.salary }}</span>
            <span v-if="a.deadline">截止 {{ a.deadline }}</span>
          </div>
          <!-- 地点是上游给的自由文本；多城市串（如「杭州/北京/深圳」）与远程岗会被组件自动隐藏 -->
          <MapOpenLink v-if="a.location" :keyword="a.location" />
          <div v-if="a.note" class="app-note">备注：{{ a.note }}</div>
        </div>

        <div class="app-actions">
          <a v-if="a.applyUrl" :href="a.applyUrl" target="_blank" rel="noopener" class="mini-btn">前往投递</a>
          <button
            v-if="a.status === 'PLANNED'"
            class="mini-btn primary"
            :disabled="busyId === a.id"
            @click="confirmApply(a)"
          >确认已投递</button>

          <select
            class="mini-select"
            :value="a.status"
            :disabled="busyId === a.id"
            @change="onStatusChange(a, $event)"
          >
            <option v-for="s in statusOrder" :key="s.key" :value="s.key">{{ s.label }}</option>
          </select>

          <button class="mini-btn" :disabled="busyId === a.id" @click="openTailor(a)">定制简历</button>
          <button class="mini-btn danger" :disabled="busyId === a.id" @click="remove(a)">移除</button>
        </div>

        <div v-if="a.tailoredResume" class="tailored-hint">
          已生成定制简历 ·
          <button class="link-btn" @click="showTailored(a)">查看</button>
        </div>
      </div>
    </section>

    <!-- 定制简历弹窗 -->
    <div v-if="tailorTarget" class="modal-mask" @click.self="tailorTarget = null">
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">定制简历 · {{ tailorTarget.title }}</div>
          <button class="modal-close" @click="tailorTarget = null" aria-label="关闭"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M18 6L6 18 M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
        </div>

        <template v-if="!tailorResult">
          <div class="modal-sub">粘贴你的简历要点，AI 会针对该岗位重写条目、对齐 JD 关键词，并列出你还缺什么。</div>
          <textarea v-model="tailorResume" class="input ta" rows="6" placeholder="例：熟悉 Java/Spring Boot，做过订单中心重构，用 Redis 做缓存，QPS 峰值 3000……"></textarea>
          <input v-model="tailorDetail" class="input" placeholder="补充岗位 JD（可选）" />
          <div class="modal-actions">
            <BaseButton variant="gradient" :disabled="tailoring || !tailorResume.trim()" @click="doTailor">
              {{ tailoring ? '正在生成…' : '生成定制简历要点' }}
            </BaseButton>
          </div>
        </template>

        <template v-else>
          <div v-if="tailorResult.matchedKeywords?.length" class="mini-block">
            <div class="label">已命中关键词</div>
            <div class="tags"><BaseTag v-for="k in tailorResult.matchedKeywords" :key="k" variant="success" size="sm">{{ k }}</BaseTag></div>
          </div>
          <div v-if="tailorResult.missingKeywords?.length" class="mini-block">
            <div class="label">待补关键词（不要写进简历，先补能力）</div>
            <div class="tags"><BaseTag v-for="k in tailorResult.missingKeywords" :key="k" variant="danger" size="sm">{{ k }}</BaseTag></div>
          </div>
          <div v-if="tailorResult.rewrittenBullets?.length" class="mini-block">
            <div class="label">条目重写</div>
            <div v-for="(b, i) in tailorResult.rewrittenBullets" :key="i" class="bullet">
              <div class="bullet-orig">原：{{ b.original }}</div>
              <div class="bullet-new">改：{{ b.rewritten }}</div>
              <div v-if="b.reason" class="bullet-reason">{{ b.reason }}</div>
            </div>
          </div>
          <div v-if="tailorResult.summary" class="mini-block">
            <div class="label">定位陈述（可放简历开头）</div>
            <div class="summary-box">{{ tailorResult.summary }}</div>
          </div>
          <div v-if="tailorResult.suggestions?.length" class="mini-block">
            <div class="label">优化建议</div>
            <ul class="plain-list"><li v-for="s in tailorResult.suggestions" :key="s">{{ s }}</li></ul>
          </div>
          <div class="modal-actions">
            <BaseButton variant="ghost" @click="tailorResult = null">重新生成</BaseButton>
          </div>
        </template>
      </div>
    </div>

    <!-- 粘贴通知识别弹窗（E）：纯本地解析，零网络；可编辑预览后才写入 -->
    <div v-if="noticeOpen" class="modal-mask" @click.self="noticeOpen = false">
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">粘贴投递通知 · 识别状态</div>
          <button class="modal-close" @click="noticeOpen = false" aria-label="关闭"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M18 6L6 18 M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
        </div>
        <div class="modal-sub">
          把邮件 / 站内信原文粘贴进来，本地识别「公司 / 岗位 / 阶段 / 时间」。
          <b>识别完全在本机进行，不联网、不上传、不调用 AI</b>；识别结果需你确认后才写入。
        </div>
        <textarea v-model="noticeText" class="input ta" rows="5"
          placeholder="例：【字节跳动】感谢您的投递，应聘的「Java 后端工程师」岗位已进入面试环节，面试时间 2026-09-05 14:00，地点：北京"></textarea>
        <div class="modal-actions">
          <BaseButton variant="gradient" :disabled="!noticeText.trim()" @click="runNoticeParse">识别</BaseButton>
        </div>

        <template v-if="noticeResult">
          <div v-if="!hasAnyField(noticeResult)" class="notice-unrecognized">
            未识别到可用信息——请手动补充下方字段，或改用「从表格导入」。系统不会替你编造任何公司 / 岗位 / 时间。
          </div>

          <div class="mini-block">
            <div class="label">识别结果（可编辑，确认后写入）</div>
            <div class="notice-grid">
              <label>公司<input v-model="noticeForm.company" class="input" placeholder="未识别" /></label>
              <label>岗位<input v-model="noticeForm.title" class="input" placeholder="未识别" /></label>
              <label>阶段
                <select v-model="noticeForm.stage" class="input">
                  <option value="">未识别</option>
                  <option v-for="s in statusOrder" :key="s.key" :value="s.key">{{ s.label }}</option>
                </select>
              </label>
              <label>面试时间<input v-model="noticeForm.interviewAt" type="datetime-local" class="input" /></label>
            </div>
            <div v-if="noticeResult.matched.length" class="notice-matched">
              <BaseTag v-for="m in noticeResult.matched" :key="m" variant="info" size="sm">{{ m }}</BaseTag>
            </div>
          </div>

          <div class="mini-block">
            <div class="label">这条通知对应哪条投递记录？</div>
            <select v-model="noticeTargetId" class="input">
              <option :value="null">请选择一条投递记录…</option>
              <option v-for="a in items" :key="a.id" :value="a.id">{{ a.companyName }} · {{ a.title }}</option>
            </select>
            <div v-if="noticeCandidateText" class="field-hint">建议匹配：{{ noticeCandidateText }}</div>
          </div>

          <div v-if="noticeExistingEvent" class="notice-exists">
            面试日历中已存在「同标题 + 同时刻」的日程，未重复创建（不静默去重，已在此明确提示）。
          </div>

          <div class="modal-actions">
            <BaseButton variant="ghost" @click="noticeOpen = false">取消</BaseButton>
            <BaseButton variant="gradient"
              :disabled="noticePushing || !noticeForm.stage || noticeTargetId == null"
              @click="confirmNotice">
              {{ noticePushing ? '写入中…' : '确认写入' }}
            </BaseButton>
          </div>
        </template>
      </div>
    </div>

    <!-- 从表格导入弹窗（F）：选择文件 → 列映射 → dryRun 预览 → 确认导入 -->
    <div v-if="importOpen" class="modal-mask" @click.self="importOpen = false">
      <div class="modal modal-wide">
        <div class="modal-head">
          <div class="modal-title">从表格导入投递记录</div>
          <button class="modal-close" @click="importOpen = false" aria-label="关闭"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M18 6L6 18 M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
        </div>
        <div class="modal-sub">
          支持 <b>.xlsx / .xls / .csv / .tsv</b>，单次最多 {{ MAX_IMPORT_ROWS }} 行。
          <b>导入只写入本地台账，不代替你投递</b>。流程：选择文件 → 核对列映射 → 预览 → 确认导入。
        </div>

        <input type="file" :accept="IMPORT_ACCEPT" class="input" @change="onImportFile" />

        <div v-if="importError" class="notice-unrecognized">{{ importError }}</div>
        <div v-else-if="importParsing" class="field-hint">正在解析文件…</div>

        <template v-if="importTable">
          <div class="mini-block">
            <div class="label">
              列映射（可修改）· 共 {{ importPreviewRows.length }} 行
              <template v-if="importTruncated">（超出上限，已截断为 {{ MAX_IMPORT_ROWS }} 行）</template>
            </div>
            <div class="map-grid">
              <div v-for="f in IMPORT_FIELDS" :key="f.field" class="map-item">
                <span class="map-name">{{ f.label }}<span v-if="f.required" class="req">*</span></span>
                <select class="input" :value="importMapping[f.field] ?? ''" @change="onMappingChange(f.field, $event)">
                  <option value="">不导入</option>
                  <option v-for="(h, i) in importTable.headers" :key="i" :value="i">{{ h || ('第 ' + (i + 1) + ' 列') }}</option>
                </select>
              </div>
            </div>
          </div>

          <div class="modal-actions">
            <BaseButton variant="ghost" :disabled="importPreviewing || !importPreviewRows.length" @click="runImportDryRun">
              {{ importPreviewing ? '预览中…' : '预览（不写库）' }}
            </BaseButton>
          </div>

          <div v-if="importDry" class="mini-block">
            <div class="label">预览结果（dryRun，未写库）</div>
            <div class="preview-counts">
              <span class="pc pc-add">新增 {{ importDry.summary.added }}</span>
              <span class="pc pc-merge">合并 {{ importDry.summary.merged }}</span>
              <span class="pc pc-skip">跳过 {{ importDry.summary.skipped }}</span>
              <span class="pc pc-warn">警告 {{ importDry.summary.errors }}</span>
            </div>
            <div v-if="!importDry.canApply" class="notice-unrecognized">
              存在致命错误行（缺少公司名称 / 岗位名称），已阻止导入，数据零变化。请修正单元格后重新选择文件。
            </div>
            <div class="plan-table">
              <div v-for="r in importDry.rows" :key="r.index" class="plan-row">
                <BaseTag :variant="actionVariant(r.action)" size="sm">{{ actionLabel(r.action) }}</BaseTag>
                <span class="plan-name">{{ r.normalized.companyName || '（缺公司）' }} · {{ r.normalized.title || '（缺岗位）' }}</span>
                <span class="plan-reason">{{ r.reason }}</span>
              </div>
            </div>
          </div>

          <div class="modal-actions">
            <BaseButton variant="ghost" @click="importOpen = false">取消</BaseButton>
            <BaseButton variant="gradient" :disabled="!importDry || !importDry.canApply || importApplying" @click="applyImport">
              {{ importApplying ? '导入中…' : '确认导入' }}
            </BaseButton>
          </div>
        </template>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag, MapOpenLink } from '../components'
import { EMPTY } from '../utils/format'
import { parseNotice, hasAnyField, type NoticeParse } from '../utils/noticeParse'
import { isConflictError } from '../utils/httpError'
import { formatRate, type ChannelStat } from '../utils/channelStats'
import {
  daysText,
  eventStatusText,
  kindLabel,
  laggingHintText,
  summaryText,
  timelineAtText,
  type StatusLaggingItem,
  type TimelineData,
} from '../utils/timeline'
import {
  IMPORT_ACCEPT,
  IMPORT_FIELDS,
  MAX_IMPORT_ROWS,
  XLS_SAVE_AS_CSV_HINT,
  autoMapColumns,
  buildImportRows,
  detectTable,
  fileKind,
  parseTextTable,
  sourceLabel,
  type ColumnMapping,
  type ImportField,
  type ImportRow,
  type ParsedTable,
  type SpreadsheetKind,
} from '../utils/tableImport'
import { XLSX_READ_ERROR_HINT, parseXlsxRows } from '../utils/xlsxLazy'

interface Application {
  id: number
  jobId: number
  title: string
  companyName: string
  location?: string | null
  salary?: string | null
  deadline?: string | null
  applyUrl?: string | null
  status: string
  note?: string | null
  tailoredResume?: string | null
  appliedAt?: string | null
  lastReplyAt?: string | null
  updatedAt?: string | null
}

interface TailoredResult {
  matchedKeywords?: string[]
  missingKeywords?: string[]
  rewrittenBullets?: { original?: string; rewritten?: string; reason?: string }[]
  summary?: string
  suggestions?: string[]
  raw?: string
}

const statusOrder = [
  { key: 'PLANNED', label: '待投递' },
  { key: 'APPLIED', label: '已投递' },
  { key: 'VIEWED', label: '已查看' },
  { key: 'REPLIED', label: '有回复' },
  { key: 'INTERVIEW', label: '面试中' },
  { key: 'OFFER', label: '已拿 Offer' },
  { key: 'REJECTED', label: '已淘汰' },
  { key: 'WITHDRAWN', label: '已放弃' },
]

const loading = ref(true)
const items = ref<Application[]>([])
const counts = ref<Record<string, number>>({})
const funnel = ref<Record<string, number>>({})
const channels = ref<ChannelStat[]>([])
const followUps = ref<Application[]>([])
const busyId = ref<number | null>(null)
/** 投递 ↔ 面试时序（v1.61.0）。为 null 表示还没加载到或不适用 —— 不渲染空壳区块 */
const timeline = ref<TimelineData | null>(null)

const tailorTarget = ref<Application | null>(null)
const tailorResume = ref('')
const tailorDetail = ref('')
const tailoring = ref(false)
const tailorResult = ref<TailoredResult | null>(null)

const total = computed(() => items.value.length)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const listData = (await api.get('/api/application/list')) as unknown as { items?: Application[] }
    items.value = listData?.items || []
    const board = (await api.get('/api/application/board')) as unknown as {
      counts?: Record<string, number>
      funnel?: Record<string, number>
      byChannel?: ChannelStat[]
      followUps?: Application[]
    }
    counts.value = board?.counts || {}
    funnel.value = board?.funnel || {}
    channels.value = board?.byChannel || []
    followUps.value = board?.followUps || []
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '加载投递台账失败'))
  } finally {
    loading.value = false
  }
  // 时序数据单独加载：它是**附加视图**，取不到就不显示该区块，
  // 不能让它的失败把已加载好的台账与看板一并清空。
  await loadTimeline()
}

async function loadTimeline() {
  try {
    const data = (await api.get('/api/application/timeline')) as unknown as TimelineData
    timeline.value = data && Array.isArray(data.nodes) ? data : null
  } catch {
    // 明确降级：不弹错误提示（台账本身可用），仅隐藏时序区块。
    // 若这里改成「保持上一次的数据」，用户会看到过期时间线却以为是最新的。
    timeline.value = null
  }
}

/** 确认已投递：PLANNED → APPLIED（语义为用户已自行完成投递） */
async function confirmApply(a: Application) {
  busyId.value = a.id
  try {
    await api.post(`/api/application/${a.id}/confirm`)
    ElMessage.success('已标记为已投递')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '操作失败'))
  } finally {
    busyId.value = null
  }
}

/** 推进状态（回复监测） */
async function onStatusChange(a: Application, ev: Event) {
  const target = (ev.target as HTMLSelectElement).value
  if (target === a.status) return
  busyId.value = a.id
  try {
    await api.post(`/api/application/${a.id}/status`, { status: target })
    ElMessage.success(`已更新为「${statusLabel(target)}」`)
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '更新状态失败'))
    ;(ev.target as HTMLSelectElement).value = a.status
  } finally {
    busyId.value = null
  }
}

/**
 * 一键把「状态滞后」的投递推进到「面试中」（v1.65.0）。
 *
 * <p>⚠️ 必须由用户**主动点击**触发：状态是用户的数据，系统只提示、不代改。
 * 后端也只列出滞后项、不自动推进，两边口径一致。
 */
async function advanceToInterview(it: StatusLaggingItem) {
  busyId.value = it.applicationId
  try {
    await api.post(`/api/application/${it.applicationId}/status`, { status: 'INTERVIEW' })
    ElMessage.success(`已更新为「${statusLabel('INTERVIEW')}」`)
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '更新状态失败'))
  } finally {
    busyId.value = null
  }
}

async function remove(a: Application) {
  busyId.value = a.id
  try {
    await api.delete(`/api/application/${a.id}`)
    items.value = items.value.filter((x) => x.id !== a.id)
    ElMessage.success('已移除')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '移除失败'))
  } finally {
    busyId.value = null
  }
}

function openTailor(a: Application) {
  tailorTarget.value = a
  tailorResult.value = null
  tailorResume.value = ''
  tailorDetail.value = ''
}

function showTailored(a: Application) {
  tailorTarget.value = a
  tailorResume.value = ''
  tailorDetail.value = ''
  try {
    tailorResult.value = a.tailoredResume ? (JSON.parse(a.tailoredResume) as TailoredResult) : null
  } catch {
    tailorResult.value = { raw: a.tailoredResume || '' }
  }
}

async function doTailor() {
  const target = tailorTarget.value
  if (!target) return
  const r = tailorResume.value.trim()
  if (!r) return ElMessage.warning('请先粘贴简历要点')
  tailoring.value = true
  try {
    const res = (await api.post(`/api/application/${target.id}/tailor`, {
      resumeText: r,
      jobDetail: tailorDetail.value.trim(),
    })) as unknown as { tailoredResume?: TailoredResult }
    tailorResult.value = res?.tailoredResume || null
    ElMessage.success('定制简历已生成')
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '生成失败，请稍后重试'))
  } finally {
    tailoring.value = false
  }
}

function statusLabel(key?: string) {
  return statusOrder.find((s) => s.key === key)?.label || key || EMPTY
}

function statusVariant(key?: string) {
  switch (key) {
    case 'OFFER': return 'success'
    case 'INTERVIEW': return 'warning'
    case 'REPLIED': return 'info'
    case 'REJECTED': return 'danger'
    case 'WITHDRAWN': return 'info'
    default: return 'info'
  }
}

// ───────────────────────── 投递通知识别（E） ─────────────────────────
// 解析全部在本地完成（noticeParse.ts 零网络），写入复用既有 /status 与 /calendar/event 接口。

const noticeOpen = ref(false)
const noticeText = ref('')
const noticeResult = ref<NoticeParse | null>(null)
const noticeTargetId = ref<number | null>(null)
const noticePushing = ref(false)
const noticeExistingEvent = ref(false)
const noticeForm = reactive({ company: '', title: '', stage: '', interviewAt: '' })

/** 可能匹配的投递记录（按公司/岗位模糊提示，绝不自动选定） */
const noticeCandidates = computed(() => {
  const c = noticeForm.company.trim().toLowerCase()
  const t = noticeForm.title.trim().toLowerCase()
  if (!c && !t) return []
  return items.value.filter((a) => {
    const at = (a.title || '').toLowerCase()
    const ac = (a.companyName || '').toLowerCase()
    return (t !== '' && at.includes(t)) || (c !== '' && ac.includes(c))
  })
})
const noticeCandidateText = computed(() =>
  noticeCandidates.value.map((a) => `${a.companyName} · ${a.title}`).join('；'),
)

function openNotice() {
  noticeOpen.value = true
  noticeText.value = ''
  noticeResult.value = null
  noticeTargetId.value = null
  noticeExistingEvent.value = false
  noticeForm.company = ''
  noticeForm.title = ''
  noticeForm.stage = ''
  noticeForm.interviewAt = ''
}

/** 纯本地识别：调用期间 Network 面板不应出现任何 XHR */
function runNoticeParse() {
  const r = parseNotice(noticeText.value)
  noticeResult.value = r
  noticeExistingEvent.value = false
  noticeForm.company = r.company ?? ''
  noticeForm.title = r.title ?? ''
  noticeForm.stage = r.stage ?? ''
  noticeForm.interviewAt = r.interviewAt ?? ''
  if (!hasAnyField(r)) {
    ElMessage.info('未识别到可用信息，请手动补充或改用「从表格导入」')
  }
}

/** ISO 秒级（后端 LocalDateTime.parse 需要 `YYYY-MM-DDTHH:mm:ss`） */
function toIsoSeconds(dtLocal: string): string {
  return dtLocal.length === 16 ? `${dtLocal}:00` : dtLocal
}

/**
 * 写入面试日历。
 *
 * <p>去重由**后端**负责（`InterviewEventService.create`：同标题 + 同时刻 → 409）。
 * 唯一能避免竞态的地方就是写库前的那一次判定，因此前端不再自己「先 GET 列表再本地比对」——
 * 那种写法有竞态窗口，且两处创建入口（本文件与日历页）要各维护一份判定（同一口径散落多处）。
 * 这里只消费结果：**409 = 已存在**，明确提示而非静默去重、也不当失败报错。
 */
async function pushCalendarEvent(targetId: number) {
  const app = items.value.find((a) => a.id === targetId)
  const company = app?.companyName || noticeForm.company || '面试'
  const role = app?.title || noticeForm.title || '面试'
  const title = `${company} · ${role} · 面试`
  const at = toIsoSeconds(noticeForm.interviewAt)
  try {
    // v1.61.0：带上 applicationId 建立「投递 ↔ 面试」关联。
    // 用户此刻正是在「把这条通知挂到某条投递记录上」，关联是已知事实而非猜测
    // → 时序视图因此能算出「投出后几天面的」，无需任何启发式匹配。
    await api.post('/api/calendar/event', {
      title, interviewAt: at, note: '来自投递通知识别', applicationId: targetId,
    })
    ElMessage.success('已加入面试日历')
  } catch (e: unknown) {
    if (isConflictError(e)) {
      noticeExistingEvent.value = true
      ElMessage.warning('日历中已存在「同标题 + 同时刻」的日程，未重复创建')
      return
    }
    throw e
  }
}

async function confirmNotice() {
  const targetId = noticeTargetId.value
  if (targetId == null) return ElMessage.warning('请选择这条通知对应的投递记录')
  if (!noticeForm.stage) return ElMessage.warning('未识别到投递阶段，无法更新状态')
  noticePushing.value = true
  try {
    await api.post(`/api/application/${targetId}/status`, { status: noticeForm.stage })
    ElMessage.success(`已更新为「${statusLabel(noticeForm.stage)}」`)
    if (noticeForm.interviewAt) await pushCalendarEvent(targetId)
    noticeOpen.value = false
    await load()
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '写入失败'))
  } finally {
    noticePushing.value = false
  }
}

// ───────────────────────── 表格导入（F） ─────────────────────────

interface ImportPlanRow {
  index: number
  action: 'ADD' | 'MERGE' | 'SKIP' | 'ERROR'
  reason: string
  normalized: ImportRow
}
interface ImportResult {
  source: string
  dryRun: boolean
  canApply: boolean
  summary: { added: number; merged: number; skipped: number; errors: number }
  rows: ImportPlanRow[]
  fatalErrors: Array<{ index: number; message: string }>
  applied?: boolean
  message?: string
}

const importOpen = ref(false)
const importFileName = ref('')
const importKind = ref<SpreadsheetKind>('unknown')
const importTable = ref<ParsedTable | null>(null)
const importMapping = reactive<ColumnMapping>({})
const importPreviewRows = ref<ImportRow[]>([])
const importTruncated = ref(false)
const importParsing = ref(false)
const importPreviewing = ref(false)
const importApplying = ref(false)
const importDry = ref<ImportResult | null>(null)
const importError = ref('')

function resetImportMapping() {
  for (const k of Object.keys(importMapping)) delete (importMapping as Record<string, unknown>)[k]
}

function openImport() {
  importOpen.value = true
  importFileName.value = ''
  importKind.value = 'unknown'
  importTable.value = null
  importPreviewRows.value = []
  importTruncated.value = false
  importDry.value = null
  importError.value = ''
  resetImportMapping()
}

function applyMapping(mapping: ColumnMapping) {
  resetImportMapping()
  Object.assign(importMapping, mapping)
  rebuildImportPreview()
}

function rebuildImportPreview() {
  const table = importTable.value
  if (!table) {
    importPreviewRows.value = []
    return
  }
  const { rows, truncated } = buildImportRows(table, importMapping)
  importPreviewRows.value = rows
  importTruncated.value = truncated
  importDry.value = null
}

function onMappingChange(field: ImportField, ev: Event) {
  const raw = (ev.target as HTMLSelectElement).value
  if (raw === '') delete (importMapping as Record<string, unknown>)[field]
  else importMapping[field] = Number(raw)
  rebuildImportPreview()
}

async function onImportFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importFileName.value = file.name
  importKind.value = fileKind(file.name)
  importError.value = ''
  importDry.value = null
  importTable.value = null
  importPreviewRows.value = []
  importParsing.value = true
  try {
    let table: ParsedTable
    if (importKind.value === 'xlsx' || importKind.value === 'xls') {
      try {
        table = detectTable(await parseXlsxRows(file))
      } catch {
        // .xls 走 best-effort 解析失败 → 显式引导「另存为 CSV」
        importError.value = importKind.value === 'xls' ? XLS_SAVE_AS_CSV_HINT : XLSX_READ_ERROR_HINT
        return
      }
    } else if (importKind.value === 'csv' || importKind.value === 'tsv') {
      table = parseTextTable(await file.text(), file.name)
    } else {
      importError.value = '仅支持 .xlsx / .xls / .csv / .tsv 文件'
      return
    }
    if (!table.rows.length) {
      importError.value = '文件没有可导入的内容'
      return
    }
    importTable.value = table
    applyMapping(autoMapColumns(table.headers))
  } catch (e: unknown) {
    importError.value = getErrMessage(e, '文件解析失败')
  } finally {
    importParsing.value = false
    input.value = ''
  }
}

async function runImportDryRun() {
  if (!importPreviewRows.value.length) return ElMessage.warning('没有可导入的行')
  if (importMapping.companyName === undefined || importMapping.title === undefined) {
    return ElMessage.warning('请先映射「公司名称」与「岗位名称」两列')
  }
  importPreviewing.value = true
  importError.value = ''
  try {
    const res = (await api.post('/api/application/import', {
      source: sourceLabel(importKind.value),
      rows: importPreviewRows.value,
      dryRun: true,
    })) as unknown as ImportResult
    importDry.value = res
    if (!res?.canApply) ElMessage.warning('存在致命错误行，无法导入，请修正后重试')
  } catch (e: unknown) {
    importError.value = getErrMessage(e, '预览失败')
  } finally {
    importPreviewing.value = false
  }
}

async function applyImport() {
  const dry = importDry.value
  if (!dry || !dry.canApply) return
  importApplying.value = true
  try {
    const res = (await api.post('/api/application/import', {
      source: sourceLabel(importKind.value),
      rows: importPreviewRows.value,
      dryRun: false,
    })) as unknown as ImportResult
    if (res?.applied) {
      const s = res.summary || { added: 0, merged: 0, skipped: 0, errors: 0 }
      ElMessage.success(`导入完成：新增 ${s.added}，合并 ${s.merged}，跳过 ${s.skipped}`)
      importOpen.value = false
      await load()
    } else {
      ElMessage.error(res?.message || '导入未完成')
    }
  } catch (e: unknown) {
    ElMessage.error(getErrMessage(e, '导入失败'))
  } finally {
    importApplying.value = false
  }
}

function actionLabel(action: string): string {
  switch (action) {
    case 'ADD': return '新增'
    case 'MERGE': return '合并'
    case 'SKIP': return '跳过'
    case 'ERROR': return '警告'
    default: return action || EMPTY
  }
}

function actionVariant(action: string) {
  switch (action) {
    case 'ADD': return 'success'
    case 'MERGE': return 'info'
    case 'SKIP': return 'info'
    case 'ERROR': return 'danger'
    default: return 'info'
  }
}
</script>

<style scoped>
.app-page { max-width: 960px; margin: 0 auto; }
.page-header { margin-bottom: 24px; }
.page-header h1 { font-size: 28px; font-weight: 700; color: var(--c-text); margin: 0 0 6px; letter-spacing: -0.5px; }
.page-header p { font-size: 14px; color: var(--c-text-secondary); margin: 0; line-height: 1.7; }

.card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 18px 20px; margin-bottom: 16px; }

.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(104px, 1fr)); gap: 10px; margin-bottom: 16px; }
.stat-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 14px 12px; text-align: center; }
.stat-num { font-family: var(--font-mono); font-size: 24px; font-weight: 700; color: var(--brand-primary); line-height: 1.2; }
.stat-label { font-size: 12px; color: var(--c-text-tertiary); margin-top: 4px; }

.funnel { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; color: var(--c-text-secondary); }
.funnel-item b { color: var(--c-text); font-size: 15px; }
.funnel-item.accent b { color: var(--c-accent); }
.funnel-arrow { color: var(--c-text-quaternary); }

.followup { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--c-border); }
.followup-title { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--c-warning); margin-bottom: 8px; }
.followup-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 6px 0; font-size: 13px; }
.fu-title { font-weight: 500; color: var(--c-text); }
.fu-company { color: var(--c-text-tertiary); }

/* 渠道效果表：窄屏用容器内横向滚动，避免整页横向溢出 */
.channel-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.channel-title { font-size: 15px; font-weight: 600; color: var(--c-text); margin: 0; }
.channel-hint { font-size: 12px; color: var(--c-text-tertiary); }
.channel-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.channel-table { width: 100%; min-width: 460px; border-collapse: collapse; font-size: 13px; }
.channel-table th {
  text-align: right; font-weight: 500; font-size: 12px; color: var(--c-text-tertiary);
  padding: 6px 10px; border-bottom: 1px solid var(--c-border-light); white-space: nowrap;
}
.channel-table th:first-child, .channel-table td:first-child { text-align: left; }
.channel-table td {
  text-align: right; padding: 8px 10px; color: var(--c-text-secondary);
  border-bottom: 1px solid var(--c-border-light); font-family: var(--font-mono);
}
.channel-table tr:last-child td { border-bottom: none; }
.channel-table .ch-name { font-family: inherit; color: var(--c-text); font-weight: 500; }
.channel-table .ch-na { color: var(--c-text-quaternary); }

/* ── 投递 → 面试时序（v1.61.0） ── */
.tl-summary { font-size: 13px; color: var(--c-text-secondary); line-height: 1.7; margin: 0 0 8px; }
.tl-legend { display: flex; gap: 6px; flex-wrap: wrap; font-size: 12px; color: var(--c-text-tertiary); margin-bottom: 14px; }
.tl-unlinked { color: var(--c-warning); }

/* 状态滞后提示（v1.65.0）：用琥珀色 —— 语义是「需要注意」而非「出错了」，
 * 用危险色会让人以为数据坏了。整块走语义令牌，暗色下自动跟随。 */
.tl-lagging {
  margin-bottom: 16px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background: var(--c-warning-light);
  border: 1px solid var(--c-warning-border);
}
.tl-lagging-head { margin: 0 0 10px; font-size: 13px; line-height: 1.6; color: var(--c-text-secondary); }
.tl-lagging-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.tl-lagging-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.tl-lagging-text { font-size: 13px; line-height: 1.6; color: var(--c-text); }

.tl-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0; }
.tl-node { display: flex; gap: 12px; position: relative; padding-bottom: 14px; }
.tl-node:last-child { padding-bottom: 0; }
.tl-node:last-child .tl-rail::after { display: none; }
.tl-rail { position: relative; flex-shrink: 0; width: 10px; display: flex; justify-content: center; }
.tl-rail::before {
  content: ''; width: 10px; height: 10px; border-radius: 50%;
  margin-top: 5px; z-index: 1; flex-shrink: 0;
}
.tl-rail::after {
  content: ''; position: absolute; top: 15px; bottom: -14px; width: 2px;
  background: var(--c-border-light);
}
/* 用形状 + 颜色双重区分：投递=实心方绿，面试=实心圆琥珀。
   只靠颜色区分对色觉障碍用户不可用。 */
.tl-rail-applied::before { background: var(--c-success); border-radius: 2px; }
.tl-rail-interview::before { background: var(--c-warning); }
.tl-rail-status::before { background: var(--c-info); }

.tl-body { flex: 1; min-width: 0; }
.tl-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tl-at { font-size: 13px; color: var(--c-text); font-weight: 600; }
.tl-stage, .tl-event-status { font-size: 12px; color: var(--c-text-tertiary); }
.tl-title { font-family: var(--font-title); font-size: 14px; font-weight: 600; color: var(--c-text); margin-top: 4px; }
.tl-meta { display: flex; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--c-text-tertiary); margin-top: 4px; }
.tl-gap b { color: var(--brand-primary); font-family: var(--font-mono); }
.tl-nolink { color: var(--c-text-quaternary); }
.tl-link { color: var(--brand-primary); text-decoration: none; font-size: 12px; }
.tl-link:hover { text-decoration: underline; }
.tl-note { font-size: 12px; color: var(--c-text-secondary); margin-top: 4px; white-space: pre-wrap; }
.fu-status { font-size: 12px; color: var(--c-warning); background: var(--c-warning-light); border-radius: var(--radius-sm); padding: 1px 6px; }
.fu-link { color: var(--brand-primary); text-decoration: none; font-size: 12px; }
.fu-link:hover { text-decoration: underline; }

.list { display: flex; flex-direction: column; gap: 12px; }
.app-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); padding: 16px 18px; }
.app-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.app-title { font-family: var(--font-title); font-size: 16px; font-weight: 600; color: var(--c-text); }
.app-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 13px; color: var(--c-text-tertiary); }
.app-note { margin-top: 8px; font-size: 13px; color: var(--c-text-secondary); line-height: 1.6; }
.app-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--c-border-light); }

.mini-btn { padding: 6px 12px; font-size: 13px; border: 1px solid var(--c-border-strong); background: var(--c-surface); color: var(--c-text-secondary); border-radius: var(--radius-md); cursor: pointer; text-decoration: none; transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast); }
.mini-btn:hover:not(:disabled) { border-color: var(--brand-primary); color: var(--brand-primary); }
.mini-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.mini-btn.primary { background: var(--brand-primary); border-color: var(--brand-primary); color: var(--c-on-primary); }
.mini-btn.primary:hover:not(:disabled) { background: var(--brand-primary-hover); color: var(--c-on-primary); }
.mini-btn.danger:hover:not(:disabled) { border-color: var(--c-danger); color: var(--c-danger); }
.mini-select { padding: 6px 8px; font-size: 13px; border: 1px solid var(--c-border-strong); border-radius: var(--radius-md); background: var(--c-surface); color: var(--c-text-secondary); cursor: pointer; }

/* ── 触控目标 ≥44px（v1.63.2）──
 * 移动端 `.mini-btn` / `.mini-select` 只有 ~29px 高，手指容易点错。
 * 与 CalendarView 同理：放大实际尺寸而非伪元素扩热区（这些控件在卡片操作行里是相邻的）。 */
@media (max-width: 768px) {
  .mini-btn,
  .mini-select { min-height: 44px; padding: 11px 14px; }
}

.tailored-hint { margin-top: 10px; font-size: 12px; color: var(--c-text-tertiary); }
.link-btn { background: transparent; border: none; color: var(--brand-primary); font-size: 12px; cursor: pointer; padding: 0; }
.link-btn:hover { text-decoration: underline; }

.empty-state { text-align: center; padding: 64px 24px; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); }
.empty-icon { display: inline-flex; color: var(--brand-primary); opacity: 0.55; margin-bottom: 16px; }
.empty-title { font-size: 18px; font-weight: 600; color: var(--c-text); margin-bottom: 6px; }
.empty-desc { font-size: 14px; color: var(--c-text-tertiary); margin-bottom: 24px; }

.skeleton { background: linear-gradient(90deg, var(--c-bg-alt) 25%, var(--c-border-light) 37%, var(--c-bg-alt) 63%); background-size: 400% 100%; animation: skeleton-loading 1.4s ease infinite; border-radius: var(--radius-sm); }
.skeleton-line { height: 14px; margin-bottom: 8px; }
.skeleton-line.w-60 { width: 60%; }
.skeleton-line.w-40 { width: 40%; }
@keyframes skeleton-loading { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

.modal-mask { position: fixed; inset: 0; background: rgba(28, 25, 23, 0.45); display: flex; align-items: center; justify-content: center; padding: 24px; z-index: 2000; }
.modal { background: var(--c-surface); border-radius: var(--radius-lg); box-shadow: var(--shadow-lg); width: 100%; max-width: 680px; max-height: 86vh; overflow-y: auto; padding: 20px 22px; }
.modal-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.modal-title { font-family: var(--font-title); font-size: 17px; font-weight: 700; color: var(--c-text); }
.modal-close { display: inline-flex; align-items: center; justify-content: center; background: transparent; border: none; color: var(--c-text-tertiary); cursor: pointer; }
.modal-sub { font-size: 13px; color: var(--c-text-tertiary); line-height: 1.6; margin-bottom: 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; }

.input { width: 100%; padding: 9px 12px; font-size: 14px; color: var(--c-text); background: var(--c-bg); border: 1px solid var(--c-border-light); border-radius: var(--radius-md); box-sizing: border-box; margin-bottom: 10px; }
.input:focus { outline: none; border-color: var(--brand-primary); }
.input.ta { resize: vertical; min-height: 72px; font-family: inherit; line-height: 1.7; }

.label { font-size: 12px; font-weight: 600; color: var(--c-text-tertiary); margin-bottom: 8px; }
.mini-block { margin-bottom: 16px; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.plain-list { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; color: var(--c-text-secondary); }
.bullet { border-left: 3px solid var(--brand-primary-200); padding: 6px 0 6px 10px; margin-bottom: 10px; font-size: 13px; line-height: 1.7; }
.bullet-orig { color: var(--c-text-tertiary); text-decoration: line-through; }
.bullet-new { color: var(--c-text); font-weight: 500; }
.bullet-reason { color: var(--c-accent); font-size: 12px; margin-top: 4px; }
.summary-box { background: var(--brand-primary-light); border-radius: var(--radius-md); padding: 12px 14px; font-size: 13px; line-height: 1.8; color: var(--c-text-secondary); }

/* ── 页面操作区（E/F 入口） ── */
.page-actions { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 14px; }

/* ── 粘贴识别弹窗（E） ── */
.notice-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; }
.notice-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--c-text-secondary); }
.notice-grid .input { margin-bottom: 0; }
.notice-matched { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.notice-unrecognized {
  background: var(--c-warning-light);
  border: 1px solid var(--c-warning);
  color: var(--c-warning);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 12px;
}
.notice-exists {
  background: var(--c-info-light);
  border: 1px solid var(--c-info);
  color: var(--c-info);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 12px;
}
.field-hint { font-size: 12px; color: var(--c-text-tertiary); margin-top: 6px; line-height: 1.6; }

/* ── 表格导入弹窗（F） ── */
.modal-wide { max-width: 860px; }
.map-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.map-item { display: flex; flex-direction: column; gap: 4px; }
.map-name { font-size: 12px; color: var(--c-text-secondary); }
.map-item .input { margin-bottom: 0; }
.req { color: var(--c-danger); margin-left: 2px; }
.preview-counts { display: flex; flex-wrap: wrap; gap: 14px; font-size: 13px; font-weight: 600; margin-bottom: 10px; }
.pc-add { color: var(--c-success); }
.pc-merge { color: var(--c-info); }
.pc-skip { color: var(--c-text-tertiary); }
.pc-warn { color: var(--c-danger); }
.plan-table { border: 1px solid var(--c-border-light); border-radius: var(--radius-md); max-height: 280px; overflow-y: auto; }
.plan-row { display: flex; align-items: center; gap: 10px; padding: 7px 12px; border-bottom: 1px solid var(--c-border-light); font-size: 13px; }
.plan-row:last-child { border-bottom: none; }
.plan-name { color: var(--c-text); font-weight: 500; }
.plan-reason { color: var(--c-text-tertiary); font-size: 12px; margin-left: auto; text-align: right; }
</style>

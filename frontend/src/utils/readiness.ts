/**
 * 准备度检测（第三批 G）纯函数层。
 *
 * <p><b>零 AI</b>：全部输入来自已有接口快照 + 用户自述项，本模块只做规则判定，
 * 与「求职诊断」（AI 能力画像）互补——准备度衡量的是**客观完成度**。
 *
 * <p><b>6 维度 14 项</b>：简历 / 岗位目标 / 投递 / 面试训练 / 知识储备 / 材料准备。
 * 等级规则：`≥11 → A`、`6–10 → B`、`≤5 → C`。
 *
 * <p><b>R3「无数据 ≠ 0」最关键的一处</b>：用户尚未开始准备（14 项一项未达成）时
 * **不给出任何等级**（{@link ReadinessResult.grade} 为 `null`），也不产出「0/14」这类
 * 看起来像真实评估结果的计数——由调用方渲染中性空态。
 */

import { EMPTY } from './format'

/** 准备度等级 */
export type ReadinessGrade = 'A' | 'B' | 'C'

/** 六个维度 */
export type ReadinessDimension = '简历' | '岗位目标' | '投递' | '面试训练' | '知识储备' | '材料准备'

/** 用户自述项（无接口可自动获取，由用户勾选，持久化在本地） */
export interface ReadinessManual {
  /** 完成 1 次 JD 拆解 */
  jdAnalysis: boolean
  /** 准备 1 分钟自我介绍 */
  selfIntro: boolean
  /** 准备求职信 */
  coverLetter: boolean
}

/** 自述项的存储键（单一来源） */
export const READINESS_MANUAL_KEY = 'readiness:manual'

/** 准备度输入快照（`null` = 该项数据源不可用或无从得知） */
export interface ReadinessInput {
  resumeCount: number | null
  resumeScore: number | null
  favoriteCount: number | null
  applicationCount: number | null
  appliedCount: number | null
  interviewInviteCount: number | null
  finishedSessionCount: number | null
  interviewAvgScore: number | null
  /** 已作答题目数（question-summary 的 answered 合计） */
  answeredQuestionCount: number | null
  /** 题库总题量（question-summary 的 total 合计） */
  questionTotal: number | null
  storyCount: number | null
  manual: ReadinessManual
}

/** 单项清单 */
export interface ReadinessItem {
  id: string
  dimension: ReadinessDimension
  label: string
  achieved: boolean
  /** 依据来源说明（达成与否都展示，如「当前 78 分」） */
  evidence: string
  /** 自述项：存在值时组件渲染可点击勾选 */
  manualKey?: keyof ReadinessManual
}

/** 准备度结果 */
export interface ReadinessResult {
  /** A(≥11) / B(6–10) / C(≤5)；未开始（0 项）→ `null`（**不评级**） */
  grade: ReadinessGrade | null
  achievedCount: number
  total: number
  /** 是否已有任何达成项（false = 尚未开始准备） */
  hasData: boolean
  items: ReadinessItem[]
}

/** 清单总项数（6 维度 14 项） */
export const READINESS_TOTAL = 14

function num(v: number | null | undefined): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

function ge(v: number | null | undefined, threshold: number): boolean {
  const n = num(v)
  return n !== null && n >= threshold
}

/** 由达成项数判定等级；0 项 → null（不评级）。 */
export function gradeFromCount(achieved: number): ReadinessGrade | null {
  if (!Number.isFinite(achieved) || achieved <= 0) return null
  if (achieved >= 11) return 'A'
  if (achieved >= 6) return 'B'
  return 'C'
}

/** 等级文案；未开始（grade=null）返回空串（**绝不产出「C 级 0」**）。 */
export function gradeLabel(grade: ReadinessGrade | null): string {
  if (grade === 'A') return 'A 级 · 已充分准备'
  if (grade === 'B') return 'B 级 · 基本就绪'
  if (grade === 'C') return 'C 级 · 仍需补强'
  return ''
}

/** 进度文案；未开始返回空串（**绝不产出「0/14」**）。 */
export function progressText(result: ReadinessResult): string {
  if (!result.hasData) return ''
  const remain = result.total - result.achievedCount
  if (remain > 0) return `已完成 ${result.achievedCount} / ${result.total} 项，还差 ${remain} 项可以更稳。`
  return `已完成 ${result.achievedCount} / ${result.total} 项，准备度满分，保持状态！`
}

function buildItems(input: ReadinessInput): ReadinessItem[] {
  const resumeCount = num(input.resumeCount)
  const resumeScore = num(input.resumeScore)
  const favoriteCount = num(input.favoriteCount)
  const applicationCount = num(input.applicationCount)
  const appliedCount = num(input.appliedCount)
  const inviteCount = num(input.interviewInviteCount)
  const finishedSessionCount = num(input.finishedSessionCount)
  const interviewAvgScore = num(input.interviewAvgScore)
  const answeredCount = num(input.answeredQuestionCount)
  const questionTotal = num(input.questionTotal)
  const storyCount = num(input.storyCount)
  const manual: ReadinessManual =
    input.manual ?? { jdAnalysis: false, selfIntro: false, coverLetter: false }

  // 刷题覆盖率 = 已作答 / 题库总量（≤ 100%）。无题量数据 → null（不臆造 0）
  const coverage =
    questionTotal !== null && questionTotal > 0 && answeredCount !== null
      ? Math.min(answeredCount / questionTotal, 1)
      : null

  const countEvidence = (n: number | null, unit: string, zeroText: string): string => {
    if (n === null) return '暂无相关数据'
    return n >= 1 ? `已达成 ${n} ${unit}` : zeroText
  }

  return [
    {
      id: 'resume_uploaded',
      dimension: '简历',
      label: '已上传简历',
      achieved: ge(resumeCount, 1),
      evidence: countEvidence(resumeCount, '份分析', '尚未上传简历'),
    },
    {
      id: 'resume_score_80',
      dimension: '简历',
      label: '简历分数达到 80 分',
      achieved: ge(resumeScore, 80),
      evidence: resumeScore === null ? '暂无简历评分' : `当前 ${Math.round(resumeScore)} 分`,
    },
    {
      id: 'favorite_any',
      dimension: '岗位目标',
      label: '已收藏心仪岗位',
      achieved: ge(favoriteCount, 1),
      evidence: countEvidence(favoriteCount, '个岗位', '尚未收藏岗位'),
    },
    {
      id: 'ledger_any',
      dimension: '岗位目标',
      label: '已建立投递台账',
      achieved: ge(applicationCount, 1),
      evidence: countEvidence(applicationCount, '条记录', '尚未建立台账'),
    },
    {
      id: 'jd_analysis',
      dimension: '岗位目标',
      label: '完成 1 次 JD 拆解',
      achieved: manual.jdAnalysis === true,
      evidence: manual.jdAnalysis ? '已自述完成' : '可在「JD 拆解」完成一次',
      manualKey: 'jdAnalysis',
    },
    {
      id: 'applied_any',
      dimension: '投递',
      label: '已投出 ≥1 份',
      achieved: ge(appliedCount, 1),
      evidence: countEvidence(appliedCount, '份投递', '尚未投出'),
    },
    {
      id: 'invite_any',
      dimension: '投递',
      label: '收到过面试邀约',
      achieved: ge(inviteCount, 1),
      evidence: countEvidence(inviteCount, '个邀约', '暂无面试邀约'),
    },
    {
      id: 'session_3',
      dimension: '面试训练',
      label: '完成 ≥3 场模拟面试',
      achieved: ge(finishedSessionCount, 3),
      evidence:
        finishedSessionCount === null
          ? '暂无面试数据'
          : `当前 ${finishedSessionCount} 场`,
    },
    {
      id: 'interview_avg_70',
      dimension: '面试训练',
      label: '模拟面试平均分 ≥70',
      achieved: ge(interviewAvgScore, 70),
      evidence: interviewAvgScore === null ? '暂无面试评分' : `当前 ${Math.round(interviewAvgScore)} 分`,
    },
    {
      id: 'practice_any',
      dimension: '知识储备',
      label: '已开始刷题（作答 ≥1 题）',
      achieved: ge(answeredCount, 1),
      evidence: countEvidence(answeredCount, '题作答', '尚未作答'),
    },
    {
      id: 'coverage_50',
      dimension: '知识储备',
      label: '刷题覆盖率 ≥50%',
      achieved: coverage !== null && coverage >= 0.5,
      evidence:
        questionTotal === null
          ? '暂无题库数据'
          : questionTotal === 0
            ? '暂无题目'
            // R3「无数据 ≠ 0」：已作答数缺失时显式渲染 EMPTY，**绝不**回落成假 0
            : `当前 ${answeredCount === null ? EMPTY : answeredCount}/${questionTotal}`,
    },
    {
      id: 'story_3',
      dimension: '材料准备',
      label: '项目故事库 ≥3 条',
      achieved: ge(storyCount, 3),
      evidence: storyCount === null ? '暂无故事库数据' : `当前 ${storyCount} 条`,
    },
    {
      id: 'self_intro',
      dimension: '材料准备',
      label: '准备 1 分钟自我介绍',
      achieved: manual.selfIntro === true,
      evidence: manual.selfIntro ? '已自述完成' : '准备一段 60 秒自我介绍',
      manualKey: 'selfIntro',
    },
    {
      id: 'cover_letter',
      dimension: '材料准备',
      label: '准备求职信',
      achieved: manual.coverLetter === true,
      evidence: manual.coverLetter ? '已自述完成' : '为心仪岗位准备一封求职信',
      manualKey: 'coverLetter',
    },
  ]
}

/**
 * 归一化为准备度结果（组件渲染的唯一入口）。
 *
 * <p>等级只在**至少达成 1 项**时给出；全未达成 → `grade === null`、`hasData === false`。
 */
export function buildReadiness(input: ReadinessInput): ReadinessResult {
  const items = buildItems(input)
  const achievedCount = items.filter((i) => i.achieved).length
  const hasData = achievedCount > 0
  return {
    grade: hasData ? gradeFromCount(achievedCount) : null,
    achievedCount,
    total: items.length,
    hasData,
    items,
  }
}

/** 默认自述项（全未达成） */
export function defaultManual(): ReadinessManual {
  return { jdAnalysis: false, selfIntro: false, coverLetter: false }
}

/** 读取本地自述项（隐私模式等异常一律回退默认值）。 */
export function loadManual(): ReadinessManual {
  try {
    if (typeof localStorage === 'undefined') return defaultManual()
    const raw = localStorage.getItem(READINESS_MANUAL_KEY)
    if (!raw) return defaultManual()
    const obj = JSON.parse(raw) as Partial<Record<keyof ReadinessManual, unknown>>
    return {
      jdAnalysis: obj?.jdAnalysis === true,
      selfIntro: obj?.selfIntro === true,
      coverLetter: obj?.coverLetter === true,
    }
  } catch {
    return defaultManual()
  }
}

/** 持久化自述项（写入失败静默）。 */
export function saveManual(m: ReadinessManual): void {
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.setItem(
      READINESS_MANUAL_KEY,
      JSON.stringify({
        jdAnalysis: m.jdAnalysis === true,
        selfIntro: m.selfIntro === true,
        coverLetter: m.coverLetter === true,
      }),
    )
  } catch {
    // 隐私模式 / 配额不足：忽略即可，不阻塞用户
  }
}

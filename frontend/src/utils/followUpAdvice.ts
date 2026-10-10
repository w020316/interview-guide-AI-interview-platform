/**
 * 投递跟进建议（N4）—— **纯本地规则，零网络、零 AI**。
 *
 * 背景：投递看板此前只列「谁需要跟进」+ 一个统一的「去催一下」链接，
 * 用户还得自己想「现在该做什么、话怎么说」。本模块按 **状态 + 时间** 给出
 * 针对性的下一步动作，并代拟一段可直接发送的跟进消息。
 *
 * 设计取舍：
 * - 不引入 AI：规则可解释、可单测、零成本 —— 与 `noticeParse` 同风格。
 * - **只给建议、不代发**：消息由用户复制后自行发送（投递是用户的对外行为）。
 */

export type FollowUpUrgency = 'high' | 'normal'

export interface FollowUpAdvice {
  /** 为什么建议跟进 */
  reason: string
  /** 建议的下一步动作 */
  action: string
  /** 可直接发送的跟进消息；空串表示此刻无需发消息 */
  message: string
  /** 是否紧迫（UI 用强调色） */
  urgency: FollowUpUrgency
}

/** 建议所需的最小字段（与 ApplicationView 的 Application 兼容） */
export interface FollowUpApp {
  status: string
  title?: string | null
  companyName?: string | null
  appliedAt?: string | null
  lastReplyAt?: string | null
  updatedAt?: string | null
}

const DAY_MS = 86_400_000

/** 距今天数（向下取整，最小 0）；不可解析返回 null */
export function daysSince(dateStr: string | null | undefined, nowMs: number): number | null {
  if (!dateStr) return null
  const t = new Date(dateStr).getTime()
  if (Number.isNaN(t)) return null
  return Math.max(0, Math.floor((nowMs - t) / DAY_MS))
}

/**
 * 计算某条投递的跟进建议。
 *
 * @returns null 表示「无需跟进」（已淘汰 / 已放弃 / 未知状态）
 */
export function followUpAdvice(app: FollowUpApp, nowMs: number = Date.now()): FollowUpAdvice | null {
  const title = app.title || '该岗位'
  const company = app.companyName || '贵司'

  switch (app.status) {
    case 'REJECTED':
    case 'WITHDRAWN':
      // 已结束的投递不再催 —— 建议只应出现在「还有下一步」的投递上
      return null

    case 'PLANNED':
      return {
        reason: '还停在「待投递」',
        action: '尽快完成投递，别错过窗口',
        message: '',
        urgency: 'normal',
      }

    case 'OFFER':
      return {
        reason: '已收到 Offer',
        action: '尽快确认接受或婉拒，并问清回复截止与入职材料',
        message: `您好，感谢「${company}」的录用通知。想确认一下 Offer 的回复截止时间与入职所需材料，谢谢！`,
        urgency: 'high',
      }

    case 'INTERVIEW': {
      const d = daysSince(app.lastReplyAt || app.updatedAt, nowMs)
      if (d !== null && d >= 3) {
        return {
          reason: `面试后 ${d} 天没有新进展`,
          action: '发一封致谢并询问结果',
          message: `您好，感谢「${company}」安排的面试。想了解一下后续安排，如需补充材料请随时告知，谢谢！`,
          urgency: 'high',
        }
      }
      return {
        reason: '面试进行中',
        action: '面试结束当天发一封致谢，保持存在感',
        message: '',
        urgency: 'normal',
      }
    }

    case 'REPLIED':
      return {
        reason: '对方已回复',
        action: '尽快回信，别让对方等',
        message: '您好，收到您的消息，我这边时间方便，可以按您安排的来。谢谢！',
        urgency: 'high',
      }

    case 'APPLIED':
    case 'VIEWED': {
      const d = daysSince(app.appliedAt || app.updatedAt, nowMs)
      if (d === null) {
        return {
          reason: '投递后暂无进展',
          action: '过几天若仍无回复，可主动跟进一次',
          message: '',
          urgency: 'normal',
        }
      }
      if (d >= 14) {
        return {
          reason: `已投递 ${d} 天仍无回复`,
          action: '主动跟进一次；同时把精力分给别的岗位',
          message: `您好，我是此前投递「${title}」的候选人。想跟进一下目前的进展，如需补充材料我可以随时提供，谢谢！`,
          urgency: 'high',
        }
      }
      if (d >= 7) {
        return {
          reason: `已投递 ${d} 天无回复`,
          action: '可以主动跟进一次',
          message: `您好，我是此前投递「${title}」的候选人。想确认一下简历是否收到，以及后续安排，谢谢！`,
          urgency: 'normal',
        }
      }
      return {
        reason: `投递 ${d} 天`,
        action: '耐心等待，一周内不必催',
        message: '',
        urgency: 'normal',
      }
    }

    default:
      return null
  }
}

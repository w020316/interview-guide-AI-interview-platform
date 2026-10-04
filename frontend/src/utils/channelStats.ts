import { EMPTY } from './format'

/**
 * 渠道效果（竞品清单 #6）：投递看板 `GET /api/application/board` → `data.byChannel` 的元素。
 *
 * <p>纯类型 + 展示格式化，零网络、零外部依赖。
 */
export interface ChannelStat {
  /** 渠道名；后端把 platform 为空/空白的记录归入「未标注」，不会丢弃 */
  channel: string
  /** 该渠道下的投递记录总数（含草稿与已撤回） */
  total: number
  /** 已投出数（不含草稿与已撤回）——两个比率的分母 */
  submitted: number
  repliedOrBeyond: number
  interviewOrBeyond: number
  offer: number
  /** 有回复 / 已投出；**已投出为 0 时后端返回 null**（表示「比率不可定义」） */
  replyRate?: number | null
  /** 面试 / 已投出；同上 */
  interviewRate?: number | null
}

/**
 * 比率展示：`null` / `undefined` / 非有限数 → 占位符 {@link EMPTY}（`—`）。
 *
 * <p><b>为什么不能兜底成 0%</b>：后端在「该渠道一条都没投出」时返回 `null`，
 * 含义是「比率不可定义」。若前端把它显示成 `0%`，用户会读成
 * 「投了不少但没人回」——与事实（还没开始投）相反。这是本项目
 * 「无数据 ≠ 0」纪律的又一处落点（同类问题已复发两次）。
 *
 * <p>注意：`0` 是**合法值**（确实投了但无人回复），必须显示为 `0%`，不能与 `null` 混为一谈。
 */
export function formatRate(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) {
    return EMPTY
  }
  return `${Math.round(rate * 100)}%`
}

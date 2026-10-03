/**
 * 折线图 X 轴刻度抽稀（v1.48.0，第六轮 UX P2）
 *
 * 背景：移动端（390px）趋势折线图此前靠 `min-width:560px` + 横向滚动撑开，
 * 右侧数据点被裁且没有滚动提示。改为「图表自适应容器宽度」后，若把每个数据点
 * 的 X 轴标签都画出来，窄屏会挤成一团。因此按需抽稀：只保留均匀分布的若干
 * 标签，且**始终包含首尾两点**（首尾最能表达趋势范围）。
 */

/**
 * 从 `count` 个数据点中选出应显示标签的下标。
 *
 * @param count     数据点总数
 * @param maxLabels 最多显示的标签数（>=2 才会抽稀；不足则全显示）
 * @returns 升序、去重的下标数组；始终包含 0 与 count-1
 */
export function pickTickIndices(count: number, maxLabels: number): number[] {
  if (!Number.isFinite(count) || count <= 0) return []
  if (count === 1) return [0]
  if (!Number.isFinite(maxLabels) || maxLabels >= count) {
    return Array.from({ length: count }, (_, i) => i)
  }
  const n = Math.max(2, Math.floor(maxLabels))
  const out: number[] = []
  for (let k = 0; k < n; k++) {
    out.push(Math.round((k * (count - 1)) / (n - 1)))
  }
  return Array.from(new Set(out))
}

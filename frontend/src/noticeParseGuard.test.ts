import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 合规守卫 · E「解析零网络」（第三批 E 红线，架构 §10.6）
 *
 * ── 为什么必须是机器守卫，而不是 grep 代证 ──────────────────────────────
 * E 的合规约束是「只允许：用户主动粘贴文本 → **本地**解析」：
 * 一旦有人给 `noticeParse.ts` 加上任何网络/接口调用，产品就会从「本地正则解析」
 * 悄悄变成「把用户邮件内容发给服务端/第三方」，这正是本批最不能破的红线。
 * 人的记忆会漏，grep 不会进 CI —— 所以固化成守卫，破了就红。
 *
 * 守卫目标（唯一）：`src/utils/noticeParse.ts` 不得出现网络 / 接口引用。
 *
 * 自证有效：改动此文件时请确认下面的「负向对照」用例仍然失败于注入样本——
 * 本次交付亦做过一次**真实注入**验证（往目标文件追加 `fetch('/x')` → 本守卫变红 →
 * 完整还原 → 守卫复绿），证明这不是一条恒真的空断言。
 */

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)))
const TARGET = path.join(SRC, 'utils', 'noticeParse.ts')

/** 禁止在 noticeParse.ts 出现的网络 / 接口引用标志 */
const FORBIDDEN = [
  '../api', // 本项目的统一 API 客户端
  '@/api',
  'axios',
  'fetch(',
  'XMLHttpRequest',
  'EventSource',
  'WebSocket',
]

function forbiddenHits(text: string): string[] {
  return FORBIDDEN.filter((token) => text.includes(token))
}

describe('合规守卫 · noticeParse 解析零网络', () => {
  it('目标文件存在（改名/移动会让守卫立即变红，而非静默通过）', () => {
    expect(fs.existsSync(TARGET)).toBe(true)
  })

  it('noticeParse.ts 不得出现任何网络 / 接口引用', () => {
    const text = fs.readFileSync(TARGET, 'utf8')
    const hits = forbiddenHits(text)
    expect(
      hits,
      `noticeParse.ts 出现网络引用（E 只允许本地解析）：${hits.join(', ')}`,
    ).toEqual([])
  })

  it('负向对照：检测器对注入样本有效（证明上面的断言不是恒真）', () => {
    const injected = fs.readFileSync(TARGET, 'utf8') + "\nfetch('/x')\n"
    expect(forbiddenHits(injected)).toContain('fetch(')
  })
})

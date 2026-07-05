/**
 * JSON 修复工具（前端版，与后端 JsonRepairUtil 对齐）
 *
 * 解决 AI 模型返回非标准 JSON 的问题：
 * 1. Markdown 代码块剥离
 * 2. JSON 主体提取（括号配对算法）
 * 3. 中文引号统一替换（" " ' ' 「 」 『 』）
 * 4. 中文冒号 ：(U+FF1A) → :
 * 5. 单引号字符串 → 双引号字符串（状态机处理嵌套单引号）
 * 6. 未加引号的 key 加双引号
 * 7. 尾随逗号清理
 * 8. 字符串字面量内部控制字符转义（\n \r \t）
 */

/**
 * 修复 AI 返回的 JSON 字符串
 * @param raw AI 原始返回
 * @returns 可被 JSON.parse 解析的字符串
 */
export function repairJson(raw: string): string {
  if (!raw) return raw

  let s = raw.trim()

  // 1. 剥离 Markdown 代码块
  s = stripMarkdownFence(s)

  // 2. 提取 JSON 主体（括号配对算法，避免误判前缀文本中的 {）
  s = extractJsonBody(s)

  // 3. 中文引号统一替换为 ASCII 双引号
  s = s.replace(/[\u201C\u201D]/g, '"')           // 中文左右双引号 " "
  s = s.replace(/[\u300C\u300D\u300E\u300F]/g, '"') // 中文角标引号 「 」 『 』
  s = s.replace(/[\u2018\u2019]/g, "'")            // 中文左右单引号 ' '

  // 4. 中文冒号 ：(U+FF1A) → ASCII 冒号 :
  s = s.replace(/\uFF1A/g, ':')

  // 5. 单引号字符串 → 双引号字符串（状态机，正确处理嵌套单引号）
  s = convertSingleQuotedStrings(s)

  // 6. 未加引号的 key 加双引号
  s = s.replace(/([{,])\s*([A-Za-z_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5\-]*)\s*:/g, '$1"$2":')

  // 7. 尾随逗号清理
  s = s.replace(/,\s*([}\]])/g, '$1')

  // 8. 字符串字面量内部的控制字符转义
  s = escapeControlCharsInStrings(s)

  return s
}

/**
 * 修复并校验：修复后尝试解析，成功返回 true
 */
export function isValidJson(raw: string): boolean {
  if (!raw || !raw.trim()) return false
  try {
    JSON.parse(raw)
    return true
  } catch {
    return false
  }
}

/**
 * 修复并尝试解析，返回是否成功
 */
export function repairAndCheck(raw: string): { repaired: string; valid: boolean } {
  if (!raw || !raw.trim()) return { repaired: raw, valid: false }
  // 先试原值能否解析
  try {
    JSON.parse(raw)
    return { repaired: raw, valid: true }
  } catch {
    // 继续走修复流程
  }
  const repaired = repairJson(raw)
  try {
    JSON.parse(repaired)
    return { repaired, valid: true }
  } catch {
    return { repaired, valid: false }
  }
}

/** 剥离 ```json ... ``` 或 ``` ... ``` 代码块 */
function stripMarkdownFence(s: string): string {
  if (!s.startsWith('```')) return s
  // 去掉首行 ```json 或 ```
  const firstNl = s.indexOf('\n')
  let body = firstNl > 0 ? s.substring(firstNl + 1) : s.substring(3)
  // 去掉末尾 ```
  const lastFence = body.lastIndexOf('```')
  // 仅在 lastFence > 0 时剥离，避免 ``` 后无换行时返回空字符串
  if (lastFence > 0) {
    body = body.substring(0, lastFence)
  }
  return body.trim()
}

/**
 * 从可能包含前后多余文本的字符串中提取 JSON 主体
 * 使用括号配对算法，避免误判前缀文本中的 {
 */
function extractJsonBody(s: string): string {
  // 找到第一个 { 或 [
  let start = -1
  let startChar = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (c === '{' || c === '[') {
      start = i
      startChar = c
      break
    }
  }
  if (start < 0) return s

  const endChar = startChar === '{' ? '}' : ']'
  // 括号配对：从 start 开始计数，遇到字符串跳过
  let depth = 0
  let inString = false
  let escaped = false
  for (let i = start; i < s.length; i++) {
    const c = s[i]
    if (inString) {
      if (escaped) {
        escaped = false
        continue
      }
      if (c === '\\') {
        escaped = true
        continue
      }
      if (c === '"') {
        inString = false
      }
      continue
    }
    if (c === '"') {
      inString = true
      continue
    }
    if (c === startChar) {
      depth++
    } else if (c === endChar) {
      depth--
      if (depth === 0) {
        return s.substring(start, i + 1)
      }
    }
  }
  // 配对失败，返回原字符串
  return s
}

/**
 * 将单引号字符串转换为双引号字符串
 * 分两阶段处理（与后端一致）：
 * 1. key 单引号：'xxx': → "xxx":（非贪婪）
 * 2. value 单引号：: 'value' → : "value"（状态机查找结束位置）
 */
function convertSingleQuotedStrings(s: string): string {
  // 阶段 1：处理 key 单引号字符串（非贪婪，key 不会包含单引号或冒号）
  s = s.replace(/'([^']*?)'(?=\s*:)/g, (_m, content) => {
    return '"' + String(content).replace(/"/g, '\\"') + '"'
  })

  // 阶段 2：处理 value 单引号字符串（状态机，正确处理嵌套单引号）
  let out = ''
  let i = 0
  while (i < s.length) {
    const c = s[i]
    if (c === "'") {
      const end = findValueEnd(s, i + 1)
      if (end > i) {
        let content = s.substring(i + 1, end)
        content = content.replace(/\\'/g, "'")
        content = content.replace(/"/g, '\\"')
        out += '"' + content + '"'
        i = end + 1
        continue
      }
    }
    out += c
    i++
  }
  return out
}

/**
 * 查找 value 单引号字符串的结束位置
 * 扫描到字符串末尾或下一个结构字符，取第一个 ',}]' 前的单引号
 */
function findValueEnd(s: string, start: number): number {
  let lastValidEnd = -1
  for (let i = start; i < s.length; i++) {
    const c = s[i]
    if (c === "'") {
      let j = i + 1
      while (j < s.length && /\s/.test(s[j])) j++
      if (j >= s.length) {
        lastValidEnd = i
        break
      }
      const next = s[j]
      if (next === ',' || next === '}' || next === ']') {
        lastValidEnd = i
        break
      }
    }
  }
  return lastValidEnd
}

/**
 * 状态机：仅在 JSON 字符串字面量内部转义控制字符
 * - 字符串外：保留原样（缩进 \n 不动）
 * - 字符串内：\n → \\n, \r → \\r, \t → \\t, 其他控制字符删除
 */
function escapeControlCharsInStrings(s: string): string {
  if (!s) return s
  let out = ''
  let inString = false
  let escaped = false
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (!inString) {
      out += c
      if (c === '"') {
        inString = true
        escaped = false
      }
      continue
    }
    if (escaped) {
      out += c
      escaped = false
      continue
    }
    if (c === '\\') {
      out += c
      escaped = true
      continue
    }
    if (c === '"') {
      out += c
      inString = false
      continue
    }
    if (c === '\n') out += '\\n'
    else if (c === '\r') out += '\\r'
    else if (c === '\t') out += '\\t'
    else if (c === '\b') out += '\\b'
    else if (c === '\f') out += '\\f'
    else if (c.charCodeAt(0) < 0x20) {
      // 其他控制字符删除
    } else out += c
  }
  return out
}

/**
 * 注册密码强度策略——后端 `util/PasswordPolicy.java` 的前端镜像。
 *
 * <p>镜像的目的不是「前端把关」（后端的校验才是权威），而是**即时反馈**：
 * 此前前端只检查「≥6 位」，用户输 `123456` 必须等提交后才知道被拒，
 * 而且错误一闪而过。两边的文案逐字一致，避免同一件事出现两种说法。
 *
 * <p>规则（与后端一一对应，改动时两边必须同步）：
 * 1. 常见弱口令黑名单（不区分大小写）
 * 2. 整串是同一个字符
 * 3. 整串是连续序列（步长 ±1，且必须纯数字或纯字母）
 * 4. 包含用户名（用户名长度 ≥ 3 时）
 *
 * <p>刻意不做「大小写+数字+符号」复杂度规则：它会误杀长口令却拦不住
 * `Passw0rd!`，这是与后端一致的产品判断，不是遗漏。
 */

const COMMON_WEAK = new Set([
  '123456', '1234567', '12345678', '123456789', '1234567890',
  '111111', '000000', '666666', '888888', '123123', '112233',
  'abc123', 'a123456', '123456a', 'qwerty', 'qwertyuiop', 'asdfgh',
  'password', 'passw0rd', 'p@ssword', 'iloveyou', 'admin', 'administrator',
  'root', 'letmein', 'welcome', 'monkey', 'dragon', 'sunshine',
  'woaini', 'woaini1314', '5201314', '1314520', 'a123456789',
  'qazwsx', 'zxcvbnm', '1qaz2wsx', 'qwer1234', 'test123', 'test1234',
])

function isSingleRepeatedChar(s: string): boolean {
  return s.length > 1 && Array.from(s).every((c) => c === s[0])
}

function isSequential(s: string): boolean {
  const chars = Array.from(s)
  if (chars.length < 3) return false
  const allDigit = chars.every((c) => /^[0-9]$/.test(c))
  const allLetter = chars.every((c) => /^\p{L}$/u.test(c))
  if (!allDigit && !allLetter) return false
  const codes = chars.map((c) => c.codePointAt(0) as number)
  const step = codes[1] - codes[0]
  if (step !== 1 && step !== -1) return false
  for (let i = 2; i < codes.length; i++) {
    if (codes[i] - codes[i - 1] !== step) return false
  }
  return true
}

/**
 * 校验密码强度。
 *
 * @param password 待校验密码
 * @param username 用户名（用于「密码包含用户名」判断），可为空
 * @returns 不合格时返回面向用户的中文原因；合格返回 null
 */
export function validatePassword(password: string, username?: string): string | null {
  if (!password) return '密码长度需 6-64 字符'
  const lower = password.toLowerCase()
  if (COMMON_WEAK.has(lower)) return '密码过于常见，容易被猜到，请更换'
  if (isSingleRepeatedChar(password)) return '密码不能是重复的同一个字符'
  if (isSequential(password)) return '密码不能是连续的数字或字母（如 123456、abcdef）'
  if (username && username.length >= 3 && lower.includes(username.toLowerCase())) {
    return '密码不能包含用户名'
  }
  return null
}

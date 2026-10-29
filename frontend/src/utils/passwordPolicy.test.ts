import { describe, expect, it } from 'vitest'
import { validatePassword } from './passwordPolicy'

describe('validatePassword —— 与后端 PasswordPolicy 同规则同文案', () => {
  it('合格密码返回 null', () => {
    expect(validatePassword('correcthorse', 'alice')).toBeNull()
    expect(validatePassword('Qz7#mVb2Lx9w', 'alice')).toBeNull()
    expect(validatePassword('a1b2c3d4')).toBeNull() // 混合字符不算连续序列
  })

  it('空值 → 长度提示', () => {
    expect(validatePassword('')).toBe('密码长度需 6-64 字符')
  })

  it('常见弱口令（大小写不敏感）', () => {
    expect(validatePassword('123456')).toBe('密码过于常见，容易被猜到，请更换')
    expect(validatePassword('PassWord')).toBe('密码过于常见，容易被猜到，请更换')
    expect(validatePassword('woaini')).toBe('密码过于常见，容易被猜到，请更换')
  })

  it('整串重复同一字符', () => {
    expect(validatePassword('aaaaaa')).toBe('密码不能是重复的同一个字符')
    expect(validatePassword('!!!!!!')).toBe('密码不能是重复的同一个字符')
  })

  it('连续序列（正序/逆序/字母）', () => {
    // 注意不要用 1234567 —— 它在弱口令黑名单里，会先命中「过于常见」
    expect(validatePassword('456789')).toBe('密码不能是连续的数字或字母（如 123456、abcdef）')
    expect(validatePassword('abcdefg')).toBe('密码不能是连续的数字或字母（如 123456、abcdef）')
    expect(validatePassword('7654321')).toBe('密码不能是连续的数字或字母（如 123456、abcdef）')
  })

  it('包含用户名（用户名 ≥3 位时）', () => {
    expect(validatePassword('xxalicexx', 'alice')).toBe('密码不能包含用户名')
    expect(validatePassword('xxalicexx', 'ALICE')).toBe('密码不能包含用户名')
    // 用户名过短不启用该规则，避免误杀
    expect(validatePassword('xxabxx', 'ab')).toBeNull()
  })

  it('长度不足不在本函数判定（由调用方按 6-64 先拦）', () => {
    // 后端同样：长度校验在 validate 之前，这里只负责「强不强度」
    expect(validatePassword('a1')).toBeNull()
  })
})

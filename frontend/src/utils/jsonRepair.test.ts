import { describe, it, expect } from 'vitest'
import { repairJson, isValidJson, repairAndCheck } from './jsonRepair'

describe('jsonRepair', () => {
  describe('repairJson', () => {
    it('空字符串原样返回', () => {
      expect(repairJson('')).toBe('')
    })

    it('合法 JSON 不修改', () => {
      const input = '{"name":"test","score":80}'
      expect(repairJson(input)).toBe(input)
    })

    it('剥离 ```json 代码块', () => {
      const input = '```json\n{"a":1}\n```'
      expect(repairJson(input)).toBe('{"a":1}')
    })

    it('剥离 ``` 代码块（无 lang 标记）', () => {
      const input = '```\n{"a":1}\n```'
      expect(repairJson(input)).toBe('{"a":1}')
    })

    it('中文双引号替换为 ASCII 双引号', () => {
      const input = '{"name":"测试"}'
      expect(repairJson(input)).toBe('{"name":"测试"}')
    })

    it('中文冒号替换为 ASCII 冒号', () => {
      const input = '{"name"："测试"}'
      expect(repairJson(input)).toBe('{"name":"测试"}')
    })

    it('单引号字符串转双引号', () => {
      const input = "{'name':'test'}"
      const result = repairJson(input)
      expect(JSON.parse(result)).toEqual({ name: 'test' })
    })

    it('尾随逗号清理', () => {
      const input = '{"a":1,}'
      expect(repairJson(input)).toBe('{"a":1}')
    })

    it('字符串内控制字符转义', () => {
      const input = '{"text":"line1\nline2"}'
      const result = repairJson(input)
      expect(JSON.parse(result).text).toBe('line1\nline2')
    })

    it('提取前后有杂质的 JSON 主体', () => {
      const input = '好的，这是结果：\n{"a":1}\n以上是分析。'
      expect(repairJson(input)).toBe('{"a":1}')
    })

    it('数组类型', () => {
      const input = '[{"q":"问题1"},{"q":"问题2"}]'
      expect(repairJson(input)).toBe(input)
    })
  })

  describe('isValidJson', () => {
    it('合法 JSON 返回 true', () => {
      expect(isValidJson('{"a":1}')).toBe(true)
    })

    it('非法 JSON 返回 false', () => {
      expect(isValidJson('{a:1}')).toBe(false)
    })

    it('空字符串返回 false', () => {
      expect(isValidJson('')).toBe(false)
    })
  })

  describe('repairAndCheck', () => {
    it('原值合法时直接返回', () => {
      const result = repairAndCheck('{"a":1}')
      expect(result.valid).toBe(true)
      expect(result.repaired).toBe('{"a":1}')
    })

    it('修复后合法返回 valid true', () => {
      const result = repairAndCheck("{'a':1}")
      expect(result.valid).toBe(true)
      expect(JSON.parse(result.repaired)).toEqual({ a: 1 })
    })

    it('无法修复时返回 valid false', () => {
      const result = repairAndCheck('这不是 JSON')
      expect(result.valid).toBe(false)
    })
  })
})

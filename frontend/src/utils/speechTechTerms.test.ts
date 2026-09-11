import { describe, expect, it } from 'vitest'
import { correctTechTerms } from './speech'

describe('correctTechTerms 技术术语纠正', () => {
  it('英文术语大小写规范化', () => {
    expect(correctTechTerms('我用 transformer 实现')).toBe('我用 Transformer 实现')
    expect(correctTechTerms('结合 RAG 与 deepseek')).toBe('结合 RAG 与 DeepSeek')
    expect(correctTechTerms('跑在 redis 和 mysql 上')).toBe('跑在 Redis 和 MySQL 上')
    expect(correctTechTerms('javascript + typescript')).toBe('JavaScript + TypeScript')
  })

  it('中文误转写替换', () => {
    expect(correctTechTerms('用了变换者模型')).toBe('用了Transformer模型')
    expect(correctTechTerms('深度求索大模型')).toBe('DeepSeek大模型')
  })

  it('组合与空值安全', () => {
    expect(correctTechTerms('')).toBe('')
    expect(correctTechTerms('RAG 检索，transformer 编码，linux 环境'))
      .toBe('RAG 检索，Transformer 编码，Linux 环境')
  })
})
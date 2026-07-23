import { describe, it, expect } from 'vitest'
import { getErrMessage } from './index'

describe('api/index getErrMessage', () => {
  it('优先返回后端 response.data.message', () => {
    const err = {
      response: { data: { message: '后端业务错误' } },
      message: 'axios 错误',
    }
    expect(getErrMessage(err, '兜底')).toBe('后端业务错误')
  })

  it('无 response.data.message 时返回 Error.message', () => {
    const err = new Error('网络错误')
    expect(getErrMessage(err, '兜底')).toBe('网络错误')
  })

  it('无 response 和 message 时返回 fallback', () => {
    const err = { foo: 'bar' }
    expect(getErrMessage(err, '兜底文案')).toBe('兜底文案')
  })

  it('null 时返回 fallback', () => {
    expect(getErrMessage(null, '兜底')).toBe('兜底')
  })

  it('undefined 时返回 fallback', () => {
    expect(getErrMessage(undefined, '兜底')).toBe('兜底')
  })

  it('response.data.message 为空字符串时回退到 Error.message', () => {
    const err = {
      response: { data: { message: '' } },
      message: 'axios 错误',
    }
    // 空字符串是 falsy，应回退
    expect(getErrMessage(err, '兜底')).toBe('axios 错误')
  })
})

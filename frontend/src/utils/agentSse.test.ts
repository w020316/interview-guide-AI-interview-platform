import { describe, expect, it } from 'vitest'
import { AgentSseParser } from './agentSse'

describe('AgentSseParser', () => {
  it('解析单个完整事件（event + data + 空行）', () => {
    const p = new AgentSseParser()
    const events = p.feed('event:token\ndata:你好\n\n')
    expect(events).toEqual([{ event: 'token', data: '你好' }])
  })

  it('跨块缓冲：token 数据被分割时正确拼接', () => {
    const p = new AgentSseParser()
    expect(p.feed('event:token\ndata:你')).toEqual([])
    expect(p.feed('好\n\n')).toEqual([{ event: 'token', data: '你好' }])
  })

  it('忽略心跳注释行', () => {
    const p = new AgentSseParser()
    const events = p.feed(': ping\n\nevent:start\ndata:\n\n')
    expect(events).toEqual([{ event: 'start', data: '' }])
  })

  it('连续多个事件按序解析', () => {
    const p = new AgentSseParser()
    const events = p.feed('event:meta\ndata:{"conversationId":1}\n\nevent:token\ndata:A\n\nevent:done\ndata:[DONE]\n\n')
    expect(events).toEqual([
      { event: 'meta', data: '{"conversationId":1}' },
      { event: 'token', data: 'A' },
      { event: 'done', data: '[DONE]' },
    ])
  })

  it('flush 冲刷无结尾空行的残留事件', () => {
    const p = new AgentSseParser()
    expect(p.feed('event:token\ndata:尾部')).toEqual([])
    expect(p.flush()).toEqual([{ event: 'token', data: '尾部' }])
    expect(p.flush()).toEqual([])
  })

  it('多行 data 拼接为带换行的 data', () => {
    const p = new AgentSseParser()
    const events = p.feed('event:token\ndata:第一行\ndata:第二行\n\n')
    expect(events).toEqual([{ event: 'token', data: '第一行\n第二行' }])
  })

  it('CRLF 行尾兼容', () => {
    const p = new AgentSseParser()
    const events = p.feed('event:token\r\ndata:ok\r\n\r\n')
    expect(events).toEqual([{ event: 'token', data: 'ok' }])
  })
})

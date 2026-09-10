/**
 * 智能体 SSE 流解析工具
 *
 * 将后端 SSE 文本流（event:/data: 行 + 空行分隔）解析为事件序列。
 * 独立成纯函数便于单元测试（Vitest）。
 */

export interface AgentSseEvent {
  event: string
  data: string
}

/**
 * 增量解析器：喂入文本块，吐出完整事件
 */
export class AgentSseParser {
  private buffer = ''
  private currentEvent = 'message'
  private dataLines: string[] = []

  /** 喂入一块文本，返回解析完成的完整事件 */
  feed(chunk: string): AgentSseEvent[] {
    this.buffer += chunk
    const events: AgentSseEvent[] = []
    const lines = this.buffer.split('\n')
    this.buffer = lines.pop() || ''

    for (const rawLine of lines) {
      const line = rawLine.replace(/\r$/, '')
      if (line.startsWith('event:')) {
        this.currentEvent = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        this.dataLines.push(line.slice(5).replace(/^ /, ''))
      } else if (line === '') {
        // 空行 = 事件结束
        if (this.dataLines.length > 0 || this.currentEvent !== 'message') {
          const data = this.dataLines.join('\n')
          if (this.currentEvent !== 'message' || data) {
            events.push({ event: this.currentEvent, data })
          }
        }
        this.currentEvent = 'message'
        this.dataLines = []
      }
      // 注释行（: ping 心跳）忽略
    }
    return events
  }

  /** 流结束时冲刷残余（部分实现可能不带结尾空行/换行） */
  flush(): AgentSseEvent[] {
    // 残余 buffer 视为最后一行处理
    if (this.buffer.trim() !== '') {
      const line = this.buffer.replace(/\r$/, '')
      this.buffer = ''
      if (line.startsWith('event:')) {
        this.currentEvent = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        this.dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    const events: AgentSseEvent[] = []
    if (this.dataLines.length > 0) {
      events.push({ event: this.currentEvent, data: this.dataLines.join('\n') })
      this.dataLines = []
      this.currentEvent = 'message'
    }
    return events
  }
}

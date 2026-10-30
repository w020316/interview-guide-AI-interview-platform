<template>
  <div class="agent-page">
    <header class="page-header">
      <h1>Career Copilot 求职智能体</h1>
      <p>对话式求职助手：找岗位 · 查知识 · 析薄弱 · 排日程，自动调用平台能力为你服务</p>
    </header>

    <div class="agent-layout">
      <!-- 会话侧栏 -->
      <aside class="conv-sidebar">
        <BaseButton variant="gradient" class="new-conv-btn" @click="newConversation">＋ 新对话</BaseButton>
        <div class="conv-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            class="conv-item"
            :class="{ active: conv.id === conversationId }"
            role="button"
            tabindex="0"
            :aria-label="`打开会话 ${conv.title}`"
            @click="loadConversation(conv.id)"
            @keydown.enter="loadConversation(conv.id)"
            @keydown.space.prevent="loadConversation(conv.id)"
          >
            <span class="conv-title">{{ conv.title }}</span>
            <button class="conv-del" title="删除会话" @click.stop="removeConversation(conv.id)">×</button>
          </div>
          <div v-if="conversations.length === 0" class="conv-empty">暂无历史会话</div>
        </div>
      </aside>

      <!-- 对话主区 -->
      <main class="chat-main">
        <div ref="chatBox" class="chat-box">
          <!-- 欢迎语 + 推荐提问 -->
          <div v-if="loadingConversation" class="welcome">
            <div class="welcome-icon" aria-hidden="true">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="9" stroke="var(--brand-primary)" stroke-width="2" opacity="0.35"/>
                <path d="M21 12a9 9 0 0 0-9-9" stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </div>
            <h3>正在加载会话…</h3>
            <p>历史消息马上就好</p>
          </div>
          <div v-else-if="messages.length === 0" class="welcome">
            <div class="welcome-icon" aria-hidden="true">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                      stroke="var(--brand-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <h3>你好，我是你的求职智能体</h3>
            <p>我可以调用平台能力帮你：搜索岗位、解答面试知识、分析薄弱点、查看面试日程</p>
            <div class="suggest-grid">
              <button v-for="s in suggestions" :key="s" class="suggest-btn" @click="send(s)">{{ s }}</button>
            </div>
          </div>

          <!-- 消息列表 -->
          <div v-for="(msg, i) in messages" :key="i" class="msg-row" :class="msg.role">
            <div class="msg-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
            <div class="msg-bubble" :class="{ streaming: msg.role === 'assistant' && streaming && i === messages.length - 1 }">
              <template v-if="msg.role === 'user'">{{ msg.content }}</template>
              <!-- C10：流式期间用纯文本渲染（pre-wrap 保留换行），结束后才走 markdown。
                   一边吐字一边解析 markdown，等于对同一篇不断变长的文档反复全量解析；
                   而且未闭合的代码围栏、半截列表会让版式中途跳变。 -->
              <div v-else-if="streaming && i === messages.length - 1" class="md-body streaming-text">{{ msg.content }}</div>
              <div v-else class="md-body" v-html="renderedContent(msg.content)"></div>
            </div>
          </div>

          <!-- 思考中 -->
          <div v-if="thinking" class="msg-row assistant">
            <div class="msg-avatar">AI</div>
            <div class="msg-bubble">
              <span class="thinking-dots">正在分析并调用工具<span>.</span><span>.</span><span>.</span></span>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="input-bar">
          <textarea
            v-model="input"
            class="chat-input"
            :rows="2"
            placeholder="输入你的问题，如：帮我找深圳的 Java 秋招岗位..."
            :disabled="streaming"
            @keydown.enter.exact="onEnterKey"
          ></textarea>
          <BaseButton v-if="streaming" variant="ghost" @click="stopStreaming">停止生成</BaseButton>
          <BaseButton v-else variant="gradient" :disabled="!input.trim()" @click="send()">
            发送
          </BaseButton>
        </div>
        <div class="input-hint">Enter 发送 · Shift+Enter 换行 · 对话记录自动保存</div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import renderMarkdown from '../utils/markdown'
import { AgentSseParser } from '../utils/agentSse'
import { createLatestGuard } from '../utils/latestRequest'
import { BaseButton } from '../components'

/**
 * Career Copilot 求职智能体对话页
 * - POST + fetch 流式读取 SSE（与 InterviewView 提示流同模式）
 * - 会话侧栏：历史会话加载/切换/删除
 * - meta 事件获取 conversationId（新建会话时回填）
 */

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

interface ConversationItem {
  id: number
  title: string
  updatedAt?: string
}

const suggestions = [
  '联网帮我找全国的算法岗位',
  '帮我出5道Java后端面试题，中等难度',
  '熟悉Java、Spring、Redis，你推荐我投哪些岗位？',
  '我哪些知识点比较薄弱？',
  'Redis 持久化面试怎么答？',
  '我最近有哪些面试安排？',
]

const messages = ref<ChatMessage[]>([])
const conversations = ref<ConversationItem[]>([])
const conversationId = ref<number | null>(null)
const input = ref('')
const streaming = ref(false)
const thinking = ref(false)
const chatBox = ref<HTMLElement | null>(null)
/** C8：历史会话正在加载。加载期间禁止发送，避免消息落进上一个会话 */
const loadingConversation = ref(false)
/** C8：快速连点会话时先发后至的旧响应不得覆盖当前会话 */
const convGuard = createLatestGuard()
let abortController: AbortController | null = null
let coldRetried = false // 冷启动已重试标记：整个页面生命周期仅唤醒一次

onBeforeUnmount(() => {
  // 离开页面时终止进行中的 SSE 流，避免继续更新已卸载组件
  abortController?.abort()
  abortController = null
})

/**
 * 渲染助手消息（仅用于**已结束**的消息）。
 *
 * <p>流式中的消息走模板里的纯文本分支（见 C10），不再调用本函数：
 * 对一篇持续变长的文档逐 token 全量解析 markdown，既贵又会让版式跳变。
 */
function renderedContent(content: string): string {
  return renderMarkdown(content ?? '')
}

async function scrollToBottom() {
  await nextTick()
  if (chatBox.value) {
    chatBox.value.scrollTop = chatBox.value.scrollHeight
  }
}

/**
 * 会话接口的鉴权失败处理（P2-16）。
 *
 * <p><b>401 与 403 语义不同，不能合在一起清号</b>：
 * 401 = 没登录 / token 失效 → 清会话、跳登录；
 * 403 = 已登录但无权（如权益不足、账号受限）→ 只提示，**保留会话与已输入的内容**。
 * 原实现把 403 也当成会话失效，用户在一个业务 403 上被静默登出并丢掉正在写的问题。
 */
function handleAuthFailure(status: number): boolean {
  if (status === 401) {
    ElMessage.error('登录已过期，请重新登录')
    clearAuth()
    window.location.href = '/login?redirect=' + encodeURIComponent('/agent')
    return true
  }
  if (status === 403) {
    ElMessage.error('当前账号没有权限使用该功能。会话已保留，请检查账号状态后重试')
    return true
  }
  return false
}

async function fetchConversations() {
  try {
    const res = await fetch(`${apiBaseUrl}/api/agent/conversations`, {
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (handleAuthFailure(res.status)) return
    if (!res.ok) return
    const body = await res.json()
    conversations.value = body?.data || []
  } catch {
    // 会话列表加载失败不阻断主流程
  }
}

async function loadConversation(id: number) {
  if (streaming.value) return
  const ticket = convGuard.begin()
  // C8：先把「当前会话」切过去，再取消息。
  // 此前 conversationId 只在请求成功后才赋值，慢网下加载期间发出的消息会带着
  // **上一个**会话的 id 提交（消息落进别的会话，用户完全无感）。
  conversationId.value = id
  messages.value = []
  loadingConversation.value = true
  try {
    const res = await fetch(`${apiBaseUrl}/api/agent/conversations/${id}/messages`, {
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (handleAuthFailure(res.status)) return
    if (!res.ok) throw new Error('load failed')
    const body = await res.json()
    if (!convGuard.isLatest(ticket)) return
    const items: Array<{ role: string; content: string }> = body?.data || []
    messages.value = items.map((m) => ({
      role: m.role === 'USER' ? 'user' : 'assistant',
      content: m.content,
    }))
    scrollToBottom()
  } catch {
    if (!convGuard.isLatest(ticket)) return
    ElMessage.error('会话加载失败')
  } finally {
    if (convGuard.isLatest(ticket)) loadingConversation.value = false
  }
}

async function removeConversation(id: number) {
  if (streaming.value) return
  try {
    const confirmed = await ElMessageBox.confirm('确定删除该会话吗？历史消息将一并清除', '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    }).then(() => true).catch(() => false)
    if (!confirmed) return
    const res = await fetch(`${apiBaseUrl}/api/agent/conversations/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (handleAuthFailure(res.status)) return
    if (!res.ok) throw new Error('delete failed')
    if (conversationId.value === id) {
      newConversation()
    }
    fetchConversations()
  } catch {
    ElMessage.error('删除失败')
  }
}

function newConversation() {
  // C8：作废在途的历史加载——否则刚点「新会话」，上一个会话的消息会晚一步填进来
  convGuard.begin()
  loadingConversation.value = false
  conversationId.value = null
  messages.value = []
  input.value = ''
}

/** Enter 发送；中文输入法组合态（选词）的回车不触发发送 */
function onEnterKey(e: KeyboardEvent) {
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  send()
}

/** 停止本次生成（终止 SSE 流） */
function stopStreaming() {
  abortController?.abort()
  abortController = null
  streaming.value = false
  thinking.value = false
}

/** 流式内容写入的最小间隔（毫秒）：逐 token 写会让长回答触发上千次重渲染 */
const STREAM_FLUSH_MS = 100

async function send(preset?: string) {
  const text = (preset || input.value).trim()
  if (!text || streaming.value) return
  if (loadingConversation.value) {
    ElMessage.warning('会话正在加载，请稍候再发送')
    return
  }
  if (!authState.token || !isTokenValid(authState.token)) {
    ElMessage.error('登录已过期，请重新登录')
    clearAuth()
    window.location.href = '/login?redirect=' + encodeURIComponent('/agent')
    return
  }

  input.value = ''
  messages.value.push({ role: 'user', content: text })
  thinking.value = true
  streaming.value = true
  scrollToBottom()

  /** C9：只有收到 done 才算完整结束 */
  let completed = false
  /** 是否已创建助手气泡（供 finally 判断是否提示「回答不完整」） */
  let bubblePushed = false
  /** 服务端下发的错误文案（供 finally 去重） */
  let errorMsg = ''
  /** 本次请求的控制器（stopStreaming 会把 abortController 置 null，这里保留引用以判断是否用户主动停止） */
  let controller: AbortController | null = null

  try {
    controller = new AbortController()
    abortController = controller
    const resp = await fetch(`${apiBaseUrl}/api/agent/chat/stream`, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${authState.token}`,
      },
      body: JSON.stringify({ message: text, conversationId: conversationId.value }),
    })

    if (!resp.ok) {
      // P2-16：401 清会话跳登录；403 只提示并保留输入（不静默登出）
      if (!handleAuthFailure(resp.status)) {
        ElMessage.error(`智能体请求失败（HTTP ${resp.status}）`)
      }
      return
    }
    if (!resp.body) throw new Error('Response body is null')

    // 首个 token 到达前显示"思考中"，到达后切换为流式气泡
    const assistantMsg: ChatMessage = { role: 'assistant', content: '' }
    /** C10：数组里存的是原始对象，模板读到的却是代理 —— 写裸对象不会触发重渲染，
     *  必须取回 push 之后数组里的那个代理再写，流式文本才会随时间出现 */
    let assistantRef: ChatMessage | null = null
    const ensureBubble = () => {
      if (!bubblePushed) {
        bubblePushed = true
        thinking.value = false
        messages.value.push(assistantMsg)
        assistantRef = messages.value[messages.value.length - 1]
      }
    }
    // token 先进缓冲区，flush 时整段写入：逐 token 写入等于逐 token 重渲染 + 逐 token 滚动
    let pendingText = ''
    let lastFlushAt = 0
    const flushTokens = (force = false) => {
      if (!pendingText || !assistantRef) return
      const now = performance.now()
      if (!force && now - lastFlushAt < STREAM_FLUSH_MS) return
      lastFlushAt = now
      assistantRef.content += pendingText
      pendingText = ''
      scrollToBottom()
    }

    const reader = resp.body.getReader()
    const parser = new AgentSseParser()
    // P1-8（2026-10-21 质量循环）：decoder 必须在整个流生命周期内复用。
    // 原写法每片 `new TextDecoder()`，而 `{stream:true}` 的语义是「把不完整的尾部
    // 字节留在**该实例内部**等下一片」——每片新建实例等于每片都丢弃尾部残字节，
    // 下一片开头的续字节在全新 decoder 里非法 → 输出 U+FFFD（「�」）。
    // 中文在 UTF-8 是 3 字节，分片边界落在字符中间的概率约 2/3，而网络分片边界随机。
    // 表现：流式回答零星出现「�」，刷新后重新加载历史会话又正常（历史走 JSON 接口），
    // 极易被误判成「模型输出有问题」。同仓 InterviewView.vue:960 是正确的单实例写法。
    const decoder = new TextDecoder()
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      for (const evt of parser.feed(decoder.decode(value, { stream: true }))) {
        if (evt.event === 'meta') {
          try {
            const meta = JSON.parse(evt.data)
            if (meta.conversationId) conversationId.value = meta.conversationId
          } catch { /* meta 解析失败忽略 */ }
        } else if (evt.event === 'token') {
          ensureBubble()
          pendingText += evt.data
          flushTokens()
        } else if (evt.event === 'error') {
          errorMsg = evt.data || 'AI 服务异常，请重试'
        } else if (evt.event === 'done') {
          completed = true
        }
      }
    }
    // 流结束后冲刷 decoder 的尾部字节。正常 SSE 以 `\n\n` 收尾，此处通常为空；
    // 但连接被提前关闭时可能残留半个多字节字符，冲刷后交给 parser 统一收尾，
    // 避免「最后半个字丢失」与「残留字节泄漏到下一次请求」两种隐患。
    const tail = decoder.decode()
    if (tail) {
      for (const evt of parser.feed(tail)) {
        if (evt.event === 'token' && bubblePushed) pendingText += evt.data
      }
    }
    for (const evt of parser.flush()) {
      if (evt.event === 'token' && bubblePushed) pendingText += evt.data
    }
    flushTokens(true)

    if (errorMsg) {
      ElMessage.error(errorMsg)
      if (bubblePushed && !assistantMsg.content) {
        messages.value = messages.value.filter((m) => m !== assistantMsg)
      }
    }
    if (!bubblePushed && !errorMsg) {
      ElMessage.warning('未收到回复，请重试')
    }
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      // 用户主动停止，静默
    } else if (e instanceof TypeError && !coldRetried) {
      // 冷启动自动重试：网络层断开（后端休眠），唤醒后再试一次（整个生命周期仅一次）
      coldRetried = true
      // 复位流控标志，否则第245行 send() 的 `streaming` 守卫会直接拦截本次重试，导致重试永远不执行
      streaming.value = false
      abortController = null
      try {
        ElMessage.info('后端服务正在冷启动（30-60s），正在唤醒，请稍候...')
        const wake = new AbortController()
        const wakeTimer = setTimeout(() => wake.abort(), 100000)
        await fetch(`${apiBaseUrl}/api/info`, { signal: wake.signal })
        clearTimeout(wakeTimer)
        // 移除本次已推入的用户消息与空气泡，交由重试重新发起
        messages.value = messages.value.filter((m) => !(m.role === 'user' && m.content === text))
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && last.content === '') {
          messages.value = messages.value.slice(0, -1)
        }
        await send(text)
      } catch {
        ElMessage.error('后端唤醒失败，请稍后重试')
      }
    } else if (e instanceof Error && e.name !== 'AbortError') {
      ElMessage.error('网络异常，请检查网络后重试')
    }
  } finally {
    // C9：连接被提前切断（无 done 事件）时，半截回答必须明确告知，
    // 不能静默留在气泡里让用户以为回答就到这里
    const wasAborted = controller?.signal.aborted === true
    streaming.value = false
    thinking.value = false
    abortController = null
    if (bubblePushed && !completed && !wasAborted && !errorMsg) {
      ElMessage.warning('回答可能不完整（连接提前结束），建议重新发送')
    }
    fetchConversations()
    scrollToBottom()
  }
}

onMounted(() => {
  fetchConversations()
})
</script>

<style scoped>
.agent-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 32px 20px 40px;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
}

.page-header p {
  color: var(--c-text-secondary);
  margin-bottom: 24px;
}

.agent-layout {
  display: flex;
  gap: 16px;
  min-height: 560px;
}

.conv-sidebar {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.new-conv-btn {
  width: 100%;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.conv-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 13px;
  background: var(--c-surface);
  transition: border-color 0.2s;
}

.conv-item:hover {
  border-color: var(--brand-primary);
}

.conv-item.active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}

.conv-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--c-text);
}

.conv-del {
  border: none;
  background: none;
  color: var(--c-text-secondary);
  cursor: pointer;
  font-size: 16px;
  flex-shrink: 0;
}

.conv-del:hover {
  color: var(--c-danger);
}

.conv-empty {
  text-align: center;
  color: var(--c-text-secondary);
  font-size: 13px;
  padding: 20px 0;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  /* 容器用 --c-bg、气泡用 --c-surface-elevated：暗色下 --c-surface 与
   * --c-bg-alt 同值（#211d1a），旧写法会让气泡与容器零对比、边界不可辨 */
  background: var(--c-bg);
  overflow: hidden;
}

.chat-box {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  max-height: 560px;
  min-height: 420px;
}

/* 欢迎区改为左对齐（v1.63.1）：它是「标题 + 说明 + 建议网格」的内容块，
 * 标题居中而下方网格左对齐会互相打架；左对齐也与全站的编辑式阅读轴线一致。
 * 图标由 emoji 换成内联 SVG —— emoji 配色不受主题控制，且与全站 SVG 图标体系割裂。 */
.welcome {
  text-align: left;
  padding: 40px 20px;
}

.welcome-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-50);
  border: 1px solid var(--brand-primary-100);
  margin-bottom: 14px;
}

.welcome h3 {
  font-size: 18px;
  margin-bottom: 8px;
  color: var(--c-text);
}

.welcome p {
  color: var(--c-text-secondary);
  font-size: 14px;
  margin-bottom: 24px;
}

.suggest-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-width: 480px;
  margin: 0 auto;
}

.suggest-btn {
  padding: 10px 14px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface-elevated); /* I2：暗色下不再白底白字 */
  color: var(--c-text);
  font-size: 13px;
  cursor: pointer;
  transition: color 0.2s, background-color 0.2s, border-color 0.2s, box-shadow 0.2s, transform 0.2s, opacity 0.2s;
  text-align: left;
}

.suggest-btn:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: flex-start;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--brand-primary-50);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.msg-row.user .msg-avatar {
  background: var(--brand-primary);
  color: var(--c-on-primary);
}

/* 助手回答常常是「长结构化正文 + 表格」，需要横向空间（DESIGN.md §5.5 的
 * 65~75 字符阅读带）。`.msg-bubble` 是 flex 子项且没有 flex-grow，基尺寸 =
 * max-content，所以 100% 只是**上限**，一句寒暄仍会收缩到内容宽，不会松散。
 * 用户消息保持 76%：都是短句，右对齐更好读。 */
.msg-bubble {
  max-width: 100%;
  /* flex 子项默认 min-width:auto，长 token / 长 URL 会撑破容器 */
  min-width: 0;
  padding: 10px 14px;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-base);
  line-height: 1.65;
  background: var(--c-surface-elevated);
  border: 1px solid var(--c-border);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.msg-row.user .msg-bubble {
  max-width: 76%;
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: var(--c-on-primary);
}

.msg-bubble.streaming::after {
  content: '▍';
  animation: blink 1s infinite;
  color: var(--brand-primary);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* markdown 正文排版统一由 styles/markdown.css 的 .md-body 提供
 * （唯一来源，由 designGuards.test.ts 锁住）。此处不再重复定义
 * p / ul / ol / a / code 等元素样式 —— 那正是此前「五套实现漂移」的成因。 */

/* 流式期间的纯文本渲染：保留换行与空格，流结束后再交给 markdown 排版 */
.streaming-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.thinking-dots span {
  animation: dot 1.4s infinite;
  opacity: 0;
}

.thinking-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.thinking-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dot {
  0% {
    opacity: 0;
  }
  50% {
    opacity: 1;
  }
  100% {
    opacity: 0;
  }
}

.input-bar {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  border-top: 1px solid var(--c-border);
  align-items: flex-end;
}

.chat-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  font-size: 14px;
  font-family: inherit;
  background: var(--c-surface-elevated); /* I2：暗色下不再白底白字 */
  color: var(--c-text);
  outline: none;
}

.chat-input:focus {
  border-color: var(--brand-primary);
}

.input-hint {
  text-align: center;
  font-size: 12px;
  color: var(--c-text-secondary);
  padding: 0 0 10px;
}

@media (max-width: 768px) {
  .agent-layout {
    flex-direction: column;
  }

  .conv-sidebar {
    width: 100%;
  }

  .conv-list {
    flex-direction: row;
    overflow-x: auto;
  }

  .conv-item {
    min-width: 140px;
  }

  .msg-row.user .msg-bubble {
    max-width: 88%;
  }
}
</style>

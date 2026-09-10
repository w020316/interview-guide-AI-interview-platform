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
            @click="loadConversation(conv.id)"
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
          <div v-if="messages.length === 0" class="welcome">
            <div class="welcome-icon">🤖</div>
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
              <div v-else class="md-content" v-html="renderedContent(msg.content)"></div>
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
            @keydown.enter.exact.prevent="send()"
          ></textarea>
          <BaseButton variant="gradient" :loading="streaming" :disabled="streaming || !input.trim()" @click="send()">
            发送
          </BaseButton>
        </div>
        <div class="input-hint">Enter 发送 · Shift+Enter 换行 · 对话记录自动保存</div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiBaseUrl } from '../api'
import { authState, isTokenValid, clearAuth } from '../auth'
import renderMarkdown from '../utils/markdown'
import { AgentSseParser } from '../utils/agentSse'
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
  '帮我找深圳的技术类秋招岗位',
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

function renderedContent(content: string): string {
  return renderMarkdown(content)
}

async function scrollToBottom() {
  await nextTick()
  if (chatBox.value) {
    chatBox.value.scrollTop = chatBox.value.scrollHeight
  }
}

async function fetchConversations() {
  try {
    const res = await fetch(`${apiBaseUrl}/api/agent/conversations`, {
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (!res.ok) return
    const body = await res.json()
    conversations.value = body?.data || []
  } catch {
    // 会话列表加载失败不阻断主流程
  }
}

async function loadConversation(id: number) {
  if (streaming.value) return
  try {
    const res = await fetch(`${apiBaseUrl}/api/agent/conversations/${id}/messages`, {
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (!res.ok) throw new Error('load failed')
    const body = await res.json()
    const items: Array<{ role: string; content: string }> = body?.data || []
    conversationId.value = id
    messages.value = items.map((m) => ({
      role: m.role === 'USER' ? 'user' : 'assistant',
      content: m.content,
    }))
    scrollToBottom()
  } catch {
    ElMessage.error('会话加载失败')
  }
}

async function removeConversation(id: number) {
  if (streaming.value) return
  try {
    await fetch(`${apiBaseUrl}/api/agent/conversations/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${authState.token}` },
    })
    if (conversationId.value === id) {
      newConversation()
    }
    fetchConversations()
  } catch {
    ElMessage.error('删除失败')
  }
}

function newConversation() {
  conversationId.value = null
  messages.value = []
  input.value = ''
}

async function send(preset?: string) {
  const text = (preset || input.value).trim()
  if (!text || streaming.value) return
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

  try {
    const resp = await fetch(`${apiBaseUrl}/api/agent/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${authState.token}`,
      },
      body: JSON.stringify({ message: text, conversationId: conversationId.value }),
    })

    if (!resp.ok) {
      if (resp.status === 401 || resp.status === 403) {
        ElMessage.error('登录已过期，请重新登录')
        clearAuth()
        window.location.href = '/login?redirect=' + encodeURIComponent('/agent')
      } else {
        ElMessage.error(`智能体请求失败（HTTP ${resp.status}）`)
      }
      return
    }
    if (!resp.body) throw new Error('Response body is null')

    // 首个 token 到达前显示"思考中"，到达后切换为流式气泡
    const assistantMsg: ChatMessage = { role: 'assistant', content: '' }
    let bubblePushed = false
    const ensureBubble = () => {
      if (!bubblePushed) {
        bubblePushed = true
        thinking.value = false
        messages.value.push(assistantMsg)
      }
    }

    const reader = resp.body.getReader()
    const parser = new AgentSseParser()
    let errorMsg = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      for (const evt of parser.feed(new TextDecoder().decode(value, { stream: true }))) {
        if (evt.event === 'meta') {
          try {
            const meta = JSON.parse(evt.data)
            if (meta.conversationId) conversationId.value = meta.conversationId
          } catch { /* meta 解析失败忽略 */ }
        } else if (evt.event === 'token') {
          ensureBubble()
          assistantMsg.content += evt.data
          scrollToBottom()
        } else if (evt.event === 'error') {
          errorMsg = evt.data || 'AI 服务异常，请重试'
        } else if (evt.event === 'done') {
          // 结束
        }
      }
    }
    for (const evt of parser.flush()) {
      if (evt.event === 'token' && bubblePushed) {
        assistantMsg.content += evt.data
      }
    }

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
    if (e instanceof Error && e.name !== 'AbortError') {
      ElMessage.error('网络异常，请检查网络后重试')
    }
  } finally {
    streaming.value = false
    thinking.value = false
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
  max-width: 1180px;
  margin: 0 auto;
  padding: 32px 20px 40px;
}

.page-header h1 {
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 8px;
}

.page-header p {
  color: var(--text-secondary, #888);
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
  border: 1px solid var(--border-color, #eee);
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  background: var(--card-bg, #fff);
  transition: border-color 0.2s;
}

.conv-item:hover {
  border-color: var(--primary-color, #4f46e5);
}

.conv-item.active {
  border-color: var(--primary-color, #4f46e5);
  background: var(--tag-bg, #f1f0fb);
}

.conv-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary, #333);
}

.conv-del {
  border: none;
  background: none;
  color: var(--text-secondary, #999);
  cursor: pointer;
  font-size: 16px;
  flex-shrink: 0;
}

.conv-del:hover {
  color: #dc2626;
}

.conv-empty {
  text-align: center;
  color: var(--text-secondary, #999);
  font-size: 13px;
  padding: 20px 0;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-color, #eee);
  border-radius: 14px;
  background: var(--card-bg, #fff);
  overflow: hidden;
}

.chat-box {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  max-height: 560px;
  min-height: 420px;
}

.welcome {
  text-align: center;
  padding: 40px 20px;
}

.welcome-icon {
  font-size: 44px;
  margin-bottom: 12px;
}

.welcome h3 {
  font-size: 18px;
  margin-bottom: 8px;
  color: var(--text-primary, #222);
}

.welcome p {
  color: var(--text-secondary, #888);
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
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  background: var(--input-bg, #fafafa);
  color: var(--text-primary, #333);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
}

.suggest-btn:hover {
  border-color: var(--primary-color, #4f46e5);
  color: var(--primary-color, #4f46e5);
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
  background: var(--tag-bg, #f1f0fb);
  color: var(--primary-color, #4f46e5);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.msg-row.user .msg-avatar {
  background: var(--primary-color, #4f46e5);
  color: #fff;
}

.msg-bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
  background: var(--input-bg, #f5f5f5);
  color: var(--text-primary, #222);
  overflow-wrap: break-word;
}

.msg-row.user .msg-bubble {
  background: var(--primary-color, #4f46e5);
  color: #fff;
}

.msg-bubble.streaming::after {
  content: '▍';
  animation: blink 1s infinite;
  color: var(--primary-color, #4f46e5);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.md-content :deep(p) {
  margin: 4px 0;
}

.md-content :deep(ul),
.md-content :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}

.md-content :deep(a) {
  color: var(--primary-color, #4f46e5);
}

.md-content :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
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
  border-top: 1px solid var(--border-color, #eee);
  align-items: flex-end;
}

.chat-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 14px;
  font-family: inherit;
  background: var(--input-bg, #fafafa);
  color: var(--text-primary, #333);
  outline: none;
}

.chat-input:focus {
  border-color: var(--primary-color, #4f46e5);
}

.input-hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-secondary, #aaa);
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

  .msg-bubble {
    max-width: 86%;
  }
}
</style>

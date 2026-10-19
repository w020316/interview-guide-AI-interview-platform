<template>
  <!-- 兜底 UI：任何页面级渲染异常都落在这里，而不是把 <main> 留成一片空白 -->
  <div v-if="err" class="route-error" data-route-error role="alert">
    <span class="re-icon" aria-hidden="true">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
        <path d="M12 8v5 M12 16.5h.01 M10.3 3.9 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"
          stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </span>
    <h2 class="re-title">这个页面没能加载出来</h2>
    <p class="re-desc">
      可能是数据格式变化或网络抖动导致的。先刷新一次通常就好了；
      如果反复出现，把下面的错误信息发给我们，能大幅缩短排查时间。
    </p>
    <div class="re-actions">
      <button class="re-btn re-btn--primary" type="button" @click="reload">刷新页面</button>
      <router-link class="re-btn re-btn--ghost" to="/">回首页</router-link>
    </div>
    <details v-if="errText" class="re-detail">
      <summary>技术细节</summary>
      <pre>{{ errText }}</pre>
    </details>
  </div>
  <slot v-else />
</template>

<script setup lang="ts">
/**
 * 路由级错误边界（v1.76.1）
 *
 * ── 为什么必须要有这一层 ──────────────────────────────────────
 * 曾经的真实故障：某个接口的返回结构从对象变成数组，页面模板读 `meta.sources.length`
 * 抛 TypeError，整棵路由子树渲染失败 —— `<main>` 里只剩一个注释节点，
 * 用户看到的是**一整屏空白**，而且因为全局 errorHandler 在生产环境吞掉了异常，
 * 连控制台都没有线索。nav 与 footer 还在，看上去像"页面没数据"而不是"页面坏了"。
 *
 * 这一层的职责只有一个：**把"页面炸了"变成"用户看得懂、能自救"的状态**，
 * 同时把异常原样打到 console（生产也打），让问题可被发现、可被上报。
 * 它不修复任何业务错误 —— 形状兜底在 utils/safeData.ts 里单独做。
 */
import { onErrorCaptured, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

const err = ref<Error | null>(null)
const route = useRoute()

/** 只展示前两行，避免把整段堆栈糊在用户脸上，又足够定位 */
const errText = ref('')

onErrorCaptured((e) => {
  const error = e instanceof Error ? e : new Error(String(e))
  err.value = error
  errText.value = `${error.name}: ${error.message}`.slice(0, 300)
  // 生产环境也要留痕：这次事故的排查成本几乎全耗在"没有任何日志"上
  console.error('[route-error]', error)
  // 返回 false：已由本边界处理，不再向上冒泡到 app.config.errorHandler
  return false
})

/** 切换路由后自动复位，否则一次失败会粘住后续所有页面 */
watch(() => route.fullPath, () => {
  err.value = null
  errText.value = ''
})

function reload() {
  window.location.reload()
}
</script>

<style scoped>
.route-error {
  max-width: 560px;
  margin: 48px auto;
  padding: 32px 28px;
  text-align: center;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.re-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-bottom: 16px;
  color: var(--c-warning);
  background: var(--c-warning-light);
  border-radius: var(--radius-full);
}

.re-title {
  margin: 0 0 8px;
  font-family: var(--font-title);
  font-size: 19px;
  font-weight: 700;
  color: var(--c-text);
}

.re-desc {
  margin: 0 0 22px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.re-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}

.re-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 20px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background-color var(--transition-fast), border-color var(--transition-fast),
    color var(--transition-fast), transform var(--transition-fast);
}

.re-btn--primary {
  color: var(--c-on-primary);
  background: var(--brand-primary);
}

.re-btn--primary:hover {
  background: var(--brand-primary-hover);
}

.re-btn--ghost {
  color: var(--c-text-secondary);
  background: var(--c-surface);
  border-color: var(--c-border);
}

.re-btn--ghost:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
}

.re-btn:active {
  transform: scale(0.985);
}

.re-detail {
  margin-top: 22px;
  text-align: left;
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.re-detail summary {
  cursor: pointer;
  min-height: 32px;
  line-height: 32px;
}

.re-detail pre {
  margin: 8px 0 0;
  padding: 10px 12px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-secondary);
  background: var(--c-bg-alt);
  border-radius: var(--radius-sm);
}

/* 移动端：按钮撑满，避免在窄屏里挤成两行小按钮 */
@media (max-width: 480px) {
  .route-error {
    margin: 24px auto;
    padding: 24px 18px;
  }
  .re-actions {
    flex-direction: column;
  }
  .re-btn {
    width: 100%;
    min-height: 48px;
  }
}
</style>

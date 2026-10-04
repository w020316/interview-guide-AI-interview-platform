/// <reference types="vite-plugin-pwa/client" />
/**
 * PWA 运行时封装（第三批 D）。
 *
 * <p>设计要点（见架构 §1.3 / 硬条件）：
 * <ul>
 *   <li><b>prompt 模式</b>：{@link setupPwa} 用 vite-plugin-pwa 的 `useRegisterSW` 注册 Service Worker，
 *       新版本**只暴露 {@link needRefresh} 供 UI 提示**，由用户点击 {@link applyUpdate} 才 `updateServiceWorker(true)`；
 *       绝不静默 reload，避免打断答题。</li>
 *   <li><b>在线/离线</b>：监听 `online`/`offline` 事件维护 {@link isOnline}，供顶部横幅提示。</li>
 * </ul>
 *
 * <p>这是唯一 import `virtual:pwa-register` 的地方；测试不 import 本模块，故 jsdom 下不会触碰虚拟模块。
 */
import { ref, watch } from 'vue'
import { useRegisterSW } from 'virtual:pwa-register/vue'

/** 存在可用新版本（prompt 模式：仅提示，不自动刷新） */
export const needRefresh = ref(false)
/** 应用已可离线使用（首次成功预缓存 app shell 后置真） */
export const offlineReady = ref(false)
/** 当前网络是否在线（navigator.onLine 的响应式镜像） */
export const isOnline = ref(true)

/** 由 useRegisterSW 注入的刷新函数；未注册（SSR/测试）时为 null */
let updateServiceWorker: ((reloadPage?: boolean) => Promise<void>) | null = null
/** 幂等守卫：setupPwa 允许被重复调用，只生效一次 */
let started = false

/**
 * 注册 Service Worker 并挂上在线/离线监听。应在 `app.mount()` **之前**调用一次。
 *
 * <p>无 `window`（SSR/非浏览器）时直接返回，保证可安全调用。
 */
export function setupPwa(): void {
  if (started || typeof window === 'undefined') return
  started = true

  const sw = useRegisterSW({
    onNeedRefresh() {
      needRefresh.value = true
    },
    onOfflineReady() {
      offlineReady.value = true
    },
  })
  // 将插件内部 ref 的变化同步到本模块公开 ref（onXxx 回调之外的双保险）
  watch(sw.needRefresh, (v) => {
    needRefresh.value = v
  })
  watch(sw.offlineReady, (v) => {
    offlineReady.value = v
  })
  updateServiceWorker = sw.updateServiceWorker

  isOnline.value = navigator.onLine
  window.addEventListener('online', () => {
    isOnline.value = true
  })
  window.addEventListener('offline', () => {
    isOnline.value = false
  })
}

/**
 * 用户确认后应用新版本（会刷新页面）。仅在 {@link needRefresh} 为真时有意义。
 */
export function applyUpdate(): void {
  needRefresh.value = false
  void updateServiceWorker?.(true)
}

/** 忽略本次版本更新（仅隐藏提示，不刷新） */
export function dismissUpdate(): void {
  needRefresh.value = false
}

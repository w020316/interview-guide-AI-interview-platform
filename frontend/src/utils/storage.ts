/**
 * 受保护的 localStorage 访问封装。
 *
 * 在隐私模式、配额超限、禁用存储或某些浏览器安全策略下，
 * localStorage 的 get/set/remove 调用都可能抛出异常（典型如 Safari 无痕模式 setItem 抛错）。
 * 这里统一捕获异常并返回安全默认值，避免此类异常向上传播导致页面逻辑中断。
 */

/** 读取：异常时返回 null（与「无该键」保持一致语义） */
export function safeGetItem(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

/** 写入：异常时静默忽略，不影响运行时内存状态 */
export function safeSetItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 持久化失败（隐私模式 / 配额超限等），忽略：内存状态不受影响
  }
}

/** 删除：异常时静默忽略 */
export function safeRemoveItem(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // 同上
  }
}

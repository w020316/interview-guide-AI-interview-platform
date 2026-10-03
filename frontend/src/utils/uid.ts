/**
 * 组件实例级唯一 id 生成器（v1.48.0）
 *
 * 背景：Vue 3.4 尚无内置 `useId()`（3.5 才引入），而表单控件需要稳定唯一的 id
 * 才能与 `<label for>` / `aria-describedby` 建立关联。此处在模块作用域维护一个
 * 自增计数器——模块只加载一次，因此跨组件实例不会重复。
 *
 * 注意：计数器必须位于模块作用域。若写在 `<script setup>` 内，会随每次
 * setup() 执行重置，导致所有实例拿到同一个 id。
 */

let seed = 0

/** 生成形如 `${prefix}-1` 的唯一 id */
export function nextUid(prefix = 'uid'): string {
  seed += 1
  return `${prefix}-${seed}`
}

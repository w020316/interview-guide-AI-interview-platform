<template>
  <div
    :class="[
      'base-input',
      `base-input--${size}`,
      { 'is-error': error, 'is-disabled': disabled, 'is-block': block, 'has-prefix': $slots.prefix, 'has-suffix': $slots.suffix }
    ]"
  >
    <span v-if="$slots.prefix" class="base-input__prefix"><slot name="prefix" /></span>
    <input
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :autocomplete="autocomplete"
      :maxlength="maxlength"
      :list="list"
      class="base-input__inner"
      @input="onInput"
      @keyup="onKeyup"
      @blur="onBlur"
    />
    <span v-if="$slots.suffix" class="base-input__suffix"><slot name="suffix" /></span>
  </div>
</template>

<script setup lang="ts">
/**
 * 通用输入框组件
 * - 三种尺寸：sm / md / lg
 * - 支持 v-model、placeholder、type、disabled、readonly、maxlength、autocomplete
 * - error 态（红色边框）、block（块级宽度）
 * - prefix / suffix 插槽（前置图标、后置按钮如密码切换）
 * - v1.11 新增，替代各 View 内联的 .input-wrap input
 *
 * 视觉对齐 LoginView .input-wrap input：
 * - 边框 var(--c-border)，聚焦变 var(--brand-primary)
 * - 暖灰背景 var(--c-surface)，聚焦变 var(--c-bg-alt)
 */
interface Props {
  modelValue?: string | number
  type?: string
  placeholder?: string
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  readonly?: boolean
  error?: boolean
  block?: boolean
  autocomplete?: string
  maxlength?: number
  /** v1.12 新增：datalist id，配合 <datalist> 实现自动补全 */
  list?: string
}
const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  type: 'text',
  placeholder: '',
  size: 'md',
  disabled: false,
  readonly: false,
  error: false,
  block: false,
  autocomplete: 'off',
  maxlength: undefined,
  list: undefined,
})
const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'keyup', ev: KeyboardEvent): void
  (e: 'blur', ev: FocusEvent): void
}>()

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}
function onKeyup(e: KeyboardEvent) {
  emit('keyup', e)
}
function onBlur(e: FocusEvent) {
  emit('blur', e)
}
</script>

<style scoped>
.base-input {
  position: relative;
  display: inline-flex;
  align-items: center;
  width: auto;
}

/* 块级宽度 */
.base-input.is-block {
  display: flex;
  width: 100%;
}

.base-input__inner {
  width: 100%;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.base-input__inner::placeholder {
  color: var(--c-text-tertiary);
}

.base-input__inner:focus {
  border-color: var(--brand-primary);
  background: var(--c-bg-alt);
}

.base-input__inner:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 尺寸 */
.base-input--sm .base-input__inner {
  font-size: 13px;
  padding: 7px 12px;
}
.base-input--md .base-input__inner {
  font-size: 14px;
  padding: 11px 14px;
}
.base-input--lg .base-input__inner {
  font-size: 16px;
  padding: 13px 16px;
}

/* 前置图标：左侧内边距留空间 */
.base-input.has-prefix .base-input__inner {
  padding-left: 40px;
}
/* 后置按钮：右侧内边距留空间 */
.base-input.has-suffix .base-input__inner {
  padding-right: 40px;
}

.base-input__prefix,
.base-input__suffix {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-tertiary);
  pointer-events: none;
  z-index: 1;
}
.base-input__prefix {
  left: 12px;
}
.base-input__suffix {
  right: 10px;
  /* suffix 可能是可点击按钮（如密码切换），允许点击 */
  pointer-events: auto;
}

/* 聚焦时前置图标变品牌色（与 LoginView 一致） */
.base-input:focus-within .base-input__prefix {
  color: var(--brand-primary);
}

/* 错误态：红色边框 */
.base-input.is-error .base-input__inner {
  border-color: var(--c-danger);
}
.base-input.is-error .base-input__inner:focus {
  border-color: var(--c-danger);
}
</style>

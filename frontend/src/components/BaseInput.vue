<template>
  <div
    :class="[
      'base-input',
      `base-input--${size}`,
      {
        'is-error': hasError,
        'is-disabled': disabled,
        'is-block': block,
        'has-prefix': $slots.prefix,
        'has-suffix': $slots.suffix,
        'has-error-text': !!errorText,
      }
    ]"
  >
    <span v-if="$slots.prefix" class="base-input__prefix"><slot name="prefix" /></span>
    <input
      :id="inputId"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :autocomplete="autocomplete"
      :maxlength="maxlength"
      :list="list"
      :aria-label="ariaLabel || undefined"
      :aria-invalid="hasError ? 'true' : undefined"
      :aria-describedby="describedBy"
      class="base-input__inner"
      @input="onInput"
      @keyup="onKeyup"
      @blur="onBlur"
    />
    <span v-if="$slots.suffix" class="base-input__suffix"><slot name="suffix" /></span>
    <p v-if="errorText" :id="errorId" class="base-input__error" role="alert">{{ errorText }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { nextUid } from '../utils/uid'

/**
 * 通用输入框组件
 * - 三种尺寸：sm / md / lg
 * - 支持 v-model、placeholder、type、disabled、readonly、maxlength、autocomplete
 * - error 态（红色边框）、block（块级宽度）
 * - prefix / suffix 插槽（前置图标、后置按钮如密码切换）
 * - v1.11 新增，替代各 View 内联的 .input-wrap input
 *
 * v1.48.0 可访问性增强（第六轮 UX P2）：
 * - `id`：自动生成或由调用方传入，供 `<label :for>` 关联（未传时组件内部兜底生成唯一 id）
 * - `ariaLabel`：无可见 label 时的可访问名兜底
 * - `error`：错误态，除红色边框外同时输出 `aria-invalid="true"`
 * - `errorText`：内联错误文案（不再「只变红」），并通过 `aria-describedby` 指向该文案
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
  /** v1.48.0：原生 input id，供 <label :for> 关联；不传则自动生成 */
  id?: string
  /** v1.48.0：无可访问 label 时提供可访问名（映射到 aria-label） */
  ariaLabel?: string
  /** v1.48.0：内联错误文案；存在时同时进入错误态并输出 aria-describedby */
  errorText?: string
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
  id: undefined,
  ariaLabel: undefined,
  errorText: undefined,
})
const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'keyup', ev: KeyboardEvent): void
  (e: 'blur', ev: FocusEvent): void
}>()

// 组件实例级唯一 id（Vue 3.4 尚无 useId，见 utils/uid）
const autoId = nextUid('base-input')
const inputId = computed(() => props.id || autoId)
const errorId = computed(() => `${inputId.value}-error`)

/** errorText 也视为错误态：保证「有错误文案」时边框与 aria-invalid 同步 */
const hasError = computed(() => props.error || !!props.errorText)
const describedBy = computed(() => (props.errorText ? errorId.value : undefined))

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

/* 有内联错误文案时允许换行：错误文案独占一行，输入框保持原宽度 */
.base-input.has-error-text {
  flex-wrap: wrap;
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

/* 内联错误文案：占据整行，语义色而非写死颜色 */
.base-input__error {
  flex-basis: 100%;
  margin: 6px 0 0;
  font-family: var(--font-sans);
  font-size: 12px;
  line-height: 1.4;
  color: var(--c-danger);
}
</style>

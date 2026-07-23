<template>
  <div
    :class="[
      'base-textarea',
      `base-textarea--${size}`,
      { 'is-error': error, 'is-disabled': disabled, 'is-block': block }
    ]"
  >
    <textarea
      :value="modelValue"
      :rows="rows"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :maxlength="maxlength"
      class="base-textarea__inner"
      @input="onInput"
      @keyup="onKeyup"
      @blur="onBlur"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 通用多行文本框组件
 * - 三种尺寸：sm / md / lg
 * - 支持 v-model、rows、placeholder、disabled、readonly、maxlength
 * - error 态（红色边框）、block（块级宽度）
 * - resize: vertical（仅允许垂直调整，防布局错乱）
 * - v1.12 新增，替代各 View 内联的 .field-row textarea
 *
 * 视觉对齐各 View 现有 textarea：
 * - 边框 var(--c-border)，聚焦变 var(--brand-primary) + box-shadow
 * - 暖灰背景 var(--c-surface)
 */
interface Props {
  modelValue?: string
  rows?: number
  placeholder?: string
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  readonly?: boolean
  error?: boolean
  block?: boolean
  maxlength?: number
}
const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  rows: 4,
  placeholder: '',
  size: 'md',
  disabled: false,
  readonly: false,
  error: false,
  block: true,
  maxlength: undefined,
})
const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'keyup', ev: KeyboardEvent): void
  (e: 'blur', ev: FocusEvent): void
}>()

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
}
function onKeyup(e: KeyboardEvent) {
  emit('keyup', e)
}
function onBlur(e: FocusEvent) {
  emit('blur', e)
}
</script>

<style scoped>
.base-textarea {
  position: relative;
  display: inline-flex;
  width: auto;
}

.base-textarea.is-block {
  display: flex;
  width: 100%;
}

.base-textarea__inner {
  width: 100%;
  font-family: var(--font-sans);
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  outline: none;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast), background var(--transition-fast);
  resize: vertical;
  line-height: 1.6;
}

.base-textarea__inner::placeholder {
  color: var(--c-text-tertiary);
}

.base-textarea__inner:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.12);
}

.base-textarea__inner:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 尺寸 */
.base-textarea--sm .base-textarea__inner {
  font-size: 13px;
  padding: 8px 12px;
}
.base-textarea--md .base-textarea__inner {
  font-size: 14px;
  padding: 11px 14px;
}
.base-textarea--lg .base-textarea__inner {
  font-size: 16px;
  padding: 13px 16px;
}

/* 错误态：红色边框 */
.base-textarea.is-error .base-textarea__inner {
  border-color: var(--c-danger);
}
.base-textarea.is-error .base-textarea__inner:focus {
  border-color: var(--c-danger);
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.12);
}
</style>

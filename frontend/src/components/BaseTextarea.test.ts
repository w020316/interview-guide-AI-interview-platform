import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BaseTextarea from './BaseTextarea.vue'

describe('BaseTextarea', () => {
  it('渲染默认 md 尺寸 + rows=4', () => {
    const wrapper = mount(BaseTextarea)
    const ta = wrapper.find('textarea')
    expect(ta.element.rows).toBe(4)
    expect(wrapper.classes()).toContain('base-textarea--md')
  })

  it('支持 sm / lg 尺寸切换', () => {
    const sm = mount(BaseTextarea, { props: { size: 'sm' } })
    const lg = mount(BaseTextarea, { props: { size: 'lg' } })
    expect(sm.classes()).toContain('base-textarea--sm')
    expect(lg.classes()).toContain('base-textarea--lg')
  })

  it('v-model 双向绑定：input 事件触发 update:modelValue', async () => {
    const wrapper = mount(BaseTextarea, { props: { modelValue: '初始' } })
    const ta = wrapper.find('textarea')
    expect(ta.element.value).toBe('初始')
    await ta.setValue('新内容')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['新内容'])
  })

  it('rows 属性透传', () => {
    const wrapper = mount(BaseTextarea, { props: { rows: 10 } })
    expect(wrapper.find('textarea').element.rows).toBe(10)
  })

  it('placeholder 透传', () => {
    const wrapper = mount(BaseTextarea, { props: { placeholder: '请输入' } })
    expect(wrapper.find('textarea').attributes('placeholder')).toBe('请输入')
  })

  it('disabled 状态设置原生 disabled 属性 + is-disabled 类', () => {
    const wrapper = mount(BaseTextarea, { props: { disabled: true } })
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.classes()).toContain('is-disabled')
  })

  it('error 状态应用 is-error 类', () => {
    const wrapper = mount(BaseTextarea, { props: { error: true } })
    expect(wrapper.classes()).toContain('is-error')
  })

  it('block 默认为 true（textarea 通常撑满父容器）', () => {
    const wrapper = mount(BaseTextarea)
    expect(wrapper.classes()).toContain('is-block')
  })

  it('block=false 时不应用 is-block 类', () => {
    const wrapper = mount(BaseTextarea, { props: { block: false } })
    expect(wrapper.classes()).not.toContain('is-block')
  })

  it('keyup 事件透传', async () => {
    const wrapper = mount(BaseTextarea)
    await wrapper.find('textarea').trigger('keyup', { key: 'Enter' })
    expect(wrapper.emitted('keyup')).toBeTruthy()
  })

  it('blur 事件透传', async () => {
    const wrapper = mount(BaseTextarea)
    await wrapper.find('textarea').trigger('blur')
    expect(wrapper.emitted('blur')).toBeTruthy()
  })

  it('maxlength 透传到原生属性', () => {
    const wrapper = mount(BaseTextarea, { props: { maxlength: 500 } })
    expect(wrapper.find('textarea').attributes('maxlength')).toBe('500')
  })
})

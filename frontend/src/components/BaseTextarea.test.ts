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

  // ── v1.48.0 可访问性 ──
  it('自动生成唯一 id，且不同实例互不相同', () => {
    const a = mount(BaseTextarea)
    const b = mount(BaseTextarea)
    const ida = a.find('textarea').attributes('id')
    const idb = b.find('textarea').attributes('id')
    expect(ida).toBeTruthy()
    expect(idb).toBeTruthy()
    expect(ida).not.toBe(idb)
  })

  it('显式传入 id 时优先使用（供 <label for> 关联）', () => {
    const wrapper = mount(BaseTextarea, { props: { id: 'jd-text' } })
    expect(wrapper.find('textarea').attributes('id')).toBe('jd-text')
  })

  it('ariaLabel 映射到原生 aria-label', () => {
    const wrapper = mount(BaseTextarea, { props: { ariaLabel: '岗位描述' } })
    expect(wrapper.find('textarea').attributes('aria-label')).toBe('岗位描述')
  })

  it('错误态输出 aria-invalid="true" 且保留 is-error 类', () => {
    const wrapper = mount(BaseTextarea, { props: { error: true } })
    expect(wrapper.find('textarea').attributes('aria-invalid')).toBe('true')
    expect(wrapper.classes()).toContain('is-error')
  })

  it('errorText：渲染可见错误文案，aria-describedby 指向该元素', () => {
    const wrapper = mount(BaseTextarea, { props: { errorText: '内容不能为空' } })
    const ta = wrapper.find('textarea')
    const err = wrapper.find('.base-textarea__error')
    expect(err.exists()).toBe(true)
    expect(err.text()).toBe('内容不能为空')
    const describedBy = ta.attributes('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(err.attributes('id')).toBe(describedBy)
    expect(wrapper.find(`#${describedBy}`).exists()).toBe(true)
    expect(ta.attributes('aria-invalid')).toBe('true')
  })
})

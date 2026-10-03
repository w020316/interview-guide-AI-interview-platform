import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BaseInput from './BaseInput.vue'

describe('BaseInput', () => {
  it('渲染默认 md 尺寸 + text 类型', () => {
    const wrapper = mount(BaseInput)
    const input = wrapper.find('input')
    expect(input.element.type).toBe('text')
    expect(wrapper.classes()).toContain('base-input--md')
  })

  it('支持 sm / lg 尺寸切换', () => {
    const sm = mount(BaseInput, { props: { size: 'sm' } })
    const lg = mount(BaseInput, { props: { size: 'lg' } })
    expect(sm.classes()).toContain('base-input--sm')
    expect(lg.classes()).toContain('base-input--lg')
  })

  it('v-model 双向绑定：input 事件触发 update:modelValue', async () => {
    const wrapper = mount(BaseInput, { props: { modelValue: '初始' } })
    const input = wrapper.find('input')
    expect(input.element.value).toBe('初始')
    await input.setValue('新值')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['新值'])
  })

  it('placeholder 透传到原生 input', () => {
    const wrapper = mount(BaseInput, { props: { placeholder: '请输入' } })
    expect(wrapper.find('input').attributes('placeholder')).toBe('请输入')
  })

  it('type=password 透传', () => {
    const wrapper = mount(BaseInput, { props: { type: 'password' } })
    expect(wrapper.find('input').element.type).toBe('password')
  })

  it('disabled 状态设置原生 disabled 属性 + is-disabled 类', () => {
    const wrapper = mount(BaseInput, { props: { disabled: true } })
    expect(wrapper.find('input').attributes('disabled')).toBeDefined()
    expect(wrapper.classes()).toContain('is-disabled')
  })

  it('error 状态应用 is-error 类', () => {
    const wrapper = mount(BaseInput, { props: { error: true } })
    expect(wrapper.classes()).toContain('is-error')
  })

  it('block 属性应用 is-block 类', () => {
    const wrapper = mount(BaseInput, { props: { block: true } })
    expect(wrapper.classes()).toContain('is-block')
  })

  it('prefix 插槽渲染并添加 has-prefix 类', () => {
    const wrapper = mount(BaseInput, {
      slots: { prefix: '<svg class="ico"/>' },
    })
    expect(wrapper.classes()).toContain('has-prefix')
    expect(wrapper.find('.base-input__prefix .ico').exists()).toBe(true)
  })

  it('suffix 插槽渲染并添加 has-suffix 类', () => {
    const wrapper = mount(BaseInput, {
      slots: { suffix: '<button class="toggle"/>' },
    })
    expect(wrapper.classes()).toContain('has-suffix')
    expect(wrapper.find('.base-input__suffix .toggle').exists()).toBe(true)
  })

  it('keyup 事件透传', async () => {
    const wrapper = mount(BaseInput)
    await wrapper.find('input').trigger('keyup', { key: 'Enter' })
    expect(wrapper.emitted('keyup')).toBeTruthy()
  })

  it('blur 事件透传', async () => {
    const wrapper = mount(BaseInput)
    await wrapper.find('input').trigger('blur')
    expect(wrapper.emitted('blur')).toBeTruthy()
  })

  it('maxlength 透传到原生属性', () => {
    const wrapper = mount(BaseInput, { props: { maxlength: 100 } })
    expect(wrapper.find('input').attributes('maxlength')).toBe('100')
  })

  it('list 属性透传（配合 datalist 自动补全）', () => {
    const wrapper = mount(BaseInput, { props: { list: 'job-suggestions' } })
    expect(wrapper.find('input').attributes('list')).toBe('job-suggestions')
  })

  // ── v1.48.0 可访问性 ──
  it('自动生成唯一 id，且不同实例互不相同', () => {
    const a = mount(BaseInput)
    const b = mount(BaseInput)
    const ida = a.find('input').attributes('id')
    const idb = b.find('input').attributes('id')
    expect(ida).toBeTruthy()
    expect(idb).toBeTruthy()
    expect(ida).not.toBe(idb)
  })

  it('显式传入 id 时优先使用（供 <label for> 关联）', () => {
    const wrapper = mount(BaseInput, { props: { id: 'target-job' } })
    expect(wrapper.find('input').attributes('id')).toBe('target-job')
  })

  it('ariaLabel 映射到原生 aria-label', () => {
    const wrapper = mount(BaseInput, { props: { ariaLabel: '目标岗位' } })
    expect(wrapper.find('input').attributes('aria-label')).toBe('目标岗位')
  })

  it('无错误时不输出 aria-invalid', () => {
    const wrapper = mount(BaseInput)
    expect(wrapper.find('input').attributes('aria-invalid')).toBeUndefined()
  })

  it('错误态输出 aria-invalid="true" 且保留 is-error 类', () => {
    const wrapper = mount(BaseInput, { props: { error: true } })
    expect(wrapper.find('input').attributes('aria-invalid')).toBe('true')
    expect(wrapper.classes()).toContain('is-error')
  })

  it('errorText：渲染可见错误文案，aria-describedby 指向该元素', () => {
    const wrapper = mount(BaseInput, { props: { errorText: '请输入目标岗位' } })
    const input = wrapper.find('input')
    const err = wrapper.find('.base-input__error')
    expect(err.exists()).toBe(true)
    expect(err.text()).toBe('请输入目标岗位')
    const describedBy = input.attributes('aria-describedby')
    expect(describedBy).toBeTruthy()
    // aria-describedby 指向的元素必须真实存在且 id 一致
    expect(err.attributes('id')).toBe(describedBy)
    expect(wrapper.find(`#${describedBy}`).exists()).toBe(true)
    // 有错误文案时同时进入错误态
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(wrapper.classes()).toContain('is-error')
  })

  it('errorText 与显式 id 组合时，错误元素 id 基于该 id', () => {
    const wrapper = mount(BaseInput, { props: { id: 'job', errorText: '必填' } })
    expect(wrapper.find('.base-input__error').attributes('id')).toBe('job-error')
    expect(wrapper.find('input').attributes('aria-describedby')).toBe('job-error')
  })
})

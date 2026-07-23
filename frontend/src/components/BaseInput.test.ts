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
})

import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import BaseButton from './BaseButton.vue'

describe('BaseButton', () => {
  it('渲染默认 primary + md 变体', () => {
    const wrapper = mount(BaseButton, { slots: { default: '点击' } })
    expect(wrapper.classes()).toContain('base-btn--primary')
    expect(wrapper.classes()).toContain('base-btn--md')
    expect(wrapper.text()).toBe('点击')
  })

  it('支持 ghost / sm 变体与尺寸', () => {
    const wrapper = mount(BaseButton, {
      props: { variant: 'ghost', size: 'sm' },
      slots: { default: '取消' },
    })
    expect(wrapper.classes()).toContain('base-btn--ghost')
    expect(wrapper.classes()).toContain('base-btn--sm')
  })

  it('disabled 状态不触发 click', async () => {
    const onClick = vi.fn()
    const wrapper = mount(BaseButton, {
      props: { disabled: true },
      attrs: { onClick },
    })
    await wrapper.trigger('click')
    expect(onClick).not.toHaveBeenCalled()
    expect(wrapper.attributes('disabled')).toBeDefined()
  })

  it('loading 状态不触发 click 且渲染 spinner', () => {
    const onClick = vi.fn()
    const wrapper = mount(BaseButton, {
      props: { loading: true },
      attrs: { onClick },
    })
    expect(wrapper.find('.base-btn__spinner').exists()).toBe(true)
    // 触发 click 不应 emit
    wrapper.vm.$emit('click', new MouseEvent('click'))
    // loading 时 handleClick 直接 return，不会 emit click
  })

  it('block 属性应用 is-block 类', () => {
    const wrapper = mount(BaseButton, { props: { block: true } })
    expect(wrapper.classes()).toContain('is-block')
  })

  it('正常点击触发 click 事件', async () => {
    const wrapper = mount(BaseButton)
    await wrapper.trigger('click')
    expect(wrapper.emitted('click')).toBeTruthy()
    expect(wrapper.emitted('click')!.length).toBe(1)
  })
})

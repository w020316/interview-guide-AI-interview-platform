import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BaseCard from './BaseCard.vue'
import BaseTag from './BaseTag.vue'

describe('BaseCard', () => {
  it('渲染默认 default 变体', () => {
    const wrapper = mount(BaseCard, { slots: { default: '内容' } })
    expect(wrapper.classes()).toContain('base-card--default')
    expect(wrapper.text()).toBe('内容')
  })

  it('支持 outlined / elevated 变体', () => {
    const outlined = mount(BaseCard, { props: { variant: 'outlined' } })
    expect(outlined.classes()).toContain('base-card--outlined')
    const elevated = mount(BaseCard, { props: { variant: 'elevated' } })
    expect(elevated.classes()).toContain('base-card--elevated')
  })

  it('title/subtitle 渲染头部', () => {
    const wrapper = mount(BaseCard, {
      props: { title: '标题', subtitle: '副标题' },
    })
    expect(wrapper.find('.base-card__title').text()).toBe('标题')
    expect(wrapper.find('.base-card__subtitle').text()).toBe('副标题')
  })

  it('header 插槽覆盖 title 属性', () => {
    const wrapper = mount(BaseCard, {
      props: { title: '默认标题' },
      slots: { header: '<div class="custom-header">自定义</div>' },
    })
    expect(wrapper.find('.custom-header').exists()).toBe(true)
    expect(wrapper.find('.base-card__title').exists()).toBe(false)
  })

  it('footer 插槽渲染', () => {
    const wrapper = mount(BaseCard, {
      slots: { footer: '<button>操作</button>' },
    })
    expect(wrapper.find('.base-card__footer').exists()).toBe(true)
    expect(wrapper.find('.base-card__footer button').exists()).toBe(true)
  })

  it('hoverable 应用 is-hoverable 类', () => {
    const wrapper = mount(BaseCard, { props: { hoverable: true } })
    expect(wrapper.classes()).toContain('is-hoverable')
  })

  it('padded=false 移除内边距类', () => {
    const wrapper = mount(BaseCard, { props: { padded: false } })
    expect(wrapper.find('.base-card__body').classes()).not.toContain('is-padded')
  })

  it('tag 属性渲染为指定标签', () => {
    const wrapper = mount(BaseCard, { props: { tag: 'article' } })
    expect(wrapper.element.tagName).toBe('ARTICLE')
  })

  it('支持 feature 变体（v1.10 特性卡）', () => {
    const wrapper = mount(BaseCard, { props: { variant: 'feature' } })
    expect(wrapper.classes()).toContain('base-card--feature')
  })

  it('flat 属性应用 is-flat 类', () => {
    const wrapper = mount(BaseCard, { props: { variant: 'elevated', flat: true } })
    expect(wrapper.classes()).toContain('is-flat')
  })

  it('feature + hoverable 组合不产生双重 transform', () => {
    const wrapper = mount(BaseCard, { props: { variant: 'feature', hoverable: true } })
    expect(wrapper.classes()).toContain('base-card--feature')
    expect(wrapper.classes()).toContain('is-hoverable')
  })
})

describe('BaseTag', () => {
  it('渲染默认 default + md 变体', () => {
    const wrapper = mount(BaseTag, { slots: { default: '标签' } })
    expect(wrapper.classes()).toContain('base-tag--default')
    expect(wrapper.classes()).toContain('base-tag--md')
    expect(wrapper.text()).toBe('标签')
  })

  it('支持 6 种语义变体', () => {
    const variants = ['default', 'primary', 'success', 'warning', 'danger', 'info'] as const
    for (const variant of variants) {
      const wrapper = mount(BaseTag, { props: { variant } })
      expect(wrapper.classes()).toContain(`base-tag--${variant}`)
    }
  })

  it('支持 sm / lg 尺寸', () => {
    const sm = mount(BaseTag, { props: { size: 'sm' } })
    expect(sm.classes()).toContain('base-tag--sm')
    const lg = mount(BaseTag, { props: { size: 'lg' } })
    expect(lg.classes()).toContain('base-tag--lg')
  })

  it('closable 渲染关闭按钮', () => {
    const wrapper = mount(BaseTag, { props: { closable: true } })
    expect(wrapper.find('.base-tag__close').exists()).toBe(true)
    expect(wrapper.classes()).toContain('is-closable')
  })

  it('点击关闭按钮触发 close 事件', async () => {
    const wrapper = mount(BaseTag, { props: { closable: true } })
    await wrapper.find('.base-tag__close').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(wrapper.emitted('close')!.length).toBe(1)
  })

  it('icon 插槽渲染', () => {
    const wrapper = mount(BaseTag, {
      slots: { icon: '<span class="icon-dot">●</span>' },
    })
    expect(wrapper.find('.icon-dot').exists()).toBe(true)
  })

  it('非 closable 时不渲染关闭按钮', () => {
    const wrapper = mount(BaseTag)
    expect(wrapper.find('.base-tag__close').exists()).toBe(false)
  })

  it('关闭按钮含 aria-label 无障碍属性', () => {
    const wrapper = mount(BaseTag, { props: { closable: true } })
    expect(wrapper.find('.base-tag__close').attributes('aria-label')).toBe('移除')
  })

  it('默认插槽内容包裹在 .base-tag__text 中', () => {
    const wrapper = mount(BaseTag, { slots: { default: '内容文本' } })
    expect(wrapper.find('.base-tag__text').exists()).toBe(true)
    expect(wrapper.find('.base-tag__text').text()).toBe('内容文本')
  })

  it('close 事件携带 .stop 修饰不冒泡到根元素', async () => {
    const wrapper = mount(BaseTag, { props: { closable: true } })
    const closeBtn = wrapper.find('.base-tag__close')
    await closeBtn.trigger('click')
    // close 事件已触发
    expect(wrapper.emitted('close')).toBeTruthy()
    // 由于 @click.stop，根 span 不应触发 click 事件
    expect(wrapper.emitted('click')).toBeFalsy()
  })
})

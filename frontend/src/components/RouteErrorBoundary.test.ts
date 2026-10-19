import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import RouteErrorBoundary from './RouteErrorBoundary.vue'

/** 一个必然在 setup 阶段抛错的页面组件 */
const BoomPage = defineComponent({
  name: 'BoomPage',
  setup() {
    throw new Error('接口结构变了')
  },
  render: () => h('div', 'never'),
})

/** 一个正常页面 */
const OkPage = defineComponent({
  name: 'OkPage',
  render: () => h('div', { class: 'ok-page' }, '正常内容'),
})

/** 插槽里放 router-view —— 与 App.vue 的真实结构一致（边界包住整个路由出口） */
const RouterHost = defineComponent({ name: 'RouterHost', render: () => h(RouterView) })

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: OkPage },
      { path: '/boom', component: BoomPage },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RouteErrorBoundary, {
    slots: { default: () => h(RouterHost) },
    global: { plugins: [router] },
  })
  await wrapper.vm.$nextTick()
  await wrapper.vm.$nextTick()
  return { wrapper, router }
}

describe('路由错误边界（v1.76.1）', () => {
  let spy: ReturnType<typeof vi.spyOn>
  beforeEach(() => { spy = vi.spyOn(console, 'error').mockImplementation(() => {}) })
  afterEach(() => { spy.mockRestore() })

  it('页面抛错时渲染兜底页，而不是留空白', async () => {
    const { wrapper } = await mountAt('/boom')
    const fallback = wrapper.find('[data-route-error]')
    expect(fallback.exists()).toBe(true)
    expect(fallback.text()).toContain('这个页面没能加载出来')
    // 兜底页必须给出可操作的自救入口
    expect(fallback.text()).toContain('刷新页面')
    expect(fallback.text()).toContain('回首页')
    // 并且把原始错误留下来（生产环境也要能看到）
    expect(fallback.text()).toContain('接口结构变了')
  })

  it('正常页面直接透传，不显示兜底页', async () => {
    const { wrapper } = await mountAt('/')
    expect(wrapper.find('[data-route-error]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正常内容')
  })

  it('切换路由后复位：一次失败不会粘住后续页面', async () => {
    const { wrapper, router } = await mountAt('/boom')
    expect(wrapper.find('[data-route-error]').exists()).toBe(true)

    await router.push('/')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-route-error]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正常内容')
  })
})

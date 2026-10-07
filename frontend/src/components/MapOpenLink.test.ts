import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MapOpenLink from './MapOpenLink.vue'

/**
 * 地图外链组件测试（v1.62.0）
 *
 * 验证「不可映射时整块不渲染」与「外链的安全属性齐备」——
 * 这两点在纯函数测试里覆盖不到（前者是渲染决策，后者是 DOM 属性）。
 */
describe('MapOpenLink', () => {
  it('地点可映射时渲染三家底图链接', () => {
    const wrapper = mount(MapOpenLink, { props: { keyword: '北京市海淀区中关村大街 1 号' } })
    const links = wrapper.findAll('a')
    expect(links).toHaveLength(3)
    expect(links.map((l) => l.text())).toEqual(['腾讯地图', '高德地图', '百度地图'])
  })

  it('链接指向白名单底图域名，且参数里带着地点', () => {
    const wrapper = mount(MapOpenLink, { props: { keyword: '深圳' } })
    const hrefs = wrapper.findAll('a').map((l) => l.attributes('href')!)
    expect(hrefs).toHaveLength(3)
    const hosts = hrefs.map((h) => new URL(h).host).sort()
    expect(hosts).toEqual(['api.map.baidu.com', 'apis.map.qq.com', 'uri.amap.com'])
    // 关键词确实进了 URL（编码后仍可解码回来）
    expect(decodeURIComponent(hrefs.join(' '))).toContain('深圳')
  })

  it('外链一律新窗口打开，并带 noopener noreferrer', () => {
    const wrapper = mount(MapOpenLink, { props: { keyword: '上海' } })
    for (const l of wrapper.findAll('a')) {
      expect(l.attributes('target')).toBe('_blank')
      expect(l.attributes('rel')).toContain('noopener')
      expect(l.attributes('rel')).toContain('noreferrer')
    }
  })

  it('线上/远程写法：整块不渲染（不给点了没结果的死链）', () => {
    for (const kw of ['视频面试', '线上笔试', 'Remote']) {
      const wrapper = mount(MapOpenLink, { props: { keyword: kw } })
      expect(wrapper.findAll('a'), `${kw} 不应渲染链接`).toHaveLength(0)
      expect(wrapper.find('.map-open').exists()).toBe(false)
    }
  })

  it('多地点串：整块不渲染（一个岗位对应多个城市时没有单一位置）', () => {
    const wrapper = mount(MapOpenLink, { props: { keyword: '杭州/北京/深圳' } })
    expect(wrapper.find('.map-open').exists()).toBe(false)
  })

  it('空地点：整块不渲染', () => {
    for (const kw of ['', '   ', null, undefined]) {
      const wrapper = mount(MapOpenLink, { props: { keyword: kw as string } })
      expect(wrapper.find('.map-open').exists()).toBe(false)
    }
  })

  it('每个链接有无障碍名称，指明在哪个地图里搜什么', () => {
    const wrapper = mount(MapOpenLink, { props: { keyword: '北京' } })
    const label = wrapper.findAll('a')[0].attributes('aria-label')!
    expect(label).toContain('腾讯地图')
    expect(label).toContain('北京')
  })
})

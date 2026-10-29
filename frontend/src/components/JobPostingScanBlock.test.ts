import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import JobPostingScanBlock from './JobPostingScanBlock.vue'
import { scanJobPosting, SCAN_DISCLAIMER, type ScanInput } from '../utils/jobPostingScan'

/**
 * 岗位内容体检块测试（v1.86.0）
 *
 * 纯函数部分的证据形态已由 `jobPostingScan.test.ts` 锁住，这里只验两条
 * **渲染决策**（单测覆盖不到、却直接决定用户怎么读）：
 *   1. clean 态也照常渲染「未命中规则 + 免责说明」—— 不能被当成「已验证」；
 *   2. 风险 / 留意各有视觉等级，且证据原文没有被吞掉。
 */

const CLEAN: ScanInput = {
  title: 'Java 后端工程师',
  companyName: '示例科技',
  description: '负责交易系统的服务端开发。',
  requirements: '熟悉 Java、Spring Boot。',
  salary: '15-25K',
  applyUrl: 'https://careers.example.com/jobs/1',
}

function mountWith(job: ScanInput) {
  return mount(JobPostingScanBlock, { props: { report: scanJobPosting(job) } })
}

describe('JobPostingScanBlock', () => {
  it('clean 态仍渲染免责说明（「没看到提示」≠「已验证」）', () => {
    const wrapper = mountWith(CLEAN)
    expect(wrapper.find('.scan-block').classes()).toContain('is-clean')
    expect(wrapper.text()).toContain('未命中规则')
    expect(wrapper.text()).toContain(SCAN_DISCLAIMER)
    expect(wrapper.findAll('.scan-item')).toHaveLength(0)
  })

  it('风险态：等级类名、条数与证据原文都渲染出来', () => {
    const wrapper = mountWith({ ...CLEAN, description: '入职需缴纳押金 500 元。' })
    expect(wrapper.find('.scan-block').classes()).toContain('is-risk')
    expect(wrapper.text()).toContain('需谨慎核实')
    const items = wrapper.findAll('.scan-item')
    expect(items).toHaveLength(1)
    expect(items[0].classes()).toContain('is-risk')
    expect(items[0].text()).toContain('要求求职者付费')
    expect(items[0].find('.scan-item-evidence').text()).toContain('押金')
  })

  it('留意态：只有 warn 信号时等级为 attention', () => {
    const wrapper = mountWith({ ...CLEAN, description: '有意者联系 13800138000。' })
    expect(wrapper.find('.scan-block').classes()).toContain('is-attention')
    expect(wrapper.text()).toContain('有几处要留意')
    expect(wrapper.find('.scan-item').classes()).toContain('is-warn')
  })

  it('多条信号全部渲染，顺序沿用报告（risk 在前）', () => {
    const wrapper = mountWith({
      ...CLEAN,
      description: '需缴纳押金。有意者联系 13800138000。',
    })
    const items = wrapper.findAll('.scan-item')
    expect(items.length).toBeGreaterThanOrEqual(2)
    expect(items[0].classes()).toContain('is-risk')
    expect(items[items.length - 1].classes()).toContain('is-warn')
  })

  it('组件不渲染任何投递 / 拦截按钮（只陈列，不替用户决策）', () => {
    const wrapper = mountWith({ ...CLEAN, description: '入职需缴纳押金 500 元。' })
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.findAll('a')).toHaveLength(0)
  })
})

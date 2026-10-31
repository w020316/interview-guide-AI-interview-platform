import { describe, it, expect, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

import ResumeIntakeSheet from './ResumeIntakeSheet.vue'
import {
  INTAKE_SHEET_NOTE,
  INTAKE_SHOT_CTA,
  INTAKE_SHOT_TITLE,
  RESUME_IMPORT_PLATFORMS,
  RESUME_PICK_SOURCES,
} from '../utils/resumeIntake'

/**
 * 取件向导（v1.90.1）。
 *
 * 真机问题：唤起 App 后回到浏览器没有任何后续。这里锁住面板的信息结构
 * 与事件契约；真实唤起/系统选择器由浏览器验证脚本覆盖。
 */

const BOSS = RESUME_IMPORT_PLATFORMS[0]

let wrapper: VueWrapper | null = null

function mountSheet(props: Record<string, unknown> = {}) {
  wrapper = mount(ResumeIntakeSheet, {
    attachTo: document.body,
    props: {
      open: true,
      phase: 'ready',
      platform: BOSS,
      handedOff: true,
      ...props,
    },
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
  document.body.style.overflow = ''
})

describe('ResumeIntakeSheet · 取件向导', () => {
  it('以对话框渲染平台步骤与位置选择，并说明网页的取件边界', async () => {
    mountSheet()
    await flushPromises()
    const dialog = document.querySelector('.ri-sheet')
    expect(dialog).not.toBeNull()
    expect(dialog!.getAttribute('role')).toBe('dialog')
    expect(dialog!.getAttribute('aria-modal')).toBe('true')
    expect(dialog!.textContent).toContain('BOSS直聘')
    expect(dialog!.querySelectorAll('.ri-step')).toHaveLength(3)
    expect(dialog!.querySelectorAll('.ri-source')).toHaveLength(RESUME_PICK_SOURCES.length)
    expect(dialog!.textContent).toContain('手机文件 · 下载')
    expect(dialog!.textContent).toContain(INTAKE_SHEET_NOTE)
    expect(document.body.style.overflow).toBe('hidden')
  })

  it('截图提取是主路径：主按钮标注「不用下载保存」并抛出 pick-image', async () => {
    const wrapperRef = mountSheet()
    await flushPromises()
    const primary = document.querySelector('.ri-shot-btn') as HTMLButtonElement
    expect(primary).not.toBeNull()
    expect(primary.textContent).toContain(INTAKE_SHOT_CTA)
    expect(document.querySelector('.ri-shot')!.textContent).toContain(INTAKE_SHOT_TITLE)

    primary.click()
    await wrapperRef.vm.$nextTick()
    expect(wrapperRef.emitted('pick-image')).toHaveLength(1)
  })

  it('唤起中显示「正在尝试打开」，就绪且已交棒时提示切回', async () => {
    const wrapperRef = mountSheet({ phase: 'launching', handedOff: false })
    expect(document.querySelector('.ri-title')!.textContent).toContain('正在尝试打开「BOSS直聘」')

    await wrapperRef.setProps({ phase: 'ready', handedOff: true })
    expect(document.querySelector('.ri-title')!.textContent).toBe('从「BOSS直聘」提取简历')
    expect(document.querySelector('.ri-lead')!.textContent).toContain('切回来了')
  })

  it('点位置行抛出 pick-file（带所选位置）', async () => {
    const wrapperRef = mountSheet()
    ;(document.querySelectorAll('.ri-source')[1] as HTMLButtonElement).click()
    await wrapperRef.vm.$nextTick()

    const events = wrapperRef.emitted('pick-file')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toMatchObject({ key: 'files' })
  })

  it('截图 / 重试 / 网页版 / 关闭各自抛出事件', async () => {
    const wrapperRef = mountSheet()
    ;(document.querySelector('.ri-shot-btn') as HTMLButtonElement).click()
    const footButtons = Array.from(
      document.querySelectorAll('.ri-foot-btn'),
    ) as HTMLButtonElement[]
    expect(footButtons).toHaveLength(2)
    footButtons[0].click()
    footButtons[1].click()
    ;(document.querySelector('.ri-close') as HTMLButtonElement).click()
    await wrapperRef.vm.$nextTick()

    expect(wrapperRef.emitted('pick-image')).toHaveLength(1)
    expect(wrapperRef.emitted('retry')).toHaveLength(1)
    expect(wrapperRef.emitted('open-web')).toHaveLength(1)
    expect(wrapperRef.emitted('close')).toHaveLength(1)
  })

  it('open=false 不渲染；打开后 Esc 关闭并恢复页面滚动', async () => {
    const wrapperRef = mountSheet({ open: false })
    expect(document.querySelector('.ri-sheet')).toBeNull()

    await wrapperRef.setProps({ open: true })
    expect(document.querySelector('.ri-sheet')).not.toBeNull()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapperRef.emitted('close')).toHaveLength(1)

    await wrapperRef.setProps({ open: false })
    expect(document.body.style.overflow).toBe('')
  })
})

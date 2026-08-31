import { describe, it, expect } from 'vitest'
import { renderMarkdown, normalizeMarkdown } from './markdown'

describe('normalizeMarkdown', () => {
  it('标题缺空格自动补全：##虚拟DOM → ## 虚拟DOM', () => {
    expect(normalizeMarkdown('##虚拟DOM内容')).toContain('## 虚拟DOM内容')
  })

  it('标题 / 列表 / 块前补空行，避免整段粘连', () => {
    const src = '背景说明。\n### 核心差异\n- 双向绑定\n- 单向数据流\n```js\nconst a=1\n```'
    const out = normalizeMarkdown(src)
    // 标题前出现空行（原文件只有一个换行）
    expect(out).toContain('背景说明。\n\n### 核心差异')
    // 无序列表项与第二个项之间被补空行（宽松列表）
    expect(out).toContain('- 双向绑定\n\n- 单向数据流')
    // 代码块前补空行
    expect(out).toContain('\n\n```js')
  })

  it('标题缺空格 + 无空行混合输入能被正确渲染为块级元素', () => {
    const html = renderMarkdown('##虚拟DOM虚拟DOM是真实DOM的JS对象表示。')
    expect(html).toContain('<h2>')
    expect(html).not.toContain('##虚拟DOM')
  })

  it('顶格无序列表缺失空行时仍渲染为列表项', () => {
    const html = renderMarkdown('注意事项：\n- 先备份\n- 再修改')
    expect(html).toContain('<ul>')
    expect(html).toMatch(/<li[^>]*>.*先备份.*<\/li>/s)
  })

  it('顶格有序列表缺失空行时仍渲染为列表项', () => {
    const html = renderMarkdown('步骤：\n1. 打开\n2. 保存')
    expect(html).toContain('<ol>')
    expect(html).toMatch(/<li[^>]*>.*打开.*<\/li>/s)
  })

  it('空输入返回空字符串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null as unknown as string)).toBe('')
  })
})

describe('renderMarkdown 安全', () => {
  it('剥离 script 标签，XSS 向量不会成为可点击链接', () => {
    const html = renderMarkdown('<script>alert(1)</script>正常内容')
    expect(html).not.toContain('<script>')
    // 内容仍在，script 标签被 HTML 实体化
    expect(html).toContain('alert(1)')
    expect(html).toContain('正常内容')
  })

  it('渲染标题 / 段落 / 内联代码', () => {
    const html = renderMarkdown('# 主题\n\n一段`code`文本')
    expect(html).toContain('<h1>主题</h1>')
    expect(html).toContain('<code>code</code>')
  })
})
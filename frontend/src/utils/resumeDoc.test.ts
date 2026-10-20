import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  RESUME_TEMPLATES,
  FALLBACK_TEMPLATE_ID,
  findTemplate,
  emptyResumeDoc,
  normalizeResumeDoc,
  hasSection,
  visibleSections,
  toMarkdown,
  isDocEmpty,
  type ResumeDoc,
} from './resumeDoc'

/**
 * 结构化简历数据模型测试（v1.79.0）
 *
 * 这里锁的是**跨端一致性与数据完整性**，不是渲染细节：
 * 模板目录前后端各有一份（后端下发元数据、前端负责版式实现），
 * 两边靠人工同步迟早会漂移，所以用一条测试直接比对后端源文件的模板 ID 列表。
 * 这是项目「凡要长期守住的约定，必须落成可执行门禁」的直接应用。
 */

/** 后端模板目录源文件（相对 frontend 工作目录） */
const BACKEND_CATALOG = path.resolve(
  process.cwd(),
  '../backend/src/main/java/com/example/interview/service/resume/ResumeTemplateCatalog.java'
)

describe('模板目录', () => {
  it('模板数量为 10 套', () => {
    expect(RESUME_TEMPLATES).toHaveLength(10)
  })

  it('模板 ID 与样式类均唯一（重复会让渲染落到错误版式）', () => {
    const ids = RESUME_TEMPLATES.map((t) => t.id)
    const classes = RESUME_TEMPLATES.map((t) => t.cssClass)
    expect(new Set(ids).size).toBe(ids.length)
    expect(new Set(classes).size).toBe(classes.length)
  })

  it('只有一套标记为推荐', () => {
    expect(RESUME_TEMPLATES.filter((t) => t.recommended)).toHaveLength(1)
  })

  it('每套模板覆盖 6 个区块，且区块标识合法', () => {
    const valid = new Set(['summary', 'experience', 'projects', 'education', 'skills', 'extras'])
    for (const t of RESUME_TEMPLATES) {
      expect(t.sections, `${t.id} 区块数不对`).toHaveLength(6)
      for (const s of t.sections) {
        expect(valid.has(s), `${t.id} 含非法区块 ${s}`).toBe(true)
      }
    }
  })

  it('每套模板都有非空的适用场景说明', () => {
    for (const t of RESUME_TEMPLATES) {
      expect(t.name.trim(), `${t.id} 缺名称`).not.toBe('')
      expect(t.scene.trim(), `${t.id} 缺场景`).not.toBe('')
      expect(t.description.trim(), `${t.id} 缺说明`).not.toBe('')
    }
  })

  const catalogExists = fs.existsSync(BACKEND_CATALOG)

  it.skipIf(!catalogExists)('与后端 ResumeTemplateCatalog 的模板 ID 完全一致', () => {
    const java = fs.readFileSync(BACKEND_CATALOG, 'utf8')
    // 后端写法：new Template(\n "template-id",\n ...)
    const backendIds = [...java.matchAll(/new\s+Template\(\s*"([a-z0-9-]+)"/g)].map((m) => m[1])

    expect(backendIds.length, '后端模板目录解析为空，正则可能已失效').toBeGreaterThan(0)
    expect([...backendIds].sort()).toEqual([...RESUME_TEMPLATES.map((t) => t.id)].sort())
  })

  it('未知 / 空 ID 回落到默认模板，不抛错', () => {
    expect(findTemplate('no-such-template').id).toBe(FALLBACK_TEMPLATE_ID)
    expect(findTemplate(null).id).toBe(FALLBACK_TEMPLATE_ID)
    expect(findTemplate(undefined).id).toBe(FALLBACK_TEMPLATE_ID)
  })

  it('默认模板是 ATS 最友好的一套（单栏）', () => {
    const fallback = findTemplate(FALLBACK_TEMPLATE_ID)
    expect(fallback.layout).toBe('one-column')
    expect(fallback.atsLevel).toBe('HIGH')
  })
})

describe('hasSection 边界（QA 回归：内容不能因判定过窄而被整段隐藏）', () => {
  const base = (): ResumeDoc => emptyResumeDoc()

  it('经历只有职位、没填公司时，整段仍然显示', () => {
    const d = base()
    d.experience.push({ org: '', role: '后端开发', start: '', end: '', location: '', bullets: [] })
    expect(hasSection(d, 'experience')).toBe(true)
  })

  it('教育只有学历、没填学校时，整段仍然显示', () => {
    const d = base()
    d.education.push({ school: '', degree: '本科', major: '', start: '', end: '', gpa: '', highlights: [] })
    expect(hasSection(d, 'education')).toBe(true)
  })

  it('全空条目对象不触发整段渲染（不留空标题）', () => {
    const d = base()
    d.experience.push({ org: '', role: '', start: '', end: '', location: '', bullets: [] })
    expect(hasSection(d, 'experience')).toBe(false)
  })

  it('空简历的可见区块为空', () => {
    expect(visibleSections(base(), findTemplate(FALLBACK_TEMPLATE_ID))).toEqual([])
    expect(isDocEmpty(base())).toBe(true)
  })

  it('应届模板把教育排在经历前面', () => {
    const t = findTemplate('campus-single')
    expect(t.sections.indexOf('education')).toBeLessThan(t.sections.indexOf('experience'))
  })

  it('学术模板把教育排在最前', () => {
    const t = findTemplate('academic-cv')
    expect(t.sections[0]).toBe('education')
  })
})

describe('normalizeResumeDoc', () => {
  it('非法输入返回空骨架而不是抛错', () => {
    for (const raw of [null, undefined, 42, 'string', []]) {
      const d = normalizeResumeDoc(raw)
      expect(d.basics.links).toEqual([])
      expect(d.experience).toEqual([])
      expect(isDocEmpty(d)).toBe(true)
    }
  })

  it('残缺字段被补齐为安全默认值', () => {
    const d = normalizeResumeDoc({ basics: { name: '张三' }, experience: [{ org: 'A' }] })
    expect(d.basics.name).toBe('张三')
    expect(d.basics.title).toBe('')
    expect(d.basics.links).toEqual([])
    expect(d.experience[0].bullets).toEqual([])
    expect(d.skills).toEqual([])
  })

  it('非字符串字段不会污染结果（防后端脏数据）', () => {
    const d = normalizeResumeDoc({ basics: { name: 123 }, summary: { a: 1 } })
    expect(d.basics.name).toBe('123')
    expect(d.summary).toBe('')
  })
})

describe('toMarkdown', () => {
  it('输出与模板无关：不含任何模板名或版式信息', () => {
    const d = normalizeResumeDoc({
      basics: { name: '张三', title: '后端开发' },
      summary: '三年经验',
      experience: [{ org: '某某科技', role: '后端', start: '2024.07', end: '至今', bullets: ['主导重构'] }],
    })
    const md = toMarkdown(d)
    expect(md).toContain('张三')
    expect(md).toContain('主导重构')
    // 模板只影响版式，不该出现在内容导出里
    for (const t of RESUME_TEMPLATES) {
      expect(md, `导出内容不应包含模板名「${t.name}」`).not.toContain(t.name)
    }
  })

  it('同一份数据多次导出结果一致（逐字节）', () => {
    const d = normalizeResumeDoc({ basics: { name: '张三' }, summary: 'x' })
    expect(toMarkdown(d)).toBe(toMarkdown(d))
  })

  it('空简历导出不崩，且不产生空标题', () => {
    const md = toMarkdown(emptyResumeDoc())
    expect(typeof md).toBe('string')
    expect(md).not.toContain('## 工作经历')
    expect(md).not.toContain('## 教育背景')
  })

  it('缺少结束时间时不留孤立分隔符', () => {
    const d = normalizeResumeDoc({
      basics: { name: '张三' },
      experience: [{ org: 'A', role: 'B', start: '2024.07', end: '', bullets: [] }],
    })
    const md = toMarkdown(d)
    expect(md).toContain('（2024.07）')
    expect(md).not.toContain('2024.07 - ')
  })
})

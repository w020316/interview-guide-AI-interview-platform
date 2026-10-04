import { describe, it, expect } from 'vitest'
import {
  MAX_IMPORT_ROWS,
  parseDelimited,
  detectDelimiter,
  detectTable,
  parseTextTable,
  autoMapColumns,
  buildImportRows,
  looksLikeHeader,
  fileKind,
  sourceLabel,
  IMPORT_FIELDS,
  type ParsedTable,
  type ColumnMapping,
} from './tableImport'

/**
 * 表格导入解析（第三批 F）单测。
 *
 * 覆盖：RFC4180（引号/转义/CRLF/BOM）、分隔符嗅探、表头识别、列映射、行归一化与 200 行上限。
 */
describe('parseDelimited（RFC4180）', () => {
  it('解析基础 CSV（含末尾换行）', () => {
    expect(parseDelimited('a,b,c\n1,2,3\n', ',')).toEqual([
      ['a', 'b', 'c'],
      ['1', '2', '3'],
    ])
  })

  it('引号包裹字段内的逗号不被拆分，双写引号还原为单个引号', () => {
    const rows = parseDelimited('name,note\n"Acme, Inc.","He said ""hi"""\n', ',')
    expect(rows).toEqual([
      ['name', 'note'],
      ['Acme, Inc.', 'He said "hi"'],
    ])
  })

  it('兼容 CRLF 与 CR 换行', () => {
    expect(parseDelimited('a,b\r\n1,2\r\n', ',')).toEqual([['a', 'b'], ['1', '2']])
    expect(parseDelimited('a,b\r1,2', ',')).toEqual([['a', 'b'], ['1', '2']])
  })

  it('支持 TSV 制表符分隔', () => {
    expect(parseDelimited('a\tb\n1\t2', '\t')).toEqual([['a', 'b'], ['1', '2']])
  })

  it('去除 UTF-8 BOM', () => {
    expect(parseDelimited('\ufeff公司,岗位\nA,B', ',')).toEqual([['公司', '岗位'], ['A', 'B']])
  })
})

describe('detectDelimiter / fileKind', () => {
  it('按扩展名优先决定分隔符', () => {
    expect(detectDelimiter('x.tsv', 'a,b')).toBe('\t')
    expect(detectDelimiter('x.csv', 'a\tb')).toBe(',')
  })

  it('未知扩展名时按首行分隔符数量嗅探', () => {
    expect(detectDelimiter('x.txt', 'a\tb\tc')).toBe('\t')
    expect(detectDelimiter('x.txt', 'a,b,c')).toBe(',')
    expect(detectDelimiter('x.txt', 'a,b\tc')).toBe(',')
  })

  it('fileKind 大小写不敏感', () => {
    expect(fileKind('A.CSV')).toBe('csv')
    expect(fileKind('a.tsv')).toBe('tsv')
    expect(fileKind('a.xlsx')).toBe('xlsx')
    expect(fileKind('a.xls')).toBe('xls')
    expect(fileKind('a.txt')).toBe('unknown')
  })

  it('sourceLabel 映射为后端可回显标签', () => {
    expect(sourceLabel('csv')).toBe('csv')
    expect(sourceLabel('tsv')).toBe('tsv')
    expect(sourceLabel('xlsx')).toBe('excel')
    expect(sourceLabel('xls')).toBe('excel')
    expect(sourceLabel('unknown')).toBe('paste')
  })
})

describe('detectTable 表头识别', () => {
  it('命中 ≥2 个字段别名时判定为表头', () => {
    const grid = [
      ['公司', '岗位', '地点', '薪资'],
      ['字节跳动', '后端工程师', '北京', '30k'],
    ]
    const t = detectTable(grid)
    expect(t.hasHeader).toBe(true)
    expect(t.headers).toEqual(['公司', '岗位', '地点', '薪资'])
    expect(t.rows).toHaveLength(2)
    expect(autoMapColumns(t.headers)).toEqual({ companyName: 0, title: 1, location: 2, salary: 3 })
  })

  it('无表头时生成「第 N 列」标题且 hasHeader=false', () => {
    const t = detectTable([
      ['字节跳动', '后端工程师'],
      ['腾讯', '前端工程师'],
    ])
    expect(t.hasHeader).toBe(false)
    expect(t.headers).toEqual(['第 1 列', '第 2 列'])
  })

  it('过滤整行空白，空表返回空结构', () => {
    expect(detectTable([['', ' '], ['A', 'B']]).rows).toEqual([['A', 'B']])
    expect(detectTable([['', '']])).toEqual({ headers: [], rows: [], hasHeader: false })
  })

  it('looksLikeHeader 需要至少两列命中别名', () => {
    expect(looksLikeHeader(['公司'])).toBe(false)
    expect(looksLikeHeader(['公司', '岗位'])).toBe(true)
  })
})

describe('autoMapColumns', () => {
  it('识别英文表头（含"公司/职位/链接/备注"别名）', () => {
    const mapping = autoMapColumns(['Company', 'Position', 'Location', 'Apply URL', 'Note'])
    expect(mapping).toEqual({ companyName: 0, title: 1, location: 2, applyUrl: 3, note: 4 })
  })

  it('未出现的字段不产生映射', () => {
    const mapping = autoMapColumns(['公司', '岗位'])
    expect(mapping.companyName).toBe(0)
    expect(mapping.title).toBe(1)
    expect(mapping.salary).toBeUndefined()
    expect(mapping.deadline).toBeUndefined()
  })
})

describe('buildImportRows 行归一化', () => {
  it('按映射产出后端字段结构，未映射列忽略', () => {
    const table = detectTable([
      ['公司', '岗位', '地点', '链接', '备注'],
      ['字节跳动', '后端工程师', '北京', 'https://a.com', '内推'],
    ])
    const mapping = autoMapColumns(table.headers)
    const { rows } = buildImportRows(table, mapping)
    expect(rows).toEqual([
      {
        companyName: '字节跳动',
        title: '后端工程师',
        location: '北京',
        applyUrl: 'https://a.com',
        note: '内推',
      },
    ])
  })

  it('映射列全空的整行被计入 blankSkipped；缺少必填字段的行仍产出（交后端判 ERROR）', () => {
    const table: ParsedTable = {
      headers: ['公司', '岗位', '地点'],
      hasHeader: true,
      rows: [
        ['公司', '岗位', '地点'],
        ['字节跳动', '后端工程师', ''],
        ['', '', '北京'], // 有内容但都在未映射列 → blankSkipped
        ['', '前端工程师', ''], // 缺公司 → 仍产出，后端判 ERROR
      ],
    }
    const mapping: ColumnMapping = { companyName: 0, title: 1 }
    const { rows, blankSkipped } = buildImportRows(table, mapping)
    expect(blankSkipped).toBe(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toEqual({ companyName: '字节跳动', title: '后端工程师' })
    expect(rows[1]).toEqual({ companyName: '', title: '前端工程师' })
  })

  it(`超过 ${MAX_IMPORT_ROWS} 行时截断并置 truncated`, () => {
    const header = ['公司', '岗位']
    const data = Array.from({ length: MAX_IMPORT_ROWS + 5 }, (_, i) => [`公司${i}`, `岗位${i}`])
    const table = detectTable([header, ...data])
    const mapping: ColumnMapping = { companyName: 0, title: 1 }
    const { rows, truncated } = buildImportRows(table, mapping)
    expect(truncated).toBe(true)
    expect(rows).toHaveLength(MAX_IMPORT_ROWS)
  })

  it('无表头时首行即数据行', () => {
    const table = detectTable([['字节跳动', '后端工程师']])
    expect(table.hasHeader).toBe(false)
    const mapping: ColumnMapping = { companyName: 0, title: 1 }
    const { rows } = buildImportRows(table, mapping)
    expect(rows).toEqual([{ companyName: '字节跳动', title: '后端工程师' }])
  })
})

describe('parseTextTable 端到端', () => {
  it('CSV 文本 → 表格 → 自动映射', () => {
    const table = parseTextTable('公司,岗位\n字节跳动,后端工程师', 'jobs.csv')
    expect(table.hasHeader).toBe(true)
    expect(table.headers).toEqual(['公司', '岗位'])
    expect(autoMapColumns(table.headers)).toEqual({ companyName: 0, title: 1 })
  })

  it('字段定义：公司与岗位为必填', () => {
    const required = IMPORT_FIELDS.filter((f) => f.required).map((f) => f.field)
    expect(required).toEqual(['companyName', 'title'])
  })
})

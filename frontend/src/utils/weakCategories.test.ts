import { describe, it, expect } from 'vitest'
import { extractWeakCategories, mostFrequentJob, type WeakSource } from './weakCategories'

interface Item extends WeakSource {
  id: number
}

function makeItem(over: Partial<Item> & { id: number }): Item {
  return { id: over.id, category: over.category, evaluationScore: over.evaluationScore, jobDescription: over.jobDescription }
}

describe('extractWeakCategories', () => {
  it('按平均分升序挑选最薄弱分类', () => {
    const items: Item[] = [
      makeItem({ id: 1, category: '算法', evaluationScore: 40 }),
      makeItem({ id: 2, category: '算法', evaluationScore: 50 }),
      makeItem({ id: 3, category: '项目深挖', evaluationScore: 60 }),
      makeItem({ id: 4, category: '行为面试', evaluationScore: 80 }),
    ]
    // 算法均分 45 最弱，项目深挖 60 次之，行为面试 80 最强
    expect(extractWeakCategories(items)).toEqual(['算法', '项目深挖', '行为面试'])
  })

  it('受 max 限制，只返回前 N 个', () => {
    const items: Item[] = [
      makeItem({ id: 1, category: 'A', evaluationScore: 30 }),
      makeItem({ id: 2, category: 'B', evaluationScore: 40 }),
      makeItem({ id: 3, category: 'C', evaluationScore: 50 }),
      makeItem({ id: 4, category: 'D', evaluationScore: 60 }),
    ]
    expect(extractWeakCategories(items, 2)).toEqual(['A', 'B'])
  })

  it('忽略缺失分类的题目', () => {
    const items: Item[] = [
      makeItem({ id: 1, category: undefined }),
      makeItem({ id: 2, category: null }),
      makeItem({ id: 3, category: '场景设计', evaluationScore: 55 }),
    ]
    expect(extractWeakCategories(items)).toEqual(['场景设计'])
  })

  it('分类存在但无得分时按 0 处理（排最前）', () => {
    const items: Item[] = [
      makeItem({ id: 1, category: '项目深挖', evaluationScore: 70 }),
      makeItem({ id: 2, category: '基础', evaluationScore: null }),
    ]
    expect(extractWeakCategories(items)).toEqual(['基础', '项目深挖'])
  })

  it('空列表或全是无效项时返回空数组', () => {
    expect(extractWeakCategories([])).toEqual([])
    expect(extractWeakCategories([makeItem({ id: 1, category: null }), makeItem({ id: 2, category: '' })])).toEqual([])
  })

  it('同分类多题计数正确，无分类题目不影响结果', () => {
    const items: Item[] = [
      makeItem({ id: 1, category: '算法', evaluationScore: 60 }),
      makeItem({ id: 2, category: '算法', evaluationScore: 80 }),
      makeItem({ id: 3, category: undefined }),
      makeItem({ id: 4, category: '算法', evaluationScore: 100 }),
    ]
    // 算法均分 (60+80+100)/3 = 80
    expect(extractWeakCategories(items)).toEqual(['算法'])
  })
})

describe('mostFrequentJob', () => {
  it('返回出现频次最高的岗位', () => {
    const items: Item[] = [
      makeItem({ id: 1, jobDescription: 'Java 后端' }),
      makeItem({ id: 2, jobDescription: 'Java 后端' }),
      makeItem({ id: 3, jobDescription: '前端' }),
    ]
    expect(mostFrequentJob(items)).toBe('Java 后端')
  })

  it('空白岗位信息被忽略', () => {
    const items: Item[] = [
      makeItem({ id: 1, jobDescription: null }),
      makeItem({ id: 2, jobDescription: '   ' }),
      makeItem({ id: 3, jobDescription: '产品经理' }),
    ]
    expect(mostFrequentJob(items)).toBe('产品经理')
  })

  it('全部无岗位时返回空串', () => {
    expect(mostFrequentJob([])).toBe('')
    expect(mostFrequentJob([makeItem({ id: 1, jobDescription: undefined })])).toBe('')
  })

  it('频次相同时取先出现者（稳定）', () => {
    const items: Item[] = [
      makeItem({ id: 1, jobDescription: 'A' }),
      makeItem({ id: 2, jobDescription: 'B' }),
    ]
    expect(mostFrequentJob(items)).toBe('A')
  })

  it('岗位前后空格被裁剪后参与统计', () => {
    const items: Item[] = [
      makeItem({ id: 1, jobDescription: ' 教师 ' }),
      makeItem({ id: 2, jobDescription: '教师' }),
    ]
    expect(mostFrequentJob(items)).toBe('教师')
  })
})
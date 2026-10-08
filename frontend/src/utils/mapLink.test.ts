import { describe, it, expect } from 'vitest'
import {
  MAP_CALLER,
  MAP_PROVIDERS,
  buildMapSearchUrl,
  isMappableLocation,
  normalizePlaceText,
  type MapProvider,
} from './mapLink'

/**
 * 地图外链测试（v1.62.0）
 *
 * 重点锁三件事：
 * 1. 三家 URL 的**主机名与参数名**正确（这是唯一会「静默失效」的地方——参数名写错不会报错，
 *    只会让地图站点打开一个空白搜索页）；
 * 2. 中文关键词正确编码（不是原样拼接）；
 * 3. **不可映射的地点不产出链接**——包括线上/远程写法与多地点串。
 */

/** 取 URL 的参数表，避免依赖具体的百分号编码细节 */
function paramsOf(url: string): URLSearchParams {
  return new URL(url).searchParams
}

describe('normalizePlaceText', () => {
  it('去掉首尾空白并把内部连续空白压成一个空格', () => {
    expect(normalizePlaceText('  北京  市朝阳区  ')).toBe('北京 市朝阳区')
  })

  it('空值与 null 安全', () => {
    expect(normalizePlaceText(null)).toBe('')
    expect(normalizePlaceText(undefined)).toBe('')
    expect(normalizePlaceText('   ')).toBe('')
  })
})

describe('isMappableLocation', () => {
  it('真实地址与单城市可以上图', () => {
    expect(isMappableLocation('北京市海淀区中关村大街 1 号')).toBe(true)
    expect(isMappableLocation('深圳')).toBe(true)
    expect(isMappableLocation('上海市浦东新区世纪大道 100 号')).toBe(true)
  })

  it('空值不上图', () => {
    expect(isMappableLocation('')).toBe(false)
    expect(isMappableLocation('   ')).toBe(false)
    expect(isMappableLocation(null)).toBe(false)
    expect(isMappableLocation(undefined)).toBe(false)
  })

  it('线上/远程写法不上图（不给点了没结果的死链）', () => {
    for (const t of ['视频面试', '线上笔试', '远程面试', '电话面试', '腾讯会议', 'Zoom', 'Remote', '待定', '另行通知']) {
      expect(isMappableLocation(t), `不应上图: ${t}`).toBe(false)
    }
  })

  it('多地点串不上图——一个岗位对应多个城市时没有单一位置', () => {
    // 本项目的国内种子岗位大量是这种写法，钉任意一个城市都是错的
    for (const t of ['杭州/北京/深圳', '深圳/北京/上海/广州', '北京、上海', '西安,南京,深圳', '全国各省市', '各省市分公司']) {
      expect(isMappableLocation(t), `多地点不应上图: ${t}`).toBe(false)
    }
  })

  it('不因拦截过宽而误杀真实地址', () => {
    // 「全国」不整体拦截：这些是真实地址，必须仍能上图
    expect(isMappableLocation('北京市全国农业展览馆')).toBe(true)
    expect(isMappableLocation('全国人大会议中心')).toBe(true)
  })

  it('泛化表述不上图（独立验证用真实种子数据复核后补漏）', () => {
    // 以下取值全部来自 backend 的 8 个国内种子源真实数据；
    // 它们不是一个可导航的地点，放行会让用户点开看到空结果。
    for (const t of [
      '全国主要城市', '全国各省', '全国门店', '全国各县域网点', '全国各城市',
      '全国高校', '全国重点城市', '全国各地', '广东省各地市', '深圳各区',
      '西部各省区基层', '各省乡镇基层',
      // 全角括号括注的多地点（不含分隔符，需单独一条词）
      '深圳（另招济南）',
      // 区域/国家级泛化（`欧洲` 有确凿来源：ArbeitnowJobProvider 非远程分支返回该值）
      '欧洲', 'USA Only', 'EMEA', 'Global',
    ]) {
      expect(isMappableLocation(t), `泛化地点不应上图: ${t}`).toBe(false)
    }
  })

  it('带全角括号的真实地址仍能上图（不把括号本身当分隔符）', () => {
    // 真实地址里的括注是正常写法，不能因为「另招」那条词而连带把括号也拦掉
    expect(isMappableLocation('北京市朝阳区建国路 88 号（国贸）')).toBe(true)
  })

  it('真实单城市仍能上图（收紧词表后的回归）', () => {
    for (const t of ['深圳', '上海', '北京', '广州', '长沙', '厦门']) {
      expect(isMappableLocation(t), `真实单城市应上图: ${t}`).toBe(true)
    }
  })

  it('含「集团」的真实地点仍能上图 —— 这是「不收录裸词『集团』」的取证依据', () => {
    // 背景：残余假阳性里有「全国铁路局集团公司」，看似补一个「集团」就能清掉。
    // 但种子数据里这三个是**真实单点地点**，加裸词「集团」会把它们一起误杀：
    // 用 1 条残余换 3 个真实地点被隐藏，收益远小于代价。
    // 这条测试把该决定锁住，防止后人「顺手补个集团」。
    for (const t of ['上海电影集团', '中国出版集团', '中国建材集团']) {
      expect(isMappableLocation(t), `真实地点被误杀: ${t}`).toBe(true)
    }
  })
})

describe('buildMapSearchUrl', () => {
  it('腾讯：主机、路径与参数名正确，referer 必填', () => {
    const url = buildMapSearchUrl('tencent', '北京故宫')
    expect(url).not.toBeNull()
    const u = new URL(url!)
    expect(u.host).toBe('apis.map.qq.com')
    expect(u.pathname).toBe('/uri/v1/search')
    const p = paramsOf(url!)
    expect(p.get('keyword')).toBe('北京故宫')
    expect(p.get('referer')).toBe(MAP_CALLER)
  })

  it('高德：主机、路径与参数名正确，keyword 必填', () => {
    const url = buildMapSearchUrl('amap', '上海市浦东新区')
    expect(url).not.toBeNull()
    const u = new URL(url!)
    expect(u.host).toBe('uri.amap.com')
    expect(u.pathname).toBe('/search')
    const p = paramsOf(url!)
    expect(p.get('keyword')).toBe('上海市浦东新区')
    expect(p.get('src')).toBe(MAP_CALLER)
  })

  it('百度：走 geocoder 的 address 参数（不是 keyword），并带 output=html', () => {
    const url = buildMapSearchUrl('baidu', '深圳市南山区')
    expect(url).not.toBeNull()
    const u = new URL(url!)
    expect(u.host).toBe('api.map.baidu.com')
    expect(u.pathname).toBe('/geocoder')
    const p = paramsOf(url!)
    // ⚠️ 百度是 address 而不是 keyword——写错会打开一个空搜索页
    expect(p.get('address')).toBe('深圳市南山区')
    expect(p.get('keyword')).toBeNull()
    expect(p.get('output')).toBe('html')
    expect(p.get('src')).toBe(`webapp.${MAP_CALLER}`)
  })

  it('中文关键词被正确编码（不是原样拼接）', () => {
    const url = buildMapSearchUrl('tencent', '北京故宫')
    expect(url).toMatch(/%E5%8C%97%E4%BA%AC/)
    expect(url).not.toContain('北京故宫')
  })

  it('三家都只使用白名单内底图域名', () => {
    const allowed = new Set(['apis.map.qq.com', 'uri.amap.com', 'api.map.baidu.com'])
    for (const p of MAP_PROVIDERS) {
      const url = buildMapSearchUrl(p.id, '北京')
      expect(url).not.toBeNull()
      expect(allowed.has(new URL(url!).host), `${p.id} 域名不在白名单`).toBe(true)
    }
  })

  it('不可映射的地点一律返回 null（不产出死链）', () => {
    for (const t of ['', '   ', null, undefined, '视频面试', 'Remote', '杭州/北京/深圳']) {
      for (const p of MAP_PROVIDERS) {
        expect(buildMapSearchUrl(p.id, t as string), `${p.id} / ${String(t)} 不应产出链接`).toBeNull()
      }
    }
  })

  it('三家都给得出链接（避免某个 provider 被漏写）', () => {
    const ids: MapProvider[] = ['tencent', 'amap', 'baidu']
    for (const id of ids) {
      expect(buildMapSearchUrl(id, '北京'), `${id} 未产出链接`).not.toBeNull()
    }
    expect(MAP_PROVIDERS.map((p) => p.id).sort()).toEqual([...ids].sort())
  })
})

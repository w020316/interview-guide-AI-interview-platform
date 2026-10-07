/**
 * 地图外链（v1.62.0）
 *
 * ── 为什么是「外链」而不是内嵌地图 ────────────────────────────────────
 * 本项目的岗位地点是**自由文本**（`job_posting.location`，如「杭州/北京/深圳」「Remote」），
 * 全表没有任何经纬度字段；面试地点同样是用户手填的文本（如「视频面试 / 公司地址」）。
 * 没有坐标，内嵌地图就只能画一个空壳，打点更是无从谈起——
 * 一个岗位写着「深圳/北京/上海/广州」，钉哪个点都是错的。
 *
 * 所以这里只做一件事：把**文本**交给地图站点去搜。零新增依赖、零 Key、零坐标存储。
 *
 * ── 为什么不需要 Key ────────────────────────────────────────────────
 * 三家用的都是「地图调起（URI API）」而非 JS SDK：
 * - 腾讯 `apis.map.qq.com/uri/v1/search`：`keyword` 与 `referer` 必填，`referer` 填应用名即可
 * - 高德 `uri.amap.com/search`：`keyword` 必填，`src` 建议填
 * - 百度 `api.map.baidu.com/geocoder`：官方文档明确「无需申请 ak」
 * 详见 skill `cn-map-deeplink`。
 *
 * ── 合规 ────────────────────────────────────────────────────────────
 * 只用白名单内底图（腾讯 / 高德 / 百度 / 天地图）。**严禁** Google Maps、Apple Maps、
 * Bing 海外版、Mapbox、Leaflet + OpenStreetMap 直连海外瓦片。
 */

export type MapProvider = 'tencent' | 'amap' | 'baidu'

export interface MapProviderMeta {
  id: MapProvider
  label: string
}

/**
 * 调用来源标识。
 * 腾讯 URI API 的 `referer` 官方说明为「一般为您的应用名称」，此处即填应用名；
 * 高德 `src`、百度 `src` 也复用同一个值，便于三家在地图侧识别来源。
 */
export const MAP_CALLER = 'OfferGo'

/** 可选的底图（全部在白名单内） */
export const MAP_PROVIDERS: MapProviderMeta[] = [
  { id: 'tencent', label: '腾讯地图' },
  { id: 'amap', label: '高德地图' },
  { id: 'baidu', label: '百度地图' },
]

/**
 * 「不是真实地点」的特征词：线上/远程面试的常见写法。
 *
 * 命中即不生成地图链接。理由是**不给用户一个点了也没结果的死链**——
 * 拿「视频面试」去地图里搜，只会返回空结果页。
 * 词表刻意收窄，只收录明确表达「不在地面某处」的说法，避免误杀真地址。
 */
const NON_PLACE_MARKERS = [
  '视频', '线上', '远程', '电话', '微信', '钉钉', '飞书', '腾讯会议',
  '网络', '笔试', '机考', '待定', '另行通知',
  '各省市', '多地', '不限',
  // ── 泛化表述（v1.62.0 独立验证补漏）────────────────────────────────
  // 验证时用 8 个国内种子源的**全部 210 种真实 location** 复核，发现
  // 「全国主要城市」「全国门店」「深圳各区」这类写法会被当成单点放行，
  // 点开地图是空结果 —— 正是本功能要避免的死链，只是方向反了。
  // ⚠️ 仍然**不收录裸词「全国」**：那会误杀「全国农业展览馆」这类真实地址。
  '各省', '各地市', '各区', '各县', '县域', '网点', '门店', '高校',
  '各城市', '主要城市', '重点城市', '各地',
  // 「另招」：多地点用全角括号括注时不含分隔符（如「深圳（另招济南）」），单独补一条。
  // 不加全角括号本身——真实地址里「（国贸）」这类括注是正常写法，加了会误杀。
  '另招',
  'remote', 'online', 'worldwide', 'anywhere', 'virtual', 'zoom',
  // 区域/国家级泛化写法（不是一个可导航的地点）。
  // `欧洲` 有确凿来源：ArbeitnowJobProvider 非远程分支会返回中文「欧洲」。
  'europe', 'apac', 'emea', 'global', 'usa', 'united states', '欧洲',
]

/**
 * 多地点分隔符。
 *
 * 本项目的岗位地点大量是「杭州/北京/深圳」这类**多城市串**（一个岗位对应 3~4 个城市）。
 * 这种文本没有单一位置：只搜第一个城市会让用户以为岗位就在那儿，搜整串又搜不到。
 * 因此**多地点一律不生成链接**，位置文本照常展示——不知道就是不知道。
 */
const MULTI_PLACE_SEPARATORS = ['/', '、', ',', '，', ';', '；', '|']

/** 规范化关键词：去首尾空白并把内部连续空白压成一个空格 */
export function normalizePlaceText(text?: string | null): string {
  return (text ?? '').trim().replace(/\s+/g, ' ')
}

/**
 * 这段文本能不能落到地图上。
 *
 * 只有「非空、不是线上/远程写法、且不是多地点串」时才为真。
 * 这是**展示闸门**：为假时视图不渲染地图入口，而不是渲染一个禁用的死按钮。
 */
export function isMappableLocation(text?: string | null): boolean {
  const s = normalizePlaceText(text)
  if (!s) return false
  const lower = s.toLowerCase()
  if (NON_PLACE_MARKERS.some((m) => lower.includes(m))) return false
  if (MULTI_PLACE_SEPARATORS.some((sep) => s.includes(sep))) return false
  return true
}

/**
 * 构造「在地图中搜索该地点」的官方深链。
 *
 * 入参不合法（空 / 线上远程 / 多地点）时返回 `null` —— **宁可没有入口，
 * 也不给一个点开是空结果的链接**。调用方据此决定是否渲染。
 *
 * ⚠️ 三家参数名不同（腾讯/百度用 `keyword`/`address`，高德用 `keyword`），
 * 且**坐标顺序规则不一致**（腾讯/百度 `lat,lng`、高德 `lng,lat`）——
 * 本项目只按关键词搜索、不传坐标，因此不受该顺序陷阱影响；
 * 将来若要改成按坐标打点，务必先读 skill `cn-map-deeplink`。
 */
export function buildMapSearchUrl(provider: MapProvider, keyword?: string | null): string | null {
  if (!isMappableLocation(keyword)) return null
  const k = normalizePlaceText(keyword)

  switch (provider) {
    case 'tencent':
      return `https://apis.map.qq.com/uri/v1/search?${new URLSearchParams({
        keyword: k,
        referer: MAP_CALLER,
      })}`
    case 'amap':
      return `https://uri.amap.com/search?${new URLSearchParams({
        keyword: k,
        view: 'map',
        src: MAP_CALLER,
      })}`
    case 'baidu':
      return `https://api.map.baidu.com/geocoder?${new URLSearchParams({
        address: k,
        output: 'html',
        src: `webapp.${MAP_CALLER}`,
      })}`
  }
}

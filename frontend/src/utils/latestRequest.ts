/**
 * 「最后一次请求为准」守卫（C1）。
 *
 * <p>快速切换筛选/分栏/阈值时，多个请求会同时在途；网络抖动下先发出的请求可能后返回，
 * 把新结果覆盖成旧数据——用户看到的是「与当前筛选不符、却没有任何报错」的列表。
 *
 * <p>用法：发请求前 `begin()` 领票，收到响应后先 `isLatest(ticket)` 判定，
 * 过期响应直接丢弃（既不写数据，也不改 loading/错误态）。
 */
export interface LatestGuard {
  /** 开启一次新请求并作废此前所有在途请求 */
  begin(): number
  /** 该票据是否仍是最新一次请求 */
  isLatest(ticket: number): boolean
}

export function createLatestGuard(): LatestGuard {
  let latest = 0
  return {
    begin: () => ++latest,
    isLatest: (ticket: number) => ticket === latest,
  }
}

<template>
  <span v-if="links.length" class="map-open" :class="{ 'is-compact': compact }">
    <span v-if="!compact" class="map-open-hint">在地图中搜索</span>
    <a
      v-for="l in shownLinks"
      :key="l.id"
      class="map-open-link"
      :href="l.url"
      target="_blank"
      rel="noopener noreferrer"
      :aria-label="`在${l.label}中搜索「${keyword}」`"
      :title="`在${l.label}中搜索「${keyword}」`"
    >{{ compact ? '地图' : l.label }}</a>
  </span>
</template>

<script setup lang="ts">
/**
 * 地图外链入口（v1.62.0 / compact 变体 v1.77.1）
 *
 * 把一段**文本地点**交给地图站点去搜。只做纯导航（`<a target="_blank">`）：
 * 我们不内嵌地图、不取坐标、不碰第三方账号。
 *
 * 地点不可映射时**整块不渲染**（而不是渲染一个禁用的死按钮）——
 * 与项目既有原则一致：不承诺走不通的路。
 *
 * ── 两种形态（v1.77.1）──────────────────────────────────────────────
 * - 完整态（默认）：列出白名单内三家底图，适合详情面板 / 日历事件这类宽松空间；
 * - **紧凑态（`compact`）**：只出一个默认底图（腾讯）的「地图」链接，
 *   适合岗位卡片这类元信息密集、放不下三个链接的位置。
 *
 * 紧凑态刻意只保留一家：卡片里并排三个地图链接会把 `job-meta` 撑成两行，
 * 而用户在列表阶段通常只是想快速确认地点是否可导航，不需要挑底图。
 * 需要挑的时候进详情面板，那里是完整态。
 */
import { computed } from 'vue'
import { MAP_PROVIDERS, buildMapSearchUrl, type MapProvider } from '../utils/mapLink'

const props = withDefaults(
  defineProps<{
    /** 地点文本（岗位地点或面试地点），可为空 */
    keyword?: string | null
    /** 紧凑态：只渲染默认底图的一个「地图」链接 */
    compact?: boolean
  }>(),
  { keyword: null, compact: false }
)

interface MapLink {
  id: MapProvider
  label: string
  url: string
}

/** 只保留真正构造出链接的底图；一个都没有时整块隐藏 */
const links = computed<MapLink[]>(() =>
  MAP_PROVIDERS
    .map((p) => ({ id: p.id, label: p.label, url: buildMapSearchUrl(p.id, props.keyword) }))
    .filter((l): l is MapLink => l.url !== null),
)

/** 紧凑态只取第一家（腾讯，白名单首位），完整态全列 */
const shownLinks = computed<MapLink[]>(() =>
  props.compact ? links.value.slice(0, 1) : links.value
)
</script>

<style scoped>
.map-open {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--font-size-xs);
  line-height: 1.6;
}

/* 紧凑态：嵌在元信息行里，不能再带「在地图中搜索」前缀，也不换行 */
.map-open.is-compact {
  flex-wrap: nowrap;
  gap: 0;
}

.map-open-hint {
  color: var(--c-text-quaternary);
}

.map-open-link {
  color: var(--c-accent);
  text-decoration: none;
  border-bottom: 1px dashed var(--c-accent-line);
}

.map-open-link:hover {
  color: var(--c-accent-hover);
  border-bottom-style: solid;
}

.map-open-link:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 2px;
  border-radius: var(--radius-xs);
}
</style>


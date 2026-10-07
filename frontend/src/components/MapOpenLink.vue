<template>
  <span v-if="links.length" class="map-open">
    <span class="map-open-hint">在地图中搜索</span>
    <a
      v-for="l in links"
      :key="l.id"
      class="map-open-link"
      :href="l.url"
      target="_blank"
      rel="noopener noreferrer"
      :aria-label="`在${l.label}中搜索「${keyword}」`"
    >{{ l.label }}</a>
  </span>
</template>

<script setup lang="ts">
/**
 * 地图外链入口（v1.62.0）
 *
 * 把一段**文本地点**交给地图站点去搜。只做纯导航（`<a target="_blank">`）：
 * 我们不内嵌地图、不取坐标、不碰第三方账号。
 *
 * 地点不可映射时**整块不渲染**（而不是渲染一个禁用的死按钮）——
 * 与项目既有原则一致：不承诺走不通的路。
 */
import { computed } from 'vue'
import { MAP_PROVIDERS, buildMapSearchUrl, type MapProvider } from '../utils/mapLink'

const props = defineProps<{
  /** 地点文本（岗位地点或面试地点），可为空 */
  keyword?: string | null
}>()

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

<template>
  <div class="job-detail-page">
    <!-- 加载态 -->
    <div v-if="loading" class="detail-skeleton">
      <div class="skeleton skeleton-line w-40"></div>
      <div class="skeleton skeleton-line w-70"></div>
      <div class="skeleton skeleton-line w-60"></div>
    </div>

    <!-- 加载失败（P2-19 口径：未知 ≠ 不存在） -->
    <LoadErrorState
      v-else-if="loadError"
      description="岗位详情没能取到，这不代表岗位已下架。请检查网络后重试。"
      @retry="load"
    />

    <!-- 不存在 / 已下架 -->
    <div v-else-if="!job" class="detail-empty">
      <h1>岗位不存在或已下架</h1>
      <p>该岗位可能已过期，或链接中的编号不正确。</p>
      <BaseButton variant="primary" @click="goList">返回招聘广场</BaseButton>
    </div>

    <!-- 详情 -->
    <template v-else>
      <header class="detail-header">
        <div class="detail-top">
          <BaseTag size="sm">{{ job.platform || '岗位来源' }}</BaseTag>
          <span v-if="job.recruitType" class="detail-type">{{ recruitTypeLabel(job.recruitType) }}</span>
        </div>
        <h1 class="detail-title">{{ job.title }}</h1>
        <p class="detail-company">{{ job.companyName }}</p>
        <div class="detail-meta">
          <span v-if="job.location">地点：{{ job.location }}</span>
          <span v-if="job.salary">薪资：{{ job.salary }}</span>
          <span v-if="job.degree">学历：{{ job.degree }}</span>
          <span v-if="job.experience">经验：{{ job.experience }}</span>
          <span v-if="job.deadline">截止：{{ job.deadline }}</span>
        </div>
        <!-- G-02：applyUrl 来自外部源，必须过协议白名单后再渲染 -->
        <a
          v-if="safeExternalUrl(job.applyUrl)"
          :href="safeExternalUrl(job.applyUrl)!"
          target="_blank"
          rel="noopener noreferrer"
          class="detail-apply"
        >前往官方申请入口 →</a>
        <p v-else class="detail-no-url">该岗位没有可安全打开的投递链接，请回到原平台搜索岗位名。</p>
      </header>

      <!-- 标签 -->
      <section v-if="tagList.length" class="detail-section">
        <h2>岗位标签</h2>
        <div class="tag-row">
          <BaseTag v-for="(t, i) in tagList" :key="i" size="sm">{{ t }}</BaseTag>
        </div>
      </section>

      <!-- JD 正文：保留换行（详情页的核心价值就是完整 JD） -->
      <section v-if="job.description" class="detail-section">
        <h2>岗位描述</h2>
        <div class="detail-body">{{ job.description }}</div>
      </section>

      <section v-if="job.requirements" class="detail-section">
        <h2>任职要求</h2>
        <div class="detail-body">{{ job.requirements }}</div>
      </section>

      <footer class="detail-footer">
        <BaseButton variant="ghost" @click="goList">返回招聘广场</BaseButton>
      </footer>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api, { getErrMessage } from '../api'
import { BaseButton, BaseTag, LoadErrorState } from '../components'
import { recruitTypeLabel } from '../utils/recruitType'
import { safeExternalUrl } from '../utils/safeLink'

/**
 * 岗位详情页（G-02，2026-10-30 质量循环）。
 *
 * ── 为什么要补这一页 ────────────────────────────────────────────────
 * 后端 `GET /api/jobs/{id}` 早已实现并有测试，但前端只有列表卡片 + 抽屉摘要，
 * 长 JD 被截断、分享只能分享列表页、SEG 落地还得自己找。这一页把「完整 JD + 可分享 URL」
 * 补上，且完全复用现成接口，零后端改动。
 *
 * ⚠️ 当前路由 `requiresAuth: true`：后端岗位详情接口在 `anyRequest().authenticated()` 之下，
 * 匿名访问会 401。若未来要对搜索引擎/未登录访客开放，需要同时调整 SecurityConfig 白名单。
 */

interface JobDetail {
  id: number
  platform?: string | null
  title: string
  companyName: string
  location?: string | null
  salary?: string | null
  degree?: string | null
  experience?: string | null
  recruitType?: string | null
  deadline?: string | null
  applyUrl?: string | null
  description?: string | null
  requirements?: string | null
  tags?: string | null
}

const route = useRoute()
const router = useRouter()
const job = ref<JobDetail | null>(null)
const loading = ref(true)
const loadError = ref(false)

const tagList = computed(() =>
  (job.value?.tags || '')
    .split(/[,，、|]/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 12),
)

async function load() {
  const id = String(route.params.id || '')
  if (!/^\d+$/.test(id)) {
    job.value = null
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = false
  try {
    job.value = (await api.get(`/api/jobs/${id}`)) as unknown as JobDetail
  } catch (e: unknown) {
    // 404 是确定性结论（岗位不存在），其余按加载失败处理并保留重试
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 404) {
      job.value = null
    } else {
      loadError.value = true
      console.warn(getErrMessage(e, '加载岗位详情失败'))
    }
  } finally {
    loading.value = false
  }
}

function goList() {
  router.push('/jobs')
}

watch(() => route.params.id, () => { void load() })
onMounted(load)
</script>

<style scoped>
.job-detail-page {
  /* designGuards：页面容器只允许 900（阅读/编辑列）/ 1080（宽页面）两档 */
  max-width: 900px;
  margin: 0 auto;
  padding: 8px 0 40px;
}

.detail-header {
  padding: 24px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}

.detail-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.detail-type {
  font-size: 12px;
  color: var(--c-text-tertiary);
}

.detail-title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--c-text);
}

.detail-company {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--c-text-secondary);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 13px;
  color: var(--c-text-tertiary);
  margin-bottom: 18px;
}

.detail-apply {
  display: inline-flex;
  align-items: center;
  padding: 10px 18px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  /* 品牌实色底上必须用随主题翻转的 on-primary，不能写死白字（暗色会看不清） */
  color: var(--c-on-primary);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  transition: filter var(--transition-fast);
}
.detail-apply:hover {
  filter: brightness(1.06);
}

.detail-no-url {
  margin: 0;
  font-size: 13px;
  color: var(--c-text-tertiary);
}

.detail-section {
  padding: 22px 24px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
  margin-bottom: 16px;
}

.detail-section h2 {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 完整 JD 必须保留换行：列表/抽屉里被压成一段正是本页要解决的问题 */
.detail-body {
  font-size: 14px;
  line-height: 1.8;
  color: var(--c-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-footer {
  display: flex;
  justify-content: flex-start;
  margin-top: 8px;
}

.detail-empty {
  padding: 48px 24px;
  text-align: center;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
}
.detail-empty h1 {
  margin: 0 0 8px;
  font-size: 20px;
}
.detail-empty p {
  margin: 0 0 20px;
  color: var(--c-text-secondary);
}

.detail-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>

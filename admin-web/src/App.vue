<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from './useSession.js'
import AccessPanel from './components/AccessPanel.vue'
import AuthPanel from './components/AuthPanel.vue'
import BrandMark from './components/BrandMark.vue'
import DocumentsPanel from './components/DocumentsPanel.vue'
import EvaluationPanel from './components/EvaluationPanel.vue'
import Icon from './components/Icon.vue'
import MonitoringPanel from './components/MonitoringPanel.vue'
import SlideOver from './components/SlideOver.vue'
import ToastHost from './components/ToastHost.vue'
import UploadDialog from './components/UploadDialog.vue'
import UsagePanel from './components/UsagePanel.vue'
import InviteCodesPanel from './components/InviteCodesPanel.vue'
import VersionDrawer from './components/VersionDrawer.vue'
import { formatCompact, formatNumber, inProgress, localDate, versionName } from './labels'

const { booting, loggingIn, identity, accessError, initialize, api, signIn, signOut } = useSession('/admin/')

const VIEWS = {
  documents: {
    hash: '',
    title: '文档管理 · 知源',
    crumb: '文档库',
    eyebrow: '内容管理',
    heading: '文档库',
    sub: '上传、解析与版本发布集中管理，让每一条知识都可追溯、可检索。',
  },
  usage: {
    hash: '#/usage',
    title: '用量分析 · 知源',
    crumb: '用量分析',
    eyebrow: '成本与用量',
    heading: '租户用量',
    sub: '按自然日统计模型调用情况，费用为估算值，待对账数量会在模型账单核销后归零。',
  },
  evaluation: {
    hash: '#/evaluation',
    title: 'RAGAS 评测 · 知源',
    crumb: 'RAGAS 评测',
    eyebrow: '质量管理',
    heading: 'RAGAS 评测',
    sub: '对 golden 数据集执行离线评测，持续校验回答可信度与上下文召回是否达到质量门禁。',
  },
  monitoring: {
    hash: '#/monitoring',
    title: '系统监控 · 知源',
    crumb: '系统监控',
    eyebrow: '运行状态',
    heading: '系统监控',
    sub: '实时探活全部依赖服务，并汇总问答成功率、耗时与积压等运行指标。',
  },
  access: {
    hash: '#/access',
    title: '用户与权限 · 知源',
    crumb: '用户与权限',
    eyebrow: '权限治理',
    heading: '用户与权限',
    sub: '查看租户内账号与平台角色，管理谁可以使用文档、评测与问答能力。',
  },
  invites: {
    hash: '#/invites',
    title: '邀请码 · 知源',
    crumb: '邀请码',
    eyebrow: '访问控制',
    heading: '邀请码',
    sub: '每两小时自动轮换，仅本租户可见。新用户凭邀请码注册后自动加入本租户并获得普通成员角色。',
  },
}

function viewFromHash() {
  const hash = window.location.hash
  const found = Object.entries(VIEWS).find(([, meta]) => meta.hash && meta.hash === hash)
  return found ? found[0] : 'documents'
}

const view = ref(viewFromHash())
const sidebarOpen = ref(false)
const documents = ref([])
const docsLoading = ref(false)
const docsRefreshing = ref(false)
const docsError = ref('')
const docsCapped = ref(false)
const selectedDoc = ref(null)
const drawerOpen = ref(false)
const uploadOpen = ref(false)
const uploadMode = ref('create')
const uploadTarget = ref(null)
const usageReport = ref(null)
const usageLoading = ref(false)
const usageRefreshing = ref(false)
const usageError = ref('')
const usageDate = ref(localDate())
const todayReport = ref(null)
const toasts = ref([])
let listRequest = 0
let usageRequest = 0
let toastSeq = 0
let pollTimer = null

const canManage = computed(() => Array.isArray(identity.value?.roles) && identity.value.roles.includes('ADMIN'))
const roleLabel = computed(() => (canManage.value ? '知识管理员' : '企业成员'))

const counts = computed(() => ({
  total: documents.value.length,
  ready: documents.value.filter((item) => item.status === 'READY').length,
  processing: documents.value.filter((item) => inProgress(item.status)).length,
  failed: documents.value.filter((item) => item.status === 'FAILED').length,
}))

const todayTotals = computed(() => {
  const result = { tokens: 0, cost: 0 }
  for (const item of todayReport.value?.models ?? []) {
    result.tokens += (Number(item.inputTokens) || 0) + (Number(item.outputTokens) || 0)
    result.cost += Number(item.estimatedCost) || 0
  }
  return result
})

const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'long' }).format(new Date())
const todayCurrency = computed(() => todayReport.value?.currency ?? 'CNY')
const todayCost = computed(() =>
  todayTotals.value.cost.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
)

const viewMeta = computed(() => VIEWS[view.value] ?? VIEWS.documents)

function applyTitle() {
  document.title = viewMeta.value.title
}

function navigate(next) {
  if (!VIEWS[next]) return
  if (view.value !== next) {
    view.value = next
    const hash = VIEWS[next].hash
    history.replaceState(null, '', window.location.pathname + hash)
    applyTitle()
    if (next === 'usage') loadUsage()
  }
  sidebarOpen.value = false
}

function onHashChange() {
  const next = viewFromHash()
  if (next !== view.value) {
    view.value = next
    applyTitle()
    if (next === 'usage') loadUsage()
  }
}

function toast(tone, title, message = '') {
  const id = ++toastSeq
  toasts.value.push({ id, tone, title, message })
  window.setTimeout(() => dismiss(id), 4800)
}

function dismiss(id) {
  toasts.value = toasts.value.filter((item) => item.id !== id)
}

function onPanelNotify({ tone = 'info', title = '', message = '' } = {}) {
  toast(tone, title, message)
}

async function loadDocuments({ quiet = false } = {}) {
  if (!canManage.value) return
  const id = ++listRequest
  if (quiet) docsRefreshing.value = true
  else docsLoading.value = true
  try {
    const result = await api('/documents?page=0&size=100')
    if (id !== listRequest) return
    documents.value = result.items
    docsCapped.value = result.hasNext
    docsError.value = ''
    if (selectedDoc.value) {
      const fresh = result.items.find((item) => item.documentId === selectedDoc.value.documentId)
      if (fresh) selectedDoc.value = fresh
    }
  } catch (error) {
    if (id === listRequest) docsError.value = error.message
  } finally {
    if (id === listRequest) {
      docsLoading.value = false
      docsRefreshing.value = false
    }
  }
}

async function refreshDocuments() {
  await loadDocuments({ quiet: true })
  if (!docsError.value) toast('info', '已刷新', '文档列表已与最新状态同步。')
}

async function loadToday() {
  try {
    todayReport.value = await api('/usage?date=' + encodeURIComponent(localDate()))
  } catch {
    todayReport.value = null
  }
}

async function loadUsage() {
  if (!canManage.value) return
  const id = ++usageRequest
  if (usageReport.value) usageRefreshing.value = true
  else usageLoading.value = true
  usageError.value = ''
  try {
    const result = await api('/usage?date=' + encodeURIComponent(usageDate.value))
    if (id !== usageRequest) return
    usageReport.value = result
  } catch (error) {
    if (id !== usageRequest) return
    usageError.value = error.message
    usageReport.value = null
  } finally {
    if (id === usageRequest) {
      usageLoading.value = false
      usageRefreshing.value = false
    }
  }
}

function changeUsageDate(value) {
  usageDate.value = value || localDate()
  loadUsage()
}

function openDoc(doc) {
  if (!doc) return
  selectedDoc.value = doc
  drawerOpen.value = true
}

function openUpload(mode = 'create', doc = null) {
  uploadMode.value = mode
  uploadTarget.value = doc
  uploadOpen.value = true
}

async function onUploaded({ mode, accepted, target }) {
  uploadOpen.value = false
  toast('success', mode === 'append' ? '新版本已提交' : '文档已提交', versionName(accepted?.version) + ' 正在解析与索引。')
  await loadDocuments({ quiet: true })
  const docId = target?.documentId ?? accepted?.documentId
  const fresh = documents.value.find((item) => item.documentId === docId)
  if (fresh && mode === 'create') openDoc(fresh)
  else if (fresh && target?.documentId === selectedDoc.value?.documentId) selectedDoc.value = fresh
  if (view.value === 'documents') await loadToday()
}

onMounted(async () => {
  applyTitle()
  window.addEventListener('hashchange', onHashChange)
  await initialize()
  if (canManage.value) {
    await Promise.all([loadDocuments(), loadToday(), view.value === 'usage' ? loadUsage() : null])
  }
  pollTimer = window.setInterval(async () => {
    if (!canManage.value || docsLoading.value || docsRefreshing.value) return
    const waiting =
      documents.value.some((item) => inProgress(item.status)) || inProgress(selectedDoc.value?.status)
    if (waiting) await loadDocuments({ quiet: true })
  }, 2000)
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  clearInterval(pollTimer)
})
</script>

<template>
  <AuthPanel
    v-if="booting || !identity || !canManage"
    :booting="booting"
    :identity="identity"
    :access-error="accessError"
    :logging-in="loggingIn"
    mode="admin"
    :sign-in="signIn"
    :sign-out="signOut"
  />

  <div v-else class="shell" :class="{ 'shell--nav-open': sidebarOpen }">
    <aside class="sidebar" aria-label="主导航">
      <div class="sidebar__brand">
        <BrandMark :size="36" />
        <div class="sidebar__brand-text">
          <strong>知源</strong>
          <span>RAG CONSOLE</span>
        </div>
      </div>

      <nav class="sidebar__nav">
        <p class="sidebar__group">工作台</p>
        <button class="nav-item" :class="{ 'nav-item--active': view === 'documents' }" type="button" @click="navigate('documents')">
          <Icon name="book" :size="18" />
          <span>文档库</span>
          <span v-if="counts.processing" class="nav-item__dot" :title="counts.processing + ' 篇处理中'" />
        </button>
        <button class="nav-item" :class="{ 'nav-item--active': view === 'usage' }" type="button" @click="navigate('usage')">
          <Icon name="chart" :size="18" />
          <span>用量分析</span>
        </button>

        <p class="sidebar__group">管理</p>
        <button
          class="nav-item"
          :class="{ 'nav-item--active': view === 'evaluation' }"
          type="button"
          @click="navigate('evaluation')"
        >
          <Icon name="clipboard-check" :size="18" />
          <span>RAGAS 评测</span>
        </button>
        <button
          class="nav-item"
          :class="{ 'nav-item--active': view === 'monitoring' }"
          type="button"
          @click="navigate('monitoring')"
        >
          <Icon name="activity" :size="18" />
          <span>系统监控</span>
        </button>
        <button
          class="nav-item"
          :class="{ 'nav-item--active': view === 'access' }"
          type="button"
          @click="navigate('access')"
        >
          <Icon name="users" :size="18" />
          <span>用户与权限</span>
        </button>
        <button
          class="nav-item"
          :class="{ 'nav-item--active': view === 'invites' }"
          type="button"
          @click="navigate('invites')"
        >
          <Icon name="ticket" :size="18" />
          <span>邀请码</span>
        </button>

        <p class="sidebar__group">知识</p>
        <a class="nav-item" href="/ask/">
          <Icon name="sparkles" :size="18" />
          <span>知识问答</span>
          <Icon class="nav-item__end" name="arrow-up-right" :size="14" />
        </a>
      </nav>

      <div class="sidebar__foot">
        <div class="tenant-card">
          <span class="tenant-card__avatar"><Icon name="user" :size="16" /></span>
          <div class="tenant-card__text">
            <strong>{{ roleLabel }}</strong>
            <span>{{ identity.tenantId }}</span>
          </div>
          <button class="icon-btn icon-btn--on-dark" type="button" aria-label="退出登录" title="退出登录" @click="signOut">
            <Icon name="logout" :size="16" />
          </button>
        </div>
        <p class="sidebar__version">知源 · 企业版</p>
      </div>
    </aside>

    <button class="shell__overlay" type="button" aria-label="关闭导航" @click="sidebarOpen = false" />

    <div class="shell__main">
      <header class="topbar">
        <div class="topbar__left">
          <button class="icon-btn topbar__menu" type="button" aria-label="打开导航" @click="sidebarOpen = true">
            <Icon name="menu" :size="18" />
          </button>
          <nav class="breadcrumb" aria-label="面包屑">
            <span>知源</span>
            <Icon name="chevron-right" :size="13" />
            <strong>{{ viewMeta.crumb }}</strong>
          </nav>
        </div>
        <div class="topbar__right">
          <span class="date-chip">
            <Icon name="calendar" :size="14" />
            {{ today }}
          </span>
          <a class="btn btn--outline btn--sm" href="/ask/">
            <Icon name="sparkles" :size="15" />
            知识问答
          </a>
        </div>
      </header>

      <main class="content">
        <template v-if="view === 'documents'">
          <section class="page-head">
            <div>
              <p class="eyebrow">内容管理</p>
              <h1>文档库</h1>
              <p class="page-head__sub">上传、解析与版本发布集中管理，让每一条知识都可追溯、可检索。</p>
            </div>
            <div class="page-head__actions">
              <button class="btn btn--ghost" type="button" :disabled="docsRefreshing" @click="refreshDocuments">
                <Icon name="refresh" :size="16" :class="{ spinning: docsRefreshing }" />
                刷新
              </button>
              <button class="btn btn--primary" type="button" @click="openUpload('create')">
                <Icon name="upload" :size="16" />
                上传文档
              </button>
            </div>
          </section>

          <section class="stat-strip" aria-label="知识资产概览">
            <article class="stat">
              <span class="stat__label">文档总数</span>
              <strong class="stat__value">{{ formatNumber(counts.total) }}</strong>
              <span class="stat__meta">本租户全部资料</span>
            </article>
            <article class="stat">
              <span class="stat__label">可检索</span>
              <strong class="stat__value">{{ formatNumber(counts.ready) }}</strong>
              <span class="stat__meta">
                <i class="stat__dot stat__dot--success" />
                已生效，可被问答检索
              </span>
            </article>
            <article class="stat">
              <span class="stat__label">处理中</span>
              <strong class="stat__value">{{ formatNumber(counts.processing) }}</strong>
              <span class="stat__meta">
                <i class="stat__dot" :class="counts.processing ? 'stat__dot--accent stat__dot--pulse' : 'stat__dot--muted'" />
                解析与索引队列
              </span>
            </article>
            <article class="stat stat--emphasis">
              <span class="stat__label">今日 Token</span>
              <strong class="stat__value">{{ formatCompact(todayTotals.tokens) }}</strong>
              <span class="stat__meta">估算 {{ todayCurrency }} {{ todayCost }}</span>
            </article>
          </section>

          <DocumentsPanel
            :items="documents"
            :loading="docsLoading"
            :error="docsError"
            :capped="docsCapped"
            :refreshing="docsRefreshing"
            @refresh="refreshDocuments"
            @upload="openUpload('create')"
            @open="openDoc"
          />
        </template>

        <UsagePanel
          v-else-if="view === 'usage'"
          :report="usageReport"
          :loading="usageLoading"
          :error="usageError"
          :date="usageDate"
          :refreshing="usageRefreshing"
          @update:date="changeUsageDate"
          @refresh="loadUsage"
        />

        <template v-else>
          <section class="page-head">
            <div>
              <p class="eyebrow">{{ viewMeta.eyebrow }}</p>
              <h1>{{ viewMeta.heading }}</h1>
              <p class="page-head__sub">{{ viewMeta.sub }}</p>
            </div>
          </section>

          <EvaluationPanel v-if="view === 'evaluation'" :key="'evaluation'" :api="api" @notify="onPanelNotify" />
          <MonitoringPanel v-else-if="view === 'monitoring'" :key="'monitoring'" :api="api" @notify="onPanelNotify" />
          <AccessPanel
            v-else-if="view === 'access'"
            :key="'access'"
            :api="api"
            :tenant="identity?.tenantId ?? ''"
            @notify="onPanelNotify"
          />
          <InviteCodesPanel v-else :key="'invites'" :api="api" @notify="onPanelNotify" />
        </template>
      </main>
    </div>

    <SlideOver
      :open="drawerOpen"
      title="文档详情"
      eyebrow="文档库"
      wide
      @close="drawerOpen = false"
    >
      <VersionDrawer
        :open="drawerOpen"
        :doc="selectedDoc"
        :api="api"
        @close="drawerOpen = false"
        @upload="(doc) => openUpload('append', doc)"
      />
    </SlideOver>

    <UploadDialog
      :open="uploadOpen"
      :mode="uploadMode"
      :target="uploadTarget"
      :api="api"
      @close="uploadOpen = false"
      @submitted="onUploaded"
    />

    <ToastHost :items="toasts" @dismiss="dismiss" />
  </div>
</template>

<style scoped>
.shell { display: flex; min-height: 100vh; }
.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: 264px;
  padding: 22px 16px 18px;
  background: var(--sidebar);
  color: #C9CCD3;
}
.sidebar__brand { display: flex; align-items: center; gap: 11px; padding: 2px 8px 22px; }
.sidebar__brand-text { display: grid; gap: 1px; }
.sidebar__brand-text strong { font-size: 15px; font-weight: 660; color: #F4F5F7; letter-spacing: -0.01em; }
.sidebar__brand-text span { font-size: 9px; font-weight: 600; letter-spacing: 0.16em; color: #7E8490; }
.sidebar__nav { display: grid; gap: 2px; }
.sidebar__group {
  margin: 16px 10px 6px;
  font-size: 10px;
  font-weight: 650;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: #6E7480;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 0 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #B9BDC6;
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  text-decoration: none;
  transition: background 150ms ease, color 150ms ease;
}
.nav-item:hover { background: rgba(255, 255, 255, 0.06); color: #F0F2F5; }
.nav-item--active { background: rgba(255, 255, 255, 0.1); color: #FFFFFF; font-weight: 600; }
.nav-item__dot { width: 6px; height: 6px; margin-left: auto; border-radius: 50%; background: #F0B429; }
.nav-item__end { margin-left: auto; opacity: 0.5; }
.sidebar__foot { margin-top: auto; padding-top: 16px; }
.tenant-card {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.06);
}
.tenant-card__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.08);
  color: #D8DBE2;
}
.tenant-card__text { flex: 1; min-width: 0; display: grid; gap: 1px; }
.tenant-card__text strong { font-size: 12px; font-weight: 620; color: #EDEFF3; }
.tenant-card__text span { font-size: 10.5px; color: #8A909B; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__version { margin: 12px 8px 0; font-size: 10px; letter-spacing: 0.04em; color: #666C78; }
.shell__main { flex: 1; min-width: 0; margin-left: 264px; display: flex; flex-direction: column; }
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  height: 64px;
  padding: 0 clamp(18px, 3vw, 34px);
  border-bottom: 1px solid var(--line);
  background: rgba(246, 245, 241, 0.86);
  backdrop-filter: blur(10px);
}
.topbar__left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.topbar__menu { display: none; }
.breadcrumb { display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: var(--ink-3); }
.breadcrumb strong { font-weight: 600; color: var(--ink); }
.topbar__right { display: flex; align-items: center; gap: 9px; }
.date-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 11px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--surface);
  font-size: 12px;
  color: var(--ink-2);
  white-space: nowrap;
}
.content {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-content: start;
  gap: 18px;
  padding: clamp(20px, 3vw, 34px) clamp(18px, 3vw, 34px) 48px;
  max-width: 1240px;
  width: 100%;
}
.content > * { min-width: 0; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.page-head h1 {
  margin: 6px 0 0;
  font-size: clamp(28px, 3.4vw, 36px);
  font-weight: 680;
  letter-spacing: -0.03em;
  line-height: 1.1;
}
.page-head__sub { margin: 9px 0 0; max-width: 56ch; font-size: 13px; line-height: 1.65; color: var(--ink-2); }
.page-head__actions { display: flex; gap: 9px; }
.stat-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  overflow: hidden;
}
.stat { display: grid; gap: 5px; padding: 16px 20px; border-right: 1px solid var(--line); }
.stat:last-child { border-right: 0; }
.stat__label { font-size: 11.5px; color: var(--ink-3); }
.stat__value { font-size: 26px; font-weight: 680; letter-spacing: -0.025em; line-height: 1.15; font-variant-numeric: tabular-nums; }
.stat--emphasis .stat__value { color: var(--accent-ink); }
.stat__meta { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--ink-3); }
.stat__dot { width: 5px; height: 5px; border-radius: 50%; background: var(--ink-3); flex: none; }
.stat__dot--success { background: var(--success); }
.stat__dot--accent { background: var(--accent); }
.stat__dot--muted { background: var(--line-strong); }
.stat__dot--pulse { animation: pulse 1.8s ease-in-out infinite; }
.shell__overlay { display: none; }
.spinning { animation: spin 900ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(57, 72, 212, 0.35); }
  50% { box-shadow: 0 0 0 4px rgba(57, 72, 212, 0.12); }
}
@media (max-width: 1024px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform 260ms cubic-bezier(0.22, 0.85, 0.24, 1);
    box-shadow: 24px 0 60px -30px rgba(0, 0, 0, 0.5);
  }
  .shell--nav-open .sidebar { transform: translateX(0); }
  .shell__main { margin-left: 0; }
  .topbar__menu { display: grid; }
  .shell--nav-open .shell__overlay {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 25;
    border: 0;
    background: rgba(16, 18, 23, 0.4);
    backdrop-filter: blur(1px);
  }
}
@media (max-width: 860px) {
  .stat-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stat:nth-child(2) { border-right: 0; }
  .stat:nth-child(-n + 2) { border-bottom: 1px solid var(--line); }
}
@media (max-width: 640px) {
  .topbar { height: 58px; }
  .date-chip { display: none; }
  .page-head__actions { width: 100%; justify-content: flex-end; }
  .page-head__actions .btn { flex: 0 0 auto; }
  .content { padding-bottom: 36px; }
}
</style>

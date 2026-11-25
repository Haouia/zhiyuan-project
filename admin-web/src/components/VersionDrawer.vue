<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from './Icon.vue'
import StatusBadge from './StatusBadge.vue'
import {
  STAGES,
  formatDateTime,
  formatFileSize,
  formatNumber,
  inProgress,
  reasonOf,
  relativeTime,
  statusOf,
  versionName,
} from '../labels'

const props = defineProps({
  open: { type: Boolean, default: false },
  doc: { type: Object, default: null },
  api: { type: Function, required: true },
})

const emit = defineEmits(['close', 'upload'])

const PAGE_SIZE = 8
const OPTION_SIZE = 100
const OPTION_PAGES = 5
const PREVIEW_LIMIT = 4000
const NOTICE_MS = 3200

const FILE_TYPES = {
  'text/plain': 'TXT',
  'application/pdf': 'PDF',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'DOCX',
}

const versions = ref([])
const detail = ref(null)
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
const page = ref(0)
const hasNext = ref(false)
const recordMap = ref({})
const selectedVersion = ref(null)
const tab = ref('content')
const parsed = ref({ version: null, status: 'idle', text: '', locations: null, error: '' })
const expanded = ref(false)
const downloading = ref(false)
const downloadError = ref('')
const downloadNotice = ref('')
const contentTab = ref(null)
const historyTab = ref(null)

let requestId = 0
let parsedRequestId = 0
let noticeTimer = null

const versionOptions = computed(() => {
  const options = Object.values(recordMap.value).sort((left, right) => right.version - left.version)
  if (options.length) return options
  const doc = props.doc
  if (!doc || doc.latestVersion == null) return []
  return [{
    documentId: doc.documentId,
    version: doc.latestVersion,
    filename: '',
    status: doc.status,
    activeVersion: doc.activeVersion,
    errorCode: doc.errorCode,
    createdAt: null,
    updatedAt: doc.updatedAt,
    contentType: null,
    byteSize: null,
  }]
})

const selectedRecord = computed(() => {
  const version = selectedVersion.value
  if (version == null) return null
  return recordMap.value[version] ?? (detail.value?.version === version ? detail.value : null)
})

const contentStatus = computed(() => selectedRecord.value?.status ?? props.doc?.status ?? null)
const busy = computed(() => inProgress(contentStatus.value))
const showProgress = computed(() => busy.value || contentStatus.value === 'FAILED')
const progressVersion = computed(() => selectedVersion.value ?? props.doc?.latestVersion ?? null)
const versionTotal = computed(() => {
  const known = Object.keys(recordMap.value).length
  return Math.max(Number(props.doc?.latestVersion) || 0, known)
})

const steps = computed(() => {
  const active = contentStatus.value === 'FAILED' ? -1 : STAGES.findIndex(([value]) => value === contentStatus.value)
  return STAGES.map(([value, label, hint], index) => ({
    value,
    label,
    hint,
    done: active >= 0 && index < active,
    active: active >= 0 && index === active,
  }))
})

const parsedText = computed(() => (parsed.value.status === 'ready' ? parsed.value.text : ''))
const previewOverflow = computed(() => parsedText.value.length > PREVIEW_LIMIT)
const visibleText = computed(() => {
  const text = parsedText.value
  if (expanded.value || text.length <= PREVIEW_LIMIT) return text
  return text.slice(0, PREVIEW_LIMIT) + '\n\n…'
})

const emptyTitle = computed(() => {
  const status = contentStatus.value
  if (status === 'FAILED') return '该版本处理失败，暂无正文可查看'
  if (status === 'PENDING' || status === 'PARSING') return '该版本尚未完成解析，暂无可查看的正文'
  if (status === 'INDEXING') return '正文正在完成索引，稍后即可查看'
  if (parsed.value.status === 'ready' && !parsedText.value) return '该版本已解析，但正文内容为空'
  return '该版本暂无可查看的正文'
})

const emptyHint = computed(() => {
  const status = contentStatus.value
  if (status === 'FAILED') return reasonOf(selectedRecord.value?.errorCode) || '请检查原文件后追加新版本重试。'
  if (status === 'PENDING' || status === 'PARSING') return '处理中每 2 秒自动刷新，完成后正文会自动显示。'
  if (status === 'INDEXING') return '解析已完成，正在生成向量并写入索引，请稍后刷新。'
  if (parsed.value.status === 'error') return parsed.value.error || '请求失败，请稍后重试。'
  return '可以到「版本记录」查看该文档的上传与处理记录。'
})

const emptyIcon = computed(() => (contentStatus.value === 'FAILED' ? 'alert-triangle' : 'file'))

function typeName(contentType) {
  if (!contentType) return '—'
  return FILE_TYPES[contentType] ?? contentType
}

function versionLabel(option) {
  const parts = ['v' + option.version, statusOf(option.status).label]
  if (props.doc?.activeVersion === option.version) parts.push('生效')
  if (props.doc?.latestVersion === option.version) parts.push('最新')
  return parts.join(' · ')
}

function mergeRecords(...groups) {
  const merged = { ...recordMap.value }
  for (const group of groups) {
    for (const record of group ?? []) {
      if (!record || record.version == null) continue
      merged[record.version] = { ...(merged[record.version] ?? {}), ...record }
    }
  }
  recordMap.value = merged
}

async function load(target = page.value, quiet = false) {
  const doc = props.doc
  if (!doc) return
  const loadId = ++requestId
  if (quiet) refreshing.value = true
  else loading.value = true
  if (!quiet) error.value = ''
  try {
    const [history, latest] = await Promise.all([
      props.api('/documents/' + doc.documentId + '/versions?page=' + target + '&size=' + PAGE_SIZE),
      doc.latestVersion == null
        ? Promise.resolve(null)
        : props.api('/documents/' + doc.documentId + '/versions/' + doc.latestVersion).catch(() => null),
    ])
    if (loadId !== requestId) return
    const items = Array.isArray(history?.items) ? history.items : []
    versions.value = items
    page.value = history?.page ?? target
    hasNext.value = Boolean(history?.hasNext)
    detail.value = latest
    mergeRecords(items, latest ? [latest] : [])

    if (selectedVersion.value == null) {
      const first = doc.latestVersion ?? items[0]?.version ?? null
      selectedVersion.value = first
      if (first != null) loadParsed(first)
    } else {
      const record = recordMap.value[selectedVersion.value]
      const status = record?.status ?? doc.status
      const stale = parsed.value.version !== selectedVersion.value
      const retry = !quiet && stale
      const retryProcessing = quiet && !stale && parsed.value.status !== 'ready'
        && (status === 'INDEXING' || status === 'READY')
      if (retry || retryProcessing) loadParsed(selectedVersion.value, { quiet: true })
    }
  } catch (caught) {
    if (loadId === requestId && !quiet) error.value = caught?.message || '版本记录加载失败，请稍后重试。'
  } finally {
    if (loadId === requestId) {
      loading.value = false
      refreshing.value = false
    }
  }
}

async function loadAllVersions() {
  const doc = props.doc
  if (!doc) return
  const documentId = doc.documentId
  const collected = []
  try {
    for (let index = 0; index < OPTION_PAGES; index += 1) {
      const result = await props.api('/documents/' + documentId + '/versions?page=' + index + '&size=' + OPTION_SIZE)
      if (!props.open || props.doc?.documentId !== documentId || !result) return
      collected.push(...(result.items ?? []))
      if (!result.hasNext) break
    }
    mergeRecords(collected)
  } catch {
    // 版本选择器退回已加载的版本记录，不影响正文浏览。
  }
}

async function loadParsed(version, { quiet = false } = {}) {
  const doc = props.doc
  if (!doc || version == null) {
    parsed.value = { version: null, status: 'idle', text: '', locations: null, error: '' }
    return
  }
  if (quiet && parsed.value.version === version && parsed.value.status === 'ready') return
  const keepText = quiet && parsed.value.version === version
  const loadId = ++parsedRequestId
  parsed.value = {
    version,
    status: keepText ? parsed.value.status : 'loading',
    text: keepText ? parsed.value.text : '',
    locations: keepText ? parsed.value.locations : null,
    error: '',
  }
  try {
    const result = await props.api('/documents/' + doc.documentId + '/versions/' + version + '/parsed')
    if (loadId !== parsedRequestId) return
    parsed.value = {
      version,
      status: 'ready',
      text: String(result?.text ?? ''),
      locations: result?.locations ?? null,
      error: '',
    }
  } catch (caught) {
    if (loadId !== parsedRequestId) return
    parsed.value = {
      version,
      status: caught?.status === 404 ? 'unavailable' : 'error',
      text: '',
      locations: null,
      error: caught?.message || '正文加载失败，请稍后重试。',
    }
  }
}

function selectVersion(version, { switchTab = false } = {}) {
  if (version == null) return
  if (switchTab) tab.value = 'content'
  if (selectedVersion.value !== version) {
    selectedVersion.value = version
    expanded.value = false
    downloadError.value = ''
    downloadNotice.value = ''
    loadParsed(version)
    return
  }
  if (parsed.value.version !== version) loadParsed(version)
}

function onSelectChange(event) {
  const version = Number(event.target.value)
  if (!Number.isNaN(version)) selectVersion(version)
}

function flashNotice(message) {
  downloadNotice.value = message
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    downloadNotice.value = ''
    noticeTimer = null
  }, NOTICE_MS)
}

async function download() {
  const doc = props.doc
  const version = selectedVersion.value
  if (!doc || version == null || downloading.value) return
  downloading.value = true
  downloadError.value = ''
  downloadNotice.value = ''
  try {
    const response = await props.api(
      '/documents/' + doc.documentId + '/versions/' + version + '/file',
      { raw: true },
    )
    const blob = await response.blob()
    const filename = selectedRecord.value?.filename || ('document-' + doc.documentId + '-v' + version)
    const url = URL.createObjectURL(blob)
    const anchor = window.document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.rel = 'noopener'
    anchor.style.display = 'none'
    window.document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    flashNotice('已开始下载「' + filename + '」')
  } catch (caught) {
    downloadError.value = caught?.message || '原文件下载失败，请稍后重试。'
  } finally {
    downloading.value = false
  }
}

function onTabKeydown(event) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  tab.value = tab.value === 'content' ? 'history' : 'content'
  nextTick(() => {
    const target = tab.value === 'content' ? contentTab.value : historyTab.value
    target?.focus()
  })
}

watch(
  () => [props.open, props.doc?.documentId],
  () => {
    requestId += 1
    parsedRequestId += 1
    loading.value = false
    refreshing.value = false
    error.value = ''
    versions.value = []
    detail.value = null
    recordMap.value = {}
    page.value = 0
    hasNext.value = false
    selectedVersion.value = null
    tab.value = 'content'
    parsed.value = { version: null, status: 'idle', text: '', locations: null, error: '' }
    expanded.value = false
    downloading.value = false
    downloadError.value = ''
    downloadNotice.value = ''
    if (noticeTimer) {
      clearTimeout(noticeTimer)
      noticeTimer = null
    }
    if (!props.open || !props.doc) return
    selectedVersion.value = props.doc.latestVersion ?? null
    if (selectedVersion.value != null) loadParsed(selectedVersion.value)
    load(0)
    loadAllVersions()
  },
  { immediate: true },
)

watch(
  () => [props.open, props.doc?.status, props.doc?.latestVersion, props.doc?.activeVersion],
  () => {
    if (!props.open || !props.doc || loading.value) return
    load(page.value, true)
  },
)

onBeforeUnmount(() => {
  if (noticeTimer) clearTimeout(noticeTimer)
})
</script>

<template>
  <div class="version-drawer">
    <header class="version-drawer__doc">
      <h3 class="version-drawer__title" :title="doc?.title || ''">{{ doc?.title || '文档详情' }}</h3>
      <p class="version-drawer__meta">
        <span v-if="doc?.documentId" class="chip chip--mono" :title="doc.documentId">{{ doc.documentId }}</span>
        <span class="chip">最新 {{ versionName(doc?.latestVersion) }}</span>
        <span v-if="doc?.activeVersion != null" class="chip chip--success">生效 {{ versionName(doc.activeVersion) }}</span>
        <span v-if="refreshing" class="muted version-drawer__refreshing">更新中…</span>
      </p>
    </header>

    <div class="detail-tabs" role="tablist" aria-label="文档详情视图">
      <button
        id="doc-detail-tab-content"
        ref="contentTab"
        class="detail-tabs__item"
        :class="{ 'detail-tabs__item--active': tab === 'content' }"
        type="button"
        role="tab"
        aria-controls="doc-detail-panel-content"
        :aria-selected="tab === 'content'"
        :tabindex="tab === 'content' ? 0 : -1"
        @click="tab = 'content'"
        @keydown="onTabKeydown"
      >
        <Icon name="eye" :size="15" />
        文档内容
      </button>
      <button
        id="doc-detail-tab-history"
        ref="historyTab"
        class="detail-tabs__item"
        :class="{ 'detail-tabs__item--active': tab === 'history' }"
        type="button"
        role="tab"
        aria-controls="doc-detail-panel-history"
        :aria-selected="tab === 'history'"
        :tabindex="tab === 'history' ? 0 : -1"
        @click="tab = 'history'"
        @keydown="onTabKeydown"
      >
        <Icon name="clock" :size="15" />
        版本记录
      </button>
    </div>

    <section
      id="doc-detail-panel-content"
      v-show="tab === 'content'"
      class="detail-panel"
      role="tabpanel"
      aria-labelledby="doc-detail-tab-content"
      tabindex="0"
    >
      <p v-if="error" class="alert alert--danger" role="alert">{{ error }}</p>

      <div v-if="loading && !versions.length && !detail" class="stack-skeleton">
        <span v-for="width in [92, 78, 86, 64, 88, 71]" :key="width" class="skeleton skeleton--line" :style="{ width: width + '%' }" />
      </div>

      <template v-else>
        <section v-if="showProgress" class="detail-progress" aria-label="版本处理进度">
          <header class="detail-progress__head">
            <span class="detail-progress__title">
              <span class="detail-progress__version">{{ versionName(progressVersion) }}</span>
              处理进度
            </span>
            <span class="detail-progress__actions">
              <StatusBadge :status="contentStatus" :error-code="selectedRecord?.errorCode" dense />
              <button
                class="icon-btn icon-btn--sm"
                type="button"
                aria-label="刷新版本状态"
                :disabled="refreshing"
                @click="load(page, true)"
              >
                <Icon name="refresh" :size="15" :class="{ spinning: refreshing }" />
              </button>
            </span>
          </header>

          <div v-if="contentStatus === 'FAILED'" class="alert alert--danger detail-progress__failure" role="alert">
            <strong>该版本处理失败</strong>
            <span>{{ reasonOf(selectedRecord?.errorCode) || '解析或索引失败，请追加新版本重试。' }}</span>
            <code v-if="selectedRecord?.errorCode" class="detail-progress__code">{{ selectedRecord.errorCode }}</code>
          </div>

          <ol v-else class="timeline">
            <li
              v-for="step in steps"
              :key="step.value"
              class="timeline__item"
              :class="{ 'timeline__item--done': step.done, 'timeline__item--active': step.active }"
            >
              <span class="timeline__dot">
                <Icon v-if="step.done" name="check" :size="11" />
              </span>
              <span class="timeline__text">
                <strong>{{ step.label }}</strong>
                <small>{{ step.hint }}</small>
              </span>
            </li>
          </ol>

          <p v-if="busy" class="detail-progress__note">
            <span class="pulse-dot" aria-hidden="true" />
            处理中，状态每 2 秒自动刷新。
          </p>
        </section>

        <div class="detail-toolbar">
          <label class="version-picker">
            <span class="version-picker__label">查看版本</span>
            <select
              class="version-picker__select"
              :value="selectedVersion"
              :disabled="!versionOptions.length"
              aria-label="选择要查看的文档版本"
              @change="onSelectChange"
            >
              <option v-for="option in versionOptions" :key="option.version" :value="option.version">
                {{ versionLabel(option) }}
              </option>
            </select>
          </label>
          <button
            class="btn btn--outline version-download"
            type="button"
            :disabled="downloading || !selectedRecord"
            @click="download"
          >
            <span v-if="downloading" class="btn-spinner" aria-hidden="true" />
            <Icon v-else name="arrow-down" :size="16" />
            {{ downloading ? '下载中…' : '下载原文件' }}
          </button>
        </div>

        <p v-if="downloadError" class="alert alert--danger detail-notice" role="alert">{{ downloadError }}</p>
        <p v-if="downloadNotice" class="detail-notice detail-notice--success" role="status">{{ downloadNotice }}</p>

        <dl class="detail-meta">
          <div class="detail-meta__item detail-meta__item--wide">
            <dt>文件名</dt>
            <dd class="detail-meta__filename" :title="selectedRecord?.filename || ''">{{ selectedRecord?.filename || '—' }}</dd>
          </div>
          <div class="detail-meta__item">
            <dt>类型</dt>
            <dd>{{ typeName(selectedRecord?.contentType) }}</dd>
          </div>
          <div class="detail-meta__item">
            <dt>大小</dt>
            <dd>{{ selectedRecord?.byteSize != null ? formatFileSize(selectedRecord.byteSize) : '—' }}</dd>
          </div>
          <div class="detail-meta__item">
            <dt>版本状态</dt>
            <dd><StatusBadge :status="contentStatus" :error-code="selectedRecord?.errorCode" dense /></dd>
          </div>
          <div class="detail-meta__item">
            <dt>更新时间</dt>
            <dd>{{ formatDateTime(selectedRecord?.updatedAt) }}</dd>
          </div>
        </dl>

        <div class="detail-content">
          <div v-if="parsed.status === 'loading'" class="stack-skeleton">
            <span v-for="width in [96, 88, 92, 74, 90, 62]" :key="width" class="skeleton skeleton--line" :style="{ width: width + '%' }" />
          </div>

          <div v-else-if="parsed.status === 'error'" class="alert alert--danger detail-content__error" role="alert">
            <span>{{ emptyTitle }}</span>
            <small>{{ parsed.error }}</small>
            <button class="btn btn--outline btn--sm" type="button" @click="loadParsed(selectedVersion)">重新加载正文</button>
          </div>

          <template v-else-if="parsed.status === 'ready' && parsedText">
            <pre class="detail-content__text" data-doc-preview>{{ visibleText }}</pre>
            <p v-if="previewOverflow" class="detail-content__more">
              <button
                class="btn btn--ghost btn--sm"
                type="button"
                :aria-expanded="expanded"
                @click="expanded = !expanded"
              >
                <Icon name="chevron-down" :size="15" :class="{ 'is-open': expanded }" />
                {{ expanded ? '收起正文' : '展开全文（共 ' + formatNumber(parsedText.length) + ' 字）' }}
              </button>
            </p>
          </template>

          <div v-else class="detail-content__empty" data-doc-empty>
            <Icon :name="emptyIcon" :size="22" />
            <p class="detail-content__title">{{ emptyTitle }}</p>
            <p class="detail-content__hint">{{ emptyHint }}</p>
            <div class="detail-content__actions">
              <StatusBadge :status="contentStatus" :error-code="selectedRecord?.errorCode" dense />
              <button class="btn btn--ghost btn--sm" type="button" @click="tab = 'history'">查看版本记录</button>
            </div>
          </div>
        </div>
      </template>
    </section>

    <section
      id="doc-detail-panel-history"
      v-show="tab === 'history'"
      class="detail-panel"
      role="tabpanel"
      aria-labelledby="doc-detail-tab-history"
      tabindex="0"
    >
      <div class="version-history" aria-label="版本记录">
        <header class="version-history__head">
          <h4>版本记录</h4>
          <span class="muted">{{ versionTotal ? '共 ' + formatNumber(versionTotal) + ' 个版本' : '' }}</span>
        </header>

        <div v-if="loading" class="stack-skeleton">
          <span v-for="n in 3" :key="n" class="skeleton skeleton--row" />
        </div>

        <p v-else-if="!versions.length" class="muted version-history__empty">暂无版本记录。</p>

        <ul v-else class="version-list">
          <li v-for="item in versions" :key="item.version" class="version-list__item">
            <button
              class="version-list__row"
              :class="{ 'version-list__row--selected': item.version === selectedVersion }"
              type="button"
              :aria-current="item.version === selectedVersion ? 'true' : undefined"
              @click="selectVersion(item.version, { switchTab: true })"
            >
              <span class="version-list__badge" :class="{ 'version-list__badge--active': item.activeVersion === item.version }">
                {{ versionName(item.version) }}
              </span>
              <span class="version-list__body">
                <strong>
                  {{ item.filename }}
                  <span v-if="item.activeVersion === item.version" class="tag tag--success">当前生效</span>
                </strong>
                <small>
                  {{ relativeTime(item.createdAt) }}
                  <template v-if="item.errorCode"> · <span class="text-danger">{{ reasonOf(item.errorCode) }}</span></template>
                </small>
              </span>
              <StatusBadge :status="item.status" :error-code="item.errorCode" dense />
            </button>
          </li>
        </ul>

        <nav v-if="page > 0 || hasNext" class="pager" aria-label="版本分页">
          <button class="btn btn--outline btn--sm" type="button" :disabled="page === 0 || refreshing" @click="load(page - 1)">
            <Icon name="chevron-left" :size="15" /> 上一页
          </button>
          <span class="pager__label">第 {{ page + 1 }} 页</span>
          <button class="btn btn--outline btn--sm" type="button" :disabled="!hasNext || refreshing" @click="load(page + 1)">
            下一页 <Icon name="chevron-right" :size="15" />
          </button>
        </nav>
      </div>
    </section>

    <div class="version-drawer__foot">
      <button class="btn btn--primary" type="button" @click="emit('upload', doc)">
        <Icon name="plus" :size="16" />
        追加新版本
      </button>
      <button class="btn btn--ghost" type="button" @click="emit('close')">关闭</button>
    </div>
  </div>
</template>

<style scoped>
.version-drawer { display: grid; gap: 16px; }
.version-drawer__doc { min-width: 0; }
.version-drawer__title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.02em;
  overflow-wrap: anywhere;
}
.version-drawer__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 10px 0 0;
}
.version-drawer__refreshing { font-size: 11.5px; }

.detail-tabs {
  display: inline-flex;
  align-self: flex-start;
  gap: 2px;
  max-width: 100%;
  padding: 3px;
  border-radius: 10px;
  background: var(--paper-2);
}
.detail-tabs__item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 40px;
  padding: 0 15px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-2);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.detail-tabs__item:hover { color: var(--ink); }
.detail-tabs__item--active {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 1px 2px rgba(21, 23, 28, 0.08);
}
.detail-tabs__item:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.detail-panel { display: grid; gap: 16px; min-width: 0; }
.detail-panel:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; border-radius: 8px; }

.detail-progress {
  display: grid;
  gap: 14px;
  padding: 15px 16px 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--paper);
}
.detail-progress__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.detail-progress__title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  font-weight: 640;
}
.detail-progress__version { font-variant-numeric: tabular-nums; }
.detail-progress__actions { display: flex; align-items: center; gap: 8px; }
.detail-progress__failure { display: grid; gap: 4px; margin: 0; }
.detail-progress__failure strong { font-size: 13px; }
.detail-progress__failure span { font-size: 12px; line-height: 1.6; }
.detail-progress__code {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--danger);
  opacity: 0.8;
  overflow-wrap: anywhere;
}
.detail-progress__note {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 12px;
  color: var(--ink-3);
}

.timeline {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}
.timeline__item { position: relative; display: grid; gap: 7px; padding-top: 2px; padding-right: 10px; }
.timeline__item::before {
  content: '';
  position: absolute;
  top: 12px;
  left: 24px;
  right: 0;
  height: 1px;
  background: var(--line-strong);
}
.timeline__item:last-child::before { display: none; }
.timeline__item--done::before { background: var(--success); opacity: 0.45; }
.timeline__dot {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1.5px solid var(--line-strong);
  background: var(--surface);
  color: transparent;
  position: relative;
  z-index: 1;
}
.timeline__item--done .timeline__dot { background: var(--success); border-color: var(--success); color: #fff; }
.timeline__item--active .timeline__dot {
  border-color: var(--accent);
  background: var(--accent);
  box-shadow: inset 0 0 0 5px var(--accent-soft);
  animation: breathe 2s ease-in-out infinite;
}
.timeline__text { display: grid; gap: 2px; }
.timeline__text strong { font-size: 12px; font-weight: 620; color: var(--ink-3); }
.timeline__item--done .timeline__text strong { color: var(--ink); }
.timeline__item--active .timeline__text strong { color: var(--accent-ink); }
.timeline__text small { font-size: 11px; color: var(--ink-3); line-height: 1.4; }

.detail-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
}
.version-picker { display: grid; gap: 6px; flex: 1; min-width: 180px; }
.version-picker__label { font-size: 11px; font-weight: 600; color: var(--ink-3); letter-spacing: 0.04em; }
.version-picker__select {
  width: 100%;
  height: 44px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
}
.version-picker__select:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.version-picker__select:disabled { color: var(--ink-3); opacity: 0.7; }
.version-download { flex: none; }

.detail-notice { margin: 0; font-size: 12.5px; line-height: 1.6; }
.detail-notice--success { color: var(--success); }

.detail-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 12px 16px;
  margin: 0;
}
.detail-meta__item { min-width: 0; display: grid; gap: 4px; }
.detail-meta__item--wide { grid-column: 1 / -1; }
.detail-meta dt { font-size: 10.5px; font-weight: 600; color: var(--ink-3); letter-spacing: 0.04em; }
.detail-meta dd { margin: 0; font-size: 12.5px; color: var(--ink); overflow-wrap: anywhere; }
.detail-meta__filename { font-weight: 550; }

.detail-content { display: grid; gap: 10px; min-width: 0; }
.detail-content__text {
  margin: 0;
  max-height: 44vh;
  padding: 18px 20px;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-sans);
  font-size: 13.5px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.detail-content__more { display: flex; justify-content: flex-end; margin: 0; }
.detail-content__error { display: grid; gap: 8px; justify-items: start; margin: 0; }
.detail-content__error small { font-size: 12px; line-height: 1.6; }
.detail-content__empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 26px 20px;
  border: 1px dashed var(--line-strong);
  border-radius: 12px;
  background: var(--surface);
  text-align: center;
  color: var(--ink-3);
}
.detail-content__title { margin: 0; font-size: 13px; font-weight: 620; color: var(--ink); }
.detail-content__hint { max-width: 46ch; margin: 0; font-size: 12px; line-height: 1.7; }
.detail-content__actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 10px; margin-top: 4px; }

.version-history { min-width: 0; }
.version-history__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.version-history__head h4 { margin: 0; font-size: 13px; font-weight: 650; }
.version-history__empty { margin: 0; font-size: 13px; }
.version-list { display: grid; gap: 2px; margin: 0; padding: 0; list-style: none; }
.version-list__item { min-width: 0; }
.version-list__row {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  min-height: 44px;
  padding: 10px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
.version-list__row:hover { background: var(--paper); }
.version-list__row:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.version-list__row--selected { background: var(--accent-soft); }
.version-list__badge {
  flex: none;
  display: grid;
  place-items: center;
  min-width: 38px;
  height: 26px;
  padding: 0 7px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: 11.5px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.version-list__badge--active {
  border-color: rgba(14, 115, 85, 0.35);
  background: var(--success-soft);
  color: var(--success);
}
.version-list__body { display: grid; flex: 1; gap: 2px; min-width: 0; }
.version-list__body strong { font-size: 12.5px; font-weight: 600; overflow-wrap: anywhere; }
.version-list__body small { font-size: 11.5px; color: var(--ink-3); }
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
}
.pager__label { font-size: 12px; color: var(--ink-3); }
.version-drawer__foot { display: flex; gap: 10px; padding-top: 4px; }
.version-drawer__foot .btn--primary { flex: 1; }

.spinning { animation: spin 900ms linear infinite; }
.is-open { transform: rotate(180deg); }

@keyframes breathe {
  0%, 100% { box-shadow: inset 0 0 0 4px var(--accent-soft); }
  50% { box-shadow: inset 0 0 0 4px var(--accent-soft), 0 0 0 4px var(--accent-soft); }
}
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 560px) {
  .timeline { grid-template-columns: 1fr; gap: 12px; }
  .timeline__item { grid-template-columns: 22px 1fr; align-items: start; padding-right: 0; }
  .timeline__item::before { display: none; }
  .detail-tabs { display: flex; width: 100%; }
  .detail-tabs__item { flex: 1; }
  .version-picker { flex-basis: 100%; }
  .version-download { width: 100%; }
  .detail-meta { grid-template-columns: 1fr 1fr; }
  .detail-content__text { max-height: 42vh; padding: 16px; }
}

@media (prefers-reduced-motion: reduce) {
  .spinning, .timeline__item--active .timeline__dot { animation: none; }
}
</style>

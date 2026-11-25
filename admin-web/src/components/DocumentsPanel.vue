<script setup>
import { computed, ref, watch } from 'vue'
import Icon from './Icon.vue'
import StatusBadge from './StatusBadge.vue'
import { formatDateTime, relativeTime, versionName } from '../labels'

const props = defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  capped: { type: Boolean, default: false },
  refreshing: { type: Boolean, default: false },
})

const emit = defineEmits(['refresh', 'upload', 'open'])

const PAGE_SIZE = 12
const filters = [
  { value: 'ALL', label: '全部' },
  { value: 'READY', label: '可检索' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'FAILED', label: '失败' },
]

const filter = ref('ALL')
const query = ref('')
const page = ref(0)

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return props.items.filter((item) => {
    if (filter.value === 'READY' && item.status !== 'READY') return false
    if (filter.value === 'FAILED' && item.status !== 'FAILED') return false
    if (filter.value === 'PROCESSING' && !['PENDING', 'PARSING', 'INDEXING'].includes(item.status)) return false
    if (!keyword) return true
    return String(item.title ?? '').toLowerCase().includes(keyword)
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const paged = computed(() => filtered.value.slice(page.value * PAGE_SIZE, page.value * PAGE_SIZE + PAGE_SIZE))
const rangeStart = computed(() => (filtered.value.length ? page.value * PAGE_SIZE + 1 : 0))
const rangeEnd = computed(() => Math.min(filtered.value.length, (page.value + 1) * PAGE_SIZE))

const counts = computed(() => ({
  ALL: props.items.length,
  READY: props.items.filter((item) => item.status === 'READY').length,
  PROCESSING: props.items.filter((item) => ['PENDING', 'PARSING', 'INDEXING'].includes(item.status)).length,
  FAILED: props.items.filter((item) => item.status === 'FAILED').length,
}))

watch([filter, query], () => {
  page.value = 0
})

watch(totalPages, (value) => {
  if (page.value > value - 1) page.value = Math.max(0, value - 1)
})

function resetFilters() {
  filter.value = 'ALL'
  query.value = ''
  page.value = 0
}
</script>

<template>
  <section class="panel documents" aria-labelledby="documents-heading">
    <header class="panel__head">
      <div class="panel__title">
        <h2 id="documents-heading">全部文档</h2>
        <span class="count-chip">{{ counts.ALL }}</span>
      </div>
      <button class="icon-btn" type="button" aria-label="刷新文档列表" :disabled="refreshing" @click="emit('refresh')">
        <Icon name="refresh" :size="17" :class="{ spinning: refreshing }" />
      </button>
    </header>

    <div class="documents__toolbar">
      <div class="segmented" role="group" aria-label="按状态筛选">
        <button
          v-for="option in filters"
          :key="option.value"
          class="segmented__item"
          :class="{ 'segmented__item--active': filter === option.value }"
          type="button"
          :aria-pressed="filter === option.value"
          @click="filter = option.value"
        >
          {{ option.label }}
          <span class="segmented__count">{{ counts[option.value] }}</span>
        </button>
      </div>
      <label class="search-field">
        <Icon name="search" :size="15" />
        <input v-model="query" type="search" placeholder="筛选标题…" aria-label="按标题筛选文档" autocomplete="off">
      </label>
    </div>

    <p v-if="error" class="alert alert--danger" role="alert">{{ error }}</p>

    <div v-if="loading" class="table-skeleton" aria-hidden="true">
      <div class="table-skeleton__head">
        <span v-for="n in 5" :key="n" class="skeleton skeleton--cell" />
      </div>
      <div v-for="row in 5" :key="row" class="table-skeleton__row">
        <span class="skeleton skeleton--cell skeleton--wide" />
        <span class="skeleton skeleton--cell" />
        <span class="skeleton skeleton--cell" />
        <span class="skeleton skeleton--cell" />
        <span class="skeleton skeleton--cell" />
      </div>
    </div>

    <div v-else-if="!items.length" class="empty">
      <span class="empty__art" aria-hidden="true">
        <Icon name="book" :size="30" />
        <span class="empty__spark">+</span>
      </span>
      <h3>还没有文档</h3>
      <p>上传 DOCX、PDF 或 UTF-8 TXT，系统会自动解析、索引并生成可检索的知识。</p>
      <button class="btn btn--primary" type="button" @click="emit('upload')">
        <Icon name="upload" :size="16" />
        上传第一份文档
      </button>
    </div>

    <div v-else-if="!filtered.length" class="empty empty--compact">
      <h3>没有匹配的文档</h3>
      <p>试试调整状态筛选或清空标题关键词。</p>
      <button class="btn btn--outline" type="button" @click="resetFilters">清空筛选条件</button>
    </div>

    <div v-else class="table-wrap">
      <table class="doc-table">
        <thead>
          <tr>
            <th scope="col">文档</th>
            <th scope="col">最新版本</th>
            <th scope="col">当前生效</th>
            <th scope="col">状态</th>
            <th scope="col">更新于</th>
            <th scope="col"><span class="visually-hidden">操作</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in paged" :key="item.documentId" class="doc-row" @click="emit('open', item)">
            <td>
              <button class="doc-cell" type="button" @click.stop="emit('open', item)">
                <span class="doc-cell__tile"><Icon name="file" :size="17" /></span>
                <span class="doc-cell__text">
                  <strong>{{ item.title }}</strong>
                  <small>{{ formatDateTime(item.updatedAt) }}</small>
                </span>
              </button>
            </td>
            <td data-label="最新版本">
              <span class="version-pill">{{ versionName(item.latestVersion) }}</span>
            </td>
            <td data-label="当前生效">
              <span v-if="item.activeVersion" class="version-pill version-pill--active">{{ versionName(item.activeVersion) }}</span>
              <span v-else class="muted">尚未生效</span>
            </td>
            <td data-label="状态">
              <StatusBadge :status="item.status" :error-code="item.errorCode" />
            </td>
            <td data-label="更新于">
              <span class="muted" :title="formatDateTime(item.updatedAt)">{{ relativeTime(item.updatedAt) }}</span>
            </td>
            <td class="doc-row__end">
              <span class="row-chevron" aria-hidden="true"><Icon name="chevron-right" :size="16" /></span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer v-if="!loading && filtered.length" class="panel__foot">
      <span class="muted">
        显示 {{ rangeStart }}–{{ rangeEnd }}，共 {{ filtered.length }} 篇
        <template v-if="capped"> · 仅加载最近 100 篇</template>
      </span>
      <nav class="pager" aria-label="文档分页">
        <button class="pager__btn" type="button" :disabled="page === 0" aria-label="上一页" @click="page -= 1">
          <Icon name="chevron-left" :size="16" />
        </button>
        <span class="pager__label">{{ page + 1 }} / {{ totalPages }}</span>
        <button class="pager__btn" type="button" :disabled="page >= totalPages - 1" aria-label="下一页" @click="page += 1">
          <Icon name="chevron-right" :size="16" />
        </button>
      </nav>
    </footer>
  </section>
</template>

<style scoped>
.documents { display: flex; flex-direction: column; min-width: 0; }
.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 14px;
}
.panel__title { display: flex; align-items: center; gap: 9px; }
.panel__title h2 { margin: 0; font-size: 15px; font-weight: 660; letter-spacing: -0.01em; }
.count-chip {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--paper-2);
  font-size: 11px;
  font-weight: 650;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.documents__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 0 22px 14px;
  border-bottom: 1px solid var(--line);
}
.segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border-radius: 10px;
  background: var(--paper-2);
}
.segmented__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 11px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-2);
  font: inherit;
  font-size: 12.5px;
  font-weight: 550;
}
.segmented__item:hover { color: var(--ink); }
.segmented__item--active {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 1px 2px rgba(16, 18, 23, 0.08);
  font-weight: 620;
}
.segmented__count { font-size: 10.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.search-field {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-3);
  transition: border-color 160ms ease, box-shadow 160ms ease;
}
.search-field:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.search-field input {
  width: 150px;
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
}
.search-field input::placeholder { color: var(--ink-3); }
.documents .alert { margin: 14px 22px 0; }
.table-wrap { overflow-x: auto; }
.doc-table { width: 100%; border-collapse: collapse; min-width: 720px; }
.doc-table th {
  padding: 10px 14px;
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-3);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.doc-table th:first-child, .doc-table td:first-child { padding-left: 22px; }
.doc-table th:last-child, .doc-table td:last-child { padding-right: 18px; }
.doc-table td {
  padding: 11px 14px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  vertical-align: middle;
}
.doc-row { cursor: pointer; transition: background 140ms ease; }
.doc-row:hover { background: var(--paper); }
.doc-row:last-child td { border-bottom: 0; }
.doc-cell {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.doc-cell__tile {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--accent-ink);
  box-shadow: 0 1px 2px rgba(16, 18, 23, 0.04);
}
.doc-cell__text { display: grid; gap: 2px; min-width: 0; }
.doc-cell__text strong {
  font-size: 13px;
  font-weight: 620;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 34ch;
}
.doc-cell__text small { font-size: 11.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.version-pill {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border-radius: 7px;
  border: 1px solid var(--line);
  background: var(--surface);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.version-pill--active { border-color: rgba(14, 115, 85, 0.3); background: var(--success-soft); color: var(--success); }
.doc-row__end { text-align: right; width: 44px; }
.row-chevron { display: inline-grid; place-items: center; color: var(--ink-3); opacity: 0.6; }
.doc-row:hover .row-chevron { opacity: 1; color: var(--ink); }
.panel__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 22px;
  border-top: 1px solid var(--line);
  font-size: 12px;
}
.pager { display: inline-flex; align-items: center; gap: 4px; }
.pager__btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink-2);
}
.pager__btn:hover:not(:disabled) { border-color: var(--line-strong); color: var(--ink); background: var(--paper); }
.pager__btn:disabled { opacity: 0.4; cursor: not-allowed; }
.pager__label { min-width: 44px; text-align: center; font-size: 12px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.empty { display: grid; justify-items: center; gap: 8px; padding: 46px 24px 50px; text-align: center; }
.empty--compact { padding: 36px 24px; }
.empty__art {
  position: relative;
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  margin-bottom: 8px;
  border-radius: 18px;
  border: 1px solid var(--line);
  background: linear-gradient(160deg, var(--surface), var(--paper-2));
  color: var(--ink-2);
  box-shadow: 0 16px 30px -24px rgba(16, 18, 23, 0.4);
}
.empty__spark {
  position: absolute;
  right: -6px;
  bottom: -6px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  border: 2px solid var(--surface);
}
.empty h3 { margin: 2px 0 0; font-size: 15px; font-weight: 650; }
.empty p { margin: 0 0 10px; max-width: 42ch; font-size: 13px; line-height: 1.65; color: var(--ink-2); }
.spinning { animation: spin 900ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) {
  .documents__toolbar { align-items: stretch; }
  .segmented { width: 100%; overflow-x: auto; }
  .search-field { width: 100%; }
  .search-field input { width: 100%; }
  .doc-cell__text strong { max-width: 18ch; }
  .doc-table { width: 100%; min-width: 0; }
  .doc-table th:nth-child(2),
  .doc-table td:nth-child(2),
  .doc-table th:nth-child(3),
  .doc-table td:nth-child(3),
  .doc-table th:nth-child(5),
  .doc-table td:nth-child(5),
  .doc-table th:nth-child(6),
  .doc-table td:nth-child(6) { display: none; }
  .doc-table th:first-child, .doc-table td:first-child { padding-left: 14px; padding-right: 6px; }
  .doc-table th:last-child, .doc-table td:last-child { padding-right: 14px; }
  .doc-row__end { min-width: 0; }
}
</style>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from './Icon.vue'
import { formatCompact, formatNumber } from '../labels'

const props = defineProps({
  api: { type: Function, required: true },
})
const emit = defineEmits(['notify'])

const snapshot = ref(null)
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
const autoRefresh = ref(true)
const lastCheckedAt = ref(0)
let timer = null
let request = 0

const CATEGORY_ORDER = ['应用服务', '平台服务', '模型端点']
const SERVICES = computed(() => snapshot.value?.services ?? [])
const metrics = computed(() => snapshot.value?.metrics ?? {})
const grouped = computed(() => {
  const groups = new Map()
  for (const name of CATEGORY_ORDER) groups.set(name, [])
  for (const service of SERVICES.value) {
    const key = groups.has(service.category) ? service.category : '其他'
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(service)
  }
  return [...groups.entries()].filter(([, items]) => items.length)
})
const summary = computed(() => {
  const services = SERVICES.value
  return {
    up: services.filter((item) => item.status === 'UP').length,
    degraded: services.filter((item) => item.status === 'DEGRADED').length,
    down: services.filter((item) => item.status === 'DOWN').length,
    total: services.length,
  }
})
const overallTone = computed(() => (summary.value.down ? 'danger' : summary.value.degraded ? 'warning' : 'success'))
const overallLabel = computed(() => {
  if (!SERVICES.value.length) return '等待采集'
  if (summary.value.down) return `${summary.value.down} 项服务不可用`
  if (summary.value.degraded) return `${summary.value.degraded} 项服务降级`
  return '全部系统运行正常'
})
const checkedLabel = computed(() => {
  if (!lastCheckedAt.value) return '尚未采集'
  const seconds = Math.max(0, Math.round((Date.now() - lastCheckedAt.value) / 1000))
  return seconds < 2 ? '刚刚' : `${seconds} 秒前`
})
const checkedTime = computed(() => {
  if (!lastCheckedAt.value) return '—'
  return new Date(lastCheckedAt.value).toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
})

const METRICS = computed(() => [
  { key: 'questionCount', label: '累计提问', value: formatNumber(metrics.value.questionCount), hint: '运行期问答总量', icon: 'sparkles' },
  {
    key: 'questionSuccessRate',
    label: '回答成功率',
    value: metrics.value.questionSuccessRate == null ? '—' : `${Number(metrics.value.questionSuccessRate).toFixed(1)}%`,
    hint: '已生成答案占比',
    icon: 'check-circle',
  },
  {
    key: 'averageLatencyMs',
    label: '平均回答耗时',
    value: metrics.value.averageLatencyMs == null ? '—' : `${formatCompact(metrics.value.averageLatencyMs)} ms`,
    hint: '问答端到端耗时',
    icon: 'clock',
  },
  { key: 'inputTokens', label: '输入 Token', value: formatCompact(metrics.value.inputTokens), hint: '模型输入消耗', icon: 'layers' },
  { key: 'outputTokens', label: '输出 Token', value: formatCompact(metrics.value.outputTokens), hint: '模型输出消耗', icon: 'layers' },
  { key: 'modelFailovers', label: '模型切换', value: formatNumber(metrics.value.modelFailovers), hint: '主备模型接管次数', icon: 'refresh' },
  {
    key: 'questionFailures',
    label: '失败请求',
    value: formatNumber(metrics.value.questionFailures),
    hint: '4xx / 5xx / 限额',
    icon: 'alert-triangle',
  },
  {
    key: 'kafkaPendingEvents',
    label: '积压事件',
    value: formatNumber(metrics.value.kafkaPendingEvents),
    hint: 'Kafka 待处理',
    icon: 'database',
  },
  { key: 'indexFailures', label: '索引失败', value: formatNumber(metrics.value.indexFailures), hint: '解析与索引异常', icon: 'layers' },
])

function statusTone(status) {
  if (status === 'UP') return 'success'
  if (status === 'DEGRADED') return 'warning'
  return 'danger'
}

function statusLabel(status) {
  if (status === 'UP') return '正常'
  if (status === 'DEGRADED') return '降级'
  return '异常'
}

function uptime(value) {
  const seconds = Number(value) || 0
  if (seconds <= 0) return '未采集'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${Math.max(1, minutes)} 分钟`
}

async function load({ quiet = false } = {}) {
  const id = ++request
  if (quiet) refreshing.value = true
  else loading.value = true
  try {
    const result = await props.api('/monitoring')
    if (id !== request) return
    snapshot.value = result
    lastCheckedAt.value = Number(result.checkedAt) || Date.now()
    error.value = ''
  } catch (failure) {
    if (id !== request) return
    error.value = failure.message
  } finally {
    if (id === request) {
      loading.value = false
      refreshing.value = false
    }
  }
}

async function manualRefresh() {
  await load({ quiet: true })
  if (!error.value) emit('notify', { tone: 'info', title: '已刷新', message: '监控快照已重新采集。' })
}

function toggleAuto() {
  autoRefresh.value = !autoRefresh.value
  setupTimer()
}

function setupTimer() {
  clearInterval(timer)
  if (autoRefresh.value) timer = window.setInterval(() => load({ quiet: true }), 20000)
}

onMounted(() => {
  load()
  setupTimer()
})

onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="monitor">
    <section class="monitor-hero panel" :class="'monitor-hero--' + overallTone">
      <div class="monitor-hero__main">
        <span class="monitor-hero__pulse" :class="'is-' + overallTone" />
        <div>
          <p class="panel__eyebrow">运行态概览</p>
          <h2 class="monitor-hero__title">{{ overallLabel }}</h2>
          <p class="monitor-hero__meta">
            {{ summary.up }}/{{ summary.total }} 服务正常 · 采集于 {{ checkedTime }}（{{ checkedLabel }}）· 每 20 秒自动刷新
          </p>
        </div>
      </div>
      <div class="monitor-hero__actions">
        <button class="btn btn--ghost btn--sm" type="button" :disabled="refreshing" @click="manualRefresh">
          <Icon name="refresh" :size="15" :class="{ spinning: refreshing }" />
          刷新
        </button>
        <button
          class="btn btn--outline btn--sm"
          type="button"
          :aria-pressed="autoRefresh"
          @click="toggleAuto"
        >
          <Icon :name="autoRefresh ? 'clock' : 'clock'" :size="15" />
          {{ autoRefresh ? '自动刷新已开' : '自动刷新已关' }}
        </button>
      </div>
    </section>

    <div v-if="error" class="alert alert--danger" role="alert">
      <Icon name="alert-circle" :size="16" />
      <span>{{ error }}</span>
      <button class="btn btn--sm btn--outline" type="button" @click="load()">重试</button>
    </div>

    <section class="monitor-services panel">
      <header class="panel__head">
        <div>
          <p class="panel__eyebrow">服务探活</p>
          <h2 class="panel__title">依赖拓扑 <span class="count-chip">{{ SERVICES.length }}</span></h2>
        </div>
        <span class="muted">并行探活 · 超时 3s</span>
      </header>

      <div v-if="loading && !SERVICES.length" class="table-skeleton" aria-hidden="true">
        <span v-for="row in 5" :key="row" class="skeleton skeleton--row" />
      </div>

      <div v-else class="service-groups">
        <div v-for="[category, items] in grouped" :key="category" class="service-group">
          <p class="service-group__title">{{ category }}</p>
          <ul class="service-list">
            <li v-for="service in items" :key="service.name" class="service">
              <span class="service__dot" :class="'is-' + statusTone(service.status)" aria-hidden="true" />
              <div class="service__ident">
                <strong>{{ service.label }}</strong>
                <span class="service__name">{{ service.name }}</span>
              </div>
              <span class="tag" :class="'tag--' + statusTone(service.status)">{{ statusLabel(service.status) }}</span>
              <span class="service__detail">{{ service.detail }}</span>
              <span class="service__latency num">{{ service.latencyMs != null ? service.latencyMs + ' ms' : '—' }}</span>
              <span class="service__uptime num">{{ uptime(service.uptimeSeconds) }}</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <section class="monitor-metrics">
      <div class="monitor-metrics__head">
        <div>
          <p class="panel__eyebrow">问答治理</p>
          <h2 class="panel__title">运行指标</h2>
        </div>
        <span class="muted">来自 Prometheus 抓取，进程重启后归零</span>
      </div>
      <div class="metric-grid">
        <article v-for="metric in METRICS" :key="metric.key" class="metric-card">
          <span class="metric-card__icon"><Icon :name="metric.icon" :size="16" /></span>
          <span class="metric-card__label">{{ metric.label }}</span>
          <strong class="metric-card__value">{{ metric.value }}</strong>
          <span class="metric-card__hint">{{ metric.hint }}</span>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.monitor { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; min-width: 0; }
.monitor > * { min-width: 0; }
.monitor-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 20px 24px;
}
.monitor-hero--success { border-color: color-mix(in srgb, var(--success) 28%, var(--line)); background: linear-gradient(120deg, var(--success-soft), var(--surface) 62%); }
.monitor-hero--warning { border-color: color-mix(in srgb, var(--warning) 30%, var(--line)); background: linear-gradient(120deg, var(--warning-soft), var(--surface) 62%); }
.monitor-hero--danger { border-color: color-mix(in srgb, var(--danger) 30%, var(--line)); background: linear-gradient(120deg, var(--danger-soft), var(--surface) 62%); }
.monitor-hero__main { display: flex; align-items: center; gap: 14px; }
.monitor-hero__pulse { position: relative; width: 12px; height: 12px; border-radius: 50%; flex: none; background: var(--success); }
.monitor-hero__pulse.is-success { background: var(--success); }
.monitor-hero__pulse.is-warning { background: var(--warning); }
.monitor-hero__pulse.is-danger { background: var(--danger); }
.monitor-hero__pulse::after {
  content: '';
  position: absolute;
  inset: -5px;
  border-radius: 50%;
  border: 1px solid currentColor;
  opacity: 0.35;
  animation: monitor-pulse 2.4s ease-out infinite;
}
.monitor-hero__title { margin: 0; font-size: 20px; font-weight: 670; letter-spacing: -0.02em; }
.monitor-hero__meta { margin: 4px 0 0; font-size: 12px; color: var(--ink-2); }
.monitor-hero__actions { display: flex; gap: 8px; }
.panel__eyebrow { margin: 0 0 4px; font-size: 11px; font-weight: 650; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-3); }
.panel__title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 17px; font-weight: 650; letter-spacing: -0.015em; }
.count-chip {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--paper-2);
  font-size: 11px;
  color: var(--ink-2);
}
.monitor-services { padding: 22px 24px; }
.panel__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.service-groups { display: grid; gap: 16px; }
.service-group__title { margin: 0 0 7px; font-size: 11px; font-weight: 650; letter-spacing: 0.1em; text-transform: uppercase; color: var(--ink-3); }
.service-list { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.service {
  display: grid;
  grid-template-columns: 10px minmax(150px, 1.1fr) 74px minmax(120px, 1.4fr) 74px 96px;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
}
.service__dot { width: 8px; height: 8px; border-radius: 50%; }
.service__dot.is-success { background: var(--success); }
.service__dot.is-warning { background: var(--warning); }
.service__dot.is-danger { background: var(--danger); }
.service__ident { display: grid; gap: 1px; min-width: 0; }
.service__ident strong { font-size: 13px; font-weight: 620; }
.service__name { font-family: var(--font-mono); font-size: 10.5px; color: var(--ink-3); }
.service__detail { font-size: 11.5px; color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.service__latency, .service__uptime { font-size: 11.5px; color: var(--ink-3); text-align: right; }
.num { font-variant-numeric: tabular-nums; }
.monitor-metrics { display: grid; gap: 12px; }
.monitor-metrics__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.monitor-metrics__head .muted { font-size: 11.5px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.metric-card {
  display: grid;
  grid-template-columns: 30px 1fr;
  align-items: center;
  gap: 4px 10px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
}
.metric-card__icon { display: grid; place-items: center; grid-row: span 2; width: 30px; height: 30px; border-radius: 9px; background: var(--accent-soft); color: var(--accent-ink); }
.metric-card__label { font-size: 11.5px; color: var(--ink-3); }
.metric-card__value { font-size: 20px; font-weight: 680; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.metric-card__hint { grid-column: 2; font-size: 10.5px; color: var(--ink-3); }
@keyframes monitor-pulse {
  0% { transform: scale(0.8); opacity: 0.5; }
  70% { transform: scale(1.5); opacity: 0; }
  100% { transform: scale(1.5); opacity: 0; }
}
@media (max-width: 1024px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .service { grid-template-columns: 10px minmax(0, 1fr) 70px; }
  .service__detail { grid-column: 2 / -1; }
  .service__latency, .service__uptime { display: none; }
}
@media (max-width: 640px) {
  .monitor-hero, .monitor-services { padding: 18px 16px; }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-card__value { font-size: 17px; }
}
</style>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
import SlideOver from './SlideOver.vue'
import { formatDateTime, relativeTime } from '../labels'

const props = defineProps({
  api: { type: Function, required: true },
})
const emit = defineEmits(['notify'])

const GATE = { faithfulness: 0.9, contextRecall: 0.85 }
const TREND_MAX = 20
// 趋势图几何（viewBox 坐标系）
const CHART = { w: 640, h: 230, ml: 46, mr: 16, mt: 18, mb: 28 }
const SCATTER = { w: 640, h: 300, ml: 46, mr: 16, mt: 18, mb: 30 }
const HIST = { w: 640, h: 168, ml: 10, mr: 10, mt: 26, mb: 30 }

const reports = ref([])
const total = ref(0)
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
const running = ref(false)
const runStartedAt = ref(0)
const runSeconds = ref(0)
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref(null)
const samplePage = ref(0)
const SAMPLE_PAGE_SIZE = 10
let detailRequest = 0
let runTimer = null

const latest = computed(() => reports.value[0] ?? null)
const gatePassed = computed(() => {
  const item = latest.value
  if (!item || item.faithfulness == null || item.contextRecall == null) return null
  return item.faithfulness >= GATE.faithfulness && item.contextRecall >= GATE.contextRecall
})
const detailSamples = computed(() => detail.value?.samples ?? [])
const samplePageCount = computed(() => Math.max(1, Math.ceil(detailSamples.value.length / SAMPLE_PAGE_SIZE)))
const sampleSlice = computed(() =>
  detailSamples.value.slice(samplePage.value * SAMPLE_PAGE_SIZE, (samplePage.value + 1) * SAMPLE_PAGE_SIZE),
)
const runProgress = computed(() => (runStartedAt.value ? Math.min(96, Math.round((runSeconds.value / 15) * 100)) : 0))

/* ---------- 图表：指标趋势（line + donut） ---------- */

// 列表本身按时间倒序，趋势图需要按时间正序展示最近 N 份报告
const trend = computed(() =>
  reports.value
    .slice(0, TREND_MAX)
    .filter((item) => item.faithfulness != null || item.contextRecall != null)
    .slice()
    .reverse(),
)

function trendRange() {
  const values = []
  trend.value.forEach((item) => {
    if (item.faithfulness != null) values.push(item.faithfulness)
    if (item.contextRecall != null) values.push(item.contextRecall)
  })
  if (!values.length) return { min: 0.5, max: 1 }
  const lo = Math.min(...values)
  return { min: Math.min(0.5, Math.floor(lo * 10) / 10), max: 1 }
}

const range = computed(trendRange)
const yTicks = computed(() => {
  const ticks = []
  for (let v = 0.5; v <= 1.0001; v += 0.1) {
    if (v >= range.value.min - 0.0001) ticks.push(v)
  }
  return ticks
})

function xPos(index, count) {
  if (count <= 1) return CHART.ml + (CHART.w - CHART.ml - CHART.mr) / 2
  return CHART.ml + (index / (count - 1)) * (CHART.w - CHART.ml - CHART.mr)
}

function yPos(value, rng) {
  return CHART.mt + (1 - (value - rng.min) / (rng.max - rng.min)) * (CHART.h - CHART.mt - CHART.mb)
}

function seriesPoints(key) {
  const points = []
  trend.value.forEach((item, index) => {
    const value = item[key]
    if (value == null) return
    points.push({ x: xPos(index, trend.value.length), y: yPos(value, range.value), value, item })
  })
  return points
}

const faithPoints = computed(() => seriesPoints('faithfulness'))
const recallPoints = computed(() => seriesPoints('contextRecall'))

function polyline(points) {
  return points.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ')
}

function areaPoints(points) {
  if (!points.length) return ''
  const base = yPos(range.value.min, range.value)
  const first = points[0]
  const last = points[points.length - 1]
  return `${first.x.toFixed(1)},${base.toFixed(1)} ${polyline(points)} ${last.x.toFixed(1)},${base.toFixed(1)}`
}

const xLabels = computed(() => {
  const count = trend.value.length
  if (!count) return []
  const indexes = count === 1 ? [0] : count === 2 ? [0, 1] : [0, Math.floor((count - 1) / 2), count - 1]
  return indexes.map((index) => ({
    x: xPos(index, count),
    text: shortTime(trend.value[index].generatedAt),
  }))
})

function shortTime(value) {
  if (value == null) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const passStats = computed(() => {
  const scored = reports.value.filter((item) => item.faithfulness != null && item.contextRecall != null)
  const passed = scored.filter(
    (item) => item.faithfulness >= GATE.faithfulness && item.contextRecall >= GATE.contextRecall,
  )
  return { scored: scored.length, passed: passed.length, ratio: scored.length ? passed.length / scored.length : 0 }
})

const DONUT = { r: 28, stroke: 9 }
const donutCirc = computed(() => 2 * Math.PI * DONUT.r)
const donutDash = computed(() => `${(donutCirc.value * passStats.value.ratio).toFixed(1)} ${donutCirc.value.toFixed(1)}`)

const chartAria = computed(() => {
  const n = trend.value.length
  if (!n) return '暂无趋势数据'
  const last = trend.value[n - 1]
  return `最近 ${n} 次评测指标趋势，最新可信度 ${percent(last.faithfulness)}，上下文召回 ${percent(last.contextRecall)}`
})

/* ---------- 图表：样本分布（scatter + histogram，评测报告抽屉内） ---------- */

function scatterPosition(value, total, marginStart, marginEnd) {
  return marginStart + value * (total - marginStart - marginEnd)
}

const scatterPoints = computed(() => {
  const points = []
  detailSamples.value.forEach((sample, index) => {
    if (sample.faithfulness == null && sample.contextRecall == null) return
    points.push({
      index,
      x: scatterPosition(sample.faithfulness ?? 0, SCATTER.w, SCATTER.ml, SCATTER.mr),
      y: scatterPosition(sample.contextRecall ?? 0, SCATTER.h, SCATTER.mt, SCATTER.mb),
      faith: sample.faithfulness,
      recall: sample.contextRecall,
      pass:
        sample.faithfulness != null &&
        sample.contextRecall != null &&
        sample.faithfulness >= GATE.faithfulness &&
        sample.contextRecall >= GATE.contextRecall,
      question: shortText(sample.userInput),
    })
  })
  return points
})

function shortText(value) {
  if (value == null) return ''
  const text = String(value)
  return text.length > 46 ? text.slice(0, 46) + '…' : text
}

const HIST_BUCKETS = [
  { label: '<60%', min: 0, max: 0.6 },
  { label: '60–70%', min: 0.6, max: 0.7 },
  { label: '70–80%', min: 0.7, max: 0.8 },
  { label: '80–90%', min: 0.8, max: 0.9 },
  { label: '90–100%', min: 0.9, max: 1.01 },
]

const histData = computed(() => {
  const buckets = HIST_BUCKETS.map((bucket) => ({ ...bucket, count: 0 }))
  detailSamples.value.forEach((sample) => {
    const value = sample.faithfulness
    if (value == null) return
    const bucket = buckets.find((item) => value >= item.min && value < item.max) ?? buckets[buckets.length - 1]
    bucket.count += 1
  })
  const max = Math.max(1, ...buckets.map((bucket) => bucket.count))
  const total = detailSamples.value.length || 1
  return buckets.map((bucket) => ({
    ...bucket,
    height: bucket.count === 0 ? 2 : Math.max(8, (bucket.count / max) * (HIST.h - HIST.mt - HIST.mb - 22)),
    ratio: bucket.count / total,
  }))
})

/* ---------- 基础方法 ---------- */

function percent(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return (Number(value) * 100).toFixed(1) + '%'
}

function statusTone(status) {
  return status === 'OK' ? 'success' : 'danger'
}

function errorLabel(row) {
  return row.error ? row.error : '—'
}

async function load({ quiet = false } = {}) {
  refreshing.value = quiet
  loading.value = !quiet
  error.value = ''
  try {
    const result = await props.api('/evals')
    reports.value = result.items ?? []
    total.value = result.total ?? reports.value.length
  } catch (failure) {
    error.value = failure.message
    reports.value = []
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function runEvaluation() {
  if (running.value) return
  running.value = true
  runStartedAt.value = Date.now()
  runSeconds.value = 0
  runTimer = window.setInterval(() => {
    runSeconds.value = Math.round((Date.now() - runStartedAt.value) / 1000)
  }, 1000)
  try {
    const summary = await props.api('/evals', { method: 'POST' })
    emit('notify', {
      tone: 'success',
      title: '评测完成',
      message: `${summary.reportId} · 可信度 ${percent(summary.faithfulness)} · 召回 ${percent(summary.contextRecall)}`,
    })
    await load({ quiet: true })
    openDetail(summary)
  } catch (failure) {
    if (failure.status === 409) {
      emit('notify', { tone: 'info', title: '评测正在执行', message: '已有一个评测任务在执行，请稍后刷新查看结果。' })
    } else {
      emit('notify', { tone: 'danger', title: '评测失败', message: failure.message })
    }
  } finally {
    running.value = false
    runStartedAt.value = 0
    clearInterval(runTimer)
  }
}

function openDetail(item) {
  if (!item?.reportId) return
  detailOpen.value = true
  detail.value = null
  detailError.value = ''
  samplePage.value = 0
  const id = ++detailRequest
  detailLoading.value = true
  props
    .api('/evals/' + encodeURIComponent(item.reportId))
    .then((result) => {
      if (id !== detailRequest) return
      detail.value = result
    })
    .catch((failure) => {
      if (id === detailRequest) detailError.value = failure.message
    })
    .finally(() => {
      if (id === detailRequest) detailLoading.value = false
    })
}

function closeDetail() {
  detailOpen.value = false
  detail.value = null
}

function sampleTone(value) {
  if (value == null) return 'neutral'
  return value >= 0.9 ? 'success' : value >= 0.7 ? 'warning' : 'danger'
}

function uptimeLabel() {
  return latest.value ? formatDateTime(latest.value.generatedAt) : '—'
}

watch(detailOpen, (open) => {
  if (!open) detailRequest += 1
})

onMounted(() => {
  load()
})
</script>

<template>
  <div class="eval">
    <section class="eval-latest panel">
      <header class="eval-latest__head">
        <div>
          <p class="panel__eyebrow">最新一次评测</p>
          <h2 class="panel__title">
            <template v-if="latest">{{ latest.dataset }} · {{ latest.reportId }}</template>
            <template v-else>等待首次评测</template>
          </h2>
        </div>
        <div v-if="latest" class="eval-latest__meta">
          <span class="tag" :class="'tag--' + statusTone(latest.status)">
            <Icon :name="latest.status === 'OK' ? 'check-circle' : 'alert-triangle'" :size="13" />
            {{ latest.status === 'OK' ? '评测通过' : '评测失败' }}
          </span>
          <span class="muted">{{ uptimeLabel() }} · {{ relativeTime(latest.generatedAt) }}</span>
        </div>
      </header>

      <div class="eval-gate">
        <article class="eval-gate__item">
          <span class="eval-gate__label">可信度 faithfulness</span>
          <strong class="eval-gate__value">{{ percent(latest?.faithfulness) }}</strong>
          <span class="eval-gate__hint" :class="latest?.faithfulness >= GATE.faithfulness ? 'is-pass' : 'is-fail'">
            门禁 ≥ {{ percent(GATE.faithfulness) }}
          </span>
        </article>
        <article class="eval-gate__item">
          <span class="eval-gate__label">上下文召回 context recall</span>
          <strong class="eval-gate__value">{{ percent(latest?.contextRecall) }}</strong>
          <span class="eval-gate__hint" :class="latest?.contextRecall >= GATE.contextRecall ? 'is-pass' : 'is-fail'">
            门禁 ≥ {{ percent(GATE.contextRecall) }}
          </span>
        </article>
        <article class="eval-gate__item">
          <span class="eval-gate__label">样本数</span>
          <strong class="eval-gate__value">{{ latest?.sampleCount ?? '—' }}</strong>
          <span class="eval-gate__hint">golden 数据集</span>
        </article>
        <article class="eval-gate__item">
          <span class="eval-gate__label">质量门禁</span>
          <strong class="eval-gate__value">{{ gatePassed == null ? '—' : gatePassed ? '通过' : '未通过' }}</strong>
          <span class="eval-gate__hint" :class="gatePassed ? 'is-pass' : 'is-fail'">
            {{ gatePassed ? '两项指标均达标' : '存在未达标指标' }}
          </span>
        </article>
      </div>

      <p v-if="latest?.reason" class="eval-reason">
        <Icon name="info" :size="15" />
        {{ latest.reason }}
      </p>
      <p v-if="latest?.status === 'FAILED'" class="alert alert--danger" role="alert">
        <Icon name="alert-circle" :size="16" />
        <span>{{ latest.reason || '评测在执行过程中中断，请重新运行。' }}</span>
      </p>

      <div class="eval-latest__actions">
        <button class="btn btn--primary" type="button" :disabled="running" @click="runEvaluation">
          <Icon :name="running ? 'refresh' : 'play'" :size="16" :class="{ spinning: running }" />
          {{ running ? '正在评测…' : '运行评测' }}
        </button>
        <button class="btn btn--ghost" type="button" :disabled="refreshing" @click="load({ quiet: true })">
          <Icon name="refresh" :size="16" :class="{ spinning: refreshing }" />
          刷新
        </button>
        <span class="eval-latest__note">评测通过 metric-service 断言式评分，约 5–15 秒完成，结果自动归档。</span>
      </div>

      <div v-if="running" class="eval-progress" role="status">
        <span class="eval-progress__bar" :style="{ width: runProgress + '%' }" />
        <span class="eval-progress__text">正在逐条评测 golden 样本… {{ runSeconds }}s</span>
      </div>
    </section>

    <section v-if="trend.length" class="panel eval-chart">
      <header class="panel__head">
        <div>
          <p class="panel__eyebrow">指标趋势</p>
          <h2 class="panel__title">Faithfulness / Context Recall 走势</h2>
        </div>
        <div class="eval-chart__legend" aria-hidden="true">
          <span class="legend-item legend-item--faith">可信度</span>
          <span class="legend-item legend-item--recall">上下文召回</span>
          <span class="legend-item legend-item--gate">门禁阈值</span>
        </div>
      </header>

      <div class="eval-chart__grid">
        <div class="eval-chart__plot">
          <svg
            :viewBox="`0 0 ${CHART.w} ${CHART.h}`"
            class="eval-chart__svg"
            role="img"
            :aria-label="chartAria"
            preserveAspectRatio="xMidYMid meet"
          >
            <defs>
              <linearGradient id="evalFaithFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.16" />
                <stop offset="100%" stop-color="var(--accent)" stop-opacity="0" />
              </linearGradient>
            </defs>

            <g v-for="tick in yTicks" :key="tick">
              <line
                class="chart-gridline"
                :x1="CHART.ml"
                :x2="CHART.w - CHART.mr"
                :y1="yPos(tick, range)"
                :y2="yPos(tick, range)"
              />
              <text class="chart-axis" :x="CHART.ml - 8" :y="yPos(tick, range) + 4" text-anchor="end">
                {{ (tick * 100).toFixed(0) }}%
              </text>
            </g>

            <!-- 门禁阈值 -->
            <line
              class="chart-gate chart-gate--faith"
              :x1="CHART.ml"
              :x2="CHART.w - CHART.mr"
              :y1="yPos(GATE.faithfulness, range)"
              :y2="yPos(GATE.faithfulness, range)"
            />
            <line
              class="chart-gate chart-gate--recall"
              :x1="CHART.ml"
              :x2="CHART.w - CHART.mr"
              :y1="yPos(GATE.contextRecall, range)"
              :y2="yPos(GATE.contextRecall, range)"
            />
            <text
              class="chart-gate-label chart-gate-label--faith"
              :x="CHART.w - CHART.mr"
              :y="yPos(GATE.faithfulness, range) - 6"
              text-anchor="end"
            >
              门禁 90%
            </text>
            <text
              class="chart-gate-label chart-gate-label--recall"
              :x="CHART.w - CHART.mr"
              :y="yPos(GATE.contextRecall, range) + 13"
              text-anchor="end"
            >
              门禁 85%
            </text>

            <polygon v-if="faithPoints.length" class="chart-area" :points="areaPoints(faithPoints)" />
            <polyline v-if="faithPoints.length" class="chart-line chart-line--faith" :points="polyline(faithPoints)" />
            <polyline v-if="recallPoints.length" class="chart-line chart-line--recall" :points="polyline(recallPoints)" />

            <g v-for="point in faithPoints" :key="'f' + point.x">
              <circle class="chart-dot chart-dot--faith" :cx="point.x" :cy="point.y" r="4">
                <title>{{ point.item.reportId }} · 可信度 {{ (point.value * 100).toFixed(1) }}% · {{ shortTime(point.item.generatedAt) }}</title>
              </circle>
            </g>
            <g v-for="point in recallPoints" :key="'r' + point.x">
              <circle class="chart-dot chart-dot--recall" :cx="point.x" :cy="point.y" r="3">
                <title>{{ point.item.reportId }} · 召回 {{ (point.value * 100).toFixed(1) }}% · {{ shortTime(point.item.generatedAt) }}</title>
              </circle>
            </g>

            <g v-for="label in xLabels" :key="label.x">
              <text class="chart-axis chart-axis--x" :x="label.x" :y="CHART.h - 8" text-anchor="middle">
                {{ label.text }}
              </text>
            </g>
          </svg>
        </div>

        <aside class="eval-chart__side">
          <div class="eval-pass">
            <svg :viewBox="`0 0 ${DONUT.r * 2 + DONUT.stroke * 2} ${DONUT.r * 2 + DONUT.stroke * 2}`" class="eval-pass__donut" role="img" :aria-label="`门禁通过率 ${(passStats.ratio * 100).toFixed(0)}%`">
              <circle
                class="eval-pass__track"
                :cx="DONUT.r + DONUT.stroke"
                :cy="DONUT.r + DONUT.stroke"
                :r="DONUT.r"
                fill="none"
                :stroke-width="DONUT.stroke"
              />
              <circle
                class="eval-pass__arc"
                :cx="DONUT.r + DONUT.stroke"
                :cy="DONUT.r + DONUT.stroke"
                :r="DONUT.r"
                fill="none"
                :stroke-width="DONUT.stroke"
                stroke-linecap="round"
                :stroke-dasharray="donutDash"
                transform="rotate(-90 37 37)"
              />
              <text class="eval-pass__value" x="37" y="34" text-anchor="middle">{{ (passStats.ratio * 100).toFixed(0) }}%</text>
              <text class="eval-pass__label" x="37" y="48" text-anchor="middle">门禁通过率</text>
            </svg>
            <p class="eval-pass__note">{{ passStats.passed }} / {{ passStats.scored }} 份报告两项指标均达标</p>
          </div>
          <dl class="eval-chart__stats">
            <div>
              <dt>最新可信度</dt>
              <dd>{{ percent(trend[trend.length - 1]?.faithfulness) }}</dd>
            </div>
            <div>
              <dt>最新上下文召回</dt>
              <dd>{{ percent(trend[trend.length - 1]?.contextRecall) }}</dd>
            </div>
            <div>
              <dt>统计报告数</dt>
              <dd>{{ trend.length }}<span class="eval-chart__unit"> / 最近 {{ TREND_MAX }} 份</span></dd>
            </div>
          </dl>
        </aside>
      </div>
    </section>

    <section class="panel eval-history">
      <header class="panel__head">
        <div>
          <p class="panel__eyebrow">评测归档</p>
          <h2 class="panel__title">历史报告 <span class="count-chip">{{ total }}</span></h2>
        </div>
        <span class="muted eval-history__hint">报告已脱敏，仅保留指标与样本摘要</span>
      </header>

      <div v-if="error" class="alert alert--danger" role="alert">
        <Icon name="alert-circle" :size="16" />
        <span>{{ error }}</span>
        <button class="btn btn--sm btn--outline" type="button" @click="load()">重试</button>
      </div>

      <div v-else-if="loading" class="table-skeleton" aria-hidden="true">
        <span v-for="row in 4" :key="row" class="skeleton skeleton--row" />
      </div>

      <div v-else-if="!reports.length" class="panel__empty">
        <span class="panel__empty-icon"><Icon name="clipboard-check" :size="22" /></span>
        <p>还没有评测记录。</p>
        <button class="btn btn--outline btn--sm" type="button" @click="runEvaluation">运行首次评测</button>
      </div>

      <div v-else class="eval-table-wrap">
        <table class="eval-table">
          <thead>
            <tr>
              <th scope="col">报告</th>
              <th scope="col">状态</th>
              <th scope="col">样本</th>
              <th scope="col">可信度</th>
              <th scope="col">召回</th>
              <th scope="col">生成时间</th>
              <th scope="col"><span class="visually-hidden">查看</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in reports" :key="item.reportId" :class="{ 'is-latest': item.reportId === latest?.reportId }">
              <td>
                <button class="eval-table__report" type="button" @click="openDetail(item)">
                  <span class="eval-table__id">{{ item.reportId }}</span>
                  <span class="eval-table__dataset">{{ item.dataset }} · {{ item.sampleCount }} 样本</span>
                </button>
              </td>
              <td>
                <span class="tag" :class="'tag--' + statusTone(item.status)">{{ item.status }}</span>
              </td>
              <td class="num">{{ item.sampleCount }}</td>
              <td class="num" :class="{ 'is-weak': item.faithfulness != null && item.faithfulness < GATE.faithfulness }">
                {{ percent(item.faithfulness) }}
              </td>
              <td class="num" :class="{ 'is-weak': item.contextRecall != null && item.contextRecall < GATE.contextRecall }">
                {{ percent(item.contextRecall) }}
              </td>
              <td class="muted">{{ relativeTime(item.generatedAt) }}</td>
              <td>
                <button class="icon-btn icon-btn--sm" type="button" aria-label="查看报告详情" @click="openDetail(item)">
                  <Icon name="chevron-right" :size="15" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <SlideOver :open="detailOpen" title="评测报告" eyebrow="RAGAS 明细" wide @close="closeDetail">
      <div v-if="detailLoading" class="stack-skeleton" aria-hidden="true">
        <span class="skeleton skeleton--line" />
        <span class="skeleton skeleton--wide" />
        <span class="skeleton skeleton--line" />
      </div>
      <div v-else-if="detailError" class="alert alert--danger" role="alert">
        <Icon name="alert-circle" :size="16" />
        <span>{{ detailError }}</span>
      </div>
      <div v-else-if="detail" class="eval-detail">
        <div class="eval-detail__head">
          <div>
            <h3>{{ detail.reportId }}</h3>
            <p class="muted">{{ detail.dataset }} · {{ formatDateTime(detail.generatedAt) }} · {{ detail.sampleCount }} 个样本</p>
          </div>
          <span class="tag" :class="'tag--' + statusTone(detail.status)">{{ detail.status }}</span>
        </div>

        <div class="eval-detail__averages">
          <article>
            <span>可信度</span>
            <strong>{{ percent(detail.averages?.faithfulness) }}</strong>
          </article>
          <article>
            <span>上下文召回</span>
            <strong>{{ percent(detail.averages?.contextRecall) }}</strong>
          </article>
          <article>
            <span>门禁判定</span>
            <strong>{{ detail.faithfulness >= GATE.faithfulness && detail.contextRecall >= GATE.contextRecall ? '通过' : '未通过' }}</strong>
          </article>
        </div>

        <p v-if="detail.reason" class="alert alert--info">
          <Icon name="info" :size="16" />
          <span>{{ detail.reason }}</span>
        </p>

        <div v-if="scatterPoints.length || histData.some((bucket) => bucket.count > 0)" class="eval-detail__charts">
          <section class="eval-detail__chart" v-if="scatterPoints.length">
            <header class="eval-detail__chart-head">
              <h4>样本分布</h4>
              <span class="muted">横轴 可信度 · 纵轴 上下文召回 · 每点一个样本</span>
            </header>
            <svg
              :viewBox="`0 0 ${SCATTER.w} ${SCATTER.h}`"
              class="eval-detail__chart-svg"
              role="img"
              :aria-label="`样本分布散点图，共 ${scatterPoints.length} 个有效样本`"
              preserveAspectRatio="xMidYMid meet"
            >
              <line class="chart-gridline" :x1="SCATTER.ml" :x2="SCATTER.w - SCATTER.mr" :y1="SCATTER.mt" :y2="SCATTER.mt" />
              <line class="chart-gridline" :x1="SCATTER.ml" :x2="SCATTER.w - SCATTER.mr" :y1="scatterPosition(0.5, SCATTER.h, SCATTER.mt, SCATTER.mb)" :y2="scatterPosition(0.5, SCATTER.h, SCATTER.mt, SCATTER.mb)" />
              <line class="chart-gridline" :x1="SCATTER.ml" :x2="SCATTER.w - SCATTER.mr" :y1="SCATTER.h - SCATTER.mb" :y2="SCATTER.h - SCATTER.mb" />
              <line class="chart-gridline" :x1="SCATTER.ml" :x2="SCATTER.ml" :y1="SCATTER.mt" :y2="SCATTER.h - SCATTER.mb" />
              <line class="chart-gridline" :x1="scatterPosition(0.5, SCATTER.w, SCATTER.ml, SCATTER.mr)" :x2="scatterPosition(0.5, SCATTER.w, SCATTER.ml, SCATTER.mr)" :y1="SCATTER.mt" :y2="SCATTER.h - SCATTER.mb" />
              <line class="chart-gridline" :x1="SCATTER.w - SCATTER.mr" :x2="SCATTER.w - SCATTER.mr" :y1="SCATTER.mt" :y2="SCATTER.h - SCATTER.mb" />

              <line
                class="chart-gate chart-gate--faith"
                :x1="scatterPosition(GATE.faithfulness, SCATTER.w, SCATTER.ml, SCATTER.mr)"
                :x2="scatterPosition(GATE.faithfulness, SCATTER.w, SCATTER.ml, SCATTER.mr)"
                :y1="SCATTER.mt"
                :y2="SCATTER.h - SCATTER.mb"
              />
              <line
                class="chart-gate chart-gate--recall"
                :x1="SCATTER.ml"
                :x2="SCATTER.w - SCATTER.mr"
                :y1="scatterPosition(GATE.contextRecall, SCATTER.h, SCATTER.mt, SCATTER.mb)"
                :y2="scatterPosition(GATE.contextRecall, SCATTER.h, SCATTER.mt, SCATTER.mb)"
              />

              <g v-for="point in scatterPoints" :key="point.index">
                <circle
                  class="scatter-dot"
                  :class="point.pass ? 'is-pass' : 'is-warn'"
                  :cx="point.x"
                  :cy="point.y"
                  r="4.5"
                >
                  <title>{{ point.question }} — 可信度 {{ percent(point.faith) }} · 召回 {{ percent(point.recall) }}</title>
                </circle>
              </g>

              <text class="chart-axis" :x="SCATTER.ml" :y="SCATTER.mt - 6">100%</text>
              <text class="chart-axis" :x="SCATTER.ml" :y="SCATTER.h - SCATTER.mb + 16">0%</text>
              <text class="chart-axis" :x="SCATTER.w - SCATTER.mr" :y="SCATTER.h - SCATTER.mb + 16" text-anchor="end">100%</text>
            </svg>
            <div class="eval-detail__chart-legend" aria-hidden="true">
              <span class="legend-item legend-item--pass">两项达标</span>
              <span class="legend-item legend-item--warn">未达标</span>
              <span class="legend-item legend-item--gate">门禁阈值</span>
            </div>
          </section>

          <section class="eval-detail__chart" v-if="histData.some((bucket) => bucket.count > 0)">
            <header class="eval-detail__chart-head">
              <h4>可信度分布</h4>
              <span class="muted">各分值区间样本数量</span>
            </header>
            <svg
              :viewBox="`0 0 ${HIST.w} ${HIST.h}`"
              class="eval-detail__chart-svg"
              role="img"
              :aria-label="`可信度直方图：` + histData.map((bucket) => `${bucket.label} ${bucket.count} 个样本`).join('，')"
              preserveAspectRatio="xMidYMid meet"
            >
              <g v-for="(bucket, index) in histData" :key="bucket.label">
                <rect
                  class="hist-bar"
                  :x="HIST.ml + index * ((HIST.w - HIST.ml - HIST.mr) / HIST_BUCKETS.length) + ((HIST.w - HIST.ml - HIST.mr) / HIST_BUCKETS.length - 46) / 2"
                  :width="46"
                  :height="bucket.height"
                  :y="HIST.h - HIST.mb - bucket.height"
                  rx="6"
                >
                  <title>{{ bucket.label }} · {{ bucket.count }} 个样本（{{ (bucket.ratio * 100).toFixed(1) }}%）</title>
                </rect>
                <text
                  class="hist-value"
                  :x="HIST.ml + (index + 0.5) * ((HIST.w - HIST.ml - HIST.mr) / HIST_BUCKETS.length)"
                  :y="HIST.h - HIST.mb - bucket.height - 6"
                  text-anchor="middle"
                  v-if="bucket.count > 0"
                >
                  {{ bucket.count }}
                </text>
                <text
                  class="chart-axis chart-axis--x"
                  :x="HIST.ml + (index + 0.5) * ((HIST.w - HIST.ml - HIST.mr) / HIST_BUCKETS.length)"
                  :y="HIST.h - 8"
                  text-anchor="middle"
                >
                  {{ bucket.label }}
                </text>
              </g>
            </svg>
          </section>
        </div>

        <div class="eval-detail__samples">
          <header>
            <h4>样本明细</h4>
            <span class="muted">已脱敏 · 第 {{ samplePage + 1 }} / {{ samplePageCount }} 页</span>
          </header>
          <ul class="sample-list">
            <li v-for="(sample, index) in sampleSlice" :key="index" class="sample">
              <div class="sample__question">{{ sample.userInput }}</div>
              <div class="sample__scores">
                <span class="tag" :class="'tag--' + sampleTone(sample.faithfulness)">
                  可信度 {{ percent(sample.faithfulness) }}
                </span>
                <span class="tag" :class="'tag--' + sampleTone(sample.contextRecall)">
                  召回 {{ percent(sample.contextRecall) }}
                </span>
                <span v-if="sample.error" class="sample__error">{{ errorLabel(sample) }}</span>
              </div>
            </li>
          </ul>
          <div class="sample-pager">
            <button class="btn btn--ghost btn--sm" type="button" :disabled="samplePage === 0" @click="samplePage -= 1">
              <Icon name="chevron-left" :size="15" />上一页
            </button>
            <button
              class="btn btn--ghost btn--sm"
              type="button"
              :disabled="samplePage >= samplePageCount - 1"
              @click="samplePage += 1"
            >
              下一页<Icon name="chevron-right" :size="15" />
            </button>
          </div>
        </div>
      </div>
    </SlideOver>
  </div>
</template>

<style scoped>
.eval { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; min-width: 0; }
.eval > * { min-width: 0; }
.eval-latest { display: grid; gap: 18px; padding: 22px 24px; }
.eval-latest__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.panel__eyebrow { margin: 0 0 4px; font-size: 11px; font-weight: 650; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink-3); }
.panel__title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 17px; font-weight: 650; letter-spacing: -0.015em; }
.eval-latest__meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; }
.eval-gate { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; }
.eval-gate__item { display: grid; gap: 4px; padding: 15px 17px; border-right: 1px solid var(--line); background: var(--paper); }
.eval-gate__item:last-child { border-right: 0; }
.eval-gate__label { font-size: 11.5px; color: var(--ink-3); }
.eval-gate__value { font-size: 24px; font-weight: 680; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.eval-gate__hint { font-size: 11px; color: var(--ink-3); }
.eval-gate__hint.is-pass { color: var(--success); }
.eval-gate__hint.is-fail { color: var(--danger); }
.eval-reason { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 12.5px; color: var(--ink-2); }
.eval-latest__actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.eval-latest__note { font-size: 11.5px; color: var(--ink-3); }
.eval-progress { position: relative; height: 30px; border-radius: 9px; background: var(--paper-2); overflow: hidden; }
.eval-progress__bar { position: absolute; inset: 0 auto 0 0; background: var(--accent-soft); transition: width 900ms ease; }
.eval-progress__text { position: relative; display: grid; place-items: center; height: 100%; font-size: 12px; color: var(--accent-ink); }

/* ---------- 指标趋势图 ---------- */
.eval-chart { padding: 22px 24px; display: grid; gap: 16px; }
.eval-chart__legend { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink-2); }
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.legend-item::before { content: ''; width: 14px; height: 3px; border-radius: 2px; background: currentColor; }
.legend-item--faith { color: var(--accent); }
.legend-item--recall { color: var(--success); }
.legend-item--recall::before { background: repeating-linear-gradient(90deg, currentColor 0 5px, transparent 5px 9px); }
.legend-item--gate { color: var(--ink-3); }
.legend-item--gate::before { background: repeating-linear-gradient(90deg, currentColor 0 4px, transparent 4px 8px); height: 1px; }
.legend-item--pass::before { width: 8px; height: 8px; border-radius: 50%; background: var(--success); }
.legend-item--warn::before { width: 8px; height: 8px; border-radius: 50%; background: var(--warning); }
.eval-chart__grid { display: grid; grid-template-columns: minmax(0, 1fr) 240px; gap: 22px; align-items: center; }
.eval-chart__plot { min-width: 0; }
.eval-chart__svg { display: block; width: 100%; height: auto; }
.chart-gridline { stroke: var(--line); stroke-width: 1; }
.chart-axis { fill: var(--ink-3); font-size: 11px; font-variant-numeric: tabular-nums; }
.chart-axis--x { font-size: 10.5px; }
.chart-gate { stroke-width: 1.2; stroke-dasharray: 5 5; }
.chart-gate--faith { stroke: var(--accent); stroke-opacity: 0.55; }
.chart-gate--recall { stroke: var(--success); stroke-opacity: 0.55; }
.chart-gate-label { fill: var(--ink-3); font-size: 10px; }
.chart-area { fill: url(#evalFaithFill); }
.chart-line { fill: none; stroke-width: 2.5; stroke-linejoin: round; stroke-linecap: round; }
.chart-line--faith { stroke: var(--accent); }
.chart-line--recall { stroke: var(--success); stroke-dasharray: 7 5; }
.chart-dot--faith { fill: var(--surface); stroke: var(--accent); stroke-width: 2.2; }
.chart-dot--recall { fill: var(--success); stroke: var(--surface); stroke-width: 1.5; }
.eval-chart__side { display: grid; gap: 16px; justify-items: center; min-width: 0; }
.eval-pass { display: grid; justify-items: center; gap: 10px; }
.eval-pass__donut { width: 74px; height: 74px; }
.eval-pass__track { stroke: var(--paper-2); }
.eval-pass__arc { stroke: var(--accent); }
.eval-pass__value { fill: var(--ink); font-size: 15px; font-weight: 680; font-variant-numeric: tabular-nums; }
.eval-pass__label { fill: var(--ink-3); font-size: 9.5px; }
.eval-pass__note { margin: 0; font-size: 11.5px; color: var(--ink-3); text-align: center; }
.eval-chart__stats { display: grid; gap: 10px; margin: 0; width: 100%; }
.eval-chart__stats > div { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 11px; background: var(--paper); }
.eval-chart__stats dt { font-size: 11.5px; color: var(--ink-3); }
.eval-chart__stats dd { margin: 0; font-size: 15px; font-weight: 650; font-variant-numeric: tabular-nums; color: var(--ink); }
.eval-chart__unit { font-size: 11px; font-weight: 480; color: var(--ink-3); }

/* ---------- 历史表 ---------- */
.eval-history { padding: 22px 24px; }
.panel__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
.eval-history__hint { font-size: 11.5px; }
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
.panel__empty { display: grid; justify-items: center; gap: 10px; padding: 42px 0; color: var(--ink-2); }
.panel__empty-icon { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 14px; background: var(--paper-2); color: var(--accent); }
.eval-table-wrap { overflow-x: auto; }
.eval-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.eval-table th {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-align: left;
  color: var(--ink-3);
  white-space: nowrap;
}
.eval-table td { padding: 12px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.eval-table tr:last-child td { border-bottom: 0; }
.eval-table tbody tr { transition: background 140ms ease; }
.eval-table tbody tr:hover { background: var(--paper); }
.eval-table tr.is-latest td:first-child { box-shadow: inset 2px 0 0 var(--accent); }
.eval-table .num { font-variant-numeric: tabular-nums; white-space: nowrap; }
.eval-table .is-weak { color: var(--danger); font-weight: 600; }
.eval-table__report { display: grid; gap: 2px; padding: 0; border: 0; background: none; font: inherit; text-align: left; cursor: pointer; }
.eval-table__id { font-family: var(--font-mono); font-size: 12px; color: var(--ink); }
.eval-table__dataset { font-size: 11.5px; color: var(--ink-3); }

/* ---------- 报告抽屉 ---------- */
.eval-detail { display: grid; gap: 18px; }
.eval-detail__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.eval-detail__head h3 { margin: 0 0 4px; font-family: var(--font-mono); font-size: 14px; font-weight: 600; }
.eval-detail__head p { margin: 0; font-size: 12px; }
.eval-detail__averages { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--line); border-radius: 13px; overflow: hidden; }
.eval-detail__averages article { display: grid; gap: 4px; padding: 13px 16px; border-right: 1px solid var(--line); background: var(--paper); }
.eval-detail__averages article:last-child { border-right: 0; }
.eval-detail__averages span { font-size: 11.5px; color: var(--ink-3); }
.eval-detail__averages strong { font-size: 20px; font-weight: 680; font-variant-numeric: tabular-nums; }
.eval-detail__charts { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 16px; }
.eval-detail__chart { display: grid; gap: 10px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 13px; background: var(--surface); min-width: 0; }
.eval-detail__chart-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.eval-detail__chart-head h4 { margin: 0; font-size: 13.5px; font-weight: 650; }
.eval-detail__chart-head .muted { font-size: 11px; }
.eval-detail__chart-svg { display: block; width: 100%; height: auto; }
.eval-detail__chart-legend { display: flex; align-items: center; gap: 14px; font-size: 11px; color: var(--ink-3); }
.scatter-dot { fill-opacity: 0.85; stroke: var(--surface); stroke-width: 1; }
.scatter-dot.is-pass { fill: var(--success); }
.scatter-dot.is-warn { fill: var(--warning); }
.hist-bar { fill: var(--accent); fill-opacity: 0.85; }
.hist-bar:hover { fill-opacity: 1; }
.hist-value { fill: var(--ink-2); font-size: 11px; font-weight: 600; font-variant-numeric: tabular-nums; }
.eval-detail__samples { display: grid; gap: 10px; }
.eval-detail__samples header { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.eval-detail__samples h4 { margin: 0; font-size: 13.5px; font-weight: 650; }
.eval-detail__samples header .muted { font-size: 11.5px; }
.sample-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.sample { display: grid; gap: 7px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); }
.sample__question { font-size: 12.5px; line-height: 1.6; color: var(--ink-2); }
.sample__scores { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.sample__error { font-size: 11.5px; color: var(--danger); }
.sample-pager { display: flex; justify-content: flex-end; gap: 8px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

@media (max-width: 1024px) {
  .eval-chart__grid { grid-template-columns: minmax(0, 1fr); }
  .eval-chart__side { grid-template-columns: auto minmax(0, 1fr); justify-items: stretch; align-items: center; }
  .eval-pass { justify-items: start; }
}
@media (max-width: 860px) {
  .eval-gate { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .eval-gate__item:nth-child(2) { border-right: 0; }
  .eval-gate__item:nth-child(-n + 2) { border-bottom: 1px solid var(--line); }
  .eval-detail__charts { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 640px) {
  .eval-latest, .eval-history, .eval-chart { padding: 18px 16px; }
  .eval-chart__side { grid-template-columns: minmax(0, 1fr); }
  .eval-pass { justify-items: center; }
  .eval-table th:nth-child(3), .eval-table td:nth-child(3),
  .eval-table th:nth-child(6), .eval-table td:nth-child(6) { display: none; }
  .eval-detail__averages { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .eval-detail__averages article:nth-child(2) { border-right: 0; }
  .eval-detail__averages article:last-child { grid-column: 1 / -1; border-top: 1px solid var(--line); }
}
</style>

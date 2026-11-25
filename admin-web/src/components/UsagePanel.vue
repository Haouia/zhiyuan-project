<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'
import { formatCompact, formatNumber } from '../labels'

const props = defineProps({
  report: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  date: { type: String, required: true },
  refreshing: { type: Boolean, default: false },
})

const emit = defineEmits(['refresh', 'update:date'])

const PURPOSE_LABELS = {
  EMBEDDING: '向量化',
  ANSWER: '回答生成',
  GENERATION: '回答生成',
  RERANK: '结果重排',
  QUERY: '查询理解',
  CHAT: '回答生成',
  embedding: '向量化',
  answer: '回答生成',
  generation: '回答生成',
  rerank: '结果重排',
  query: '查询理解',
  chat: '回答生成',
}

const models = computed(() => props.report?.models ?? [])

const totals = computed(() => {
  const result = { input: 0, output: 0, cost: 0, pending: 0 }
  for (const item of models.value) {
    result.input += Number(item.inputTokens) || 0
    result.output += Number(item.outputTokens) || 0
    result.cost += Number(item.estimatedCost) || 0
    result.pending += Number(item.reconcilingAttempts) || 0
  }
  return result
})

const currency = computed(() => props.report?.currency ?? 'CNY')

function money(value) {
  const number = Number(value) || 0
  return number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}

function share(item) {
  const total = Number(item.inputTokens) + Number(item.outputTokens)
  if (!total) return 0
  return Math.round((Number(item.inputTokens) / total) * 100)
}

function purposeLabel(purpose) {
  return PURPOSE_LABELS[purpose] ?? purpose
}

function changeDate(event) {
  emit('update:date', event.target.value)
}
</script>

<template>
  <section class="usage" aria-labelledby="usage-heading">
    <header class="usage__head">
      <div>
        <p class="eyebrow">成本与用量</p>
        <h1 id="usage-heading">租户用量</h1>
        <p class="usage__sub">按自然日统计模型调用情况，费用为估算值，待对账数量会在模型账单核销后归零。</p>
      </div>
      <div class="usage__controls">
        <label class="date-field">
          <Icon name="calendar" :size="15" />
          <input :value="date" type="date" aria-label="选择统计日期" maxlength="10" @change="changeDate">
        </label>
        <button class="btn btn--outline btn--sm" type="button" :disabled="refreshing" @click="emit('refresh')">
          <Icon name="refresh" :size="15" :class="{ spinning: refreshing }" />
          刷新
        </button>
      </div>
    </header>

    <div class="stat-grid">
      <article class="stat-card">
        <span class="stat-card__label">输入 Token</span>
        <strong class="stat-card__value">{{ formatCompact(totals.input) }}</strong>
        <span class="stat-card__meta">{{ formatNumber(totals.input) }} tokens</span>
      </article>
      <article class="stat-card">
        <span class="stat-card__label">输出 Token</span>
        <strong class="stat-card__value">{{ formatCompact(totals.output) }}</strong>
        <span class="stat-card__meta">{{ formatNumber(totals.output) }} tokens</span>
      </article>
      <article class="stat-card stat-card--accent">
        <span class="stat-card__label">估算费用</span>
        <strong class="stat-card__value">{{ currency }} {{ money(totals.cost) }}</strong>
        <span class="stat-card__meta">共 {{ models.length }} 个模型分组</span>
      </article>
      <article class="stat-card">
        <span class="stat-card__label">待对账次数</span>
        <strong class="stat-card__value">{{ formatNumber(totals.pending) }}</strong>
        <span class="stat-card__meta">模型侧账单核销队列</span>
      </article>
    </div>

    <p v-if="error" class="alert alert--danger" role="alert">{{ error }}</p>

    <section class="panel usage__panel" aria-label="模型调用明细">
      <header class="panel__head">
        <div class="panel__title">
          <h2>模型调用明细</h2>
          <span class="count-chip">{{ models.length }}</span>
        </div>
        <span class="muted usage__date-label">{{ date }}</span>
      </header>

      <div v-if="loading" class="stack-skeleton usage__skeleton">
        <span v-for="n in 4" :key="n" class="skeleton skeleton--row" />
      </div>

      <div v-else-if="!models.length" class="empty empty--compact">
        <span class="empty__art" aria-hidden="true"><Icon name="chart" :size="26" /></span>
        <h3>当日暂无模型调用</h3>
        <p>选择其他日期查看历史用量，或在知识问答中发起一次提问。</p>
      </div>

      <div v-else class="table-wrap">
        <table class="usage-table">
          <thead>
            <tr>
              <th scope="col">模型</th>
              <th scope="col">用途</th>
              <th scope="col">Token 构成</th>
              <th scope="col" class="num">输入</th>
              <th scope="col" class="num">输出</th>
              <th scope="col" class="num">待对账</th>
              <th scope="col" class="num">估算费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in models" :key="item.model + item.purpose">
              <td>
                <div class="model-cell">
                  <span class="model-cell__icon"><Icon name="layers" :size="15" /></span>
                  <strong>{{ item.model }}</strong>
                </div>
              </td>
              <td><span class="tag">{{ purposeLabel(item.purpose) }}</span></td>
              <td class="bar-cell">
                <div class="bar" role="img" :aria-label="'输入占比 ' + share(item) + '%'">
                  <span class="bar__in" :style="{ width: share(item) + '%' }" />
                  <span class="bar__out" :style="{ width: 100 - share(item) + '%' }" />
                </div>
              </td>
              <td class="num">{{ formatNumber(item.inputTokens) }}</td>
              <td class="num">{{ formatNumber(item.outputTokens) }}</td>
              <td class="num">{{ formatNumber(item.reconcilingAttempts) }}</td>
              <td class="num cost">{{ currency }} {{ money(item.estimatedCost) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="usage__legend">
        <span class="legend"><i class="legend__dot legend__dot--in" /> 输入 Token</span>
        <span class="legend"><i class="legend__dot legend__dot--out" /> 输出 Token</span>
        <span class="muted">费用按模型单价估算，仅供成本观察，不作为结算依据。</span>
      </footer>
    </section>
  </section>
</template>

<style scoped>
.usage { display: grid; gap: 18px; }
.usage__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}
.usage__head h1 {
  margin: 6px 0 0;
  font-size: clamp(26px, 3vw, 32px);
  letter-spacing: -0.03em;
  font-weight: 680;
  line-height: 1.15;
}
.usage__sub { margin: 8px 0 0; max-width: 62ch; font-size: 13px; line-height: 1.65; color: var(--ink-2); }
.usage__controls { display: flex; align-items: center; gap: 8px; }
.date-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
}
.date-field:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.date-field input {
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat-card {
  display: grid;
  gap: 6px;
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
}
.stat-card--accent { background: linear-gradient(150deg, var(--surface), var(--accent-soft)); border-color: #DCDFF7; }
.stat-card__label { font-size: 12px; color: var(--ink-3); }
.stat-card__value { font-size: 24px; font-weight: 680; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.stat-card--accent .stat-card__value { font-size: 20px; color: var(--accent-ink); }
.stat-card__meta { font-size: 11.5px; color: var(--ink-3); }
.usage__panel { overflow: hidden; }
.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 17px 20px;
  border-bottom: 1px solid var(--line);
}
.panel__title { display: flex; align-items: center; gap: 9px; }
.panel__title h2 { margin: 0; font-size: 14.5px; font-weight: 650; }
.count-chip {
  display: inline-grid;
  place-items: center;
  min-width: 22px;
  height: 19px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--paper-2);
  font-size: 10.5px;
  font-weight: 650;
  color: var(--ink-2);
}
.usage__date-label { font-size: 12px; font-variant-numeric: tabular-nums; }
.table-wrap { overflow-x: auto; }
.usage-table { width: 100%; border-collapse: collapse; min-width: 680px; }
.usage-table th {
  padding: 10px 16px;
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-3);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.usage-table th.num, .usage-table td.num { text-align: right; }
.usage-table td { padding: 12px 16px; border-bottom: 1px solid var(--line); font-size: 13px; }
.usage-table tbody tr:last-child td { border-bottom: 0; }
.usage-table tbody tr:hover { background: var(--paper); }
.usage-table td.num { font-variant-numeric: tabular-nums; color: var(--ink-2); white-space: nowrap; }
.usage-table td.cost { color: var(--ink); font-weight: 600; }
.model-cell { display: flex; align-items: center; gap: 9px; }
.model-cell__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--accent-ink);
}
.model-cell strong { font-size: 12.5px; font-weight: 620; }
.bar-cell { width: 150px; }
.bar { display: flex; height: 7px; border-radius: 999px; overflow: hidden; background: var(--paper-2); }
.bar__in { background: var(--accent); opacity: 0.9; }
.bar__out { background: var(--success); opacity: 0.75; }
.usage__legend {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 12px 20px 14px;
  border-top: 1px solid var(--line);
  font-size: 11.5px;
}
.legend { display: inline-flex; align-items: center; gap: 6px; color: var(--ink-2); }
.legend__dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.legend__dot--in { background: var(--accent); }
.legend__dot--out { background: var(--success); opacity: 0.75; }
.usage__skeleton { padding: 18px 20px; }
.spinning { animation: spin 900ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1024px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .stat-grid { grid-template-columns: 1fr; }
  .usage__controls { width: 100%; }
  .date-field { flex: 1; }
}
</style>

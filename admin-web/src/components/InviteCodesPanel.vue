<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from './Icon.vue'
import { formatDateTime } from '../labels'

const props = defineProps({
  api: { type: Function, required: true },
})
const emit = defineEmits(['notify'])

const data = ref(null)
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const copied = ref(false)
const remaining = ref(0)
let ticker = null
let copyTimer = null

const active = computed(() => data.value?.active ?? null)
const history = computed(() => data.value?.history ?? [])
const rotationMinutes = computed(() => data.value?.rotationMinutes ?? 120)

// 纯展示：按 generatedAt → expiresAt 的窗口计算剩余比例，驱动进度条。
const progressPercent = computed(() => {
  const generatedAt = Number(active.value?.generatedAt)
  const expiresAt = Number(active.value?.expiresAt)
  if (!generatedAt || !expiresAt || expiresAt <= generatedAt) return 0
  const total = (expiresAt - generatedAt) / 1000
  if (total <= 0) return 0
  return Math.min(100, Math.max(0, (remaining.value / total) * 100))
})

function countdownLabel() {
  const total = remaining.value
  if (total <= 0) return '轮换中…'
  const mm = String(Math.floor(total / 60)).padStart(2, '0')
  const ss = String(total % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

function syncTicker() {
  const expiresAt = active.value?.expiresAt
  if (!expiresAt) {
    remaining.value = 0
    return
  }
  remaining.value = Math.max(0, Math.floor((Number(expiresAt) - Date.now()) / 1000))
}

function startTicker() {
  stopTicker()
  ticker = window.setInterval(syncTicker, 1000)
}

function stopTicker() {
  if (ticker) {
    window.clearInterval(ticker)
    ticker = null
  }
}

async function load({ quiet = false } = {}) {
  if (quiet) refreshing.value = true
  else loading.value = true
  try {
    data.value = await props.api('/invite-codes')
    error.value = ''
    syncTicker()
    startTicker()
  } catch (failure) {
    if (!quiet) error.value = failure.message
    else emit('notify', { tone: 'danger', title: '刷新失败', message: failure.message })
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function copyCode() {
  const code = active.value?.code
  if (!code) return
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(code)
    else {
      const area = document.createElement('textarea')
      area.value = code
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      area.remove()
    }
  } catch {
    emit('notify', { tone: 'danger', title: '复制失败', message: '请手动复制邀请码。' })
    return
  }
  copied.value = true
  if (copyTimer) window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => (copied.value = false), 1800)
}

async function rotate() {
  const ok = window.confirm('将立即作废当前邀请码并生成新码，确认轮换？')
  if (!ok) return
  try {
    data.value = await props.api('/invite-codes/rotate', { method: 'POST' })
    syncTicker()
    startTicker()
    emit('notify', { tone: 'success', title: '邀请码已轮换', message: '旧码已失效，新码立即生效。' })
  } catch (failure) {
    emit('notify', { tone: 'danger', title: '轮换失败', message: failure.message })
  }
}

onMounted(() => load())
onBeforeUnmount(() => {
  stopTicker()
  if (copyTimer) window.clearTimeout(copyTimer)
})
</script>

<template>
  <div class="invite">
    <div v-if="loading" class="invite__skeleton" aria-label="加载中">
      <div class="skeleton skeleton--wide"></div>
      <div class="skeleton skeleton--row"></div>
      <div class="skeleton skeleton--row"></div>
    </div>

    <div v-else-if="error" class="alert alert--danger" role="alert">
      <p>{{ error }}</p>
      <button class="btn btn--ghost btn--sm" type="button" @click="load()">重新加载</button>
    </div>

    <template v-else>
      <section class="panel invite-active" aria-label="当前邀请码">
        <header class="panel__head invite-active__head">
          <div class="invite-head__text">
            <h2 class="invite-head__title">
              <span class="pulse-dot" aria-hidden="true"></span>
              当前邀请码
            </h2>
            <p class="invite-head__hint">发给新成员，注册时输入即可加入本租户</p>
          </div>
          <span class="chip invite-active__period">
            <Icon name="clock" :size="12" />
            {{ rotationMinutes }} 分钟轮换
          </span>
        </header>

        <div class="invite-active__body">
          <div class="invite-code-card">
            <div class="invite-code-card__main">
              <span class="invite-code-card__label">邀请码</span>
              <code class="invite-code">{{ active?.code ?? '--------' }}</code>
            </div>
            <div class="invite-actions">
              <button class="btn btn--outline" type="button" @click="copyCode">
                <Icon name="copy" :size="15" />
                <span>{{ copied ? '已复制' : '复制' }}</span>
              </button>
              <button class="btn btn--primary" type="button" @click="rotate" :disabled="refreshing">
                <Icon name="refresh" :size="15" />
                <span>立即轮换</span>
              </button>
            </div>
          </div>

          <div
            class="invite-progress"
            role="progressbar"
            aria-label="邀请码剩余有效期"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="Math.round(progressPercent)"
          >
            <span
              class="invite-progress__fill"
              :class="{ 'invite-progress__fill--low': progressPercent <= 12 }"
              :style="{ width: progressPercent + '%' }"
            ></span>
          </div>

          <div class="invite-active__meta">
            <span class="invite-meta__remaining">
              <Icon name="clock" :size="13" />
              剩余 <strong>{{ countdownLabel() }}</strong>
            </span>
            <span class="invite-meta__times">
              <span class="invite-meta__item">生成于 {{ formatDateTime(active?.generatedAt) }}</span>
              <span class="invite-meta__dot" aria-hidden="true">·</span>
              <span class="invite-meta__item">过期于 {{ formatDateTime(active?.expiresAt) }}</span>
            </span>
          </div>
        </div>
      </section>

      <section class="panel invite-history" aria-label="历史邀请码">
        <header class="panel__head invite-history__head">
          <div class="invite-history__title">
            <h2>历史邀请码</h2>
            <span class="chip invite-history__count">共 {{ history.length }} 条</span>
          </div>
          <button
            class="btn btn--ghost btn--sm invite-history__refresh"
            type="button"
            :disabled="refreshing"
            @click="load({ quiet: true })"
          >
            <Icon name="refresh" :size="15" :class="{ 'invite-spin': refreshing }" />
            <span>刷新</span>
          </button>
        </header>

        <div class="invite-history__body">
          <div v-if="history.length === 0" class="invite-empty">
            <Icon name="ticket" :size="22" />
            <p>还没有历史邀请码，首个邀请码轮换后会自动归档到这里。</p>
          </div>
          <div v-else class="table-wrap">
            <table class="invite-table">
              <thead>
                <tr>
                  <th>邀请码</th>
                  <th>生成时间</th>
                  <th>过期时间</th>
                  <th class="invite-table__status">状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in history" :key="item.code">
                  <td><code class="invite-code invite-code--sm">{{ item.code }}</code></td>
                  <td>{{ formatDateTime(item.generatedAt) }}</td>
                  <td>{{ formatDateTime(item.expiresAt) }}</td>
                  <td class="invite-table__status">
                    <span class="tag" :class="item.active ? 'tag--success' : ''">
                      {{ item.active ? '生效中' : '已失效' }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <footer class="invite-history__foot">
          <Icon name="info" :size="14" class="invite-foot__icon" />
          <span>邀请码每两小时自动轮换；新用户注册后自动加入本租户，并获得普通成员角色。</span>
        </footer>
      </section>
    </template>
  </div>
</template>

<style scoped>
.invite {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  min-width: 0;
}

.invite > * {
  min-width: 0;
}

/* ---------- 通用面板骨架（与其余面板一致的头部留白） ---------- */

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 18px 22px 14px;
}

/* ---------- 加载 / 错误 ---------- */

.invite__skeleton {
  display: grid;
  gap: 12px;
  padding: 22px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 16px;
}

.invite__skeleton .skeleton--wide {
  width: 32%;
  height: 18px;
}

/* ---------- 当前邀请码 ---------- */

.invite-head__text {
  min-width: 0;
}

.invite-head__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 680;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.invite-head__hint {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-3);
}

.invite-active__period {
  flex: none;
  margin-left: auto;
  height: 26px;
  padding: 0 10px;
  gap: 6px;
  border-radius: 999px;
  font-size: 11.5px;
  letter-spacing: 0.01em;
}

.invite-active__body {
  padding: 0 22px 20px;
}

.invite-code-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px 20px;
  padding: 16px 18px;
  border: 1px dashed rgba(57, 72, 212, 0.5);
  border-radius: 14px;
  background: var(--accent-soft);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.invite-code-card__main {
  min-width: 0;
}

.invite-code-card__label {
  display: block;
  margin-bottom: 5px;
  font-size: 10.5px;
  font-weight: 650;
  letter-spacing: 0.14em;
  color: var(--accent-ink);
  opacity: 0.72;
}

.invite-code {
  display: block;
  font-family: var(--font-mono);
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: 0.16em;
  color: var(--accent-ink);
  white-space: nowrap;
  user-select: all;
}

.invite-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  margin-left: auto;
}

.invite-progress {
  height: 4px;
  margin-top: 14px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--line);
}

.invite-progress__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 450ms linear, background 200ms ease;
}

.invite-progress__fill--low {
  background: var(--warning);
}

.invite-active__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 10px;
}

.invite-meta__remaining {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 550;
  color: var(--ink-2);
}

.invite-meta__remaining strong {
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--accent-ink);
}

.invite-meta__times {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--ink-3);
}

.invite-meta__item {
  white-space: nowrap;
}

.invite-meta__dot {
  color: var(--line-strong);
  font-weight: 700;
}

/* ---------- 历史邀请码 ---------- */

.invite-history {
  overflow: hidden;
}

.invite-history__title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.invite-history__title h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 680;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.invite-history__count {
  height: 21px;
  padding: 0 8px;
  font-size: 10.5px;
  font-weight: 600;
}

.invite-history__refresh {
  flex: none;
  margin-left: auto;
}

.invite-spin {
  animation: invite-spin 900ms linear infinite;
}

@keyframes invite-spin {
  to {
    transform: rotate(360deg);
  }
}

.invite-history__body {
  padding: 0 22px 18px;
}

.invite-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 26px 18px;
  border: 1px dashed var(--line-strong);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink-3);
  text-align: center;
}

.invite-empty p {
  margin: 0;
  font-size: 12.5px;
}

.table-wrap {
  overflow-x: auto;
  min-width: 0;
}

.invite-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.invite-table th {
  padding: 9px 14px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-3);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-align: left;
  white-space: nowrap;
}

.invite-table td {
  padding: 13px 14px;
  border-bottom: 1px solid var(--paper-2);
  color: var(--ink-2);
  vertical-align: middle;
  white-space: nowrap;
}

.invite-table th:first-child,
.invite-table td:first-child {
  padding-left: 2px;
}

.invite-table th:last-child,
.invite-table td:last-child {
  padding-right: 2px;
}

.invite-table th.invite-table__status,
.invite-table td.invite-table__status {
  text-align: right;
}

.invite-table tbody tr {
  transition: background-color 140ms ease;
}

.invite-table tbody tr:hover td {
  background: var(--paper);
}

.invite-table tbody tr:last-child td {
  border-bottom: none;
}

.invite-code--sm {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--paper);
  color: var(--ink);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.invite-history__foot {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 13px 22px 14px;
  border-top: 1px solid var(--line);
  background: var(--paper);
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.65;
}

.invite-foot__icon {
  flex: none;
  margin-top: 2px;
}

/* ---------- 响应式 ---------- */

@media (max-width: 760px) {
  .panel__head {
    padding: 16px 18px 12px;
  }

  .invite-active__body {
    padding: 0 18px 18px;
  }

  .invite-history__body {
    padding: 0 18px 16px;
  }

  .invite-history__foot {
    padding: 12px 18px 13px;
  }

  .invite-code {
    font-size: 26px;
  }
}

@media (max-width: 640px) {
  .invite-code-card {
    padding: 14px 16px;
    gap: 14px;
  }

  .invite-code-card__main {
    width: 100%;
  }

  .invite-code {
    font-size: 24px;
    letter-spacing: 0.12em;
  }

  .invite-actions {
    width: 100%;
    margin-left: 0;
  }

  .invite-actions .btn {
    flex: 1 1 0;
    min-width: 120px;
  }

  .invite-active__meta {
    align-items: flex-start;
  }

  .invite-meta__times {
    margin-left: 0;
  }

  /* 窄屏：历史表格转为两行卡片（仍是一个 table/tbody/tr） */
  .invite-table thead {
    display: none;
  }

  .invite-table,
  .invite-table tbody {
    display: block;
  }

  .invite-table tr {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px 12px;
    padding: 12px 0;
    border-bottom: 1px solid var(--paper-2);
  }

  .invite-table tr:last-child {
    border-bottom: 0;
  }

  .invite-table tbody tr:hover td {
    background: transparent;
  }

  .invite-table td {
    display: inline-flex;
    align-items: center;
    padding: 0;
    border: 0;
    white-space: nowrap;
  }

  .invite-table td:first-child {
    order: 1;
    padding-left: 0;
  }

  .invite-table td:nth-child(4) {
    order: 2;
    margin-left: auto;
    padding-right: 0;
  }

  .invite-table td:nth-child(2) {
    order: 3;
  }

  .invite-table td:nth-child(3) {
    order: 4;
  }

  .invite-table td:nth-child(2)::before,
  .invite-table td:nth-child(3)::before {
    margin-right: 4px;
    color: var(--ink-3);
    font-size: 11px;
  }

  .invite-table td:nth-child(2)::before {
    content: "生成";
  }

  .invite-table td:nth-child(3)::before {
    content: "过期";
  }

  .invite-table td:nth-child(2),
  .invite-table td:nth-child(3) {
    color: var(--ink-3);
    font-size: 11.5px;
  }

  .invite-table td.invite-table__status {
    text-align: left;
  }
}

@media (max-width: 420px) {
  .invite-code {
    font-size: 22px;
    letter-spacing: 0.1em;
  }

  .invite-actions .btn {
    padding: 0 10px;
  }

  .invite-head__hint {
    font-size: 12px;
  }
}
</style>

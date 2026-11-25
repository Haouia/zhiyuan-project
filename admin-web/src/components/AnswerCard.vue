<script setup>
import { computed, ref } from 'vue'
import BrandMark from './BrandMark.vue'
import Icon from './Icon.vue'
import { locatorLabel, versionName } from '../labels'

const props = defineProps({
  entry: { type: Object, required: true },
})

const emit = defineEmits(['cite', 'retry'])

const copied = ref(false)
let copyTimer = null

function segments(text) {
  const citations = props.entry.citations ?? []
  const result = []
  const pattern = /\[(\d+)\]/g
  let cursor = 0
  let match
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > cursor) result.push({ type: 'text', value: text.slice(cursor, match.index) })
    const index = Number(match[1]) - 1
    if (citations[index]) result.push({ type: 'cite', number: Number(match[1]), citation: citations[index] })
    else result.push({ type: 'text', value: match[0] })
    cursor = match.index + match[0].length
  }
  if (cursor < text.length) result.push({ type: 'text', value: text.slice(cursor) })
  return result
}

const paragraphs = computed(() => {
  if (!props.entry.answer) return []
  return String(props.entry.answer)
    .split(/\n{2,}/)
    .filter((part) => part.trim())
    .map((part) => ({ text: part, parts: segments(part) }))
})

const citations = computed(() => props.entry.citations ?? [])

async function copyAnswer() {
  if (!props.entry.answer) return
  try {
    await navigator.clipboard.writeText(props.entry.answer)
    copied.value = true
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copied.value = false }, 1800)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <article class="answer">
    <div class="answer__question">
      <p>{{ entry.question }}</p>
    </div>

    <div class="answer__card" :class="{ 'answer__card--error': entry.error && !entry.answer }">
      <header class="answer__head">
        <div class="answer__author">
          <BrandMark :size="26" />
          <span>知源</span>
          <span v-if="entry.status === 'INSUFFICIENT_EVIDENCE'" class="tag tag--warning">证据不足</span>
        </div>
        <span v-if="!entry.loading" class="answer__trace" :title="entry.traceId || ''">
          跟踪号 {{ (entry.traceId || '').slice(0, 8) || '—' }}
        </span>
      </header>

      <div v-if="entry.loading" class="answer__loading" role="status" aria-live="polite">
        <span class="typing" aria-hidden="true"><i /><i /><i /></span>
        <span>正在检索企业知识库并整理答案…</span>
      </div>

      <template v-else-if="entry.error && !entry.answer">
        <div class="alert alert--danger answer__alert" role="alert">
          <Icon name="alert-circle" :size="16" />
          <div>
            <strong>回答没有生成</strong>
            <p>{{ entry.error }}</p>
            <code v-if="entry.traceId">traceId · {{ entry.traceId }}</code>
          </div>
        </div>
        <button class="btn btn--outline btn--sm" type="button" @click="emit('retry', entry)">
          <Icon name="refresh" :size="15" />
          重新尝试
        </button>
      </template>

      <template v-else>
        <div v-if="paragraphs.length" class="answer__body">
          <p v-for="(paragraph, index) in paragraphs" :key="index">
            <template v-for="(segment, position) in paragraph.parts" :key="position">
              <button
                v-if="segment.type === 'cite'"
                class="cite-chip"
                type="button"
                :title="'查看引用：' + segment.citation.title"
                :aria-label="'引用 ' + segment.number + '：' + segment.citation.title"
                @click="emit('cite', segment.citation)"
              >{{ segment.number }}</button>
              <template v-else>{{ segment.value }}</template>
            </template>
          </p>
        </div>
        <div v-else class="answer__empty">
          <Icon name="quote" :size="20" />
          <div>
            <strong>没有找到足够的依据</strong>
            <p>当前已发布的资料不足以回答这个问题。换个问法，或让管理员补充相关文档。</p>
          </div>
        </div>

        <section v-if="citations.length" class="answer__sources" aria-label="参考来源">
          <h4>参考来源 <span class="answer__source-count">{{ citations.length }}</span></h4>
          <div class="source-grid">
            <button
              v-for="(citation, index) in citations"
              :key="citation.chunkId || index"
              class="source-card"
              type="button"
              @click="emit('cite', citation)"
            >
              <span class="source-card__num">{{ index + 1 }}</span>
              <span class="source-card__main">
                <strong>{{ citation.title }}</strong>
                <span class="source-card__meta">{{ versionName(citation.version) }} · {{ locatorLabel(citation.locator) }}</span>
                <span class="source-card__quote">{{ citation.quote }}</span>
              </span>
              <Icon name="arrow-up-right" :size="14" />
            </button>
          </div>
        </section>

        <footer class="answer__foot">
          <span class="answer__foot-note">答案由知源生成，请以引用原文为准</span>
          <button class="btn btn--quiet btn--sm" type="button" :disabled="!entry.answer" @click="copyAnswer">
            <Icon :name="copied ? 'check' : 'copy'" :size="14" />
            {{ copied ? '已复制' : '复制答案' }}
          </button>
        </footer>
      </template>
    </div>
  </article>
</template>

<style scoped>
.answer { display: grid; gap: 12px; }
.answer__question { display: flex; justify-content: flex-end; }
.answer__question p {
  max-width: min(560px, 88%);
  margin: 0;
  padding: 11px 16px;
  border-radius: 16px 16px 5px 16px;
  background: var(--ink);
  color: #F7F7F5;
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.answer__card {
  padding: 18px 20px 16px;
  border: 1px solid var(--line);
  border-radius: 18px 18px 18px 5px;
  background: var(--surface);
  box-shadow: 0 20px 50px -46px rgba(16, 18, 23, 0.45);
}
.answer__card--error { border-color: #F0D2D6; }
.answer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.answer__author { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 650; color: var(--ink-2); }
.answer__trace { font-size: 10.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; white-space: nowrap; }
.answer__loading { display: flex; align-items: center; gap: 10px; padding: 6px 0 4px; font-size: 13px; color: var(--ink-2); }
.typing { display: inline-flex; gap: 3px; }
.typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: blink 1.2s ease-in-out infinite;
}
.typing i:nth-child(2) { animation-delay: 0.18s; }
.typing i:nth-child(3) { animation-delay: 0.36s; }
.answer__body { display: grid; gap: 10px; }
.answer__body p { margin: 0; font-size: 14px; line-height: 1.85; color: var(--ink); overflow-wrap: anywhere; }
.cite-chip {
  display: inline-grid;
  place-items: center;
  min-width: 19px;
  height: 19px;
  margin: 0 2px;
  padding: 0 5px;
  border: 1px solid #D5D9F6;
  border-radius: 6px;
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
  vertical-align: 1px;
  cursor: pointer;
  font-variant-numeric: tabular-nums;
  transition: background 140ms ease, transform 140ms ease;
}
.cite-chip:hover { background: #E0E4FB; transform: translateY(-1px); }
.answer__empty { display: flex; gap: 11px; padding: 10px 12px; border-radius: 12px; background: var(--paper); color: var(--ink-2); }
.answer__empty strong { display: block; font-size: 13px; color: var(--ink); }
.answer__empty p { margin: 3px 0 0; font-size: 12.5px; line-height: 1.6; }
.answer__alert { margin: 0 0 12px; }
.answer__alert strong { display: block; font-size: 13px; }
.answer__alert p { margin: 3px 0 0; font-size: 12.5px; }
.answer__alert code { font-size: 11px; color: var(--ink-3); }
.answer__sources { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--line); }
.answer__sources h4 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 9px;
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.answer__source-count {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 17px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--paper-2);
  color: var(--ink-2);
  font-size: 10px;
}
.source-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.source-card {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink);
  text-align: left;
  font: inherit;
  cursor: pointer;
  transition: border-color 140ms ease, background 140ms ease, transform 140ms ease;
}
.source-card:hover { border-color: var(--line-strong); background: var(--surface); transform: translateY(-1px); }
.source-card__num {
  flex: none;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--accent);
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.source-card__main { display: grid; gap: 2px; min-width: 0; flex: 1; }
.source-card__main strong { font-size: 12px; font-weight: 620; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-card__meta { font-size: 10.5px; color: var(--ink-3); }
.source-card__quote {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.source-card > svg { flex: none; margin-top: 2px; color: var(--ink-3); }
.answer__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.answer__foot .answer__trace { font-size: 10px; }
.answer__foot-note { font-size: 10.5px; color: var(--ink-3); }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
@media (max-width: 640px) {
  .source-grid { grid-template-columns: 1fr; }
  .answer__question p { max-width: 94%; }
}
</style>

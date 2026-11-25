<script setup>
import { computed, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { locatorLabel, versionName } from '../labels'

const props = defineProps({
  citation: { type: Object, default: null },
  api: { type: Function, required: true },
})

const text = ref('')
const locator = ref(null)
const loading = ref(false)
const error = ref('')
const copied = ref(false)
let requestId = 0
let copyTimer = null

const quote = computed(() => String(props.citation?.quote ?? ''))

const highlighted = computed(() => {
  const content = text.value
  const needle = quote.value.trim()
  if (!content || !needle) return null
  const index = content.indexOf(needle)
  if (index < 0) return null
  return {
    before: content.slice(0, index),
    mark: content.slice(index, index + needle.length),
    after: content.slice(index + needle.length),
  }
})

const shownLocator = computed(() => props.citation?.locator ?? locator.value)

async function load() {
  const citation = props.citation
  if (!citation?.documentId) return
  const id = ++requestId
  loading.value = true
  error.value = ''
  text.value = ''
  try {
    const params = new URLSearchParams()
    const loc = citation.locator ?? {}
    if (loc.unit != null) params.set('unit', loc.unit)
    if (loc.start != null) params.set('start', loc.start)
    if (loc.end != null) params.set('end', loc.end)
    const suffix = params.toString() ? '?' + params.toString() : ''
    const result = await props.api(
      '/documents/' + citation.documentId + '/versions/' + citation.version + '/source' + suffix,
    )
    if (id !== requestId) return
    text.value = result.text ?? ''
    locator.value = result.locator ?? citation.locator ?? null
  } catch (caught) {
    if (id === requestId) error.value = caught.message
  } finally {
    if (id === requestId) loading.value = false
  }
}

watch(
  () => [props.citation?.documentId, props.citation?.version, props.citation?.chunkId],
  () => load(),
  { immediate: true },
)

async function copy() {
  if (!text.value) return
  try {
    await navigator.clipboard.writeText(text.value)
    copied.value = true
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copied.value = false }, 1800)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <div class="source-view">
    <div v-if="citation" class="source-view__meta">
      <span class="chip">{{ versionName(citation.version) }}</span>
      <span class="chip">{{ locatorLabel(shownLocator) }}</span>
      <button class="btn btn--ghost btn--sm source-view__copy" type="button" :disabled="!text" @click="copy">
        <Icon :name="copied ? 'check' : 'copy'" :size="14" />
        {{ copied ? '已复制' : '复制原文' }}
      </button>
    </div>

    <div v-if="loading" class="stack-skeleton source-view__skeleton" aria-hidden="true">
      <span v-for="n in 7" :key="n" class="skeleton skeleton--line" :style="{ width: [92, 100, 84, 96, 70, 88, 60][n - 1] + '%' }" />
    </div>

    <div v-else-if="error" class="source-view__error">
      <p class="alert alert--danger" role="alert">{{ error }}</p>
      <button class="btn btn--outline btn--sm" type="button" @click="load">
        <Icon name="refresh" :size="15" />
        重新加载
      </button>
    </div>

    <template v-else>
      <div v-if="quote" class="source-view__quote">
        <span class="source-view__quote-label">引用片段</span>
        <p>{{ quote }}</p>
      </div>

      <pre v-if="text" class="source-view__text"><template v-if="highlighted"><span>{{ highlighted.before }}</span><mark>{{ highlighted.mark }}</mark><span>{{ highlighted.after }}</span></template><template v-else>{{ text }}</template></pre>
      <p v-else class="muted">原文内容为空。</p>
    </template>
  </div>
</template>

<style scoped>
.source-view { display: grid; gap: 16px; }
.source-view__meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.source-view__copy { margin-left: auto; }
.source-view__quote {
  padding: 12px 14px;
  border-left: 2px solid var(--accent);
  border-radius: 0 10px 10px 0;
  background: var(--accent-soft);
}
.source-view__quote-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  font-weight: 650;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--accent-ink);
}
.source-view__quote p { margin: 6px 0 0; font-size: 13px; line-height: 1.7; color: var(--ink); }
.source-view__text {
  margin: 0;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  font-family: var(--font-sans);
  font-size: 13.5px;
  line-height: 1.9;
  letter-spacing: 0.002em;
  color: var(--ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.source-view__text mark {
  padding: 1px 3px;
  border-radius: 4px;
  background: #FBEFC9;
  color: #3F3000;
  box-shadow: 0 0 0 1px #F2E1A8;
}
.source-view__skeleton { padding: 6px 0; }
.source-view__error { display: grid; justify-items: start; gap: 10px; }
.source-view__error .alert { margin: 0; }
</style>

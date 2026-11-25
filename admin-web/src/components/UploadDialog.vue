<script setup>
import { nextTick, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { formatFileSize, versionName } from '../labels'
import { lockScroll, unlockScroll } from '../scrollLock'

const props = defineProps({
  open: { type: Boolean, default: false },
  mode: { type: String, default: 'create' },
  target: { type: Object, default: null },
  api: { type: Function, required: true },
})

const emit = defineEmits(['close', 'submitted'])

const dialog = ref(null)
const fileInput = ref(null)
const title = ref('')
const file = ref(null)
const dragging = ref(false)
const saving = ref(false)
const formError = ref('')
let idem = null
let locked = false

watch(
  () => props.open,
  async (open) => {
    await nextTick()
    const element = dialog.value
    if (!element) return
    if (open) {
      title.value = ''
      file.value = null
      formError.value = ''
      dragging.value = false
      idem = null
      if (!element.open) {
        element.showModal()
        if (!locked) {
          locked = true
          lockScroll()
        }
      }
      await nextTick()
      if (props.mode === 'create') element.querySelector('input[type="text"]')?.focus()
    } else if (element.open) {
      element.close()
    }
  },
  { immediate: true },
)

function close() {
  if (saving.value) return
  emit('close')
}

function onCancel() {
  if (!saving.value) close()
}

function onBackdrop(event) {
  if (event.target === dialog.value && !saving.value) close()
}

function afterClose() {
  if (locked) {
    locked = false
    unlockScroll()
  }
}

function issueText(candidate) {
  const name = String(candidate?.name ?? '').toLowerCase()
  if (!/\.(docx|pdf|txt)$/.test(name)) return '仅支持 DOCX、PDF 或 UTF-8 TXT 文件。'
  if (!candidate.size) return '文件内容为空，请选择有效文件。'
  if (candidate.size > 10 * 1024 * 1024) return '文件大小不能超过 10 MiB。'
  return ''
}

function pickFile(candidate) {
  if (!candidate) return
  const problem = issueText(candidate)
  if (problem) {
    file.value = null
    formError.value = problem
    return
  }
  file.value = candidate
  formError.value = ''
  idem = null
}

function change(event) {
  pickFile(event.target.files?.[0])
  event.target.value = ''
}

function drop(event) {
  dragging.value = false
  if (saving.value) return
  pickFile(event.dataTransfer?.files?.[0])
}

function openPicker() {
  fileInput.value?.click()
}

function removeFile() {
  file.value = null
  idem = null
}

async function submit() {
  formError.value = ''
  if (props.mode === 'create' && !title.value.trim()) {
    formError.value = '请输入文档标题。'
    return
  }
  if (!file.value) {
    formError.value = '请选择要上传的文件。'
    return
  }
  const problem = issueText(file.value)
  if (problem) {
    formError.value = problem
    return
  }
  const body = new FormData()
  if (props.mode === 'create') body.set('title', title.value.trim())
  body.set('file', file.value)
  idem ??= crypto.randomUUID()
  saving.value = true
  try {
    const endpoint =
      props.mode === 'append' && props.target
        ? '/documents/' + props.target.documentId + '/versions'
        : '/documents'
    const accepted = await props.api(endpoint, {
      method: 'POST',
      headers: { 'Idempotency-Key': idem },
      body,
    })
    emit('submitted', { mode: props.mode, accepted, target: props.target })
    saving.value = false
    emit('close')
  } catch (error) {
    formError.value = error.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="upload-title" @cancel.prevent="onCancel" @click="onBackdrop" @close="afterClose">
    <form class="dialog__card" novalidate @submit.prevent="submit">
      <header class="dialog__head">
        <div>
          <p class="eyebrow">{{ mode === 'append' ? '版本管理' : '文档入库' }}</p>
          <h2 id="upload-title">{{ mode === 'append' ? '追加新版本' : '上传文档' }}</h2>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭" :disabled="saving" @click="close">
          <Icon name="close" :size="18" />
        </button>
      </header>

      <div class="dialog__body">
        <div v-if="mode === 'append'" class="upload-target">
          <span class="upload-target__icon"><Icon name="file" :size="18" /></span>
          <div class="upload-target__text">
            <strong>{{ target?.title }}</strong>
            <small>最新 {{ versionName(target?.latestVersion) }} · 生效 {{ versionName(target?.activeVersion) }}</small>
          </div>
        </div>

        <label v-else class="field">
          <span class="field__label">文档标题</span>
          <input
            v-model="title"
            class="field__input"
            type="text"
            maxlength="255"
            autocomplete="off"
            placeholder="例如：员工差旅与报销制度"
            :disabled="saving"
            @input="idem = null"
          >
        </label>

        <div
          class="dropzone"
          :class="{ 'dropzone--active': dragging, 'dropzone--filled': file }"
          @dragover.prevent="dragging = !saving"
          @dragleave="dragging = false"
          @drop.prevent="drop"
        >
          <input ref="fileInput" class="visually-hidden" type="file" accept=".docx,.pdf,.txt" :disabled="saving" @change="change">
          <template v-if="!file">
            <span class="dropzone__icon"><Icon name="upload" :size="22" /></span>
            <p class="dropzone__title">拖拽文件到此处，或 <button class="link-button" type="button" @click="openPicker">选择文件</button></p>
            <p class="dropzone__hint">DOCX · PDF · UTF-8 TXT，单个文件不超过 10 MiB</p>
          </template>
          <div v-else class="file-chip">
            <span class="file-chip__icon"><Icon name="file" :size="18" /></span>
            <div class="file-chip__body">
              <strong>{{ file.name }}</strong>
              <small>{{ formatFileSize(file.size) }}</small>
            </div>
            <button class="icon-btn icon-btn--sm" type="button" aria-label="移除文件" :disabled="saving" @click="removeFile">
              <Icon name="close" :size="15" />
            </button>
          </div>
        </div>

        <p v-if="formError" class="alert alert--danger" role="alert">{{ formError }}</p>
        <p class="hint-row">
          <Icon name="shield" :size="14" />
          仅在本租户内可见，上传后自动解析与索引
        </p>
      </div>

      <footer class="dialog__foot">
        <button class="btn btn--ghost" type="button" :disabled="saving" @click="close">取消</button>
        <button class="btn btn--primary" type="submit" :disabled="saving">
          <span v-if="saving" class="btn-spinner" aria-hidden="true" />
          {{ saving ? '正在上传…' : mode === 'append' ? '追加版本' : '上传并解析' }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(560px, calc(100vw - 32px));
  max-width: 100%;
  max-height: calc(100vh - 48px);
  margin: auto;
  padding: 0;
  border: 0;
  border-radius: 20px;
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 40px 90px -50px rgba(16, 18, 23, 0.55);
  overflow: hidden;
}
.dialog::backdrop {
  background: rgba(16, 18, 23, 0.44);
  backdrop-filter: blur(2px);
}
.dialog__card { display: flex; flex-direction: column; max-height: calc(100vh - 48px); }
.dialog__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 16px;
  border-bottom: 1px solid var(--line);
}
.dialog__head h2 {
  margin: 4px 0 0;
  font-size: 19px;
  font-weight: 660;
  letter-spacing: -0.02em;
}
.dialog__body {
  display: grid;
  gap: 16px;
  padding: 22px 24px;
  overflow-y: auto;
}
.upload-target {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
}
.upload-target__icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 9px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--accent-ink);
}
.upload-target__text { display: grid; gap: 2px; min-width: 0; }
.upload-target__text strong { font-size: 13.5px; font-weight: 620; overflow-wrap: anywhere; }
.upload-target__text small { font-size: 12px; color: var(--ink-3); }
.dropzone {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 26px 20px;
  border: 1.5px dashed var(--line-strong);
  border-radius: 14px;
  background: var(--paper);
  text-align: center;
  transition: border-color 160ms ease, background 160ms ease;
}
.dropzone--active { border-color: var(--accent); background: var(--accent-soft); }
.dropzone--filled { padding: 14px; border-style: solid; background: var(--surface); }
.dropzone__icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 4px;
  border-radius: 13px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--accent-ink);
}
.dropzone__title { margin: 0; font-size: 13.5px; color: var(--ink-2); }
.dropzone__hint { margin: 0; font-size: 12px; color: var(--ink-3); }
.link-button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent-ink);
  font: inherit;
  font-weight: 620;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.file-chip {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  text-align: left;
}
.file-chip__icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 9px;
  background: var(--accent-soft);
  color: var(--accent-ink);
}
.file-chip__body { display: grid; gap: 1px; min-width: 0; flex: 1; }
.file-chip__body strong { font-size: 13px; font-weight: 620; overflow-wrap: anywhere; }
.file-chip__body small { font-size: 11.5px; color: var(--ink-3); }
.hint-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 12px;
  color: var(--ink-3);
}
.dialog__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 24px;
  border-top: 1px solid var(--line);
  background: var(--surface);
}
@media (max-width: 560px) {
  .dialog__head, .dialog__body, .dialog__foot { padding-left: 18px; padding-right: 18px; }
}
</style>

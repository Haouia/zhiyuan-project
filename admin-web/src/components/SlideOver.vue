<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import Icon from './Icon.vue'
import { lockScroll, unlockScroll } from '../scrollLock'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  wide: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])
const dialog = ref(null)
const headingId = useId()
let opener = null
let locked = false

function lock() {
  if (locked) return
  locked = true
  lockScroll()
}

function unlock() {
  if (!locked) return
  locked = false
  unlockScroll()
}

async function sync(open) {
  await nextTick()
  const element = dialog.value
  if (!element) return
  if (open && !element.open) {
    opener = document.activeElement
    element.showModal()
    lock()
  } else if (!open && element.open) {
    element.close()
    unlock()
  }
}

watch(() => props.open, sync)

onMounted(() => {
  if (props.open) sync(true)
})

onBeforeUnmount(unlock)

function requestClose() {
  emit('close')
}

function onBackdrop(event) {
  if (event.target === dialog.value) requestClose()
}

function onClosed() {
  unlock()
  if (opener && typeof opener.focus === 'function' && document.contains(opener)) opener.focus()
  opener = null
}
</script>

<template>
  <dialog
    ref="dialog"
    class="drawer"
    :class="{ 'drawer--wide': wide }"
    :aria-labelledby="headingId"
    @cancel.prevent="requestClose"
    @click="onBackdrop"
    @close="onClosed"
  >
    <div class="drawer__panel">
      <header class="drawer__head">
        <div class="drawer__heading">
          <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
          <h2 :id="headingId" class="drawer__title">{{ title }}</h2>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭" @click="requestClose">
          <Icon name="close" :size="18" />
        </button>
      </header>
      <div class="drawer__body">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="drawer__foot">
        <slot name="footer" />
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.drawer {
  position: fixed;
  inset: 0 0 0 auto;
  width: min(480px, 100%);
  max-width: 100%;
  height: 100%;
  max-height: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  overflow: visible;
}
.drawer--wide { width: min(640px, 100%); }
.drawer::backdrop {
  background: rgba(16, 18, 23, 0.42);
  backdrop-filter: blur(2px);
  animation: drawer-fade 200ms ease both;
}
.drawer__panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface);
  border-left: 1px solid var(--line);
  box-shadow: -32px 0 80px -40px rgba(16, 18, 23, 0.4);
  animation: drawer-in 340ms cubic-bezier(0.22, 0.85, 0.24, 1) both;
}
.drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 18px;
  border-bottom: 1px solid var(--line);
}
.drawer__heading { min-width: 0; }
.drawer__title {
  margin: 4px 0 0;
  font-size: 19px;
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -0.015em;
  overflow-wrap: anywhere;
}
.drawer__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 22px 24px 28px;
}
.drawer__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 24px;
  border-top: 1px solid var(--line);
  background: var(--surface);
}
@keyframes drawer-in {
  from { transform: translateX(36px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}
@keyframes drawer-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@media (max-width: 560px) {
  .drawer { width: 100%; }
  .drawer__head { padding: 18px 18px 14px; }
  .drawer__body { padding: 18px; }
  .drawer__foot { padding: 12px 18px; }
}
</style>

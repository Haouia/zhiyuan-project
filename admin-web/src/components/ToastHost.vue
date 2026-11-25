<script setup>
import Icon from './Icon.vue'

defineProps({ items: { type: Array, default: () => [] } })
defineEmits(['dismiss'])

const icons = { success: 'check-circle', danger: 'alert-circle', info: 'info' }
</script>

<template>
  <div class="toast-host" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in items" :key="item.id" class="toast" :class="'toast--' + item.tone" role="status">
        <span class="toast__icon">
          <Icon :name="icons[item.tone] ?? 'info'" :size="18" />
        </span>
        <div class="toast__body">
          <strong v-if="item.title">{{ item.title }}</strong>
          <p v-if="item.message">{{ item.message }}</p>
        </div>
        <button class="toast__close" type="button" aria-label="关闭提示" @click="$emit('dismiss', item.id)">
          <Icon name="close" :size="14" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 90;
  display: grid;
  gap: 10px;
  width: min(380px, calc(100vw - 32px));
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 13px 13px 13px 14px;
  border-radius: 14px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: 0 24px 48px -28px rgba(16, 18, 23, 0.45);
  pointer-events: auto;
}
.toast__icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  background: var(--paper-2);
  color: var(--ink-2);
}
.toast--success .toast__icon { background: var(--success-soft); color: var(--success); }
.toast--danger .toast__icon { background: var(--danger-soft); color: var(--danger); }
.toast--info .toast__icon { background: var(--info-soft); color: var(--info); }
.toast__body { flex: 1; min-width: 0; padding-top: 3px; }
.toast__body strong { display: block; font-size: 13.5px; font-weight: 650; }
.toast__body p { margin: 3px 0 0; font-size: 12.5px; color: var(--ink-2); line-height: 1.5; }
.toast__close {
  flex: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-3);
}
.toast__close:hover { background: var(--paper-2); color: var(--ink); }
.toast-enter-active, .toast-leave-active { transition: opacity 220ms ease, transform 260ms cubic-bezier(0.2, 0.8, 0.2, 1); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(10px) scale(0.98); }
@media (max-width: 560px) {
  .toast-host { right: 16px; bottom: 16px; }
}
</style>

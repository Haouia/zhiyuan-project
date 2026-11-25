<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'
import { statusOf, reasonOf } from '../labels.js'

const props = defineProps({
  status: { type: String, default: '' },
  errorCode: { type: String, default: '' },
  dense: { type: Boolean, default: false },
})

const view = computed(() => statusOf(props.status))
</script>

<template>
  <span
    class="status-badge"
    :class="['status-badge--' + view.tone, { 'status-badge--dense': dense }]"
    :title="reasonOf(errorCode) || view.label"
  >
    <span class="status-badge__dot" aria-hidden="true" />
    <span>{{ view.label }}</span>
    <Icon v-if="status === 'FAILED' && !dense" name="alert-circle" :size="13" />
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px 4px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
  border: 1px solid transparent;
}
.status-badge--dense { padding: 2px 7px; font-size: 11.5px; }
.status-badge__dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status-badge--success { color: var(--success); background: var(--success-soft); border-color: var(--success-line); }
.status-badge--info { color: var(--info); background: var(--info-soft); border-color: var(--info-line); }
.status-badge--neutral { color: var(--ink-2); background: var(--paper-2); border-color: var(--line); }
.status-badge--danger { color: var(--danger); background: var(--danger-soft); border-color: var(--danger-line); }
</style>

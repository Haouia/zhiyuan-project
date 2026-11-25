<script setup>
import { useId } from 'vue'

defineProps({ size: { type: Number, default: 36 } })

const gradientId = useId()
</script>

<template>
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    viewBox="0 0 36 36"
    fill="none"
    aria-hidden="true"
    focusable="false"
  >
    <rect width="36" height="36" rx="10" :fill="'url(#' + gradientId + ')'" />
    <path d="M9.5 13.2 18 9l8.5 4.2-8.5 4.3-8.5-4.3Z" fill="#fff" />
    <path d="M9.5 18.4 18 22.7l8.5-4.3" stroke="rgba(255,255,255,.66)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
    <path d="M9.5 23.4 18 27.7l8.5-4.3" stroke="rgba(255,255,255,.34)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
    <defs>
      <linearGradient :id="gradientId" x1="2" y1="0" x2="34" y2="36" gradientUnits="userSpaceOnUse">
        <stop stop-color="#5968F2" />
        <stop offset="1" stop-color="#2B359F" />
      </linearGradient>
    </defs>
  </svg>
</template>

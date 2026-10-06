<script setup lang="ts">
// 一律 aria-hidden：圖示旁邊都有文字，或按鈕本身有 aria-label，螢幕閱讀器不必再唸一次
import { computed } from 'vue'
import { ICONS, type IconName, type IconShape } from './icons'

const props = defineProps<{ name: IconName; size?: number }>()
const shape = computed<IconShape>(() => ICONS[props.name])
</script>

<template>
  <svg
    :width="size ?? 20"
    :height="size ?? 20"
    viewBox="0 0 24 24"
    :fill="shape.fill ? 'currentColor' : 'none'"
    :stroke="shape.fill ? 'none' : 'currentColor'"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <rect v-if="shape.rect" x="4" y="5" width="16" height="15" rx="2" />
    <circle v-for="c in shape.circles ?? []" :key="`${c.cx},${c.cy}`" :cx="c.cx" :cy="c.cy" :r="c.r" />
    <path v-for="d in shape.paths" :key="d" :d="d" />
  </svg>
</template>

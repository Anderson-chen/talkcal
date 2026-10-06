<script setup lang="ts">
// 月／週／日切換。用 aria-pressed 的按鈕組而不是分頁（tablist）：三個按鈕切的是同一塊內容的看法，不是三份不同的內容
import { useCalendarState, type View } from './useCalendar'

defineProps<{ wide?: boolean }>()
const calendar = useCalendarState()
const TABS: { view: View; label: string }[] = [
  { view: 'month', label: '月' },
  { view: 'week', label: '週' },
  { view: 'day', label: '日' },
]
</script>

<template>
  <div class="tabs" :class="{ wide }">
    <button
      v-for="tab in TABS"
      :key="tab.view"
      type="button"
      :aria-pressed="calendar.view.value === tab.view"
      @click="calendar.view.value = tab.view"
    >
      {{ tab.label }}
    </button>
  </div>
</template>

<style scoped>
.tabs { padding: 4px; background: var(--seg); border-radius: 10px; display: flex; gap: 4px; }
.tabs.wide { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border-radius: 12px; }
button { height: 34px; min-width: 56px; padding: 0 14px; border: 0; border-radius: 7px; font-size: 14px; font-weight: 600; background: transparent; color: var(--muted); }
button[aria-pressed='true'] { background: var(--segOn); color: var(--ink); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.14); }
</style>

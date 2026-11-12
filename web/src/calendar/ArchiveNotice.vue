<script setup lang="ts">
// 月曆翻到 2 年以前的月份時的說明列（#274），桌面、手機共用：那個月的行程已經封存、月曆上看不到，
// 不說的話使用者會以為行程不見了。按鈕打開查看封存的畫面（ArchiveViewer），開在哪裡由版面決定。
import { ARCHIVE_NOTICE } from './archive'
import { useCalendarState } from './useCalendar'

const calendar = useCalendarState()
const emit = defineEmits<{ open: [] }>()
</script>

<template>
  <div v-if="calendar.archived.value" class="archive-notice">
    <span>{{ ARCHIVE_NOTICE }}。</span>
    <button type="button" class="open" @click="emit('open')">查看封存的行程</button>
  </div>
</template>

<style scoped>
.archive-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 10px 12px; border-radius: 10px; background: var(--accentSoft); color: var(--ink); font-size: 14px; }
.archive-notice span { flex: 1; min-width: 180px; }
.open { height: 32px; padding: 0 12px; border: 1px solid var(--accent); border-radius: 8px; background: var(--card); color: var(--accent); font-size: 13px; font-weight: 700; }
</style>

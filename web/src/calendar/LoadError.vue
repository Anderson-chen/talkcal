<script setup lang="ts">
// 讀不到行程時的提示列，桌面、手機共用（#43）。用 div 不用 p：沒有預設外距，版面要留白自己加 class（會掛到這個元素上），不必跟這裡的樣式比誰先載入
import { useCalendarState } from './useCalendar'

const calendar = useCalendarState()
</script>

<template>
  <div v-if="calendar.loadError.value" role="alert" class="load-error">
    讀不到行程：{{ calendar.loadError.value }}
    <button type="button" class="reload" @click="calendar.reload()">重新讀取</button>
  </div>
</template>

<style scoped>
.load-error { padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }
.reload { display: block; margin-top: 6px; height: 32px; padding: 0 12px; border: 1px solid var(--danger); border-radius: 8px; background: transparent; color: var(--danger); font-size: 13px; font-weight: 700; }
</style>

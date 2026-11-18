<script setup lang="ts">
// 行事曆、AI 助理、主題的狀態建在這裡 provide 給底下，兩種版面不各建一份：
// 視窗拉窄、從桌面換成手機時，選的日期、看的月份、AI 對話都還在。
// 沒登入時以訪客身分建（guest），哪些功能鎖住見 useCalendar、useAssistant。
import { computed, onBeforeUnmount, provide, ref } from 'vue'
import { me } from '../auth'
import DesktopCalendar from './DesktopCalendar.vue'
import MobileCalendar from './MobileCalendar.vue'
import { toCssVars } from './theme'
import { ASSISTANT, useAssistant } from './useAssistant'
import { CALENDAR, useCalendar } from './useCalendar'
import { THEME, useTheme } from './useTheme'

// App 換人登入時用 key 重建這個元件，所以建的時候看一次就好
const calendar = useCalendar(!me.value)
const theme = useTheme()
provide(CALENDAR, calendar)
provide(THEME, theme)
provide(ASSISTANT, useAssistant(calendar))

const vars = computed(() => toCssVars(theme.theme.value))

// 用 matchMedia 不監聽 resize：只有跨過分界那一刻才通知，拉視窗時不會一直重算
const query = window.matchMedia('(max-width: 760px)')
const mobile = ref(query.matches)
const onChange = (e: MediaQueryListEvent) => (mobile.value = e.matches)
query.addEventListener('change', onChange)
onBeforeUnmount(() => query.removeEventListener('change', onChange))
</script>

<template>
  <div class="calendar" :style="vars">
    <MobileCalendar v-if="mobile" />
    <DesktopCalendar v-else />
  </div>
</template>

<style scoped>
.calendar { height: 100%; background: var(--bg); color: var(--ink); font-family: 'Noto Sans TC', Manrope, sans-serif; }
/* 照設計稿：按鈕、輸入框沿用頁面字型 */
.calendar :deep(button) { font-family: inherit; cursor: pointer; }
.calendar :deep(button:disabled) { cursor: default; }
.calendar :deep(input), .calendar :deep(textarea) { font-family: inherit; }
.calendar :deep(:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>

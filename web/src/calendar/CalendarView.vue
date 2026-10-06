<script setup lang="ts">
// 行事曆分頁的根：建好共用的狀態（行事曆、AI 助理、主題）provide 給底下，再依螢幕寬度選版面。
//
// 狀態建在這裡而不是兩種版面各建一份：視窗拉窄、版面從桌面換成手機時，
// 選的日期、看的月份、AI 對話都還在，不會一換版面就全部重來。
import { computed, onBeforeUnmount, provide, ref } from 'vue'
import DesktopCalendar from './DesktopCalendar.vue'
import MobileCalendar from './MobileCalendar.vue'
import { toCssVars } from './theme'
import { ASSISTANT, useAssistant } from './useAssistant'
import { CALENDAR, useCalendar } from './useCalendar'
import { THEME, useTheme } from './useTheme'

const calendar = useCalendar()
const theme = useTheme()
provide(CALENDAR, calendar)
provide(THEME, theme)
provide(ASSISTANT, useAssistant(calendar))

const vars = computed(() => toCssVars(theme.theme.value))

// 760px 以下用手機版。用 matchMedia 而不是監聽 resize：只有跨過分界那一刻才會通知，拉視窗時不會一直重算
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
/* 設計稿的基本設定：按鈕、輸入框沿用頁面字型，按鈕有手指游標 */
.calendar :deep(button) { font-family: inherit; cursor: pointer; }
.calendar :deep(button:disabled) { cursor: default; }
.calendar :deep(input), .calendar :deep(textarea) { font-family: inherit; }
.calendar :deep(:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>

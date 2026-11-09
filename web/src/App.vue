<script setup lang="ts">
// 畫面只有行事曆（連同它的 AI 助理）：產品只有一條主線，用自然語言管行事曆。
//
// 要登入才看得到（#103）：打開頁面先問後端「我是誰」，
// 還沒問到就什麼都不畫（不閃一下登入頁）、沒登入畫登入頁、登入了才建行事曆。
// 問超過 1 秒還沒回來才說「正在連線」：平常幾十毫秒就回來，馬上顯示只會閃一下；
// 但伺服器出事時可能要等好幾秒（nginx 找不到 app），整頁空白看起來像壞掉（#43）。
// 行事曆整個在 v-if 裡：登出時它被拆掉，裡面的狀態（行程、AI 對話）跟著丟掉，換人登入不會看到上一個人的東西。
// 問不到「我是誰」又不是 401（伺服器連不上、正在重啟）：畫「連不上」，不畫登入頁（#43，理由在 auth.ts 的 meUnreachable）。
import { onMounted, onUnmounted, ref } from 'vue'
import { connect, me, meUnreachable } from './auth'
import CalendarView from './calendar/CalendarView.vue'
import LoginView from './LoginView.vue'
import { savedThemeVars } from './calendar/theme'
import ServerUnreachable from './ServerUnreachable.vue'

const slow = ref(false)
const timer = setTimeout(() => (slow.value = true), 1000)
onUnmounted(() => clearTimeout(timer))
const vars = savedThemeVars()

onMounted(connect)
</script>

<template>
  <!-- 行事曆是滿版的（側欄、時間軸各自捲動），所以填滿整個視窗高度 -->
  <div v-if="me !== undefined" class="view">
    <CalendarView v-if="me" :key="me.id" />
    <LoginView v-else />
  </div>
  <div v-else-if="meUnreachable" class="view">
    <ServerUnreachable :reason="meUnreachable" :retry="connect" />
  </div>
  <div v-else-if="slow" class="view connecting" :style="vars" role="status">正在連線…</div>
</template>

<style scoped>
.view { flex: 1; min-height: 0; overflow-y: auto; }
.connecting { display: flex; align-items: center; justify-content: center; background: var(--panel); color: var(--muted);
  font-family: 'Noto Sans TC', Manrope, sans-serif; font-size: 15px; }
</style>

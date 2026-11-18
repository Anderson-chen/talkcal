<script setup lang="ts">
// 打開頁面先向後端查詢「我是誰」。還沒查到什麼都不畫，免得登入頁出現一下又消失；
// 查不到又不是 401 就畫「連不上」，不畫登入頁（理由見 auth.ts 的 meUnreachable）。
// 超過 1 秒還沒回來才顯示「正在連線」：平常幾十毫秒就回來，馬上顯示的話提示一出現就消失；
// 伺服器異常時卻可能等好幾秒，整頁空白會像是頁面故障。
// 沒登入時先以訪客身分看行事曆（功能鎖住），要登入時（loginPrompt）才換成登入頁。
// CalendarView 在 v-if 裡、key 是登入者的 id（訪客是 guest）：換人登入時整個重建，不會看到上一個人的行程和對話。
import { onMounted, onUnmounted, ref } from 'vue'
import { connect, loginPrompt, me, meUnreachable, requestLogin } from './auth'
import CalendarView from './calendar/CalendarView.vue'
import LoginView from './LoginView.vue'
import { savedThemeVars } from './calendar/theme'
import ServerUnreachable from './ServerUnreachable.vue'

const slow = ref(false)
const timer = setTimeout(() => (slow.value = true), 1000)
onUnmounted(() => clearTimeout(timer))
const vars = savedThemeVars()

// 從 Google、GitHub 轉回來但沒登入成功：直接顯示登入頁和原因（見 LoginView）
if (new URLSearchParams(window.location.search).has('login_error')) requestLogin()

onMounted(connect)
</script>

<template>
  <!-- 行事曆是滿版的（側欄、時間軸各自捲動），所以填滿整個視窗高度 -->
  <div v-if="me !== undefined" class="view">
    <CalendarView v-if="me || !loginPrompt" :key="me?.id ?? 'guest'" />
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

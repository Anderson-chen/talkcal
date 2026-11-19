<script setup lang="ts">
// 打開頁面先向後端查詢「我是誰」。還沒查到什麼都不畫，免得登入頁出現一下又消失；
// 查不到又不是 401 就畫「連不上」，不畫登入頁（理由見 auth.ts 的 meUnreachable）。
// 超過 1 秒還沒回來才顯示「正在連線」：平常幾十毫秒就回來，馬上顯示的話提示一出現就消失；
// 伺服器異常時卻可能等好幾秒，整頁空白會像是頁面故障。
// CalendarView 在 v-if 裡、key 是登入者的 id：換人登入時整個重建，不會看到上一個人的行程和對話。
// 訪客要登入、註冊時（loginPrompt）行事曆只用 v-show 藏起來，不卸載：按「先不登入」回來時對話還在。
import { onMounted, onUnmounted, ref } from 'vue'
import { connect, loginPrompt, me, meUnreachable } from './auth'
import { onQuotaExceeded } from './http'
import QuotaDialog from './QuotaDialog.vue'
import CalendarView from './calendar/CalendarView.vue'
import LoginView from './LoginView.vue'
import { savedThemeVars } from './calendar/theme'
import ServerUnreachable from './ServerUnreachable.vue'

const slow = ref(false)
const timer = setTimeout(() => (slow.value = true), 1000)
onUnmounted(() => clearTimeout(timer))
const vars = savedThemeVars()

const quotaMessage = ref<string | null>(null)
onUnmounted(onQuotaExceeded((message) => (quotaMessage.value = message)))

onMounted(connect)
</script>

<template>
  <!-- 行事曆是滿版的（側欄、時間軸各自捲動），所以填滿整個視窗高度 -->
  <div v-if="me !== undefined" class="view">
    <CalendarView v-if="me" v-show="!loginPrompt" :key="me.id" />
    <LoginView v-if="!me || loginPrompt" />
    <!-- 沒登入時（例如訪客名額滿了）錯誤顯示在登入頁上，不另外跳視窗 -->
    <QuotaDialog v-if="me && quotaMessage" :message="quotaMessage" :style="vars" @close="quotaMessage = null" />
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

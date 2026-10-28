<script setup lang="ts">
// 打開頁面時連不上伺服器（#43）：網路斷了、伺服器正在重啟或更新。
// 跟登入頁分開畫：這時候不知道使用者有沒有登入，畫登入頁會讓登入著的人以為被登出，而且那時候登入也一樣失敗。
// 「重新連線」再問一次「我是誰」；網路回來時（瀏覽器的 online 事件）自己問，不必等使用者按。
import { onMounted, onUnmounted, ref } from 'vue'
import { loadThemeKey, THEMES, toCssVars } from './calendar/theme'
import type { Problem } from './http'
import TraceId from './TraceId.vue'

const props = defineProps<{ problem: Problem; retry: () => Promise<void> }>()

// 行事曆還沒建好主題狀態，跟登入頁一樣直接用上次選的主題
const vars = toCssVars(THEMES[loadThemeKey()])
const busy = ref(false)

async function reconnect() {
  if (busy.value) return
  busy.value = true
  try {
    await props.retry()
  } finally {
    busy.value = false
  }
}

// 事件監聽不接 Promise：包一層，加上和拿掉的要是同一個函式
const onOnline = () => void reconnect()
onMounted(() => window.addEventListener('online', onOnline))
onUnmounted(() => window.removeEventListener('online', onOnline))
</script>

<template>
  <div class="unreachable" :style="vars">
    <div class="card" role="alert">
      <h1>暫時連不上伺服器</h1>
      <p>{{ problem.text }}</p>
      <p class="hint">可能是網路斷了，或伺服器正在更新。你的行程和對話都存在伺服器上，連上之後就會回來。</p>
      <TraceId v-if="problem.traceId" :id="problem.traceId" />
      <button type="button" class="primary" :disabled="busy" @click="reconnect">{{ busy ? '連線中…' : '重新連線' }}</button>
    </div>
  </div>
</template>

<style scoped>
.unreachable { height: 100%; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box;
  background: var(--panel); color: var(--ink); font-family: 'Noto Sans TC', Manrope, sans-serif; }
.card { width: 100%; max-width: 360px; padding: 28px; border-radius: 16px; background: var(--card); border: 1px solid var(--line);
  display: flex; flex-direction: column; gap: 12px; box-sizing: border-box; }
h1 { margin: 0; font-size: 22px; font-weight: 700; }
p { margin: 0; font-size: 15px; line-height: 1.6; }
.hint { font-size: 13px; color: var(--muted); }
.primary { height: 44px; margin-top: 4px; border: 0; border-radius: 10px; background: var(--accent); color: var(--onAccent); font-size: 15px;
  font-weight: 600; font-family: inherit; cursor: pointer; }
.primary:disabled { opacity: 0.7; cursor: default; }
.primary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>

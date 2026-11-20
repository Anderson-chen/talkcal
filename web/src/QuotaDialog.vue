<script setup lang="ts">
// 每日次數用完時跳出來（http.ts 的 onQuotaExceeded）。訊息是後端規則表寫的那句。
// 訪客多一個「註冊」：註冊之後在同一個帳號上升級，行程和對話都還在。
import { isGuest, requestLogin } from './auth'

defineProps<{ message: string }>()
const emit = defineEmits<{ close: [] }>()

function register() {
  emit('close')
  requestLogin('register', '註冊之後次數就不受訪客的限制，現在的行程和對話都會留著')
}
</script>

<template>
  <div class="scrim">
    <div role="dialog" aria-modal="true" aria-labelledby="quota-title" class="dialog">
      <h2 id="quota-title">今天的次數用完了</h2>
      <p>{{ message }}</p>
      <div class="actions">
        <button v-if="isGuest" type="button" class="primary" @click="register">註冊</button>
        <button type="button" class="secondary" @click="emit('close')">知道了</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scrim { position: fixed; inset: 0; z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px;
  background: var(--scrim, rgba(20, 23, 31, 0.42)); }
.dialog { width: 100%; max-width: 360px; padding: 24px; border-radius: 16px; background: var(--card); color: var(--ink);
  border: 1px solid var(--line); display: flex; flex-direction: column; gap: 12px; box-sizing: border-box;
  font-family: 'Noto Sans TC', Manrope, sans-serif; }
h2 { margin: 0; font-size: 18px; font-weight: 700; }
p { margin: 0; font-size: 14px; line-height: 1.6; color: var(--muted); }
.actions { display: flex; gap: 8px; justify-content: flex-end; }
.primary { height: 40px; padding: 0 16px; border: 0; border-radius: 9px; background: var(--accent); color: var(--onAccent);
  font-size: 14px; font-weight: 700; font-family: inherit; cursor: pointer; }
.secondary { height: 40px; padding: 0 16px; border: 1px solid var(--field); border-radius: 9px; background: transparent;
  color: var(--ink); font-size: 14px; font-family: inherit; cursor: pointer; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>

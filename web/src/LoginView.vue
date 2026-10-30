<script setup lang="ts">
// 登入／註冊（#103）。沒登入時 App 只畫這一頁，行事曆整個不出現。
//
// 一個表單兩種模式：帳號名稱、密碼兩欄都一樣，只差送去哪裡。註冊成功會接著登入（auth.ts 的 signUp），
// 使用者感覺上是一步。錯誤訊息直接顯示後端回的那句（「帳號或密碼錯誤」「帳號名稱已經有人用了」……），
// 規則（長度、字元）只寫在後端一份，這裡不重複一份會漂移的規則。
//
// 下面的「用 Google／GitHub 登入」（#105）只在後端有設定時才出現（問 /api/auth/providers）。
// 它們是一般的連結、不是 fetch：要整頁轉到 Google 去同意，再轉回首頁。失敗時後端轉回 /?login_error=oauth。
import { computed, onMounted, ref } from 'vue'
import { loadProviders, logIn, providerLoginUrl, signUp, type Provider } from './auth'
import { savedThemeVars } from './calendar/theme'

const vars = savedThemeVars()

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

const providers = ref<Provider[]>([])

const title = computed(() => (mode.value === 'login' ? '登入' : '註冊'))

const PROVIDER_LABELS: Record<Provider, string> = { google: 'Google', github: 'GitHub' }

onMounted(async () => {
  // 從 Google、GitHub 轉回來但沒登入成功（按了取消、或後端對不到帳號）。
  // 原因不放在網址上（後端只記 log），這裡只說「沒成功」；顯示完把參數拿掉，重新整理才不會一直看到
  const params = new URLSearchParams(window.location.search)
  if (params.has('login_error')) {
    error.value = '沒有登入成功，請再試一次或改用其他方式登入'
    params.delete('login_error')
    const query = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (query ? `?${query}` : ''))
  }
  providers.value = await loadProviders()
})

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = null
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    await (mode.value === 'login' ? logIn : signUp)(username.value, password.value)
    // 成功之後 auth.ts 的 me 會變成登入的人，App 自己換成行事曆，這裡不必做什麼
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login" :style="vars">
    <form class="card" @submit.prevent="submit">
      <h1>{{ title }}</h1>
      <div class="field">
        <label for="login-username">帳號名稱</label>
        <input id="login-username" v-model="username" autocomplete="username" autocapitalize="none" spellcheck="false" />
      </div>
      <div class="field">
        <label for="login-password">密碼</label>
        <!-- autocomplete 分開寫：註冊時瀏覽器會提議產生強密碼，登入時會帶入記住的密碼 -->
        <input id="login-password" v-model="password" type="password"
               :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
      </div>
      <p v-if="mode === 'register'" class="hint">帳號名稱 3～32 個字（英數、底線、點、減號）；密碼至少 8 個字</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <button type="submit" class="primary" :disabled="busy">{{ busy ? '請稍候…' : title }}</button>
      <template v-if="providers.length">
        <div class="divider"><span>或</span></div>
        <a v-for="provider in providers" :key="provider" class="provider" :href="providerLoginUrl(provider)">
          用 {{ PROVIDER_LABELS[provider] }} 登入
        </a>
      </template>
      <button type="button" class="switch" @click="switchMode">
        {{ mode === 'login' ? '還沒有帳號？註冊' : '已經有帳號？登入' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login { height: 100%; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box;
  background: var(--panel); color: var(--ink); font-family: 'Noto Sans TC', Manrope, sans-serif; }
.card { width: 100%; max-width: 360px; padding: 28px; border-radius: 16px; background: var(--card); border: 1px solid var(--line);
  display: flex; flex-direction: column; gap: 16px; box-sizing: border-box; }
h1 { margin: 0; font-size: 22px; font-weight: 700; }
.field { display: flex; flex-direction: column; gap: 6px; }
label { font-size: 13px; font-weight: 500; color: var(--muted); }
input { height: 44px; border: 1px solid var(--field); border-radius: 10px; padding: 0 12px; font-size: 15px;
  background: var(--card); color: var(--ink); font-family: inherit; }
input:focus-visible, button:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.hint { margin: 0; font-size: 13px; color: var(--muted); }
.error { margin: 0; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }
.primary { height: 44px; border: 0; border-radius: 10px; background: var(--accent); color: var(--onAccent); font-size: 15px;
  font-weight: 600; font-family: inherit; cursor: pointer; }
.primary:disabled { opacity: 0.7; cursor: default; }
.divider { display: flex; align-items: center; gap: 12px; color: var(--muted); font-size: 13px; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--line); }
.provider { height: 44px; border: 1px solid var(--field); border-radius: 10px; display: flex; align-items: center;
  justify-content: center; color: var(--ink); background: var(--card); font-size: 15px; font-weight: 500; text-decoration: none; }
.switch { border: 0; background: transparent; color: var(--accent); font-size: 14px; font-family: inherit; cursor: pointer; }
</style>

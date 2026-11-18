<script setup lang="ts">
// 登入頁旁邊的助理示範：沒登入的人點固定問題，看助理怎麼回答，再決定要不要註冊。
// 沒有輸入框：訪客只能問 SUGGESTIONS 這幾句，回答是後端預先寫好的。
// 卡片上的「加入行事曆」不存檔，交給登入頁處理（emit need-login）；看滿 3 次後請訪客註冊（emit sign-up）。
import { ref } from 'vue'
import type { EventFields } from './calendar'
import { SUGGESTIONS } from './calendar/useAssistant'
import { askGuestDemo } from './guestDemo'
import { ApiError, message } from './http'
import { addDays, dayLabel, timeOf } from './monthGrid'

const emit = defineEmits<{ 'need-login': []; 'sign-up': [] }>()

interface DemoMessage {
  id: number
  role: 'me' | 'ai'
  text: string
  proposals: EventFields[]
}

let nextId = 1
const say = (role: DemoMessage['role'], text: string, proposals: EventFields[] = []): DemoMessage =>
  ({ id: nextId++, role, text, proposals })

const messages = ref<DemoMessage[]>([say('ai', '還沒登入也能先試試：點下面的問題，看我怎麼回答。每天可以試 3 次。')])
const busy = ref(false)
const limitReached = ref(false)
const remaining = ref<number | null>(null)

function when(draft: EventFields) {
  const day = draft.start.slice(0, 10)
  const endDay = draft.end.slice(0, 10)
  const end = endDay === day ? timeOf(draft.end) : endDay === addDays(day, 1) ? `隔天 ${timeOf(draft.end)}` : `${dayLabel(endDay)} ${timeOf(draft.end)}`
  return `${dayLabel(day)}　${timeOf(draft.start)} – ${end}`
}

async function ask(question: string) {
  if (busy.value || limitReached.value) return
  busy.value = true
  messages.value.push(say('me', question))
  try {
    const answer = await askGuestDemo(question)
    messages.value.push(say('ai', answer.reply, answer.proposals))
    remaining.value = answer.remaining
  } catch (e) {
    if (e instanceof ApiError && e.detail.status === 429) limitReached.value = true
    messages.value.push(say('ai', message(e)))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="demo" aria-labelledby="guest-demo-title">
    <h2 id="guest-demo-title">先看看助理怎麼回答</h2>
    <div class="messages" aria-live="polite">
      <div v-for="m in messages" :key="m.id" class="bubble" :class="m.role">
        <span>{{ m.text }}</span>
        <div v-for="(p, i) in m.proposals" :key="i" class="card">
          <span class="title">{{ p.title }}</span>
          <span class="when">{{ when(p) }}</span>
          <button type="button" class="primary" @click="emit('need-login')">加入行事曆</button>
        </div>
      </div>
      <div v-if="busy" class="bubble ai typing">思考中…</div>
    </div>
    <div v-if="limitReached" class="limit">
      <button type="button" class="primary" @click="emit('sign-up')">註冊，直接跟助理說話</button>
    </div>
    <template v-else>
      <div class="suggestions">
        <button v-for="s in SUGGESTIONS" :key="s" type="button" :disabled="busy" @click="ask(s)">{{ s }}</button>
      </div>
      <p v-if="remaining !== null" class="hint">今天還能試 {{ remaining }} 次</p>
    </template>
  </section>
</template>

<style scoped>
.demo { width: 100%; max-width: 400px; padding: 24px; border-radius: 16px; background: var(--card); border: 1px solid var(--line);
  display: flex; flex-direction: column; gap: 12px; box-sizing: border-box; }
h2 { margin: 0; font-size: 18px; font-weight: 700; }
.messages { display: flex; flex-direction: column; gap: 10px; }
.bubble { max-width: 90%; padding: 9px 13px; font-size: 14px; line-height: 1.6; white-space: pre-line; }
.me { align-self: flex-end; border-radius: 16px 16px 4px 16px; background: var(--accent); color: var(--onAccent); }
.ai { align-self: flex-start; border-radius: 16px 16px 16px 4px; background: var(--seg); color: var(--ink); display: flex; flex-direction: column; gap: 10px; }
.typing { color: var(--muted); font-size: 13px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 6px; white-space: normal; }
.title { font-size: 15px; font-weight: 700; }
.when { font-size: 13px; color: var(--muted); font-family: Manrope, 'Noto Sans TC', sans-serif; }
.primary { height: 40px; border: 0; border-radius: 9px; background: var(--accent); color: var(--onAccent); font-size: 14px;
  font-weight: 700; font-family: inherit; cursor: pointer; }
.limit .primary { width: 100%; }
.suggestions { display: flex; flex-wrap: wrap; gap: 6px; }
.suggestions button { height: 32px; padding: 0 10px; border: 1px solid var(--field); border-radius: 16px; background: transparent;
  color: var(--ink); font-size: 13px; font-family: inherit; cursor: pointer; }
.suggestions button:disabled { opacity: 0.5; cursor: default; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.hint { margin: 0; font-size: 13px; color: var(--muted); }
</style>

<script setup lang="ts">
// AI 助理的對話區：訊息、建議說法、輸入框。外框（桌面版側欄、手機版底部面板）由外面決定。
//
// 每個解析出的行程一張卡片，卡片上可以「修改」（帶到新增表單）或「移除」；
// 訊息底下的「加入行事曆」把還沒處理的那幾筆一次存起來。
import { addDays, dayLabel, timeOf } from '../monthGrid'
import type { EventFields } from '../calendar'
import Icon from './Icon.vue'
import { SUGGESTIONS, useAssistantState, type ChatMessage } from './useAssistant'
import { useThemeState } from './useTheme'

defineProps<{ inputId: string; large?: boolean }>()
const assistant = useAssistantState()
const theme = useThemeState()

function when(draft: EventFields) {
  const day = draft.start.slice(0, 10)
  const endDay = draft.end.slice(0, 10)
  const end = endDay === day ? timeOf(draft.end) : endDay === addDays(day, 1) ? `隔天 ${timeOf(draft.end)}` : `${dayLabel(endDay)} ${timeOf(draft.end)}`
  return `${dayLabel(day)}　${timeOf(draft.start)} – ${end}`
}

const pendingCount = (msg: ChatMessage) => msg.proposals.filter((p) => p.status === 'pending').length
</script>

<template>
  <div class="chat" :class="{ large }">
    <div class="scroll">
      <div class="messages" aria-live="polite">
        <template v-for="m in assistant.messages.value" :key="m.id">
          <div v-if="m.role === 'me'" class="bubble me">{{ m.text }}</div>
          <div v-else class="bubble ai">
            <span>{{ m.text }}</span>
            <template v-for="(p, i) in m.proposals" :key="i">
              <div v-if="p.status !== 'removed'" class="card">
                <span class="chip" :style="theme.chipStyle(p.draft.category)">{{ theme.colors.value[p.draft.category].label }}</span>
                <span class="title">{{ p.draft.title }}</span>
                <span class="when">{{ when(p.draft) }}</span>
                <span v-if="p.draft.location" class="where"><Icon name="pin" :size="14" />{{ p.draft.location }}</span>
                <div v-if="p.status === 'pending'" class="card-actions">
                  <button type="button" class="link" @click="assistant.edit(p)"><Icon name="edit" :size="14" />修改</button>
                  <button type="button" class="link" @click="assistant.drop(p)"><Icon name="close" :size="14" />移除</button>
                </div>
                <span v-else-if="p.status === 'added'" class="done">已加入行事曆</span>
              </div>
            </template>
            <div v-if="pendingCount(m) > 0" class="actions">
              <button type="button" class="primary" :disabled="assistant.saving.value" @click="assistant.confirm(m)">
                {{ assistant.saving.value ? '加入中…' : pendingCount(m) > 1 ? `加入行事曆（${pendingCount(m)} 筆）` : '加入行事曆' }}
              </button>
              <button type="button" class="secondary" :disabled="assistant.saving.value" @click="assistant.cancel(m)">取消</button>
            </div>
            <span v-else-if="m.proposals.length && m.proposals.every((p) => p.status === 'removed')" class="cancelled">已取消</span>
            <span v-if="m.error" role="alert" class="error">{{ m.error }}</span>
          </div>
        </template>
        <div v-if="assistant.typing.value" class="bubble typing">思考中…</div>
      </div>
    </div>
    <div class="suggestions">
      <button v-for="s in SUGGESTIONS" :key="s" type="button" :disabled="assistant.typing.value" @click="assistant.send(s)">{{ s }}</button>
    </div>
    <form class="input" @submit.prevent="assistant.send()">
      <label :for="inputId" class="visually-hidden">輸入訊息</label>
      <input :id="inputId" v-model="assistant.input.value" type="text" autocomplete="off" placeholder="例如：下週二早上10點面試" />
      <button type="submit" aria-label="送出" :disabled="assistant.typing.value || !assistant.input.value.trim()"><Icon name="send" :size="18" /></button>
    </form>
  </div>
</template>

<style scoped>
.chat { display: flex; flex-direction: column; flex: 1; min-height: 0; }
/* column-reverse：新訊息出現時自動停在底部，不必自己算捲動位置 */
.scroll { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column-reverse; }
.messages { display: flex; flex-direction: column; gap: 10px; padding: 16px; }
.bubble { max-width: 90%; padding: 9px 13px; font-size: 14px; line-height: 1.6; white-space: pre-line; }
.large .bubble { font-size: 15px; }
.me { align-self: flex-end; max-width: 82%; border-radius: 16px 16px 4px 16px; background: var(--accent); color: var(--onAccent); line-height: 1.5; }
.ai { align-self: flex-start; border-radius: 16px 16px 16px 4px; background: var(--seg); color: var(--ink); display: flex; flex-direction: column; gap: 10px; }
.typing { align-self: flex-start; border-radius: 16px 16px 16px 4px; background: var(--seg); color: var(--muted); font-size: 13px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 6px; white-space: normal; }
.chip { align-self: flex-start; display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 11px; font-size: 12px; font-weight: 600; }
.title { font-size: 15px; font-weight: 700; }
.when, .where { font-size: 13px; color: var(--muted); font-family: Manrope, 'Noto Sans TC', sans-serif; display: flex; align-items: center; gap: 4px; }
.card-actions { display: flex; gap: 12px; margin-top: 2px; }
.link { border: 0; background: transparent; padding: 4px 0; color: var(--accent); font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 4px; }
.done { font-size: 13px; font-weight: 700; color: var(--accent); margin-top: 4px; }
.cancelled { font-size: 13px; color: var(--muted); }
.actions { display: flex; gap: 8px; }
.primary { flex: 1; height: 40px; border: 0; border-radius: 9px; background: var(--accent); color: var(--onAccent); font-size: 13px; font-weight: 700; }
.large .primary, .large .secondary { height: 44px; font-size: 14px; }
.secondary { height: 40px; padding: 0 12px; border: 1px solid var(--field); border-radius: 9px; background: transparent; color: var(--ink); font-size: 13px; }
.error { font-size: 13px; color: var(--danger); }
.suggestions { display: flex; gap: 6px; padding: 8px 16px 4px; overflow-x: auto; }
.suggestions button { flex: none; height: 32px; padding: 0 10px; border: 1px solid var(--field); border-radius: 16px; background: transparent; color: var(--ink); font-size: 12px; white-space: nowrap; }
.input { display: flex; gap: 8px; align-items: center; padding: 8px 16px 20px; }
.input input { flex: 1; min-width: 0; height: 44px; border: 1px solid var(--field); border-radius: 22px; padding: 0 16px; font-size: 14px; background: var(--bg); color: var(--ink); box-sizing: border-box; }
.large .input input { height: 48px; border-radius: 24px; font-size: 16px; }
.input button { width: 44px; height: 44px; flex: none; border: 0; border-radius: 22px; background: var(--accent); color: var(--onAccent); display: flex; align-items: center; justify-content: center; }
.input button:disabled { opacity: 0.5; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
</style>

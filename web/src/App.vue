<script setup lang="ts">
import { ref } from 'vue'
import { ask } from './chat'

interface Turn {
  role: 'user' | 'assistant'
  text: string
}

const turns = ref<Turn[]>([])
const question = ref('')
const pending = ref(false)
const error = ref<string | null>(null)
// 先放記憶體：重新整理就開新對話。要不要存 localStorage / URL 還沒決定
let conversationId: string | undefined

async function send() {
  const text = question.value.trim()
  if (!text || pending.value) return

  turns.value.push({ role: 'user', text })
  question.value = ''
  pending.value = true
  error.value = null
  try {
    const res = await ask({ question: text, conversationId })
    conversationId = res.conversationId
    turns.value.push({ role: 'assistant', text: res.reply })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <main>
    <div v-for="(turn, i) in turns" :key="i" :class="turn.role">{{ turn.text }}</div>
    <div v-if="pending" class="assistant">…</div>
    <p v-if="error" class="error">{{ error }}</p>

    <form @submit.prevent="send">
      <input v-model="question" placeholder="問點什麼，例如：雞胸肉一百克有多少蛋白質？" :disabled="pending" />
      <button :disabled="pending || !question.trim()">送出</button>
    </form>
  </main>
</template>

<style scoped>
main { max-width: 720px; margin: 0 auto; padding: 16px; font-family: system-ui, sans-serif; }
.user, .assistant { white-space: pre-wrap; padding: 8px 12px; margin: 8px 0; border-radius: 8px; }
.user { background: #e8f0fe; margin-left: 20%; }
.assistant { background: #f1f3f4; margin-right: 20%; }
.error { color: #c5221f; }
form { display: flex; gap: 8px; margin-top: 16px; }
input { flex: 1; padding: 8px; }
</style>

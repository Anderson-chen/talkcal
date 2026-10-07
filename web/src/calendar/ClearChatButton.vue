<script setup lang="ts">
// AI 助理的「清空對話」：按兩次才清（「清空」→「確定清空？」），跟刪除行程同一套 ——
// 清掉的是畫面上這段對話，這台瀏覽器之後接不回來（後端的紀錄不刪，留給清理排程）。三秒內沒按第二下就退回原狀，免得按鈕一直停在「確定清空？」
import { onBeforeUnmount, ref } from 'vue'
import Icon from './Icon.vue'
import { useAssistantState } from './useAssistant'

const assistant = useAssistantState()
const confirming = ref(false)
let timer: number | undefined

function onClick() {
  if (!confirming.value) {
    confirming.value = true
    timer = window.setTimeout(() => (confirming.value = false), 3000)
    return
  }
  window.clearTimeout(timer)
  confirming.value = false
  assistant.clear()
}

onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<template>
  <button
    type="button"
    class="clear"
    :class="{ confirming }"
    :disabled="assistant.typing.value"
    :aria-label="confirming ? '確定清空對話' : '清空對話'"
    @click="onClick"
  >
    <Icon name="trash" :size="16" />
    <span>{{ confirming ? '確定清空？' : '清空' }}</span>
  </button>
</template>

<style scoped>
.clear { height: 32px; padding: 0 10px; border: 1px solid var(--field); border-radius: 8px; background: transparent; color: var(--muted); font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 4px; flex: none; }
.clear.confirming { border-color: var(--danger); background: var(--danger); color: var(--card); }
.clear:disabled { opacity: 0.5; }
</style>

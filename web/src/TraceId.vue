<script setup lang="ts">
// 追蹤碼：某一次請求的 trace id，附一顆「複製」。使用者說「剛剛那次怪怪的」時，把這串貼給我們，
// 在 Grafana 的 Tempo 一貼就是那一次請求（#42）。
//
// 顯示完整的 32 個字、不縮成前 8 個：使用者最常做的是截圖回報，縮短的話截圖裡的那串在 Tempo 查不到。
// 用等寬字、可以斷行：手機上也放得下，自己選取也選得到整串。
// 複製失敗（瀏覽器不給剪貼簿權限、不是 https）就把整串選起來，使用者自己按複製
import { ref } from 'vue'

defineProps<{ id: string }>()

const copied = ref(false)
const code = ref<HTMLElement | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined

async function copy(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => (copied.value = false), 2000)
  } catch {
    if (code.value) window.getSelection()?.selectAllChildren(code.value)
  }
}
</script>

<template>
  <span class="trace-id">
    <span class="label">追蹤碼</span>
    <code ref="code">{{ id }}</code>
    <button type="button" :aria-label="`複製追蹤碼 ${id}`" @click="copy(id)">{{ copied ? '已複製' : '複製' }}</button>
  </span>
</template>

<style scoped>
.trace-id { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 6px; font-size: 11px; color: var(--muted); line-height: 1.5; white-space: normal; }
.label { flex: none; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; word-break: break-all; user-select: all; }
button { flex: none; border: 0; background: transparent; padding: 2px 0; color: var(--accent); font-size: 11px; font-weight: 600; }
</style>

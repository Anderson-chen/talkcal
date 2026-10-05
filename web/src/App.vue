<script setup lang="ts">
// 只管分頁：聊天、行事曆各是一個元件，這裡決定現在顯示哪一個。
// 不用 vue-router：兩個畫面、不需要網址，多一個依賴（還有 nginx 的 try_files）不划算。
import { shallowRef } from 'vue'
import CalendarView from './CalendarView.vue'
import ChatView from './ChatView.vue'

const tabs = [
  { label: '聊天', view: ChatView },
  { label: '行事曆', view: CalendarView },
] as const

// 元件本身不需要被深層追蹤，用 shallowRef（用 ref 的話 Vue 會在 console 警告把元件變成了 reactive）
const current = shallowRef<(typeof tabs)[number]>(tabs[0])
</script>

<template>
  <nav>
    <button
      v-for="tab in tabs"
      :key="tab.label"
      :class="{ active: current === tab }"
      @click="current = tab"
    >
      {{ tab.label }}
    </button>
  </nav>
  <!--
    KeepAlive：切走的畫面不銷毀、只是藏起來。聊天的對話和 conversationId、行事曆看到哪個月都留著。
    而且畫面是第一次切過去才建立 —— 只開聊天的人，不會因為行事曆而多打一次資料庫。
  -->
  <KeepAlive>
    <component :is="current.view" />
  </KeepAlive>
</template>

<style scoped>
nav { display: flex; justify-content: center; gap: 4px; padding: 8px; border-bottom: 1px solid #dadce0; font-family: system-ui, sans-serif; }
nav button { padding: 6px 16px; background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer; font-size: 1em; }
nav button.active { border-bottom-color: #1a73e8; color: #1a73e8; }
</style>

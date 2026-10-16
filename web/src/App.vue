<script setup lang="ts">
// 畫面只有行事曆（連同它的 AI 助理）：產品只有一條主線，用自然語言管行事曆。
//
// 要登入才看得到（#103）：打開頁面先問後端「我是誰」，
// 還沒問到就什麼都不畫（不閃一下登入頁）、沒登入畫登入頁、登入了才建行事曆。
// 行事曆整個在 v-if 裡：登出時它被拆掉，裡面的狀態（行程、AI 對話）跟著丟掉，換人登入不會看到上一個人的東西。
import { onMounted } from 'vue'
import { loadMe, me } from './auth'
import CalendarView from './calendar/CalendarView.vue'
import LoginView from './LoginView.vue'

onMounted(loadMe)
</script>

<template>
  <!-- 行事曆是滿版的（側欄、時間軸各自捲動），所以填滿整個視窗高度 -->
  <div v-if="me !== undefined" class="view">
    <CalendarView v-if="me" :key="me.id" />
    <LoginView v-else />
  </div>
</template>

<style scoped>
.view {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>

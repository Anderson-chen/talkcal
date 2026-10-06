<script setup lang="ts">
// 畫面上目前只有行事曆。
//
// 原本上方有「聊天 / 行事曆」兩個分頁，聊天分頁（純營養問答，打 POST /api/chat）先從畫面拿掉了。
// ChatView.vue、chat.ts 和後端的 /api/chat 都還在：要放回來，就是把分頁列和 KeepAlive 加回這裡
// （見 git 歷史裡這個檔案的上一版）。
import CalendarView from './calendar/CalendarView.vue'
</script>

<template>
  <!-- 行事曆是滿版的（側欄、時間軸各自捲動），所以填滿整個視窗高度 -->
  <div class="view">
    <CalendarView />
  </div>
</template>

<style scoped>
.view { flex: 1; min-height: 0; overflow-y: auto; }
</style>

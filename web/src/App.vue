<script setup lang="ts">
// 畫面只有行事曆（連同它的 AI 助理）：產品只有一條主線，用自然語言管行事曆。
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

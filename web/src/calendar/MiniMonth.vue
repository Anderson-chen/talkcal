<script setup lang="ts">
// 桌面版側欄的小月曆：選一天就跳過去。在月視圖點日期會切到日視圖（照設計稿），週、日視圖則維持原視圖
import { computed } from 'vue'
import { monthDayLabel } from '../monthGrid'
import Icon from './Icon.vue'
import { useCalendarState } from './useCalendar'

const calendar = useCalendarState()
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

const cells = computed(() =>
  calendar.days.value.map((day) => ({
    day,
    date: Number(day.slice(8)),
    other: Number(day.slice(5, 7)) !== calendar.shown.value.month,
    today: day === calendar.today,
    selected: day === calendar.sel.value,
  })),
)

function pick(day: string) {
  calendar.pickDate(day, calendar.view.value === 'month' ? 'day' : undefined)
}
</script>

<template>
  <div class="mini">
    <div class="head">
      <span class="title">{{ calendar.shown.value.year }}年{{ calendar.shown.value.month }}月</span>
      <div>
        <button type="button" class="icon" aria-label="上個月" @click="calendar.shiftMonth(-1)"><Icon name="chevronLeft" :size="16" /></button>
        <button type="button" class="icon" aria-label="下個月" @click="calendar.shiftMonth(1)"><Icon name="chevronRight" :size="16" /></button>
      </div>
    </div>
    <div class="grid">
      <span v-for="w in WEEKDAYS" :key="w" class="weekday">{{ w }}</span>
      <button
        v-for="c in cells"
        :key="c.day"
        type="button"
        class="cell"
        :class="{ other: c.other, today: c.today, selected: c.selected }"
        :aria-label="monthDayLabel(c.day)"
        :aria-current="c.today ? 'date' : undefined"
        @click="pick(c.day)"
      >
        {{ c.date }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.mini { display: flex; flex-direction: column; gap: 8px; }
.head { display: flex; align-items: center; justify-content: space-between; }
.title { font-size: 14px; font-weight: 700; }
.icon { width: 32px; height: 32px; border: 0; border-radius: 8px; background: transparent; color: var(--ink); display: inline-flex; align-items: center; justify-content: center; padding: 0; }
.grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 2px; }
.weekday { text-align: center; font-size: 11px; color: var(--muted); padding: 2px 0; }
.cell { height: 30px; border: 0; border-radius: 8px; font-size: 12px; font-family: Manrope, sans-serif; font-weight: 600; padding: 0; background: transparent; color: var(--ink); }
.cell.other { color: var(--faint); }
.cell.today { background: var(--accentSoft); color: var(--accent); }
.cell.selected { background: var(--accent); color: var(--onAccent); }
</style>

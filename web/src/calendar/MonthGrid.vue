<script setup lang="ts">
// 桌面版的月視圖：6 週 × 7 天，每格最多三個行程標籤，再多就「+N 更多」（點了進日視圖）。
// 點日期數字進日視圖、點「+」在那天新增、點行程看詳情 —— 都照設計稿。
import { computed } from 'vue'
import type { SavedEvent } from '../calendar'
import { monthDayLabel, timeOf } from '../monthGrid'
import Icon from './Icon.vue'
import { useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const calendar = useCalendarState()
const theme = useThemeState()
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const MAX_CHIPS = 3

const cells = computed(() =>
  calendar.days.value.map((day) => {
    const events = calendar.eventsOn(day)
    return {
      day,
      date: Number(day.slice(8)),
      other: Number(day.slice(5, 7)) !== calendar.shown.value.month,
      today: day === calendar.today,
      selected: day === calendar.sel.value,
      chips: events.slice(0, MAX_CHIPS),
      more: events.length - MAX_CHIPS,
      label: `${monthDayLabel(day)}，${events.length ? `${events.length} 個行程` : '沒有行程'}，開啟日視圖`,
    }
  }),
)

// 這天開始的行程顯示開始時間；從前一天延續過來的顯示「↳」
const startLabel = (event: SavedEvent, day: string) => (event.start.startsWith(day) ? timeOf(event.start) : '↳')

function add(day: string) {
  calendar.pickDate(day)
  calendar.openForm()
}

function chipStyle(event: SavedEvent) {
  const c = theme.colors.value[event.category]
  const on = event.id === calendar.detailId.value
  // 被選中的行程反白（設計稿的做法），一眼看得出詳情面板在講哪一筆
  return on ? { background: c.ink, color: c.soft } : { background: c.soft, color: c.ink }
}
</script>

<template>
  <div class="scroll">
    <div class="grid">
      <div v-for="w in WEEKDAYS" :key="w" class="weekday">週{{ w }}</div>
      <div v-for="c in cells" :key="c.day" class="cell" :class="{ other: c.other }">
        <div class="cell-head">
          <button
            type="button"
            class="num"
            :class="{ today: c.today, selected: c.selected }"
            :aria-label="c.label"
            :aria-current="c.today ? 'date' : undefined"
            @click="calendar.pickDate(c.day, 'day')"
          >
            {{ c.date }}
          </button>
          <button type="button" class="add" :aria-label="`在${monthDayLabel(c.day)}新增行程`" @click="add(c.day)">
            <Icon name="plus" :size="14" />
          </button>
        </div>
        <button
          v-for="e in c.chips"
          :key="e.id"
          type="button"
          class="chip"
          :style="chipStyle(e)"
          :title="e.title"
          @click="calendar.detailId.value = e.id"
        >
          <span class="time">{{ startLabel(e, c.day) }}</span>{{ e.title }}
        </button>
        <button v-if="c.more > 0" type="button" class="more" @click="calendar.pickDate(c.day, 'day')">+{{ c.more }} 更多</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scroll { overflow-x: auto; }
.grid { min-width: 640px; background: var(--line); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 1px; }
.weekday { background: var(--card); padding: 10px 12px; font-size: 12px; font-weight: 600; color: var(--muted); }
.cell { background: var(--card); min-height: 116px; padding: 8px; display: flex; flex-direction: column; gap: 4px; box-sizing: border-box; min-width: 0; }
.cell.other { background: var(--panel); }
.cell-head { display: flex; align-items: center; justify-content: space-between; }
.num { width: 30px; height: 30px; border: 0; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 14px; font-family: Manrope, sans-serif; font-weight: 700; padding: 0; background: transparent; color: var(--ink); }
.other .num { color: var(--faint); }
.num.selected { box-shadow: inset 0 0 0 2px var(--accent); color: var(--accent); }
.num.today { background: var(--accent); color: var(--onAccent); box-shadow: none; }
.add { width: 28px; height: 28px; border: 0; border-radius: 8px; background: transparent; color: var(--faint); display: flex; align-items: center; justify-content: center; padding: 0; }
.add:hover { color: var(--accent); background: var(--accentSoft); }
.chip { width: 100%; border: 0; border-radius: 6px; padding: 4px 7px; font-size: 12px; text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.time { font-family: Manrope, sans-serif; font-weight: 700; margin-right: 4px; }
.more { border: 0; background: transparent; text-align: left; padding: 2px 6px; font-size: 12px; font-weight: 600; color: var(--muted); }
</style>

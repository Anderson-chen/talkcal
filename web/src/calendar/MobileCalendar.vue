<script setup lang="ts">
// 手機版：照設計稿的「手機 App」畫板。
// - 月視圖：小格子（每天最多三個分類圓點）＋ 下面是選中那天的行程清單
// - 週、日視圖：時間軸
// - 行程詳情、新增表單都是整頁；AI 助理和主題選擇是從底部滑上來的面板
// - 右下角兩顆浮動按鈕：AI 助理、新增行程
import { computed, ref } from 'vue'
import type { Category, SavedEvent } from '../calendar'
import { dayLabel, monthDayLabel, timeOf, weekdayName, weekOf } from '../monthGrid'
import AssistantChat from './AssistantChat.vue'
import ClearChatButton from './ClearChatButton.vue'
import EventDetail from './EventDetail.vue'
import EventForm from './EventForm.vue'
import Icon from './Icon.vue'
import ThemePicker from './ThemePicker.vue'
import TimeGrid from './TimeGrid.vue'
import ViewTabs from './ViewTabs.vue'
import { useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const calendar = useCalendarState()
const theme = useThemeState()
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

const aiOpen = ref(false)
const themeOpen = ref(false)

const week = computed(() => weekOf(calendar.sel.value))

const cells = computed(() =>
  calendar.days.value.map((day) => {
    const events = calendar.eventsOn(day)
    // 每個分類最多一個圓點、最多三個：手機格子小，圓點是「這天大概有什麼」的提示，不是清單
    const dots = [...new Set(events.map((e) => e.category))].slice(0, 3)
    return {
      day,
      date: Number(day.slice(8)),
      other: Number(day.slice(5, 7)) !== calendar.shown.value.month,
      today: day === calendar.today,
      selected: day === calendar.sel.value,
      dots,
      label: `${monthDayLabel(day)}，${events.length ? `${events.length} 個行程` : '沒有行程'}`,
    }
  }),
)

const agenda = computed(() => calendar.eventsOn(calendar.sel.value))

const weekTitle = computed(() => {
  const [first, last] = [week.value[0], week.value[6]]
  return `${monthDayLabel(first)} – ${first.slice(5, 7) === last.slice(5, 7) ? `${Number(last.slice(8))}日` : monthDayLabel(last)}`
})

const stepLabel = computed(() => ({ month: ['上個月', '下個月'], week: ['上一週', '下一週'], day: ['前一天', '後一天'] })[calendar.view.value])

function timeLabel(event: SavedEvent) {
  const day = calendar.sel.value
  const from = event.start.startsWith(day) ? timeOf(event.start) : '↳'
  const to = event.end.startsWith(day) ? timeOf(event.end) : '隔天'
  return `${from} – ${to}`
}

const dotColor = (category: Category) => theme.colors.value[category].color
</script>

<template>
  <div class="mobile">
    <EventDetail v-if="calendar.detail.value && !calendar.form.value" variant="page" />
    <EventForm v-else-if="calendar.form.value" variant="page" />

    <template v-else>
      <!-- 頁首：月、週是「年 + 大標」；日視圖是「‹ 10月」回到月視圖 + 下面的大標 -->
      <header v-if="calendar.view.value !== 'day'" class="head">
        <div>
          <div class="year">{{ calendar.shown.value.year }}</div>
          <h1 :class="{ small: calendar.view.value === 'week' }">
            {{ calendar.view.value === 'month' ? `${calendar.shown.value.month}月` : weekTitle }}
          </h1>
        </div>
        <div class="head-actions">
          <button type="button" class="today" @click="calendar.pickDate(calendar.today)">今天</button>
          <button type="button" class="icon" :aria-label="stepLabel[0]" @click="calendar.step(-1)"><Icon name="chevronLeft" /></button>
          <button type="button" class="icon" :aria-label="stepLabel[1]" @click="calendar.step(1)"><Icon name="chevronRight" /></button>
          <button type="button" class="icon" aria-label="選擇主題" @click="themeOpen = true"><Icon name="palette" /></button>
        </div>
      </header>
      <template v-else>
        <header class="head day-head">
          <button type="button" class="back" @click="calendar.view.value = 'month'"><Icon name="chevronLeft" />{{ Number(calendar.sel.value.slice(5, 7)) }}月</button>
          <div class="head-actions">
            <button type="button" class="icon" :aria-label="stepLabel[0]" @click="calendar.step(-1)"><Icon name="chevronLeft" /></button>
            <button type="button" class="icon" :aria-label="stepLabel[1]" @click="calendar.step(1)"><Icon name="chevronRight" /></button>
            <button type="button" class="icon" aria-label="選擇主題" @click="themeOpen = true"><Icon name="palette" /></button>
          </div>
        </header>
        <h1 class="day-title">{{ dayLabel(calendar.sel.value) }}</h1>
      </template>

      <ViewTabs wide class="tabs" />
      <p v-if="calendar.loadError.value" role="alert" class="error">讀不到行程：{{ calendar.loadError.value }}</p>

      <!-- 月視圖 -->
      <template v-if="calendar.view.value === 'month'">
        <div class="month">
          <div v-for="w in WEEKDAYS" :key="w" class="weekday">{{ w }}</div>
          <button
            v-for="c in cells"
            :key="c.day"
            type="button"
            class="cell"
            :aria-label="c.label"
            :aria-current="c.today ? 'date' : undefined"
            @click="calendar.pickDate(c.day)"
            @dblclick="calendar.pickDate(c.day, 'day')"
          >
            <span class="num" :class="{ other: c.other, today: c.today, selected: c.selected }">{{ c.date }}</span>
            <span class="dots"><span v-for="d in c.dots" :key="d" class="dot" :style="{ background: dotColor(d) }" /></span>
          </button>
        </div>
        <section class="agenda">
          <div class="agenda-head">
            <h2>{{ dayLabel(calendar.sel.value) }}</h2>
            <span class="count">{{ agenda.length ? `${agenda.length} 個行程` : '' }}</span>
          </div>
          <div class="agenda-list">
            <button v-for="e in agenda" :key="e.id" type="button" class="item" @click="calendar.detailId.value = e.id">
              <span class="item-dot" :style="{ background: dotColor(e.category) }" />
              <span class="item-text">
                <span class="item-title">{{ e.title }}</span>
                <span class="item-sub">{{ timeLabel(e) }}<template v-if="e.location"> · {{ e.location }}</template></span>
              </span>
            </button>
            <div v-if="agenda.length === 0" class="nothing">
              <span>這天沒有行程</span>
              <button type="button" @click="calendar.openForm()">新增行程</button>
            </div>
          </div>
        </section>
      </template>

      <!-- 週視圖 -->
      <template v-else-if="calendar.view.value === 'week'">
        <div class="week-head">
          <button
            v-for="day in week"
            :key="day"
            type="button"
            class="week-day"
            :aria-label="`${monthDayLabel(day)}，開啟日視圖`"
            @click="calendar.pickDate(day, 'day')"
          >
            <span class="wd">{{ weekdayName(day) }}</span>
            <span class="num" :class="{ today: day === calendar.today, selected: day === calendar.sel.value }">{{ Number(day.slice(8)) }}</span>
          </button>
        </div>
        <TimeGrid :key="'w' + week[0]" :days="week" compact title-only class="timeline" />
      </template>

      <!-- 日視圖 -->
      <template v-else>
        <div class="strip">
          <button v-for="day in week" :key="day" type="button" class="strip-day" :aria-label="monthDayLabel(day)" @click="calendar.pickDate(day)">
            <span class="wd">{{ weekdayName(day) }}</span>
            <span class="num" :class="{ today: day === calendar.today, selected: day === calendar.sel.value }">{{ Number(day.slice(8)) }}</span>
            <span class="has" :class="{ on: calendar.eventsOn(day).length > 0 && day !== calendar.sel.value }" />
          </button>
        </div>
        <TimeGrid :key="'d' + calendar.sel.value" :days="[calendar.sel.value]" compact class="timeline" />
      </template>

      <button type="button" class="fab-ai" aria-label="開啟 AI 助理" @click="aiOpen = true"><Icon name="sparkle" :size="18" />AI 助理</button>
      <button type="button" class="fab-new" aria-label="新增行程" @click="calendar.openForm()"><Icon name="plus" :size="26" /></button>
    </template>

    <div v-if="aiOpen" class="sheet-scrim">
      <button type="button" class="sheet-dismiss" aria-label="關閉 AI 助理" @click="aiOpen = false" />
      <div role="dialog" aria-modal="true" aria-labelledby="m-ai-title" class="sheet ai-sheet">
        <span class="grip" />
        <div class="ai-head">
          <span class="ai-logo"><Icon name="sparkle" :size="22" /></span>
          <div class="ai-title">
            <h2 id="m-ai-title">AI 助理</h2>
            <div class="ai-sub">用一句話新增、查詢或找空檔</div>
          </div>
          <ClearChatButton />
          <button type="button" class="icon" aria-label="關閉" @click="aiOpen = false"><Icon name="close" /></button>
        </div>
        <AssistantChat input-id="m-ai-input" large />
      </div>
    </div>

    <div v-if="themeOpen" class="sheet-scrim">
      <button type="button" class="sheet-dismiss" aria-label="關閉主題選單" @click="themeOpen = false" />
      <div role="dialog" aria-modal="true" aria-labelledby="m-theme-title" class="sheet theme-sheet">
        <span class="grip" />
        <div class="theme-head">
          <h2 id="m-theme-title">選擇主題</h2>
          <button type="button" class="done" @click="themeOpen = false">完成</button>
        </div>
        <ThemePicker large />
      </div>
    </div>
  </div>
</template>

<style scoped>
.mobile { position: relative; height: 100%; overflow: hidden; background: var(--bg); color: var(--ink); display: flex; flex-direction: column; }
.head { padding: 20px 12px 8px 20px; display: flex; align-items: flex-end; justify-content: space-between; gap: 8px; }
.year { font-size: 13px; color: var(--muted); font-family: Manrope, sans-serif; font-weight: 600; letter-spacing: 0.04em; }
h1 { margin: 0; font-size: 30px; font-weight: 700; line-height: 1.2; }
h1.small { font-size: 22px; line-height: 1.3; white-space: nowrap; }
.head-actions { display: flex; align-items: center; }
.today { height: 36px; padding: 0 12px; border: 1px solid var(--field); border-radius: 18px; background: transparent; color: var(--ink); font-size: 14px; font-weight: 500; margin-right: 4px; }
.icon { width: 44px; height: 44px; border: 0; border-radius: 12px; background: transparent; color: var(--ink); display: flex; align-items: center; justify-content: center; padding: 0; }
.day-head { padding: 16px 12px 4px 8px; align-items: center; }
.back { height: 44px; padding: 0 12px 0 6px; border: 0; background: transparent; color: var(--accent); font-size: 16px; font-weight: 500; display: flex; align-items: center; gap: 2px; }
.day-title { margin: 0 20px 8px; font-size: 26px; }
.tabs { margin: 4px 20px 12px; }
.error { margin: 0 20px 8px; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }

.month { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); padding: 0 12px; row-gap: 2px; }
.weekday { text-align: center; font-size: 12px; color: var(--muted); font-weight: 500; padding: 4px 0; }
.cell { height: 50px; border: 0; background: transparent; padding: 2px 0; display: flex; flex-direction: column; align-items: center; gap: 3px; }
.num { width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 15px; font-weight: 600; font-family: Manrope, sans-serif; color: var(--ink); box-sizing: border-box; }
.num.other { color: var(--faint); }
.num.selected { box-shadow: inset 0 0 0 2px var(--accent); color: var(--accent); }
.num.today { background: var(--accent); color: var(--onAccent); box-shadow: none; }
.dots { display: flex; gap: 3px; height: 5px; }
.dot { width: 5px; height: 5px; border-radius: 50%; }

.agenda { flex: 1; min-height: 0; margin-top: 12px; border-top: 1px solid var(--line); background: var(--panel); display: flex; flex-direction: column; }
.agenda-head { padding: 16px 20px 8px; display: flex; align-items: baseline; justify-content: space-between; }
.agenda-head h2 { margin: 0; font-size: 17px; font-weight: 700; }
.count { font-size: 13px; color: var(--muted); }
.agenda-list { flex: 1; min-height: 0; overflow-y: auto; padding: 0 16px 96px; display: flex; flex-direction: column; gap: 8px; }
.item { display: flex; gap: 12px; align-items: flex-start; text-align: left; padding: 12px 14px; background: var(--card); border: 1px solid var(--line); border-radius: 14px; color: var(--ink); min-height: 56px; }
.item-dot { flex: none; width: 10px; height: 10px; border-radius: 50%; margin-top: 6px; }
.item-text { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.item-title { font-size: 15px; font-weight: 600; }
.item-sub { font-size: 13px; color: var(--muted); font-family: Manrope, 'Noto Sans TC', sans-serif; }
.nothing { padding: 28px 12px; text-align: center; color: var(--muted); font-size: 14px; display: flex; flex-direction: column; align-items: center; gap: 10px; }
.nothing button { height: 44px; padding: 0 18px; border: 1px solid var(--field); border-radius: 22px; background: var(--card); color: var(--ink); font-size: 14px; }

.week-head { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); padding: 0 8px 8px 44px; border-bottom: 1px solid var(--line); }
.week-day, .strip-day { border: 0; background: transparent; color: var(--ink); display: flex; flex-direction: column; align-items: center; gap: 3px; padding: 2px 0; min-height: 52px; }
.wd { font-size: 11px; color: var(--muted); }
.week-day .num { width: 28px; height: 28px; font-size: 14px; }
.strip { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); padding: 0 12px 10px; border-bottom: 1px solid var(--line); }
.strip-day { min-height: 60px; gap: 4px; }
.strip-day .wd { font-size: 12px; }
.strip-day .num { width: 36px; height: 36px; font-size: 16px; }
.has { width: 5px; height: 5px; border-radius: 50%; }
.has.on { background: var(--muted); }
.timeline { padding-bottom: 96px; --card: var(--bg); }

.fab-ai { position: absolute; right: 20px; bottom: 100px; height: 44px; padding: 0 16px 0 12px; border-radius: 22px; border: 1px solid var(--line); background: var(--card); color: var(--accent); display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 700; box-shadow: 0 6px 16px rgba(0, 0, 0, 0.14); z-index: 3; }
.fab-new { position: absolute; right: 20px; bottom: 28px; width: 60px; height: 60px; border-radius: 30px; border: 0; background: var(--accent); color: var(--onAccent); display: flex; align-items: center; justify-content: center; box-shadow: 0 8px 20px rgba(0, 0, 0, 0.25); z-index: 3; }

.sheet-scrim { position: absolute; inset: 0; background: var(--scrim); display: flex; flex-direction: column; justify-content: flex-end; z-index: 20; }
.sheet-dismiss { flex: 1; border: 0; background: transparent; cursor: default; }
.sheet { background: var(--card); border-radius: 22px 22px 0 0; display: flex; flex-direction: column; }
.ai-sheet { height: min(660px, 85%); }
.grip { align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--field); margin-top: 10px; flex: none; }
.ai-head { display: flex; align-items: center; gap: 12px; padding: 10px 12px 12px 20px; border-bottom: 1px solid var(--line); }
.ai-logo { width: 40px; height: 40px; border-radius: 12px; background: var(--accentSoft); color: var(--accent); display: flex; align-items: center; justify-content: center; flex: none; }
.ai-title { flex: 1; min-width: 0; }
.ai-title h2 { margin: 0; font-size: 17px; font-weight: 700; }
.ai-sub { font-size: 12px; color: var(--muted); }
.theme-sheet { padding: 12px 20px 32px; gap: 16px; }
.theme-sheet .grip { margin-top: 0; }
.theme-head { display: flex; align-items: center; justify-content: space-between; }
.theme-head h2 { margin: 0; font-size: 18px; font-weight: 700; }
.done { height: 44px; padding: 0 8px; border: 0; background: transparent; color: var(--accent); font-size: 16px; font-weight: 600; }
</style>

<script setup lang="ts">
// 桌面版：左側欄（新增、小月曆、分類篩選、主題）＋ 中間的月／週／日視圖 ＋ 右側 AI 助理。
// 行程詳情是從右邊滑出的抽屜，新增表單是對話框。版面照設計稿的「桌面 Web」畫板。
import { computed, ref } from 'vue'
import { CATEGORIES } from '../calendar'
import { monthDayLabel, weekdayName, weekOf } from '../monthGrid'
import AssistantChat from './AssistantChat.vue'
import EventDetail from './EventDetail.vue'
import EventForm from './EventForm.vue'
import Icon from './Icon.vue'
import MiniMonth from './MiniMonth.vue'
import MonthGrid from './MonthGrid.vue'
import ThemePicker from './ThemePicker.vue'
import TimeGrid from './TimeGrid.vue'
import ViewTabs from './ViewTabs.vue'
import { useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const calendar = useCalendarState()
const theme = useThemeState()
const aiOpen = ref(true)

const week = computed(() => weekOf(calendar.sel.value))

const title = computed(() => {
  const view = calendar.view.value
  const { year, month } = calendar.shown.value
  if (view === 'month') return `${year}年${month}月`
  const sel = calendar.sel.value
  if (view === 'day') return `${monthDayLabel(sel)} 星期${weekdayName(sel)}`
  const [first, last] = [week.value[0], week.value[6]]
  const lastLabel = first.slice(5, 7) === last.slice(5, 7) ? `${Number(last.slice(8))}日` : monthDayLabel(last)
  return `${first.slice(0, 4)}年${monthDayLabel(first)} – ${lastLabel}`
})

const stepLabel = computed(() => ({ month: ['上個月', '下個月'], week: ['上一週', '下一週'], day: ['前一天', '後一天'] })[calendar.view.value])

const noEventsToday = computed(() => calendar.view.value === 'day' && calendar.eventsOn(calendar.sel.value).length === 0)
</script>

<template>
  <div class="desktop">
    <aside class="side">
      <div class="brand">
        <span class="logo"><Icon name="calendar" /></span>
        <span>行事曆</span>
      </div>
      <button type="button" class="new" @click="calendar.openForm()"><Icon name="plus" :size="18" />新增行程</button>
      <MiniMonth />
      <div class="section">
        <span class="section-title">我的分類</span>
        <label v-for="c in CATEGORIES" :key="c" class="filter">
          <input
            type="checkbox"
            :checked="!calendar.hidden.value[c]"
            :style="{ accentColor: theme.colors.value[c].color }"
            @change="calendar.toggleCategory(c)"
          />
          <span class="filter-label">{{ theme.colors.value[c].label }}</span>
          <span class="count">{{ calendar.monthCounts.value[c] }}</span>
        </label>
      </div>
      <div class="section">
        <span class="section-title">主題</span>
        <ThemePicker />
      </div>
    </aside>

    <main class="main">
      <div class="toolbar">
        <button type="button" class="today" @click="calendar.pickDate(calendar.today)">今天</button>
        <div class="steps">
          <button type="button" class="icon" :aria-label="stepLabel[0]" @click="calendar.step(-1)"><Icon name="chevronLeft" /></button>
          <button type="button" class="icon" :aria-label="stepLabel[1]" @click="calendar.step(1)"><Icon name="chevronRight" /></button>
        </div>
        <h1>{{ title }}</h1>
        <button type="button" class="ai-toggle" :aria-pressed="aiOpen" @click="aiOpen = !aiOpen"><Icon name="sparkle" :size="18" />AI 助理</button>
        <ViewTabs />
      </div>
      <p v-if="calendar.loadError.value" role="alert" class="error">讀不到行程：{{ calendar.loadError.value }}</p>

      <MonthGrid v-if="calendar.view.value === 'month'" />

      <div v-else class="panel">
        <div v-if="calendar.view.value === 'week'" class="week-head">
          <button
            v-for="day in week"
            :key="day"
            type="button"
            class="week-day"
            :class="{ today: day === calendar.today, selected: day === calendar.sel.value }"
            :aria-label="`${monthDayLabel(day)}，開啟日視圖`"
            @click="calendar.pickDate(day, 'day')"
          >
            <span class="wd">週{{ weekdayName(day) }}</span>
            <span class="num">{{ Number(day.slice(8)) }}</span>
          </button>
        </div>
        <div v-else class="strip">
          <button
            v-for="day in week"
            :key="day"
            type="button"
            class="strip-day"
            :class="{ selected: day === calendar.sel.value, today: day === calendar.today }"
            :aria-label="monthDayLabel(day)"
            @click="calendar.pickDate(day)"
          >
            <span class="wd">週{{ weekdayName(day) }}</span>
            <span class="num">{{ Number(day.slice(8)) }}</span>
          </button>
        </div>
        <TimeGrid v-if="calendar.view.value === 'week'" :key="'w' + week[0]" :days="week" compact />
        <TimeGrid v-else :key="'d' + calendar.sel.value" :days="[calendar.sel.value]" />
        <p v-if="noEventsToday" class="empty">這天沒有行程，點左側「新增行程」或右側的 AI 助理來建立。</p>
      </div>
    </main>

    <aside v-if="aiOpen" class="assistant" aria-labelledby="d-ai-title">
      <div class="ai-head">
        <span class="ai-logo"><Icon name="sparkle" /></span>
        <div class="ai-title">
          <h2 id="d-ai-title">AI 助理</h2>
          <div class="ai-sub">用一句話新增行程</div>
        </div>
        <button type="button" class="icon" aria-label="收合 AI 助理" @click="aiOpen = false"><Icon name="chevronRight" /></button>
      </div>
      <AssistantChat input-id="d-ai-input" />
    </aside>

    <div v-if="calendar.detail.value && !calendar.form.value" class="drawer">
      <EventDetail variant="drawer" />
    </div>

    <div v-if="calendar.form.value" class="scrim" @click.self="calendar.form.value = null">
      <div role="dialog" aria-modal="true" aria-labelledby="event-form-title" class="dialog">
        <EventForm variant="dialog" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.desktop { position: relative; height: 100%; display: flex; background: var(--panel); color: var(--ink); overflow: hidden; }
.side { flex: none; width: 260px; box-sizing: border-box; padding: 24px 20px; background: var(--card); border-right: 1px solid var(--line); display: flex; flex-direction: column; gap: 24px; overflow-y: auto; }
.brand { display: flex; align-items: center; gap: 10px; font-size: 19px; font-weight: 700; }
.logo { width: 36px; height: 36px; border-radius: 10px; background: var(--accent); color: var(--onAccent); display: flex; align-items: center; justify-content: center; }
.new { height: 48px; flex: none; border: 0; border-radius: 12px; background: var(--accent); color: var(--onAccent); font-size: 15px; font-weight: 600; display: flex; align-items: center; justify-content: center; gap: 8px; }
.section { display: flex; flex-direction: column; gap: 4px; }
.section-title { font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 0.06em; margin-bottom: 4px; }
.filter { display: flex; align-items: center; gap: 10px; min-height: 36px; font-size: 14px; cursor: pointer; }
.filter input { width: 18px; height: 18px; margin: 0; }
.filter-label { flex: 1; }
.count { font-size: 12px; color: var(--muted); font-family: Manrope, sans-serif; }

.main { flex: 1; min-width: 0; box-sizing: border-box; padding: 24px 28px 24px; display: flex; flex-direction: column; gap: 16px; overflow-y: auto; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.today { height: 40px; padding: 0 16px; border: 1px solid var(--field); border-radius: 10px; background: var(--card); color: var(--ink); font-size: 14px; font-weight: 500; }
.steps { display: flex; }
.icon { width: 40px; height: 40px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); display: flex; align-items: center; justify-content: center; padding: 0; }
h1 { margin: 0; font-size: 24px; font-weight: 700; flex: 1; min-width: 200px; }
.ai-toggle { height: 40px; padding: 0 14px 0 10px; border-radius: 10px; display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 700; border: 1px solid var(--field); background: var(--card); color: var(--accent); }
.ai-toggle[aria-pressed='true'] { border-color: var(--accent); background: var(--accentSoft); }
.error { margin: 0; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }

.panel { flex: 1; min-height: 420px; background: var(--card); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; display: flex; flex-direction: column; }
.week-head, .strip { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); padding: 8px; border-bottom: 1px solid var(--line); }
/* 跟 TimeGrid 緊湊模式的欄位起點（左邊 44px 放時間）對齊，表頭的日子才會正好在那一欄上面 */
.week-head { padding-left: 44px; }
.week-day, .strip-day { border: 0; border-radius: 10px; background: transparent; color: var(--muted); min-height: 60px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; }
.wd { font-size: 12px; font-weight: 500; }
.num { font-size: 16px; font-weight: 700; font-family: Manrope, sans-serif; width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: var(--ink); }
.week-day.today { color: var(--accent); }
.week-day.selected .num { box-shadow: inset 0 0 0 2px var(--accent); color: var(--accent); }
.week-day.today .num { background: var(--accent); color: var(--onAccent); box-shadow: none; }
.strip-day .num { font-size: 20px; width: auto; height: auto; }
.strip-day.today { color: var(--accent); }
.strip-day.today .num { color: var(--accent); }
.strip-day.selected { background: var(--accent); color: var(--onAccent); }
.strip-day.selected .num { color: var(--onAccent); }
.empty { margin: 0; padding: 0 16px 16px 76px; color: var(--muted); font-size: 14px; }

.assistant { flex: none; width: 340px; box-sizing: border-box; background: var(--card); border-left: 1px solid var(--line); display: flex; flex-direction: column; }
.ai-head { display: flex; align-items: center; gap: 12px; padding: 20px 12px 16px 20px; border-bottom: 1px solid var(--line); }
.ai-logo { width: 36px; height: 36px; border-radius: 10px; background: var(--accentSoft); color: var(--accent); display: flex; align-items: center; justify-content: center; flex: none; }
.ai-title { flex: 1; min-width: 0; }
.ai-title h2 { margin: 0; font-size: 16px; font-weight: 700; }
.ai-sub { font-size: 12px; color: var(--muted); }

.drawer { position: absolute; top: 0; right: 0; bottom: 0; width: 380px; max-width: 100%; border-left: 1px solid var(--line); box-shadow: -12px 0 32px rgba(0, 0, 0, 0.14); z-index: 5; }
.scrim { position: absolute; inset: 0; background: var(--scrim); z-index: 10; display: flex; justify-content: center; align-items: flex-start; padding: 72px 16px; box-sizing: border-box; overflow-y: auto; }
.dialog { width: 520px; max-width: 100%; border-radius: 18px; overflow: hidden; box-shadow: 0 24px 60px rgba(0, 0, 0, 0.28); }

/* 視窗不夠寬時，AI 助理改成蓋在右邊（設計稿是 flex-wrap 掉到下一行，但這裡整頁是固定高度，掉下去就看不到了） */
@media (max-width: 1180px) {
  .assistant { position: absolute; top: 0; right: 0; bottom: 0; z-index: 4; box-shadow: -12px 0 32px rgba(0, 0, 0, 0.14); }
}
</style>

<script setup lang="ts">
// 週、日視圖的時間軸：一天一欄，每小時一格，行程依起訖畫成方塊，重疊的並排。
//
// 跟設計稿不同的地方：從 00:00 畫到 24:00（設計稿從 06:00 開始），打開時捲到 07:00。
// 這樣跨夜行程的凌晨那一段（例如前一晚 22:00 唱到 01:00）在隔天也畫得出來。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { timeOf } from '../monthGrid'
import { placeSegments, segmentsOn, type Placed } from './timeline'
import { useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const props = defineProps<{
  days: string[]
  // 週視圖、手機版：每小時矮一點、字小一點
  compact?: boolean
  // 手機版的週視圖一欄很窄，只放得下標題
  titleOnly?: boolean
}>()

const calendar = useCalendarState()
const theme = useThemeState()
const scroller = ref<HTMLElement | null>(null)

const hourHeight = computed(() => (props.compact ? 52 : 64))
const HOURS = Array.from({ length: 24 }, (_, h) => `${String(h).padStart(2, '0')}:00`)

const columns = computed(() =>
  props.days.map((day) => ({
    day,
    today: day === calendar.today,
    blocks: placeSegments(segmentsOn(day, calendar.eventsOn(day))),
  })),
)

// 現在時間那條紅線：每分鐘更新一次就夠了
const nowMinutes = ref(minutesNow())
let timer: number | undefined
onMounted(() => {
  timer = window.setInterval(() => (nowMinutes.value = minutesNow()), 60_000)
  // 打開時捲到早上七點：凌晨通常沒事，但要捲得上去看
  if (scroller.value) scroller.value.scrollTop = 7 * hourHeight.value - 8
})
onBeforeUnmount(() => window.clearInterval(timer))

function minutesNow() {
  const now = new Date()
  return now.getHours() * 60 + now.getMinutes()
}

function blockStyle(block: Placed) {
  const c = theme.colors.value[block.event.category]
  const on = block.event.id === calendar.detailId.value
  const gap = props.compact ? 2 : 3
  const top = (block.startMin / 60) * hourHeight.value
  // 太短的行程（15 分鐘）也要高到點得到、看得到字
  const height = Math.max(props.compact ? 24 : 30, ((block.endMin - block.startMin) / 60) * hourHeight.value - 2)
  return {
    top: `${top + 1}px`,
    height: `${height}px`,
    left: `calc(${(block.col / block.cols) * 100}% + ${gap}px)`,
    width: `calc(${100 / block.cols}% - ${gap * 2}px)`,
    background: on ? c.ink : c.soft,
    color: on ? c.soft : c.ink,
  }
}

// 「15:00 – 16:00」；跨夜的那一段用「↳」標出是延續或還沒結束
function timeLabel(block: Placed) {
  const from = block.startsToday ? timeOf(block.event.start) : '↳'
  const to = block.endsToday ? timeOf(block.event.end) : '隔天'
  return `${from} – ${to}`
}

function subLabel(block: Placed) {
  const location = block.event.location
  return location && !props.compact ? `${timeLabel(block)} · ${location}` : timeLabel(block)
}
</script>

<template>
  <div ref="scroller" class="scroller">
    <div class="timeline" :class="{ compact }" :style="{ height: `${24 * hourHeight}px` }">
      <div v-for="(label, h) in HOURS" :key="label" class="hour" :style="{ top: `${h * hourHeight}px`, height: `${hourHeight}px` }">
        <span v-if="h > 0" class="hour-label">{{ label }}</span>
      </div>
      <div class="columns" :style="{ gridTemplateColumns: `repeat(${days.length}, minmax(0, 1fr))` }">
        <div v-for="col in columns" :key="col.day" class="column" :class="{ today: col.today && days.length > 1, single: days.length === 1 }">
          <button
            v-for="b in col.blocks"
            :key="b.event.id"
            type="button"
            class="block"
            :style="blockStyle(b)"
            :aria-label="`${b.event.title}，${timeLabel(b)}`"
            @click="calendar.detailId.value = b.event.id"
          >
            <span class="title">{{ b.event.title }}</span>
            <span v-if="!titleOnly" class="sub">{{ subLabel(b) }}</span>
          </button>
          <div v-if="col.today" class="now" :style="{ top: `${(nowMinutes / 60) * hourHeight}px` }"><span /></div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scroller { overflow-y: auto; min-height: 0; flex: 1; }
.timeline { position: relative; margin: 12px 0; }
.hour { position: absolute; left: 0; right: 0; border-top: 1px solid var(--line); box-sizing: border-box; }
.hour:first-child { border-top: 0; }
.hour-label { position: absolute; left: 12px; top: -9px; width: 44px; font-size: 12px; color: var(--muted); font-family: Manrope, sans-serif; background: var(--card); text-align: right; padding-right: 4px; }
.compact .hour-label { left: 4px; width: 34px; font-size: 10px; top: -7px; }
.columns { position: absolute; left: 68px; right: 8px; top: 0; bottom: 0; display: grid; }
.compact .columns { left: 44px; }
.column { position: relative; border-left: 1px solid var(--line); }
.column.single { border-left: 0; }
.column.today { background: color-mix(in srgb, var(--accentSoft) 40%, transparent); }
.block { position: absolute; border: 0; border-radius: 10px; padding: 7px 10px; box-sizing: border-box; overflow: hidden; text-align: left; }
.compact .block { border-radius: 8px; padding: 4px 6px; }
.title { display: block; font-size: 14px; font-weight: 700; line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.compact .title { font-size: 12px; }
.sub { display: block; font-size: 12px; font-family: Manrope, 'Noto Sans TC', sans-serif; opacity: 0.85; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.compact .sub { font-size: 11px; }
.now { position: absolute; left: 0; right: 0; height: 2px; background: var(--now); z-index: 2; pointer-events: none; }
.now span { position: absolute; left: -5px; top: -4px; width: 10px; height: 10px; border-radius: 50%; background: var(--now); }
</style>

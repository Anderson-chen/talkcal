<script setup lang="ts">
// 行程詳情。桌面版是右側抽屜、手機版是整頁，內容一樣，只有外框和按鈕排法不同（variant）。
//
// 刪除要按兩次（「刪除」→「確定刪除？」）：設計稿是按一下就刪，但這裡是真的刪資料庫，沒有復原。
import { computed, ref, watch } from 'vue'
import { addDays, dayLabel, timeOf, weekdayName } from '../monthGrid'
import Icon from './Icon.vue'
import { message, useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const props = defineProps<{ variant: 'drawer' | 'page' }>()
const calendar = useCalendarState()
const theme = useThemeState()

const confirming = ref(false)
const removing = ref(false)
const error = ref<string | null>(null)

const event = computed(() => calendar.detail.value)

// 換看另一筆行程時，「確定刪除？」不能留著 —— 不然一不小心就刪錯筆
watch(() => event.value?.id, () => {
  confirming.value = false
  error.value = null
})

const dateText = computed(() => {
  const e = event.value
  if (!e) return ''
  const day = e.start.slice(0, 10)
  return `${day.slice(0, 4)}年${Number(day.slice(5, 7))}月${Number(day.slice(8))}日 星期${weekdayName(day)}`
})

// 同一天：「15:00 – 16:00」；隔天結束：「22:00 – 隔天 01:00」；更久：「08:00 – 10月8日（週四）18:00」
const timeText = computed(() => {
  const e = event.value
  if (!e) return ''
  const startDay = e.start.slice(0, 10)
  const endDay = e.end.slice(0, 10)
  if (endDay === startDay) return `${timeOf(e.start)} – ${timeOf(e.end)}`
  if (endDay === addDays(startDay, 1)) return `${timeOf(e.start)} – 隔天 ${timeOf(e.end)}`
  return `${timeOf(e.start)} – ${dayLabel(endDay)} ${timeOf(e.end)}`
})

const close = () => (calendar.detailId.value = null)

function showDay() {
  if (event.value) calendar.pickDate(event.value.start.slice(0, 10), 'day')
}

async function remove() {
  if (!event.value) return
  if (!confirming.value) {
    confirming.value = true
    return
  }
  removing.value = true
  error.value = null
  try {
    await calendar.remove(event.value.id)
  } catch (e) {
    error.value = message(e)
  } finally {
    removing.value = false
    confirming.value = false
  }
}
</script>

<template>
  <div v-if="event" class="detail" :class="variant">
    <div class="top">
      <template v-if="props.variant === 'drawer'">
        <span class="chip" :style="theme.chipStyle(event.category)">{{ theme.colors.value[event.category].label }}</span>
        <button type="button" class="icon" aria-label="關閉" @click="close"><Icon name="close" /></button>
      </template>
      <button v-else type="button" class="back" @click="close"><Icon name="chevronLeft" />返回</button>
    </div>

    <div class="body">
      <div class="heading">
        <span v-if="variant === 'page'" class="chip" :style="theme.chipStyle(event.category)">{{ theme.colors.value[event.category].label }}</span>
        <h2>{{ event.title }}</h2>
      </div>
      <div class="rows">
        <div class="row">
          <Icon name="clock" />
          <div class="stack">
            <span class="main">{{ dateText }}</span>
            <span class="sub">{{ timeText }}</span>
          </div>
        </div>
        <div v-if="event.location" class="row">
          <Icon name="pin" />
          <span class="main">{{ event.location }}</span>
        </div>
        <div v-if="event.note" class="row">
          <Icon name="note" />
          <span class="main note">{{ event.note }}</span>
        </div>
      </div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>

    <div class="actions">
      <button v-if="variant === 'drawer'" type="button" class="secondary" @click="showDay">在日視圖查看</button>
      <button type="button" class="danger" :class="{ confirming }" :disabled="removing" @click="remove">
        <Icon v-if="variant === 'page'" name="trash" :size="18" />
        {{ removing ? '刪除中…' : confirming ? '確定刪除？' : variant === 'page' ? '刪除行程' : '刪除' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; height: 100%; background: var(--card); color: var(--ink); }
.top { display: flex; justify-content: space-between; align-items: center; padding: 16px 16px 8px 24px; }
.page .top { padding: 16px 12px 4px 8px; }
.icon { width: 40px; height: 40px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); display: flex; align-items: center; justify-content: center; padding: 0; }
.back { height: 44px; padding: 0 12px 0 6px; border: 0; background: transparent; color: var(--accent); font-size: 16px; font-weight: 500; display: flex; align-items: center; gap: 2px; }
.chip { align-self: flex-start; display: inline-flex; align-items: center; height: 28px; padding: 0 12px; border-radius: 14px; font-size: 13px; font-weight: 600; }
.body { padding: 8px 24px; display: flex; flex-direction: column; gap: 20px; flex: 1; overflow-y: auto; }
.heading { display: flex; flex-direction: column; gap: 12px; }
h2 { margin: 0; font-size: 24px; font-weight: 700; line-height: 1.3; }
.page h2 { font-size: 26px; }
.rows { display: flex; flex-direction: column; border-top: 1px solid var(--line); }
.row { display: flex; gap: 14px; padding: 14px 0; border-bottom: 1px solid var(--line); color: var(--muted); align-items: flex-start; }
.row :deep(svg) { flex: none; margin-top: 2px; }
.stack { display: flex; flex-direction: column; gap: 2px; }
.main { font-size: 15px; font-weight: 500; color: var(--ink); }
.note { font-weight: 400; line-height: 1.6; white-space: pre-line; }
.sub { font-size: 14px; color: var(--muted); font-family: Manrope, 'Noto Sans TC', sans-serif; }
.error { margin: 0; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }
.actions { padding: 16px 24px 24px; display: flex; gap: 10px; }
.page .actions { padding: 16px 24px 28px; }
.secondary { flex: 1; height: 44px; border: 1px solid var(--field); border-radius: 10px; background: transparent; color: var(--ink); font-size: 14px; font-weight: 600; }
.danger { height: 44px; padding: 0 16px; border: 1px solid var(--dangerLine); border-radius: 10px; background: transparent; color: var(--danger); font-size: 14px; font-weight: 600; display: flex; align-items: center; justify-content: center; gap: 8px; }
.page .danger { width: 100%; height: 50px; border-radius: 14px; font-size: 16px; }
.danger.confirming { background: var(--danger); color: var(--card); border-color: var(--danger); }
</style>

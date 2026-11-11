<script setup lang="ts">
// 查看封存的行程（#274）：選一天或一段期間，列出那段時間已經封存的行程。桌面版是對話框，手機版是整頁（variant）。
//
// 封存的行程只能看：沒有修改、刪除按鈕，也不會放回月曆。查不到（封存暫時讀不到）只影響這裡，月曆照常。
// 預設查正在看的那個月：使用者是翻到那個月才點進來的。
import { computed, ref, watch } from 'vue'
import { listArchivedEvents, type SavedEvent } from '../calendar'
import { ARCHIVE_NOTICE, archivedTimeLabel, monthBounds, rangeProblem } from './archive'
import Icon from './Icon.vue'
import { message, monthOf, useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const props = defineProps<{ variant: 'dialog' | 'page' }>()
const emit = defineEmits<{ close: [] }>()
const calendar = useCalendarState()
const theme = useThemeState()

const { year, month } = calendar.shown.value
const bounds = monthBounds(year, month)
const selected = monthOf(calendar.sel.value)

const mode = ref<'day' | 'range'>('range')
const day = ref(selected.year === year && selected.month === month ? calendar.sel.value : bounds.from)
const from = ref(bounds.from)
const to = ref(bounds.to)

const results = ref<SavedEvent[] | null>(null)
const error = ref<string | null>(null)
const problem = ref<string | null>(null)
const loading = ref(false)

const range = computed(() => (mode.value === 'day' ? { from: day.value, to: day.value } : { from: from.value, to: to.value }))
// 改了日期或換了一天／一段期間，上一次的「日期不對」就不算數了
watch(range, () => (problem.value = null))

// 查詢中「查看」按鈕停用（按 Enter 也送不出去），不會有兩次查詢同時在跑、舊的結果蓋掉新的
async function search() {
  problem.value = rangeProblem(range.value.from, range.value.to)
  if (problem.value) return
  loading.value = true
  error.value = null
  try {
    results.value = await listArchivedEvents(range.value.from, range.value.to)
  } catch (e) {
    results.value = null
    error.value = message(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="archive" :class="variant">
    <header class="head">
      <button v-if="props.variant === 'page'" type="button" class="back" @click="emit('close')"><Icon name="chevronLeft" />返回</button>
      <h2 id="archive-title">封存的行程</h2>
      <button v-if="props.variant === 'dialog'" type="button" class="icon" aria-label="關閉" @click="emit('close')"><Icon name="close" /></button>
    </header>

    <div class="body">
      <p class="hint">{{ ARCHIVE_NOTICE }}，不能修改或刪除。</p>

      <form class="fields" @submit.prevent="search">
        <fieldset class="modes">
          <legend>範圍</legend>
          <label class="mode"><input v-model="mode" type="radio" value="day" />一天</label>
          <label class="mode"><input v-model="mode" type="radio" value="range" />一段期間</label>
        </fieldset>
        <div v-if="mode === 'day'" class="field">
          <label for="archive-day">日期</label>
          <input id="archive-day" v-model="day" type="date" />
        </div>
        <div v-else class="dates">
          <div class="field">
            <label for="archive-from">開始日期</label>
            <input id="archive-from" v-model="from" type="date" />
          </div>
          <div class="field">
            <label for="archive-to">結束日期</label>
            <input id="archive-to" v-model="to" type="date" />
          </div>
        </div>
        <p v-if="problem" role="alert" class="error">{{ problem }}</p>
        <button type="submit" class="primary" :disabled="loading">{{ loading ? '查詢中…' : '查看' }}</button>
      </form>

      <p v-if="error" role="alert" class="error">讀不到封存的行程：{{ error }}</p>

      <section v-if="results" class="results" aria-label="查到的封存行程">
        <p class="count">{{ results.length ? `${results.length} 個行程` : '這段期間沒有封存的行程' }}</p>
        <ul>
          <li v-for="e in results" :key="e.id" class="item">
            <div class="item-head">
              <span class="chip" :style="theme.chipStyle(e.category)">{{ theme.colors.value[e.category].label }}</span>
              <span class="item-title">{{ e.title }}</span>
            </div>
            <span class="row"><Icon name="clock" :size="16" />{{ archivedTimeLabel(e.start, e.end) }}</span>
            <span v-if="e.location" class="row"><Icon name="pin" :size="16" />{{ e.location }}</span>
            <span v-if="e.note" class="row note"><Icon name="note" :size="16" />{{ e.note }}</span>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.archive { display: flex; flex-direction: column; background: var(--card); color: var(--ink); max-height: 100%; }
.archive.page { height: 100%; background: var(--panel); }
.head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 20px 20px 4px 28px; }
.page .head { padding: 16px 12px 8px 8px; justify-content: flex-start; background: var(--bg); border-bottom: 1px solid var(--line); }
h2 { margin: 0; font-size: 20px; font-weight: 700; }
.page h2 { font-size: 17px; }
.icon { width: 40px; height: 40px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); display: flex; align-items: center; justify-content: center; padding: 0; }
.back { height: 44px; padding: 0 12px 0 6px; border: 0; background: transparent; color: var(--accent); font-size: 16px; font-weight: 500; display: flex; align-items: center; gap: 2px; }
.body { padding: 8px 28px 24px; display: flex; flex-direction: column; gap: 16px; overflow-y: auto; min-height: 0; }
.page .body { flex: 1; padding: 16px 16px 32px; }
.hint { margin: 0; font-size: 14px; color: var(--muted); line-height: 1.6; }
.fields { display: flex; flex-direction: column; gap: 14px; }
.modes { display: flex; gap: 16px; border: 0; margin: 0; padding: 0; }
legend { font-size: 13px; font-weight: 500; color: var(--muted); padding: 0; margin-bottom: 8px; }
.mode { display: flex; align-items: center; gap: 6px; font-size: 15px; cursor: pointer; min-height: 32px; }
.mode input { width: 18px; height: 18px; margin: 0; accent-color: var(--accent); }
.dates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.field label { font-size: 13px; font-weight: 500; color: var(--muted); }
.field input { height: 44px; border: 1px solid var(--field); border-radius: 10px; padding: 0 12px; font-size: 15px; background: var(--card); color: var(--ink); box-sizing: border-box; min-width: 0; width: 100%; }
.primary { align-self: flex-start; height: 44px; padding: 0 22px; border: 0; border-radius: 10px; background: var(--accent); color: var(--onAccent); font-size: 14px; font-weight: 600; }
.error { margin: 0; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }
.results { display: flex; flex-direction: column; gap: 8px; }
.count { margin: 0; font-size: 13px; color: var(--muted); }
ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.item { display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.item-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.chip { display: inline-flex; align-items: center; height: 24px; padding: 0 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
.item-title { font-size: 15px; font-weight: 600; }
.row { display: flex; align-items: flex-start; gap: 8px; font-size: 14px; color: var(--muted); }
.row :deep(svg) { flex: none; margin-top: 2px; }
.note { white-space: pre-line; line-height: 1.6; }
</style>

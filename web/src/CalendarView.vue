<script setup lang="ts">
// 行事曆畫面：上半部是「一句話 → 預覽 → 確認」，下半部是月曆。
// 流程對應後端三個端點：parse（不存）→ 使用者可以改 → events（存）→ 重新讀這一頁。
import { computed, onMounted, ref } from 'vue'
import { addEvents, listEvents, parseEvents, type EventFields, type SavedEvent } from './calendar'
import { daysCovered, gridRange, monthGrid, toIsoDate } from './monthGrid'

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

// ── 月曆 ─────────────────────────────────────────────
const today = toIsoDate(new Date())
const shown = ref({ year: Number(today.slice(0, 4)), month: Number(today.slice(5, 7)) })
const days = computed(() => monthGrid(shown.value.year, shown.value.month))
const events = ref<SavedEvent[]>([])
const loadError = ref<string | null>(null)

// 每一天有哪些行程。跨夜、跨天的行程會出現在它涵蓋的每一天（規則在 daysCovered）
const eventsByDay = computed(() => {
  const map = new Map<string, SavedEvent[]>()
  for (const event of events.value) {
    for (const day of daysCovered(event.start, event.end)) {
      const list = map.get(day) ?? []
      list.push(event)
      map.set(day, list)
    }
  }
  return map
})

// 快速連按「下個月」時，回應可能不照順序回來：慢的上個月蓋掉快的這個月。
// 每次載入領一個號碼，回來時不是最新那一號就丟掉
let latestLoad = 0

async function load() {
  const ticket = ++latestLoad
  const { from, to } = gridRange(days.value)
  try {
    const loaded = await listEvents(from, to)
    if (ticket !== latestLoad) return
    events.value = loaded
    loadError.value = null
  } catch (e) {
    if (ticket !== latestLoad) return
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

function showMonth(year: number, month: number) {
  // 交給 Date 處理進位：month = 13 會自動變成明年一月、0 變成去年十二月
  const first = new Date(year, month - 1, 1)
  shown.value = { year: first.getFullYear(), month: first.getMonth() + 1 }
  load()
}

const isOtherMonth = (day: string) => Number(day.slice(5, 7)) !== shown.value.month

// 格子裡每筆行程前面的小字：在這天開始的顯示開始時間，從前一天延續過來的顯示「↳」
const startLabel = (event: SavedEvent, day: string) =>
  event.start.slice(0, 10) === day ? event.start.slice(11, 16) : '↳'

onMounted(load)

// ── 一句話 → 預覽 → 確認 ─────────────────────────────
const text = ref('')
const drafts = ref<EventFields[]>([])
const parsing = ref(false)
const saving = ref(false)
const draftMessage = ref<string | null>(null)

async function parse() {
  const input = text.value.trim()
  if (!input || parsing.value) return
  parsing.value = true
  draftMessage.value = null
  try {
    // datetime-local 的值是到分鐘的 "YYYY-MM-DDTHH:mm"；後端給的帶秒數，先切掉，輸入框才會好好顯示。
    // 送回後端時不必補秒數 —— Jackson 兩種格式都讀得懂
    drafts.value = (await parseEvents(input)).map((e) => ({ ...e, start: e.start.slice(0, 16), end: e.end.slice(0, 16) }))
    if (drafts.value.length === 0) {
      draftMessage.value = '沒看出任何行程，換個說法試試（例如：明天下午三點跟小明吃飯）'
    }
  } catch (e) {
    draftMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    parsing.value = false
  }
}

async function confirm() {
  if (drafts.value.length === 0 || saving.value) return
  saving.value = true
  draftMessage.value = null
  try {
    const saved = await addEvents(drafts.value)
    drafts.value = []
    text.value = ''
    // 跳到第一筆行程那個月：說「下個月五號」時，存完要看得到它（showMonth 會重新載入）
    const first = saved[0].start
    showMonth(Number(first.slice(0, 4)), Number(first.slice(5, 7)))
  } catch (e) {
    // 400（使用者把時間改壞了）或 502：草稿留著，讓使用者改完再按一次
    draftMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

function removeDraft(index: number) {
  drafts.value.splice(index, 1)
}

function cancel() {
  drafts.value = []
  draftMessage.value = null
}
</script>

<template>
  <main>
    <form class="ask" @submit.prevent="parse">
      <input v-model="text" placeholder="用一句話新增行程，例如：明天下午三點跟小明吃飯，下週三早上九點看牙醫" :disabled="parsing || saving" />
      <button :disabled="parsing || saving || !text.trim()">{{ parsing ? '解析中…' : '解析' }}</button>
    </form>

    <section v-if="drafts.length" class="preview">
      <p class="hint">確認一下，有錯直接改：</p>
      <div v-for="(draft, i) in drafts" :key="i" class="draft">
        <input v-model="draft.title" class="title" aria-label="標題" />
        <input v-model="draft.start" type="datetime-local" aria-label="開始" />
        <span>～</span>
        <input v-model="draft.end" type="datetime-local" aria-label="結束" />
        <button type="button" class="remove" :disabled="saving" @click="removeDraft(i)" aria-label="刪除這筆">✕</button>
      </div>
      <div class="actions">
        <button :disabled="saving" @click="confirm">{{ saving ? '加入中…' : `加入行事曆（${drafts.length} 筆）` }}</button>
        <button type="button" class="secondary" :disabled="saving" @click="cancel">取消</button>
      </div>
    </section>
    <p v-if="draftMessage" class="message">{{ draftMessage }}</p>

    <header class="nav">
      <button type="button" @click="showMonth(shown.year, shown.month - 1)" aria-label="上個月">‹</button>
      <h2>{{ shown.year }} 年 {{ shown.month }} 月</h2>
      <button type="button" @click="showMonth(shown.year, shown.month + 1)" aria-label="下個月">›</button>
      <button type="button" class="secondary" @click="showMonth(Number(today.slice(0, 4)), Number(today.slice(5, 7)))">今天</button>
    </header>
    <p v-if="loadError" class="message">讀不到行程：{{ loadError }}</p>

    <div class="grid">
      <div v-for="w in WEEKDAYS" :key="w" class="weekday">{{ w }}</div>
      <div
        v-for="day in days"
        :key="day"
        class="day"
        :class="{ other: isOtherMonth(day), today: day === today }"
      >
        <span class="date">{{ Number(day.slice(8)) }}</span>
        <div
          v-for="event in eventsByDay.get(day) ?? []"
          :key="event.id"
          class="event"
          :title="`${event.title}\n${event.start.replace('T', ' ').slice(0, 16)} ～ ${event.end.replace('T', ' ').slice(0, 16)}`"
        >
          <span class="time">{{ startLabel(event, day) }}</span> {{ event.title }}
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
main { max-width: 960px; margin: 0 auto; padding: 16px; font-family: system-ui, sans-serif; }
button { padding: 6px 12px; cursor: pointer; }
button:disabled { cursor: default; }
.secondary { background: none; border: 1px solid #dadce0; border-radius: 4px; }

.ask { display: flex; gap: 8px; }
.ask input { flex: 1; padding: 8px; }

.preview { margin-top: 12px; padding: 12px; background: #e8f0fe; border-radius: 8px; }
.hint { margin: 0 0 8px; color: #3c4043; }
.draft { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 6px; }
.draft .title { flex: 1; min-width: 8em; padding: 6px; }
.remove { background: none; border: none; color: #5f6368; }
.actions { display: flex; gap: 8px; margin-top: 8px; }
.message { color: #c5221f; }

.nav { display: flex; align-items: center; gap: 8px; margin: 20px 0 8px; }
.nav h2 { margin: 0; min-width: 9em; text-align: center; font-size: 1.2em; }

.grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); border-left: 1px solid #dadce0; border-top: 1px solid #dadce0; }
.weekday { padding: 4px; text-align: center; font-size: 0.85em; color: #5f6368; border-right: 1px solid #dadce0; border-bottom: 1px solid #dadce0; }
.day { min-height: 88px; padding: 4px; border-right: 1px solid #dadce0; border-bottom: 1px solid #dadce0; overflow: hidden; }
.day.other { background: #f8f9fa; color: #9aa0a6; }
.date { display: inline-block; min-width: 1.6em; font-size: 0.85em; text-align: center; border-radius: 50%; }
.day.today .date { background: #1a73e8; color: white; }
.event { margin-top: 2px; padding: 1px 4px; font-size: 0.8em; background: #e8f0fe; border-radius: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.time { color: #1967d2; }
</style>

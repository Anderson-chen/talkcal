<script setup lang="ts">
// 手動新增行程。桌面版是對話框、手機版是整頁（variant）。從 AI 卡片按「修改」過來時，欄位帶著草稿。
//
// 這裡只管畫面和接線：欄位怎麼驗證、怎麼變成送出的資料，在 eventDraft.ts（純函式，有單元測試）。
// 跟設計稿不同的地方：結束時間早於開始時間時，當成「結束在隔天」並標示出來（理由見 isOvernight）。
import { computed, onMounted, reactive, ref } from 'vue'
import { CATEGORIES } from '../calendar'
import { timeOf } from '../monthGrid'
import { isOvernight, toDraft, type FormFields } from './eventDraft'
import Icon from './Icon.vue'
import { problemOf, type Problem } from '../http'
import TraceId from '../TraceId.vue'
import { useCalendarState } from './useCalendar'
import { useThemeState } from './useTheme'

const props = defineProps<{ variant: 'dialog' | 'page' }>()
const calendar = useCalendarState()
const theme = useThemeState()

const request = calendar.form.value!
const initial = request.initial
const form = reactive<FormFields>({
  title: initial.title,
  date: initial.start.slice(0, 10),
  start: timeOf(initial.start),
  end: timeOf(initial.end),
  category: initial.category,
  location: initial.location ?? '',
  note: initial.note ?? '',
})
const error = ref<Problem | null>(null)
const saving = ref(false)
const titleInput = ref<HTMLInputElement | null>(null)

onMounted(() => titleInput.value?.focus())

const overnight = computed(() => isOvernight(form.start, form.end))

function close() {
  calendar.form.value = null
}

async function save() {
  if (saving.value) return
  const result = toDraft(form)
  // 表單自己的檢查（結束早於開始……）沒有送出請求，也就沒有追蹤碼
  if ('error' in result) return fail({ text: result.error, traceId: null })

  saving.value = true
  error.value = null
  try {
    // 表單只產生行程內容；從 AI 卡片「修改」來的，初始值裡有卡片的 id，再把它接回去（#223）。手動新增不帶這個欄位
    const proposalId = initial.proposalId
    const draft = proposalId ? { ...result.draft, proposalId } : result.draft
    const [saved] = await calendar.add([draft])
    request.onSaved?.(saved)
    close()
    // 存好直接打開它的詳情（照設計稿），使用者看得到存進去的樣子
    calendar.detailId.value = saved.id
  } catch (e) {
    fail(problemOf(e))
  } finally {
    saving.value = false
  }
}

function fail(problem: Problem) {
  error.value = problem
}
</script>

<template>
  <div class="form" :class="variant" @keydown.esc="close">
    <header v-if="props.variant === 'page'" class="page-head">
      <button type="button" class="text muted" @click="close">取消</button>
      <h1 id="event-form-title">新增行程</h1>
      <button type="button" class="text accent" :disabled="saving" @click="save">{{ saving ? '儲存中' : '儲存' }}</button>
    </header>
    <header v-else class="dialog-head">
      <h2 id="event-form-title">新增行程</h2>
      <button type="button" class="icon" aria-label="關閉" @click="close"><Icon name="close" /></button>
    </header>

    <form class="fields" @submit.prevent="save">
      <div class="field">
        <label for="ev-title">標題</label>
        <input id="ev-title" ref="titleInput" v-model="form.title" type="text" placeholder="例如：產品週會" />
      </div>
      <div class="times" :class="variant">
        <div class="field">
          <label for="ev-date">日期</label>
          <input id="ev-date" v-model="form.date" type="date" />
        </div>
        <div class="field">
          <label for="ev-start">開始</label>
          <input id="ev-start" v-model="form.start" type="time" />
        </div>
        <div class="field">
          <label for="ev-end">結束<span v-if="overnight" class="hint">（隔天）</span></label>
          <input id="ev-end" v-model="form.end" type="time" />
        </div>
      </div>
      <fieldset class="field">
        <legend>分類</legend>
        <div class="cats">
          <button
            v-for="c in CATEGORIES"
            :key="c"
            type="button"
            class="cat"
            :aria-pressed="form.category === c"
            :style="form.category === c ? { borderColor: theme.colors.value[c].ink, background: theme.colors.value[c].soft, color: theme.colors.value[c].ink } : undefined"
            @click="form.category = c"
          >
            <span class="dot" :style="{ background: form.category === c ? theme.colors.value[c].ink : theme.colors.value[c].color }" />
            {{ theme.colors.value[c].label }}
          </button>
        </div>
      </fieldset>
      <div class="field">
        <label for="ev-loc">地點</label>
        <input id="ev-loc" v-model="form.location" type="text" placeholder="選填" />
      </div>
      <div class="field">
        <label for="ev-note">備註</label>
        <textarea id="ev-note" v-model="form.note" rows="3" placeholder="選填" />
      </div>
      <p v-if="error" role="alert" class="error">{{ error.text }}<TraceId v-if="error.traceId" :id="error.traceId" /></p>
      <!-- 讓 Enter 也能送出；畫面上的按鈕在下面（對話框）或上面（整頁） -->
      <button type="submit" hidden />
    </form>

    <footer v-if="props.variant === 'dialog'" class="dialog-foot">
      <button type="button" class="secondary" @click="close">取消</button>
      <button type="button" class="primary" :disabled="saving" @click="save">{{ saving ? '儲存中…' : '儲存行程' }}</button>
    </footer>
  </div>
</template>

<style scoped>
.form { display: flex; flex-direction: column; background: var(--card); color: var(--ink); }
.form.page { height: 100%; background: var(--panel); }
.page-head { padding: 16px 12px 12px; display: grid; grid-template-columns: 80px 1fr 80px; align-items: center; background: var(--bg); border-bottom: 1px solid var(--line); }
.page-head h1 { margin: 0; font-size: 17px; font-weight: 700; text-align: center; }
.text { height: 44px; border: 0; background: transparent; font-size: 16px; padding: 0 8px; }
.text.muted { color: var(--muted); text-align: left; }
.text.accent { color: var(--accent); font-weight: 700; text-align: right; }
.dialog-head { display: flex; justify-content: space-between; align-items: center; padding: 20px 20px 4px 28px; }
.dialog-head h2 { margin: 0; font-size: 20px; font-weight: 700; }
.icon { width: 40px; height: 40px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); display: flex; align-items: center; justify-content: center; padding: 0; }
.fields { padding: 16px 28px; display: flex; flex-direction: column; gap: 16px; }
.page .fields { flex: 1; min-height: 0; overflow-y: auto; padding: 20px 16px 32px; gap: 18px; }
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; border: 0; margin: 0; padding: 0; }
label, legend { font-size: 13px; font-weight: 500; color: var(--muted); padding: 0; }
legend { margin-bottom: 8px; }
.hint { color: var(--accent); font-weight: 600; }
input, textarea { height: 44px; border: 1px solid var(--field); border-radius: 10px; padding: 0 12px; font-size: 15px; background: var(--card); color: var(--ink); box-sizing: border-box; min-width: 0; width: 100%; }
textarea { height: auto; padding: 10px 12px; resize: vertical; }
.times { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.times.page { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.times.page .field:first-child { grid-column: 1 / -1; }
.cats { display: flex; flex-wrap: wrap; gap: 8px; }
.cat { height: 40px; padding: 0 14px; border-radius: 20px; font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; border: 1px solid var(--field); background: var(--card); color: var(--ink); }
.cat[aria-pressed='true'] { border-width: 2px; padding: 0 13px; }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.error { margin: 0; padding: 10px 12px; border-radius: 10px; background: var(--dangerSoft); color: var(--danger); font-size: 14px; }
.dialog-foot { display: flex; justify-content: flex-end; gap: 10px; padding: 8px 28px 24px; }
.secondary { height: 44px; padding: 0 18px; border: 1px solid var(--field); border-radius: 10px; background: transparent; color: var(--ink); font-size: 14px; font-weight: 600; }
.primary { height: 44px; padding: 0 22px; border: 0; border-radius: 10px; background: var(--accent); color: var(--onAccent); font-size: 14px; font-weight: 600; }
</style>

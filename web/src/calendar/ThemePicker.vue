<script setup lang="ts">
// 主題選擇：每個選項畫一個縮圖（底色、強調色、兩個分類色塊），比只寫名字好挑
import { categoryColors, THEME_KEYS, THEMES } from './theme'
import { useThemeState } from './useTheme'

defineProps<{ large?: boolean }>()
const theme = useThemeState()
const options = THEME_KEYS.map((key) => ({ key, theme: THEMES[key], colors: categoryColors(THEMES[key].dark) }))
</script>

<template>
  <div class="grid" :class="{ large }">
    <button
      v-for="o in options"
      :key="o.key"
      type="button"
      class="option"
      :aria-pressed="theme.key.value === o.key"
      @click="theme.choose(o.key)"
    >
      <span class="preview" :style="{ background: o.theme.bg, borderColor: o.theme.line }">
        <span class="bar" :style="{ background: o.theme.accent }" />
        <span class="chip" :style="{ background: o.colors.WORK.soft, width: '80%' }" />
        <span class="chip" :style="{ background: o.colors.SOCIAL.soft, width: '60%' }" />
      </span>
      <span class="label">
        <span class="name">{{ o.theme.name }}</span>
        <span class="sub">{{ o.theme.dark ? '深色' : '淺色' }}</span>
      </span>
    </button>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.grid.large { gap: 12px; }
.option { display: flex; flex-direction: column; gap: 6px; padding: 6px; border: 1px solid var(--line); border-radius: 12px; text-align: left; background: var(--card); color: var(--ink); }
.option[aria-pressed='true'] { padding: 5px; border: 2px solid var(--accent); }
.preview { height: 48px; border-radius: 8px; border: 1px solid; padding: 7px; display: flex; flex-direction: column; gap: 4px; box-sizing: border-box; }
.large .preview { height: 64px; }
.bar { width: 40%; height: 6px; border-radius: 3px; }
.chip { height: 8px; border-radius: 3px; }
.label { display: flex; align-items: baseline; justify-content: space-between; padding: 0 2px; }
.name { font-size: 13px; font-weight: 700; }
.large .name { font-size: 15px; }
.sub { font-size: 11px; color: var(--muted); }
</style>

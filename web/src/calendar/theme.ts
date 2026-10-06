// 主題與分類顏色，數值照設計稿（行事曆 Prototype）。
//
// 主題是資料：元件裡一律寫 var(--accent)、var(--line) 這種 CSS 變數，
// 換主題只是把根元素上的變數換掉（toCssVars），不必每個元件都知道現在是哪個主題。
// 分類顏色有深淺兩組：深色主題下，淺色底的分類標籤會刺眼，所以跟著 dark 換一組。

import type { Category } from '../calendar'

export type ThemeKey = 'sky' | 'night' | 'forest' | 'sunset'

export interface Theme {
  name: string
  dark: boolean
  bg: string
  panel: string
  card: string
  ink: string
  muted: string
  faint: string
  line: string
  field: string
  seg: string
  segOn: string
  accent: string
  accentSoft: string
  onAccent: string
  danger: string
  dangerLine: string
  dangerSoft: string
  now: string
  scrim: string
}

export const THEMES: Record<ThemeKey, Theme> = {
  sky: { name: '晴空', dark: false, bg: '#FFFFFF', panel: '#F5F6F8', card: '#FFFFFF', ink: '#14171F', muted: '#5B6372', faint: '#9AA1AE', line: '#E5E8EC', field: '#D5D9E0', seg: '#E9ECF0', segOn: '#FFFFFF', accent: '#2F5BEA', accentSoft: '#E6ECFD', onAccent: '#FFFFFF', danger: '#B4232F', dangerLine: '#F0C4CA', dangerSoft: '#FBE3E7', now: '#D92D3F', scrim: 'rgba(20, 23, 31, 0.42)' },
  night: { name: '夜幕', dark: true, bg: '#12141A', panel: '#12141A', card: '#1C1F27', ink: '#ECEEF2', muted: '#A3AAB8', faint: '#5D6472', line: '#2A2F3A', field: '#3A404C', seg: '#232731', segOn: '#353B48', accent: '#7C9BFF', accentSoft: '#253158', onAccent: '#0D1020', danger: '#F6A3B1', dangerLine: '#5A2832', dangerSoft: '#3D1A22', now: '#FF6B7D', scrim: 'rgba(0, 0, 0, 0.6)' },
  forest: { name: '森林', dark: false, bg: '#FBFAF6', panel: '#F2F1EA', card: '#FFFFFF', ink: '#1B2420', muted: '#59625C', faint: '#A0A69F', line: '#E4E2D8', field: '#D3D1C5', seg: '#E8E6DC', segOn: '#FFFFFF', accent: '#1F6B4F', accentSoft: '#DCEDE4', onAccent: '#FFFFFF', danger: '#A3262F', dangerLine: '#EBC4C4', dangerSoft: '#F8E3E1', now: '#C6363F', scrim: 'rgba(27, 36, 32, 0.42)' },
  sunset: { name: '暖陽', dark: false, bg: '#FFFBF7', panel: '#FBF3EC', card: '#FFFFFF', ink: '#23170F', muted: '#6B5A4E', faint: '#B0A196', line: '#F0E4D9', field: '#E0CFC0', seg: '#F4E8DC', segOn: '#FFFFFF', accent: '#B83C0A', accentSoft: '#FDE6D6', onAccent: '#FFFFFF', danger: '#A3262F', dangerLine: '#EBC4C4', dangerSoft: '#F8E3E1', now: '#C6363F', scrim: 'rgba(35, 23, 15, 0.42)' },
}

export const THEME_KEYS = Object.keys(THEMES) as ThemeKey[]

export interface CategoryColors {
  label: string
  // 圓點、勾選框
  color: string
  // 標籤、時間軸方塊的底色
  soft: string
  // 底色上的字
  ink: string
}

const LIGHT: Record<Category, CategoryColors> = {
  WORK: { label: '工作', color: '#2F5BEA', soft: '#E6ECFD', ink: '#1E3FA8' },
  PERSONAL: { label: '個人', color: '#D9661F', soft: '#FCEBDD', ink: '#8A3C0E' },
  HEALTH: { label: '健康', color: '#16865A', soft: '#DDF3E8', ink: '#0E5E3F' },
  SOCIAL: { label: '社交', color: '#7C4DDB', soft: '#EEE6FC', ink: '#5530A8' },
}

const DARK: Record<Category, CategoryColors> = {
  WORK: { label: '工作', color: '#6F8FFF', soft: '#1E2A4F', ink: '#B4C5FF' },
  PERSONAL: { label: '個人', color: '#F08A3E', soft: '#3A2614', ink: '#F7BE90' },
  HEALTH: { label: '健康', color: '#3BBF86', soft: '#13362A', ink: '#95E0BD' },
  SOCIAL: { label: '社交', color: '#A586F5', soft: '#2E2350', ink: '#D2BFFF' },
}

export function categoryColors(dark: boolean): Record<Category, CategoryColors> {
  return dark ? DARK : LIGHT
}

/** 主題 → 根元素上的 CSS 變數（--bg、--accent…）。color-scheme 讓原生的日期、時間選擇器也跟著深淺色。 */
export function toCssVars(theme: Theme): Record<string, string> {
  const vars: Record<string, string> = { 'color-scheme': theme.dark ? 'dark' : 'light' }
  for (const [key, value] of Object.entries(theme)) {
    if (typeof value === 'string' && key !== 'name') vars[`--${key}`] = value
  }
  return vars
}

const STORAGE_KEY = 'eat.calendar.theme'

/**
 * 記住使用者選的主題。只是這台瀏覽器的個人偏好，所以放 localStorage 就好，不必存到後端。
 * 私密視窗、被封鎖的網站資料都可能讓 localStorage 丟例外，一律吞掉、退回預設主題：選不到主題不該讓整頁壞掉。
 */
export function loadThemeKey(): ThemeKey {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && saved in THEMES) return saved as ThemeKey
  } catch {
    // 讀不到就用預設
  }
  return 'sky'
}

export function saveThemeKey(key: ThemeKey): void {
  try {
    localStorage.setItem(STORAGE_KEY, key)
  } catch {
    // 存不了就算了，這次的選擇在記憶體裡仍然有效
  }
}

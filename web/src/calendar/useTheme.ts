import { computed, inject, ref, type InjectionKey } from 'vue'
import type { Category } from '../calendar'
import { categoryColors, loadThemeKey, saveThemeKey, THEMES, type ThemeKey } from './theme'

export function useTheme() {
  const key = ref<ThemeKey>(loadThemeKey())
  const theme = computed(() => THEMES[key.value])
  const colors = computed(() => categoryColors(theme.value.dark))

  function choose(next: ThemeKey) {
    key.value = next
    saveThemeKey(next)
  }

  const chipStyle = (category: Category) => ({ background: colors.value[category].soft, color: colors.value[category].ink })

  return { key, theme, colors, choose, chipStyle }
}

export type ThemeState = ReturnType<typeof useTheme>

export const THEME: InjectionKey<ThemeState> = Symbol('theme')

export function useThemeState(): ThemeState {
  const state = inject(THEME)
  if (!state) throw new Error('行事曆元件必須放在 CalendarView 底下')
  return state
}

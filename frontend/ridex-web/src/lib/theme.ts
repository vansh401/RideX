import { useSyncExternalStore } from 'react'

/**
 * The colour theme: "light" (Ivory & Ink, the default) or "dark" (Midnight).
 *
 * The theme lives in the data-theme attribute of <html>. A tiny script in index.html sets it before the first paint (so there is no flash of the
 * wrong theme), ThemeToggle changes it, and the choice is remembered in localStorage. Components never read it to pick colours (the CSS variables do
 * that); only the few things that cannot use CSS variables do: the map's tiles, the toast style and the hero illustration.
 */
export type Theme = 'light' | 'dark'

const KEY = 'ridex-theme'
const EVENT = 'ridex-theme-change'

export function readTheme(): Theme {
  const current = document.documentElement.dataset.theme
  return current === 'dark' ? 'dark' : 'light'
}

export function applyTheme(theme: Theme, remember = true): void {
  const root = document.documentElement
  // Cross-fade the colours for a third of a second, then stop (the class would slow every later hover transition)
  root.classList.add('theme-fade')
  root.dataset.theme = theme
  window.setTimeout(() => root.classList.remove('theme-fade'), 400)
  if (remember) {
    try {
      localStorage.setItem(KEY, theme)
    } catch {
      // private window or blocked storage: the choice just is not remembered
    }
  }
  window.dispatchEvent(new Event(EVENT))
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener(EVENT, onChange)
  return () => window.removeEventListener(EVENT, onChange)
}

/** The current theme, re-rendering the component when it changes. */
export function useTheme(): { theme: Theme; setTheme: (theme: Theme) => void; toggle: () => void } {
  const theme = useSyncExternalStore(subscribe, readTheme, () => 'light' as Theme)
  return { theme, setTheme: (next) => applyTheme(next), toggle: () => applyTheme(theme === 'dark' ? 'light' : 'dark') }
}

import type { CSSProperties } from 'react'

// One look for every chart: gold bars, hairline grid, and a glass tooltip that matches the panels.
export const CHART = {
  accent: 'var(--chart-accent)',
  secondary: 'var(--chart-blue)',
  grid: 'var(--chart-grid)',
  axis: 'var(--chart-axis)',
} as const

export const tooltipStyle: CSSProperties = {
  background: 'var(--chart-tooltip)',
  border: '1px solid var(--border-strong)',
  borderRadius: 12,
  color: 'var(--text-primary)',
  boxShadow: 'var(--sh-float)',
}

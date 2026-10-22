import type { CSSProperties } from 'react'

// One look for every chart: gold bars, hairline grid, and a glass tooltip that matches the panels.
export const CHART = {
  accent: '#e9c84a',
  secondary: '#72aeff',
  grid: '#1d2127',
  axis: '#8e9096',
} as const

export const tooltipStyle: CSSProperties = {
  background: 'rgb(18 21 26 / 0.92)',
  border: '1px solid rgb(255 255 255 / 0.12)',
  borderRadius: 12,
  color: '#f6f6f3',
  boxShadow: '0 12px 32px rgb(0 0 0 / 0.5)',
}

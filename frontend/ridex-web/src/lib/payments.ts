import type { DailyCount, PaymentStatus } from '@/api/types'
import type { Tone } from '@/lib/rides'

export const PAYMENT_LABEL: Record<PaymentStatus, string> = {
  PENDING: 'Processing',
  SUCCEEDED: 'Paid',
  FAILED: 'Payment failed',
}

export const PAYMENT_TONE: Record<PaymentStatus, Tone> = {
  PENDING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
}

/** A payment that will not change by itself any more (a failed one changes only when the rider retries). */
export function isSettled(status: PaymentStatus): boolean {
  return status !== 'PENDING'
}

export interface DayRow {
  day: string
  requested: number
  completed: number
  cancelled: number
}

/**
 * The analytics API returns one row per (day, status). A chart wants one row per day. Days with no events are
 * simply absent from the result.
 */
export function dailyChartData(rows: DailyCount[]): DayRow[] {
  const byDay = new Map<string, DayRow>()
  for (const row of rows) {
    const entry = byDay.get(row.day) ?? { day: row.day, requested: 0, completed: 0, cancelled: 0 }
    if (row.status === 'SEARCHING_DRIVER') entry.requested += row.count
    if (row.status === 'RIDE_COMPLETED') entry.completed += row.count
    if (row.status === 'CANCELLED') entry.cancelled += row.count
    byDay.set(row.day, entry)
  }
  return [...byDay.values()].sort((a, b) => a.day.localeCompare(b.day))
}

/** Total messages still waiting across all consumer groups. */
export function totalLag(lags: { lag: number }[]): number {
  return lags.reduce((sum, entry) => sum + entry.lag, 0)
}

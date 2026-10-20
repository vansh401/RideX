import { describe, expect, it } from 'vitest'
import { dailyChartData, isSettled, totalLag } from './payments'

describe('isSettled', () => {
  it('treats only PENDING as still moving', () => {
    expect(isSettled('PENDING')).toBe(false)
    expect(isSettled('SUCCEEDED')).toBe(true)
    expect(isSettled('FAILED')).toBe(true)
  })
})

describe('dailyChartData', () => {
  it('turns one row per (day, status) into one row per day', () => {
    const rows = [
      { day: '2026-10-02', status: 'RIDE_COMPLETED', count: 3 },
      { day: '2026-10-01', status: 'SEARCHING_DRIVER', count: 5 },
      { day: '2026-10-01', status: 'RIDE_COMPLETED', count: 2 },
      { day: '2026-10-01', status: 'CANCELLED', count: 1 },
      { day: '2026-10-01', status: 'DRIVER_ARRIVED', count: 9 },
    ]

    expect(dailyChartData(rows)).toEqual([
      { day: '2026-10-01', requested: 5, completed: 2, cancelled: 1 },
      { day: '2026-10-02', requested: 0, completed: 3, cancelled: 0 },
    ])
  })

  it('is empty when there are no rows', () => {
    expect(dailyChartData([])).toEqual([])
  })
})

describe('totalLag', () => {
  it('adds the lag of every group', () => {
    expect(totalLag([{ lag: 2 }, { lag: 0 }, { lag: 5 }])).toBe(7)
    expect(totalLag([])).toBe(0)
  })
})

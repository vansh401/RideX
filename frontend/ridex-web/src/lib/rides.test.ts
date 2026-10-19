import { describe, expect, it } from 'vitest'
import type { RideStatus } from '@/api/types'
import { vehicleSchema } from '@/features/driver/vehicleSchema'
import {
  driverCanCancel,
  formatDistance,
  formatDuration,
  isActiveRide,
  progressIndex,
  riderCanCancel,
  secondsUntil,
} from './rides'

const ALL: RideStatus[] = [
  'REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED',
  'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'CANCELLED',
]

describe('ride status rules (mirror the backend state machine)', () => {
  it('treats completed and cancelled rides as finished', () => {
    expect(ALL.filter((s) => !isActiveRide(s))).toEqual(['RIDE_COMPLETED', 'CANCELLED'])
  })

  it('lets a rider cancel until the trip starts, never after', () => {
    expect(riderCanCancel('SEARCHING_DRIVER')).toBe(true)
    expect(riderCanCancel('DRIVER_ARRIVED')).toBe(true)
    expect(riderCanCancel('RIDE_STARTED')).toBe(false)
    expect(riderCanCancel('RIDE_COMPLETED')).toBe(false)
    expect(riderCanCancel('CANCELLED')).toBe(false)
  })

  it('lets a driver cancel only between accepting and starting', () => {
    expect(ALL.filter(driverCanCancel)).toEqual(['DRIVER_ACCEPTED', 'DRIVER_ARRIVED'])
  })

  it('maps every status to a progress step, and cancelled to none', () => {
    expect(progressIndex('REQUESTED')).toBe(0)
    expect(progressIndex('DRIVER_ASSIGNED')).toBe(0)
    expect(progressIndex('DRIVER_ACCEPTED')).toBe(1)
    expect(progressIndex('RIDE_STARTED')).toBe(3)
    expect(progressIndex('RIDE_COMPLETED')).toBe(4)
    expect(progressIndex('CANCELLED')).toBe(-1)
  })
})

describe('formatting', () => {
  it('counts seconds down and never goes negative', () => {
    const now = new Date('2026-01-01T10:00:00Z').getTime()
    expect(secondsUntil('2026-01-01T10:00:20Z', now)).toBe(20)
    expect(secondsUntil('2026-01-01T09:59:00Z', now)).toBe(0)
    expect(secondsUntil(null, now)).toBe(0)
  })

  it('formats distance and duration for people', () => {
    expect(formatDistance(4.26)).toBe('4.3 km')
    expect(formatDuration(25)).toBe('25 min')
    expect(formatDuration(60)).toBe('1 h')
    expect(formatDuration(95)).toBe('1 h 35 min')
  })
})

describe('vehicle form validation', () => {
  const valid = {
    category: 'CAB', make: 'Maruti', model: 'Dzire', color: 'White',
    registrationNumber: 'KA01AB1234', manufactureYear: '2021', seats: '4',
  }

  it('accepts a valid vehicle and converts the numbers', () => {
    const result = vehicleSchema.safeParse(valid)
    expect(result.success).toBe(true)
    if (result.success) expect(result.data.manufactureYear).toBe(2021)
  })

  it('accepts spaced and hyphenated registration numbers', () => {
    expect(vehicleSchema.safeParse({ ...valid, registrationNumber: 'KA 01 AB 1234' }).success).toBe(true)
    expect(vehicleSchema.safeParse({ ...valid, registrationNumber: 'KA-01-AB-1234' }).success).toBe(true)
  })

  it('rejects a malformed registration, an old year and too many seats', () => {
    expect(vehicleSchema.safeParse({ ...valid, registrationNumber: '1234' }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...valid, manufactureYear: '1999' }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...valid, seats: '9' }).success).toBe(false)
  })
})

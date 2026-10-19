import type { RideStatus, VehicleCategory } from '@/api/types'

// Everything the UI knows about ride statuses lives here, so labels, colours and the progress
// stepper can never disagree with each other.

export const STATUS_LABEL: Record<RideStatus, string> = {
  REQUESTED: 'Requested',
  SEARCHING_DRIVER: 'Finding a driver',
  DRIVER_ASSIGNED: 'Driver offered',
  DRIVER_ACCEPTED: 'Driver on the way',
  DRIVER_ARRIVED: 'Driver arrived',
  RIDE_STARTED: 'In trip',
  RIDE_COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
}

export type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'

export const STATUS_TONE: Record<RideStatus, Tone> = {
  REQUESTED: 'info',
  SEARCHING_DRIVER: 'warning',
  DRIVER_ASSIGNED: 'warning',
  DRIVER_ACCEPTED: 'accent',
  DRIVER_ARRIVED: 'accent',
  RIDE_STARTED: 'accent',
  RIDE_COMPLETED: 'success',
  CANCELLED: 'danger',
}

export const CATEGORY_LABEL: Record<VehicleCategory, string> = {
  BIKE: 'Bike',
  AUTO: 'Auto',
  CAB: 'Cab',
}

export const CATEGORY_SEATS: Record<VehicleCategory, number> = { BIKE: 1, AUTO: 3, CAB: 6 }

/** The steps a rider sees, in order. SEARCHING covers REQUESTED too. */
export const PROGRESS_STEPS = [
  { key: 'search', label: 'Finding a driver', statuses: ['REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED'] },
  { key: 'accepted', label: 'Driver on the way', statuses: ['DRIVER_ACCEPTED'] },
  { key: 'arrived', label: 'Driver arrived', statuses: ['DRIVER_ARRIVED'] },
  { key: 'trip', label: 'In trip', statuses: ['RIDE_STARTED'] },
  { key: 'done', label: 'Completed', statuses: ['RIDE_COMPLETED'] },
] as const satisfies readonly { key: string; label: string; statuses: readonly RideStatus[] }[]

/** Index of the current step (0-based), or -1 for a cancelled ride. */
export function progressIndex(status: RideStatus): number {
  if (status === 'CANCELLED') return -1
  return PROGRESS_STEPS.findIndex((step) => (step.statuses as readonly RideStatus[]).includes(status))
}

/** A ride that still occupies its rider (and maybe a driver). Mirrors the backend. */
export function isActiveRide(status: RideStatus): boolean {
  return status !== 'RIDE_COMPLETED' && status !== 'CANCELLED'
}

/** Rider may cancel until the trip starts (mirrors the backend state machine). */
export function riderCanCancel(status: RideStatus): boolean {
  return isActiveRide(status) && status !== 'RIDE_STARTED'
}

/** A driver may cancel only after accepting and before the trip starts. */
export function driverCanCancel(status: RideStatus): boolean {
  return status === 'DRIVER_ACCEPTED' || status === 'DRIVER_ARRIVED'
}

/** Seconds left until an offer expires (never negative). */
export function secondsUntil(iso: string | null, now: number = Date.now()): number {
  if (!iso) return 0
  return Math.max(0, Math.ceil((new Date(iso).getTime() - now) / 1000))
}

export function formatDistance(km: number): string {
  return `${km.toFixed(1)} km`
}

export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m === 0 ? `${h} h` : `${h} h ${m} min`
}

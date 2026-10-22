import type { ComplaintCategory, ComplaintStatus } from '@/api/types'
import type { Tone } from '@/lib/rides'

export const CATEGORY_OPTIONS: { value: ComplaintCategory; label: string }[] = [
  { value: 'OVERCHARGED', label: 'I was overcharged' },
  { value: 'RUDE_BEHAVIOUR', label: 'Rude behaviour' },
  { value: 'UNSAFE_DRIVING', label: 'Unsafe driving' },
  { value: 'ROUTE_ISSUE', label: 'Wrong or long route' },
  { value: 'VEHICLE_ISSUE', label: 'Vehicle problem' },
  { value: 'PAYMENT_ISSUE', label: 'Payment problem' },
  { value: 'LOST_ITEM', label: 'Lost item' },
  { value: 'OTHER', label: 'Something else' },
]

export const COMPLAINT_STATUS_LABEL: Record<ComplaintStatus, string> = {
  OPEN: 'Open',
  IN_REVIEW: 'In review',
  RESOLVED: 'Resolved',
  REJECTED: 'Rejected',
}

export const COMPLAINT_STATUS_TONE: Record<ComplaintStatus, Tone> = {
  OPEN: 'warning',
  IN_REVIEW: 'info',
  RESOLVED: 'success',
  REJECTED: 'neutral',
}

/** A reason code from the coupon preview, in words a rider understands. */
export function couponReason(reason: string): string {
  switch (reason) {
    case 'UNKNOWN':
      return 'We do not know this code.'
    case 'INACTIVE':
      return 'This offer is switched off.'
    case 'NOT_STARTED':
      return 'This offer has not started yet.'
    case 'EXPIRED':
      return 'This offer has expired.'
    case 'FARE_TOO_LOW':
      return 'The fare is below the minimum for this offer.'
    case 'SOLD_OUT':
      return 'This offer has been fully used.'
    case 'ALREADY_USED':
      return 'You have already used this offer the allowed number of times.'
    default:
      return 'This code cannot be used.'
  }
}

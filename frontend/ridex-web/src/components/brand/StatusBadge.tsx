import type { ApprovalStatus, RideStatus } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { STATUS_LABEL, STATUS_TONE, type Tone } from '@/lib/rides'

export function RideStatusBadge({ status }: { status: RideStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
}

const APPROVAL: Record<ApprovalStatus, { label: string; tone: Tone }> = {
  PENDING_APPROVAL: { label: 'Pending approval', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
}

export function ApprovalBadge({ status }: { status: ApprovalStatus }) {
  const { label, tone } = APPROVAL[status]
  return <Badge tone={tone}>{label}</Badge>
}

const ACCOUNT: Record<string, { label: string; tone: Tone }> = {
  ACTIVE: { label: 'Active', tone: 'success' },
  PENDING_VERIFICATION: { label: 'Unverified', tone: 'warning' },
  SUSPENDED: { label: 'Suspended', tone: 'danger' },
}

export function AccountBadge({ status }: { status: string }) {
  const { label, tone } = ACCOUNT[status] ?? { label: status, tone: 'neutral' as Tone }
  return <Badge tone={tone}>{label}</Badge>
}

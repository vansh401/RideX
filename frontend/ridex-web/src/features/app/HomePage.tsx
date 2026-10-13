import { BadgeCheck, CalendarDays, ClipboardCheck, MapPinned, ShieldCheck, UserRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { Role } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { useProfile } from './useProfile'

const COPY: Record<Role, { eyebrow: string; title: string; next: { icon: LucideIcon; title: string; text: string } }> = {
  RIDER: {
    eyebrow: 'Rider',
    title: 'Your RideX home',
    next: {
      icon: MapPinned,
      title: 'Booking is on its way',
      text: 'Choosing a destination, seeing fares and requesting a ride arrive with the ride system (step 3.7).',
    },
  },
  DRIVER: {
    eyebrow: 'Driver',
    title: 'Driver dashboard',
    next: {
      icon: ClipboardCheck,
      title: 'Onboarding comes next',
      text: 'Adding your licence and vehicle, and waiting for approval, arrive in steps 3.3 and 3.4.',
    },
  },
  ADMIN: {
    eyebrow: 'Admin',
    title: 'Admin console',
    next: {
      icon: BadgeCheck,
      title: 'Management tools are being built',
      text: 'Driver approvals, users and ride oversight arrive in steps 3.3 and 3.11.',
    },
  },
}

const STATUS_LABEL = {
  ACTIVE: 'Active',
  PENDING_VERIFICATION: 'Pending verification',
  SUSPENDED: 'Suspended',
} as const

/** First screen after login. Shows real data from /api/users/me; nothing here is sample data. */
export function HomePage({ role }: { role: Role }) {
  const { profile, error, loading } = useProfile()
  const copy = COPY[role]

  return (
    <>
      <PageHeader eyebrow={copy.eyebrow} title={copy.title} />

      {error && (
        <Alert kind="error" className="mb-6">
          {error}
        </Alert>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        {loading || !profile ? (
          <>
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
          </>
        ) : (
          <>
            <StatCard icon={ShieldCheck} label="Account status" value={STATUS_LABEL[profile.status]} />
            <StatCard icon={UserRound} label="Signed in as" value={profile.fullName} />
            <StatCard
              icon={CalendarDays}
              label="Member since"
              value={formatDateTime(profile.createdAt).split(',').slice(0, 2).join(',')}
            />
          </>
        )}
      </div>

      <div className="mt-8">
        <EmptyState icon={copy.next.icon} title={copy.next.title} description={copy.next.text} />
      </div>
    </>
  )
}

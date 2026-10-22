import { History } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Ride, RideStatus } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { RideStatusBadge } from '@/components/brand/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { SelectField } from '@/components/ui/select'
import { formatDateTime, formatINR } from '@/lib/format'
import { CATEGORY_LABEL, STATUS_LABEL } from '@/lib/rides'
import { usePagedList } from '@/lib/usePagedList'

const FILTERS: RideStatus[] = ['RIDE_COMPLETED', 'CANCELLED', 'RIDE_STARTED', 'DRIVER_ACCEPTED']

/**
 * A paged list of rides. `path` is /api/rides for riders and drivers (their own, newest first)
 * and /api/admin/rides for admins (everyone's).
 */
export function RideHistoryPage({ path = '/api/rides', eyebrow, title }: { path?: string; eyebrow: string; title: string }) {
  const [status, setStatus] = useState('')
  const { data, error, loading, setPage } = usePagedList<Ride>(path, { status }, 10)
  const admin = path.startsWith('/api/admin')

  return (
    <>
      <PageHeader eyebrow={eyebrow} title={title} />
      <div className="mb-4 max-w-xs">
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All</option>
          {FILTERS.map((s) => (
            <option key={s} value={s}>{STATUS_LABEL[s]}</option>
          ))}
        </SelectField>
      </div>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-64" />}

      {data && data.content.length === 0 && (
        <EmptyState icon={History} title="No rides yet" description="Rides will appear here once there are some." />
      )}

      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((ride) => (
              <li key={ride.id}>
                <Link
                  to={`/rides/${ride.id}`}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-lg panel border border-border p-4 transition-colors hover:border-fg-muted"
                >
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium text-fg">{ride.pickup.address} → {ride.dropoff.address}</p>
                    <p className="tabular text-sm text-fg-muted">
                      {formatDateTime(ride.requestedAt)} · {CATEGORY_LABEL[ride.category]}
                      {admin && ` · ${ride.rider.fullName}${ride.driver ? ` / ${ride.driver.fullName}` : ''}`}
                    </p>
                  </div>
                  <RideStatusBadge status={ride.status} />
                  <p className="tabular w-24 text-right text-lg font-bold text-fg">{formatINR(ride.fare.total)}</p>
                </Link>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

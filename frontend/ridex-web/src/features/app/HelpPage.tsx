import { LifeBuoy } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { Complaint } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { CATEGORY_OPTIONS, COMPLAINT_STATUS_LABEL, COMPLAINT_STATUS_TONE } from '@/lib/complaints'
import { formatDateTime } from '@/lib/format'
import { usePagedList } from '@/lib/usePagedList'

/** Everything the person has reported, with the answer. New reports are made from the ride itself ("Help with this trip"). */
export function HelpPage() {
  const { data, error, loading, setPage } = usePagedList<Complaint>('/api/complaints', {}, 10)

  return (
    <>
      <PageHeader
        eyebrow="Help"
        title="Your reports"
        description="Problems you reported about a trip, and how we answered. To report something new, open the trip and use “Help with this trip”."
      />
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-40" />}
      {data?.content.length === 0 && <EmptyState icon={LifeBuoy} title="Nothing reported" description="If a trip goes wrong, open it from your ride history and tell us." />}
      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((c) => (
              <li key={c.id}>
                <Link to={`/rides/${c.rideId}`} className="panel flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border p-4 hover:border-fg-muted">
                  <span className="min-w-0">
                    <span className="block font-semibold text-fg">{CATEGORY_OPTIONS.find((o) => o.value === c.category)?.label}</span>
                    <span className="block truncate text-sm text-fg-secondary">{c.description}</span>
                    <span className="text-xs text-fg-muted">
                      {formatDateTime(c.createdAt)}
                      {c.resolutionNote ? ` · ${c.resolutionNote}` : ''}
                    </span>
                  </span>
                  <Badge tone={COMPLAINT_STATUS_TONE[c.status]}>{COMPLAINT_STATUS_LABEL[c.status]}</Badge>
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

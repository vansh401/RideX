import { ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { FraudSignal } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { usePagedList } from '@/lib/usePagedList'
import { usePolling } from '@/lib/usePolling'

interface RuleSummary { rule: string; total: number; open: number; confirmed: number; dismissed: number }

/** Signals raised by simple rules. A signal is a reason to look, not a verdict: a person confirms or dismisses it. */
export function AdminFraudPage() {
  const [status, setStatus] = useState('NEW')
  const { data, error, loading, setPage, reload } = usePagedList<FraudSignal>('/api/admin/fraud/signals', { status }, 10)
  const summary = usePolling(async () => (await api.get<RuleSummary[]>('/api/admin/fraud/summary')).data, { intervalMs: 60000 })

  async function review(id: number, decision: 'CONFIRMED' | 'DISMISSED') {
    try {
      await api.post(`/api/admin/fraud/signals/${id}/review`, { status: decision })
      reload()
      void summary.refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Admin" title="Fraud signals" description="Unusual patterns worth a human look. Nothing here blocks anyone automatically." />
      {summary.data && summary.data.length > 0 && (
        <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {summary.data.map((r) => (
            <div key={r.rule} className="rounded-lg panel border border-border p-4 text-sm">
              <p className="font-semibold text-fg">{r.rule}</p>
              <p className="text-fg-muted">{r.total} raised · {r.open} open · {r.confirmed} confirmed · {r.dismissed} dismissed</p>
            </div>
          ))}
        </div>
      )}
      <div className="mb-4 max-w-xs">
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="NEW">Needs review</option>
          <option value="CONFIRMED">Confirmed</option>
          <option value="DISMISSED">Dismissed</option>
          <option value="">All</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && <EmptyState icon={ShieldAlert} title="Nothing to review" description="No signals match this filter." />}
      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg panel border border-border p-4">
                <div className="min-w-0 text-sm">
                  <p className="flex items-center gap-2 font-semibold text-fg">
                    {s.rule} <Badge tone={s.severity >= 3 ? 'danger' : 'warning'}>severity {s.severity}</Badge>
                  </p>
                  <p className="text-fg-secondary">{s.details}</p>
                  <p className="text-xs text-fg-muted">{s.subjectType} {s.subjectId} · {formatDateTime(s.createdAt)}</p>
                </div>
                {s.status === 'NEW' ? (
                  <div className="flex gap-2">
                    <Button size="sm" variant="secondary" onClick={() => review(s.id, 'DISMISSED')}>Dismiss</Button>
                    <Button size="sm" onClick={() => review(s.id, 'CONFIRMED')}>Confirm</Button>
                  </div>
                ) : (
                  <Badge tone={s.status === 'CONFIRMED' ? 'danger' : 'neutral'}>{s.status.toLowerCase()}</Badge>
                )}
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

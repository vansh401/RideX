import { LifeBuoy } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Complaint, ComplaintStatus } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { CATEGORY_OPTIONS, COMPLAINT_STATUS_LABEL, COMPLAINT_STATUS_TONE } from '@/lib/complaints'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePagedList } from '@/lib/usePagedList'

function Row({ c, onChanged }: { c: Complaint; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [internal, setInternal] = useState(false)
  const [note, setNote] = useState('')
  const [refund, setRefund] = useState('')
  const [busy, setBusy] = useState(false)
  const final = c.status === 'RESOLVED' || c.status === 'REJECTED'

  async function run(path: string, body?: unknown, ok?: string) {
    setBusy(true)
    try {
      await api.post(`/api/admin/complaints/${c.id}/${path}`, body)
      if (ok) toast.success(ok)
      setMessage('')
      onChanged()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <button type="button" className="flex flex-wrap items-center justify-between gap-2 text-left" onClick={() => setOpen(!open)} aria-expanded={open}>
          <span>
            <span className="block font-semibold text-fg">{CATEGORY_OPTIONS.find((o) => o.value === c.category)?.label}</span>
            <span className="text-sm text-fg-muted">{c.filedByName ?? c.filedByRole} ({c.filedByRole.toLowerCase()}) · {formatDateTime(c.createdAt)}</span>
          </span>
          <Badge tone={COMPLAINT_STATUS_TONE[c.status]}>{COMPLAINT_STATUS_LABEL[c.status]}</Badge>
        </button>
        {open && (
          <div className="flex flex-col gap-3 text-sm">
            <p className="text-fg-secondary">{c.description}</p>
            {c.ride && (
              <p className="text-fg-muted">
                <Link className="text-accent hover:underline" to={`/rides/${c.rideId}`}>Ride</Link>: {c.ride.pickup} → {c.ride.dropoff} · fare {formatINR(c.ride.fare)}
              </p>
            )}
            {c.comments.map((m) => (
              <p key={m.id} className={`rounded px-2 py-1 ${m.internal ? 'bg-warning/10' : 'bg-elevated'}`}>
                <span className="font-medium text-fg">{m.authorRole}{m.internal ? ' (internal)' : ''}:</span> {m.message}
              </p>
            ))}
            {c.resolutionNote && <Alert kind="info">{c.resolutionNote}{c.refundAmount != null ? ` · refund ${formatINR(c.refundAmount)}` : ''}</Alert>}
            {!final && (
              <>
                <div className="flex flex-wrap gap-2">
                  {c.status === 'OPEN' && <Button size="sm" variant="secondary" loading={busy} onClick={() => run('assign', undefined, 'Assigned to you')}>Take it</Button>}
                </div>
                <div className="flex flex-col gap-2">
                  <TextField label="Message" value={message} maxLength={1000} onChange={(e) => setMessage(e.target.value)} />
                  <label className="flex items-center gap-2 text-fg">
                    <input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} /> Internal note (the person does not see it)
                  </label>
                  <Button size="sm" variant="secondary" disabled={!message.trim()} loading={busy} onClick={() => run('messages', { message, internal }, 'Sent')}>Send</Button>
                </div>
                <div className="grid gap-2 border-t border-border pt-3 sm:grid-cols-2">
                  <TextField label="Decision note" value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />
                  <TextField label="Refund (₹, optional)" type="number" min={0.01} step="0.01" value={refund} onChange={(e) => setRefund(e.target.value)} hint="Never more than was paid" />
                  <Button size="sm" disabled={!note.trim()} loading={busy} onClick={() => run('resolve', { note, refundAmount: refund ? Number(refund) : null }, 'Resolved')}>Resolve</Button>
                  <Button size="sm" variant="danger" disabled={!note.trim()} loading={busy} onClick={() => run('reject', { note }, 'Rejected')}>Reject</Button>
                </div>
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

export function AdminComplaintsPage() {
  const [status, setStatus] = useState<ComplaintStatus | ''>('')
  const { data, error, loading, setPage, reload } = usePagedList<Complaint>('/api/admin/complaints', { status }, 10)

  return (
    <>
      <PageHeader eyebrow="Admin" title="Complaints" description="Review, answer and settle what riders and drivers report." />
      <div className="mb-4 max-w-xs">
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value as ComplaintStatus | '')}>
          <option value="">All</option>
          {(Object.keys(COMPLAINT_STATUS_LABEL) as ComplaintStatus[]).map((s) => (
            <option key={s} value={s}>{COMPLAINT_STATUS_LABEL[s]}</option>
          ))}
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && <EmptyState icon={LifeBuoy} title="No complaints" description="Nothing to review here." />}
      {data && data.content.length > 0 && (
        <>
          <div className="flex flex-col gap-3">
            {data.content.map((c) => <Row key={c.id} c={c} onChanged={reload} />)}
          </div>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

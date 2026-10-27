import { ShieldAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { SosAlert } from '@/api/types'
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
import { formatDateTime } from '@/lib/format'
import type { Tone } from '@/lib/rides'
import { usePagedList } from '@/lib/usePagedList'

const TONE: Record<SosAlert['status'], Tone> = { OPEN: 'danger', ACKNOWLEDGED: 'warning', RESOLVED: 'success' }
const LABEL: Record<SosAlert['status'], string> = { OPEN: 'Open', ACKNOWLEDGED: 'Being handled', RESOLVED: 'Resolved' }

function AlertCard({ alert, onChanged }: { alert: SosAlert; onChanged: () => void }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  async function decide(action: 'acknowledge' | 'resolve') {
    setBusy(true)
    try {
      await api.post(`/api/admin/sos/${alert.id}/${action}`, note.trim() ? { note: note.trim() } : {})
      toast.success(action === 'resolve' ? 'Marked as resolved' : 'Acknowledged')
      setNote('')
      onChanged()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className={alert.status === 'OPEN' ? 'ring-2 ring-danger/60' : undefined}>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-semibold text-fg">
            {alert.raisedByName} <span className="font-normal text-fg-muted">({alert.raisedByRole.toLowerCase()})</span>
          </p>
          <Badge tone={TONE[alert.status]}>{LABEL[alert.status]}</Badge>
        </div>
        <p className="text-sm text-fg-secondary">
          {alert.pickupAddress} → {alert.dropoffAddress}
        </p>
        <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
          <span className="text-fg-muted">{formatDateTime(alert.createdAt)}</span>
          {alert.raisedByPhone && (
            <a className="text-accent hover:underline" href={`tel:${alert.raisedByPhone}`}>
              Call {alert.raisedByPhone}
            </a>
          )}
          {alert.latitude != null && alert.longitude != null && (
            <a className="text-accent hover:underline" href={`https://www.openstreetmap.org/?mlat=${alert.latitude}&mlon=${alert.longitude}#map=16/${alert.latitude}/${alert.longitude}`} target="_blank" rel="noreferrer">
              Open location
            </a>
          )}
          <a className="text-accent hover:underline" href={`/rides/${alert.rideId}`}>
            Open the ride
          </a>
        </p>
        {alert.note && <p className="rounded bg-fg/[0.05] px-3 py-2 text-sm text-fg-secondary">Note: {alert.note}</p>}
        {alert.status !== 'RESOLVED' && (
          <div className="flex flex-wrap items-end gap-2">
            <div className="min-w-48 flex-1">
              <TextField label="Note (optional)" value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
            </div>
            {alert.status === 'OPEN' && (
              <Button variant="secondary" loading={busy} onClick={() => decide('acknowledge')}>
                Acknowledge
              </Button>
            )}
            <Button loading={busy} onClick={() => decide('resolve')}>
              Resolve
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** SOS alerts from riders and drivers, newest open ones first. Refreshes every 10 seconds, because an emergency should not wait for a page reload. */
export function AdminSafetyPage() {
  const [status, setStatus] = useState('')
  const { data, error, loading, setPage, reload } = usePagedList<SosAlert>('/api/admin/sos', { status }, 10)

  useEffect(() => {
    const timer = window.setInterval(reload, 10_000)
    return () => window.clearInterval(timer)
  }, [reload])

  return (
    <>
      <PageHeader eyebrow="Admin" title="Safety" description="SOS alerts from trips. Call the person, check the location, and record what you did." />
      <div className="mb-4 max-w-xs">
        <SelectField label="Show" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All alerts</option>
          <option value="OPEN">Open</option>
          <option value="ACKNOWLEDGED">Being handled</option>
          <option value="RESOLVED">Resolved</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && <EmptyState icon={ShieldAlert} title="No alerts" description="When a rider or driver presses SOS on a trip, it appears here and is emailed to administrators." />}
      {data && data.content.length > 0 && (
        <>
          <div className="flex flex-col gap-4">
            {data.content.map((a) => (
              <AlertCard key={a.id} alert={a} onChanged={reload} />
            ))}
          </div>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

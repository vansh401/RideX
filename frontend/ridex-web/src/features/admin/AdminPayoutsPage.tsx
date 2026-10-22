import { Banknote, Copy } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Payout, PayoutStatus } from '@/api/types'
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
import { formatDateTime, formatINR } from '@/lib/format'
import type { Tone } from '@/lib/rides'
import { usePagedList } from '@/lib/usePagedList'

const TONE: Record<PayoutStatus, Tone> = { REQUESTED: 'warning', PAID: 'success', REJECTED: 'danger' }
const LABEL: Record<PayoutStatus, string> = { REQUESTED: 'Waiting', PAID: 'Paid', REJECTED: 'Returned' }

function PayoutRow({ payout, onChanged }: { payout: Payout; onChanged: () => void }) {
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  async function run(action: 'paid' | 'reject') {
    setBusy(true)
    try {
      await api.post(`/api/admin/payouts/${payout.id}/${action}`, action === 'paid' ? { reference } : { note })
      toast.success(action === 'paid' ? 'Recorded as paid' : 'Returned to the driver')
      onChanged()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(payout.upiId)
      toast.success('UPI id copied')
    } catch {
      toast.error('Could not copy')
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="tabular text-lg font-bold text-fg">{formatINR(payout.amount)}</p>
          <Badge tone={TONE[payout.status]}>{LABEL[payout.status]}</Badge>
        </div>
        <p className="flex flex-wrap items-center gap-x-3 text-sm text-fg-secondary">
          <button type="button" onClick={copy} className="inline-flex items-center gap-1 font-medium text-accent hover:underline" aria-label={`Copy UPI id ${payout.upiId}`}>
            {payout.upiId} <Copy className="size-3.5" aria-hidden />
          </button>
          <span className="text-fg-muted">Requested {formatDateTime(payout.requestedAt)}</span>
          <span className="font-mono text-xs text-fg-muted">driver {payout.driverUserId.slice(0, 8)}</span>
        </p>
        {payout.reference && <p className="text-xs text-fg-muted">Transfer reference {payout.reference}</p>}
        {payout.note && <p className="text-xs text-fg-muted">{payout.note}</p>}
        {payout.status === 'REQUESTED' && (
          <div className="grid gap-3 border-t border-border pt-3 sm:grid-cols-2">
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <TextField label="Transfer reference (UTR)" value={reference} maxLength={80} onChange={(e) => setReference(e.target.value)} hint="After you sent the money" />
              </div>
              <Button loading={busy} disabled={!reference.trim()} onClick={() => run('paid')}>
                Mark paid
              </Button>
            </div>
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <TextField label="Reason to return it" value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} />
              </div>
              <Button variant="danger" loading={busy} disabled={!note.trim()} onClick={() => run('reject')}>
                Return
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** Driver withdrawals. You make the transfer outside RideX (UPI or bank), then record its reference here; the money bookkeeping follows. */
export function AdminPayoutsPage() {
  const [status, setStatus] = useState('REQUESTED')
  const { data, error, loading, setPage, reload } = usePagedList<Payout>('/api/admin/payouts', { status }, 10)

  return (
    <>
      <PageHeader eyebrow="Admin" title="Driver payouts" description="Send the money by UPI or bank transfer, then record the reference. Returning a request gives the money back to the driver." />
      <div className="mb-4 max-w-xs">
        <SelectField label="Show" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="REQUESTED">Waiting for a transfer</option>
          <option value="PAID">Paid</option>
          <option value="REJECTED">Returned</option>
          <option value="">All</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-40" />}
      {data?.content.length === 0 && <EmptyState icon={Banknote} title="No withdrawals" description="Requests from drivers appear here." />}
      {data && data.content.length > 0 && (
        <>
          <div className="flex flex-col gap-4">
            {data.content.map((p) => (
              <PayoutRow key={p.id} payout={p} onChanged={reload} />
            ))}
          </div>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

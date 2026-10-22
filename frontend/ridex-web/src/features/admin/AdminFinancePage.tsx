import { CheckCircle2, Flame, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { LedgerIntegrity, RefundRecord, SurgeZone } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

const NAMES: Record<string, string> = {
  GATEWAY: 'Outside world (cards, banks)',
  REVENUE: 'Platform revenue',
  PROMO: 'Offers budget',
  INCENTIVES: 'Driver bonuses',
  PAYOUT_HOLD: 'Withdrawals on hold',
  ADJUSTMENT: 'Manual adjustments',
  USER_WALLETS_TOTAL: 'All rider wallets',
  DRIVER_EARNINGS_TOTAL: 'All driver earnings',
}

function Section({ title, description, children }: { title: string; description?: string; children: React.ReactNode }) {
  return (
    <Card className="min-w-0">
      <CardContent className="flex flex-col gap-3 p-5">
        <div>
          <p className="font-semibold text-fg">{title}</p>
          {description && <p className="text-sm text-fg-muted">{description}</p>}
        </div>
        {children}
      </CardContent>
    </Card>
  )
}

function IntegrityCheck() {
  const [result, setResult] = useState<LedgerIntegrity | null>(null)
  const [busy, setBusy] = useState(false)

  async function run() {
    setBusy(true)
    try {
      setResult((await api.get<LedgerIntegrity>('/api/admin/ledger/integrity')).data)
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Ledger integrity" description="Re-adds every entry and checks that every balance matches its entries and that all money adds up to zero.">
      <div>
        <Button variant="secondary" loading={busy} onClick={run}>
          <ShieldCheck className="size-4" aria-hidden /> Run the check
        </Button>
      </div>
      {result && (
        <Alert kind={result.ok ? 'success' : 'error'}>
          {result.ok ? (
            <span className="flex items-center gap-2">
              <CheckCircle2 className="size-4" aria-hidden /> The books balance: the sum of all accounts is {formatINR(result.sumOfAllBalances)}.
            </span>
          ) : (
            <>Problem found. Sum {formatINR(result.sumOfAllBalances)}. Accounts out of step: {result.accountsOutOfStep.join(', ') || 'none'}.</>
          )}
        </Alert>
      )}
    </Section>
  )
}

function Adjustment({ onDone }: { onDone: () => void }) {
  const [userId, setUserId] = useState('')
  const [kind, setKind] = useState('WALLET')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const valid = /^[0-9a-f-]{36}$/i.test(userId.trim()) && Number(amount) !== 0 && reason.trim().length >= 3

  async function submit() {
    setBusy(true)
    try {
      await api.post('/api/admin/ledger/adjustments', { userId: userId.trim(), kind, amount: Number(amount), reason: reason.trim() })
      toast.success('Adjustment recorded')
      setAmount('')
      setReason('')
      onDone()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Manual adjustment" description="A goodwill credit or a correction. Positive adds money, negative takes it. It is recorded in the ledger with your name and can never make a balance negative.">
      <TextField label="User id" value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="the user's id (UUID)" autoComplete="off" />
      <div className="grid grid-cols-2 gap-3">
        <SelectField label="Account" value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="WALLET">Rider wallet</option>
          <option value="EARNINGS">Driver earnings</option>
        </SelectField>
        <TextField label="Amount (₹)" type="number" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} hint="Negative to take money" />
      </div>
      <TextField label="Reason" value={reason} maxLength={150} onChange={(e) => setReason(e.target.value)} />
      <div>
        <Button loading={busy} disabled={!valid} onClick={submit}>
          Record adjustment
        </Button>
      </div>
    </Section>
  )
}

function RefundForm({ onDone }: { onDone: () => void }) {
  const [rideId, setRideId] = useState('')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)
  const valid = /^[0-9a-f-]{36}$/i.test(rideId.trim()) && reason.trim().length >= 3

  async function submit() {
    setBusy(true)
    try {
      // One key per attempt: pressing the button twice (or a retried request) refunds once
      await api.post('/api/admin/refunds', { rideId: rideId.trim(), amount: amount ? Number(amount) : null, reason: reason.trim(), destination: 'WALLET' }, { headers: { 'Idempotency-Key': key } })
      toast.success('Refund sent to the rider wallet')
      setKey(crypto.randomUUID())
      setRideId('')
      setAmount('')
      setReason('')
      onDone()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Refund a ride" description="Returns money to the rider's wallet. Leave the amount empty to refund everything that is still refundable. Never more than was paid.">
      <TextField label="Ride id" value={rideId} onChange={(e) => setRideId(e.target.value)} placeholder="the ride's id (UUID)" autoComplete="off" />
      <TextField label="Amount (₹, optional)" type="number" step="0.01" min={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} />
      <TextField label="Reason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
      <div>
        <Button loading={busy} disabled={!valid} onClick={submit}>
          Refund
        </Button>
      </div>
    </Section>
  )
}

function IncentiveRun() {
  const [day, setDay] = useState(() => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10))
  const [busy, setBusy] = useState(false)

  async function run() {
    setBusy(true)
    try {
      const { data } = await api.post<{ driversPaid: number; totalPaid: number }>(`/api/admin/incentives/run?day=${day}`)
      toast.success(`${data.driversPaid} drivers paid ${formatINR(data.totalPaid)} for ${day}`)
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Driver bonuses" description="Bonuses are paid automatically after midnight. Run a day by hand if it was missed. Running a day twice pays nobody twice.">
      <div className="flex items-end gap-3">
        <TextField label="Day" type="date" value={day} onChange={(e) => setDay(e.target.value)} />
        <Button variant="secondary" loading={busy} onClick={run}>
          Pay that day
        </Button>
      </div>
    </Section>
  )
}

/** Money and pricing controls for administrators: where the money is, proof the books balance, refunds, corrections, bonuses, and where surge pricing is active. */
export function AdminFinancePage() {
  const summary = usePolling(async () => (await api.get<Record<string, number>>('/api/admin/ledger/summary')).data, { intervalMs: 30000 })
  const refunds = usePolling(async () => (await api.get<RefundRecord[]>('/api/admin/refunds')).data, { intervalMs: 30000 })
  const surge = usePolling(async () => (await api.get<SurgeZone[]>('/api/admin/surge-zones')).data, { intervalMs: 30000 })

  function reload() {
    void summary.refresh()
    void refunds.refresh()
  }

  return (
    <>
      <PageHeader eyebrow="Admin" title="Finance" description="Where the money is and the tools to correct it. Every change here is a recorded ledger entry." />
      {summary.error && <Alert kind="error" className="mb-4">{summary.error}</Alert>}
      {!summary.data ? (
        <Skeleton className="h-32" />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {Object.entries(summary.data).map(([key, value]) => (
            <Card key={key} className="min-w-0">
              <CardContent className="p-4">
                <p className="text-xs text-fg-muted">{NAMES[key] ?? key}</p>
                <p className="tabular mt-1 text-xl font-bold text-fg">{formatINR(value)}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <IntegrityCheck />
        <IncentiveRun />
        <Adjustment onDone={reload} />
        <RefundForm onDone={reload} />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Section title="Recent refunds">
          {refunds.data?.length === 0 && <p className="text-sm text-fg-muted">No refunds yet.</p>}
          <ul className="flex flex-col gap-2">
            {refunds.data?.slice(0, 8).map((r) => (
              <li key={r.id} className="flex items-center justify-between gap-3 rounded-md bg-white/[0.04] px-3 py-2 text-sm">
                <span className="min-w-0 text-fg-secondary">
                  <span className="tabular font-semibold text-fg">{formatINR(r.amount)}</span> · {r.source.toLowerCase()} · {formatDateTime(r.createdAt)}
                  {r.failureReason && <span className="block truncate text-xs text-danger">{r.failureReason}</span>}
                </span>
                <Badge tone={r.status === 'DONE' || r.status === 'SUCCEEDED' ? 'success' : r.status === 'FAILED' ? 'danger' : 'warning'}>{r.status.toLowerCase()}</Badge>
              </li>
            ))}
          </ul>
        </Section>

        <Section title="Surge pricing now" description="Zones where demand is above supply and fares are higher.">
          {surge.data?.length === 0 && <p className="text-sm text-fg-muted">No surge anywhere: prices are normal.</p>}
          <ul className="flex flex-col gap-2">
            {surge.data?.map((z) => (
              <li key={z.zone} className="flex items-center justify-between gap-3 rounded-md bg-white/[0.04] px-3 py-2 text-sm">
                <span className="flex items-center gap-2 text-fg-secondary">
                  <Flame className="size-4 text-accent" aria-hidden /> Zone {z.zone} ({z.latitude.toFixed(3)}, {z.longitude.toFixed(3)})
                </span>
                <Badge tone="warning">{z.multiplier.toFixed(2)}x</Badge>
              </li>
            ))}
          </ul>
        </Section>
      </div>
    </>
  )
}

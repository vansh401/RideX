import { Banknote } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Payout, PayoutStatus } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { TextField } from '@/components/ui/text-field'
import { formatDateTime, formatINR } from '@/lib/format'
import type { Tone } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

const TONE: Record<PayoutStatus, Tone> = { REQUESTED: 'warning', PAID: 'success', REJECTED: 'danger' }
const LABEL: Record<PayoutStatus, string> = { REQUESTED: 'Waiting', PAID: 'Paid', REJECTED: 'Returned' }

/**
 * Withdraw earnings to a UPI id. The money is set aside the moment you ask (it leaves your balance), an administrator makes the
 * transfer and records its reference, and a rejected request returns the money to your balance.
 */
export function PayoutsSection({ balance, onChanged }: { balance: number; onChanged: () => void }) {
  const { data, refresh } = usePolling(async () => (await api.get<Payout[]>('/api/driver/payouts')).data, { intervalMs: 30000 })
  const [amount, setAmount] = useState('')
  const [upi, setUpi] = useState('')
  const [busy, setBusy] = useState(false)

  const value = Number(amount)
  const valid = value >= 100 && value <= balance && upi.includes('@')

  async function request() {
    setBusy(true)
    try {
      await api.post('/api/driver/payouts', { amount: value, upiId: upi })
      toast.success('Withdrawal requested. We will transfer it and record the reference here.')
      setAmount('')
      await refresh()
      onChanged()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-4">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <Banknote className="size-4 text-accent" aria-hidden /> Withdraw earnings
        </p>
        <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <TextField label="Amount (₹)" type="number" min={100} max={balance} value={amount} onChange={(e) => setAmount(e.target.value)} hint={`At least ₹100, up to ${formatINR(balance)}`} />
          <TextField label="UPI id" value={upi} maxLength={80} onChange={(e) => setUpi(e.target.value)} placeholder="name@bank" autoComplete="off" />
          <Button loading={busy} disabled={!valid} onClick={request}>
            Request
          </Button>
        </div>
        {data && data.length > 0 && (
          <ul className="flex flex-col gap-2">
            {data.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-white/[0.04] px-3 py-2.5 text-sm">
                <span className="text-fg-secondary">
                  <span className="tabular font-semibold text-fg">{formatINR(p.amount)}</span> to {p.upiId} · {formatDateTime(p.requestedAt)}
                  {p.reference && <span className="block text-xs text-fg-muted">Reference {p.reference}</span>}
                  {p.note && <span className="block text-xs text-fg-muted">{p.note}</span>}
                </span>
                <Badge tone={TONE[p.status]}>{LABEL[p.status]}</Badge>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

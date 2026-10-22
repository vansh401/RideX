import { ArrowDownLeft, ArrowUpRight, Copy, Gift, Wallet as WalletIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { ReferralSummary, Wallet, WalletEntry } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { StatCard } from '@/components/brand/StatCard'
import { useCheckoutReturn, useOnlinePayment } from '@/components/payments/useOnlinePayment'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePagedList } from '@/lib/usePagedList'
import { usePolling } from '@/lib/usePolling'

const QUICK = [100, 250, 500, 1000]

/** "Invite a friend": your code, and a box for a friend's code (only until you have paid for a ride). */
function ReferralCard() {
  const { data, refresh } = usePolling(async () => (await api.get<ReferralSummary>('/api/referrals/me')).data, { intervalMs: 60000 })
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)

  async function copy() {
    if (!data) return
    try {
      await navigator.clipboard.writeText(data.code)
      toast.success('Invite code copied')
    } catch {
      toast.error('Could not copy. Select the code and copy it by hand.')
    }
  }

  async function apply() {
    setBusy(true)
    try {
      await api.post('/api/referrals/apply', { code })
      toast.success('Invite code added. You both get a reward after your first paid ride.')
      setCode('')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  if (!data) return <Skeleton className="h-40" />
  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <Gift className="size-4 text-accent" aria-hidden /> Invite a friend
        </p>
        <p className="text-sm text-fg-secondary">
          Share your code. When a friend pays for their first ride, you each get <span className="font-semibold text-fg">{formatINR(data.reward)}</span> in your wallet.
        </p>
        <button
          type="button"
          onClick={copy}
          className="neu flex items-center justify-between rounded-md px-4 py-3 text-left"
          aria-label={`Your invite code is ${data.code}. Copy it`}
        >
          <span className="tabular text-xl font-bold tracking-[0.2em] text-accent">{data.code}</span>
          <Copy className="size-4 text-fg-muted" aria-hidden />
        </button>
        <p className="text-xs text-fg-muted">
          {data.invited} invited · {data.rewarded} rewarded
        </p>
        {data.appliedCode ? (
          <p className="text-sm text-fg-secondary">
            You used code <span className="font-semibold text-fg">{data.appliedCode}</span>
            {data.rewardPending ? ': your reward arrives after your first paid ride.' : '. Reward received.'}
          </p>
        ) : (
          <div className="flex items-end gap-2">
            <TextField label="Have a friend's code?" value={code} maxLength={12} className="uppercase" onChange={(e) => setCode(e.target.value)} />
            <Button variant="secondary" disabled={code.trim().length < 4} loading={busy} onClick={apply}>
              Apply
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** The rider's wallet: balance, add money (online), invite a friend, and the statement of every movement. */
export function WalletPage() {
  const { user } = useAuth()
  const { data: wallet, error, refresh } = usePolling(async () => (await api.get<Wallet>('/api/wallet')).data, { intervalMs: 20000 })
  const statement = usePagedList<WalletEntry>('/api/wallet/transactions', {}, 10)
  const [amount, setAmount] = useState('250')
  const { pay, busy, dialog } = useOnlinePayment({ name: user?.fullName, email: user?.email })
  // Stripe sends the rider back here after its hosted page
  useCheckoutReturn(async () => {
    await refresh()
    statement.reload()
  })

  const value = Number(amount)
  const valid = Number.isFinite(value) && value >= 10 && value <= 10000

  return (
    <>
      <PageHeader eyebrow="Rider" title="Wallet" description="Money for rides: instant, no card needed at the end of a trip." />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_1fr]">
        <div className="flex flex-col gap-4">
          {wallet ? <StatCard icon={WalletIcon} label="Balance" value={formatINR(wallet.balance)} /> : <Skeleton className="h-32" />}
          <Card>
            <CardContent className="flex flex-col gap-3">
              <p className="font-semibold text-fg">Add money</p>
              <div className="flex flex-wrap gap-2">
                {QUICK.map((q) => (
                  <Button key={q} variant={value === q ? 'primary' : 'secondary'} size="sm" onClick={() => setAmount(String(q))}>
                    {formatINR(q, { whole: true })}
                  </Button>
                ))}
              </div>
              <TextField label="Amount (₹)" type="number" min={10} max={10000} step={1} value={amount} onChange={(e) => setAmount(e.target.value)} hint="Between ₹10 and ₹10,000" />
              <Button
                loading={busy}
                disabled={!valid}
                onClick={() =>
                  pay({
                    purpose: 'TOPUP',
                    amount: value,
                    description: 'RideX wallet top-up',
                    onPaid: async () => {
                      await refresh()
                      statement.reload()
                    },
                  })
                }
              >
                Add {valid ? formatINR(value) : 'money'}
              </Button>
              <p className="text-xs text-fg-muted">You pay in a secure window (card, UPI or netbanking). RideX never sees your card details.</p>
            </CardContent>
          </Card>
          <ReferralCard />
        </div>

        <div>
          <p className="mb-3 font-semibold text-fg">Statement</p>
          {statement.loading && !statement.data && <Skeleton className="h-48" />}
          {statement.data?.content.length === 0 && (
            <EmptyState icon={WalletIcon} title="No movements yet" description="Top-ups, ride payments and refunds appear here." />
          )}
          {statement.data && statement.data.content.length > 0 && (
            <>
              <ul className="flex flex-col gap-2">
                {statement.data.content.map((e) => (
                  <li key={e.id} className="panel flex items-center justify-between gap-3 rounded-lg border border-border p-3">
                    <span className="flex min-w-0 items-center gap-3">
                      {e.amount >= 0 ? <ArrowDownLeft className="size-4 text-success" aria-hidden /> : <ArrowUpRight className="size-4 text-fg-muted" aria-hidden />}
                      <span className="min-w-0">
                        <span className="block truncate text-sm text-fg">{e.description}</span>
                        <span className="text-xs text-fg-muted">{formatDateTime(e.at)}</span>
                      </span>
                    </span>
                    <span className="text-right">
                      <span className={`tabular block font-semibold ${e.amount >= 0 ? 'text-success' : 'text-fg'}`}>
                        {e.amount >= 0 ? '+' : '−'}{formatINR(Math.abs(e.amount))}
                      </span>
                      <span className="tabular text-xs text-fg-muted">balance {formatINR(e.balanceAfter)}</span>
                    </span>
                  </li>
                ))}
              </ul>
              <Pagination page={statement.data.page} size={statement.data.size} totalElements={statement.data.totalElements} totalPages={statement.data.totalPages} onPage={statement.setPage} />
            </>
          )}
        </div>
      </div>
      {dialog}
    </>
  )
}

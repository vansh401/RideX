import { ArrowDownLeft, ArrowUpRight, Wallet as WalletIcon } from 'lucide-react'
import { useRef, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Wallet, WalletEntry } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePagedList } from '@/lib/usePagedList'
import { usePolling } from '@/lib/usePolling'

const QUICK = [100, 250, 500, 1000]

/** The rider's wallet: balance, add money (test money), and the statement of every movement. */
export function WalletPage() {
  const { data: wallet, error, refresh } = usePolling(async () => (await api.get<Wallet>('/api/wallet')).data, { intervalMs: 20000 })
  const statement = usePagedList<WalletEntry>('/api/wallet/transactions', {}, 10)
  const [amount, setAmount] = useState('250')
  const [busy, setBusy] = useState(false)
  // One key per "intention to top up": pressing the button twice (or a retry after a lost response) must not add the money twice.
  const key = useRef<string>(crypto.randomUUID())

  const value = Number(amount)
  const valid = Number.isFinite(value) && value >= 1

  async function topUp() {
    setBusy(true)
    try {
      await api.post('/api/wallet/topups', { amount: value }, { headers: { 'Idempotency-Key': key.current } })
      key.current = crypto.randomUUID()
      toast.success(`${formatINR(value)} added to your wallet`)
      await refresh()
      statement.reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Rider" title="Wallet" description="Test money that pays for rides before your card is tried." />
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
              <TextField label="Amount (₹)" type="number" min={1} step={1} value={amount} onChange={(e) => setAmount(e.target.value)} />
              <Button loading={busy} disabled={!valid} onClick={topUp}>Add to wallet</Button>
              <p className="text-xs text-fg-muted">This is a practice wallet. No real money moves.</p>
            </CardContent>
          </Card>
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
                  <li key={e.id} className="flex items-center justify-between gap-3 rounded-lg border border-border bg-surface p-3">
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
    </>
  )
}

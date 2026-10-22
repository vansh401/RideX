import { HeartHandshake, ReceiptText } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { AppNotification, Payment, Receipt } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { useCheckoutReturn, useOnlinePayment } from '@/components/payments/useOnlinePayment'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatINR } from '@/lib/format'
import { PAYMENT_LABEL, PAYMENT_TONE, isSettled } from '@/lib/payments'
import { usePolling } from '@/lib/usePolling'
import { useSubscription } from '@/realtime/useRealtime'

const TIPS = [10, 20, 50]

/** Thank the driver: one tip per ride, from the wallet. Shown to the rider once the ride is paid. */
function TipBox({ rideId }: { rideId: string }) {
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api
      .get<Receipt>(`/api/payments/rides/${rideId}/receipt`)
      .then((response) => setReceipt(response.data))
      .catch(() => undefined)
  }, [rideId])

  async function tip(amount: number) {
    setBusy(true)
    try {
      await api.post(`/api/payments/rides/${rideId}/tip`, { amount })
      toast.success(`Thank you! ${formatINR(amount)} sent to your driver`)
      setReceipt((current) => (current ? { ...current, tip: amount } : current))
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  if (!receipt) return null
  if (receipt.tip != null) {
    return (
      <p className="flex items-center gap-2 text-sm text-fg-secondary">
        <HeartHandshake className="size-4 text-accent" aria-hidden /> You tipped {formatINR(receipt.tip)}. Thank you!
      </p>
    )
  }
  const custom1 = Number(custom)
  return (
    <div className="flex flex-col gap-2 border-t border-border pt-3">
      <p className="flex items-center gap-2 text-sm font-medium text-fg">
        <HeartHandshake className="size-4 text-accent" aria-hidden /> Say thanks with a tip
      </p>
      <div className="flex flex-wrap items-center gap-2">
        {TIPS.map((amount) => (
          <Button key={amount} size="sm" variant="secondary" disabled={busy} onClick={() => tip(amount)}>
            {formatINR(amount, { whole: true })}
          </Button>
        ))}
        <input
          aria-label="Other tip amount in rupees"
          inputMode="numeric"
          placeholder="Other"
          className="h-9 w-20 rounded-md border border-border-strong bg-canvas/70 px-2 text-sm"
          value={custom}
          onChange={(event) => setCustom(event.target.value.replace(/[^0-9]/g, ''))}
        />
        <Button size="sm" disabled={busy || !(custom1 >= 1 && custom1 <= 500)} onClick={() => tip(custom1)}>
          Tip
        </Button>
      </div>
      <p className="text-xs text-fg-muted">Paid from your wallet; the driver keeps all of it.</p>
    </div>
  )
}

/**
 * The payment of a completed ride. It does not exist the instant the ride completes: an event travels to the payment service, which
 * books it (wallet first). So this card asks every few seconds until the payment is settled, and also wakes up the moment the payment
 * notification arrives. If the wallet could not cover the ride, "Pay now" opens the payment window for exactly the shortfall.
 */
export function PaymentCard({ rideId, canRetry }: { rideId: string; canRetry: boolean }) {
  const { user } = useAuth()
  const [settled, setSettled] = useState(false)
  const [retrying, setRetrying] = useState(false)

  const { data: payment, refresh } = usePolling<Payment | null>(
    async () => {
      try {
        return (await api.get<Payment>(`/api/payments/rides/${rideId}`)).data
      } catch (err) {
        if (toFriendlyError(err).status === 404) return null   // not created yet
        throw err
      }
    },
    { intervalMs: 3000, enabled: !settled },
  )

  const { pay, busy, dialog } = useOnlinePayment({ name: user?.fullName, email: user?.email })
  // Stripe sends the rider back to this ride after its hosted page
  useCheckoutReturn(async () => {
    setSettled(false)
    await refresh()
  })

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reacts to the loaded payment
    if (payment && isSettled(payment.status)) setSettled(true)
  }, [payment])

  useSubscription<AppNotification>('/user/queue/notifications', (n) => {
    if (n.rideId === rideId) void refresh()
  })

  async function retry() {
    setRetrying(true)
    try {
      await api.post(`/api/payments/rides/${rideId}/retry`)
      setSettled(false)
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setRetrying(false)
    }
  }

  const how = payment?.method === 'WALLET' ? 'from your wallet' : payment?.method === 'PROMO' ? 'with your offer' : payment?.method ? 'by card' : ''

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <p className="font-semibold text-fg">Payment</p>
          {payment ? <Badge tone={PAYMENT_TONE[payment.status]}>{PAYMENT_LABEL[payment.status]}</Badge> : <Badge tone="warning">Processing</Badge>}
        </div>

        {!payment && <p className="text-sm text-fg-muted">The payment is being created. This takes a moment.</p>}

        {payment?.status === 'SUCCEEDED' && (
          <>
            <p className="text-sm text-fg-secondary">
              <span className="tabular font-semibold text-fg">{formatINR(payment.amount - (payment.discount ?? 0))}</span> paid {how}.
              {(payment.discount ?? 0) > 0 && <span className="block text-xs text-fg-muted">Offer saved you {formatINR(payment.discount ?? 0)}</span>}
            </p>
            <Link to={`/receipt/${rideId}`} className="inline-flex items-center gap-2 text-sm font-medium text-accent hover:underline">
              <ReceiptText className="size-4" aria-hidden /> View receipt
            </Link>
            {canRetry && <TipBox rideId={rideId} />}
          </>
        )}

        {payment?.status === 'PENDING' && <p className="text-sm text-fg-muted">Taking {formatINR(payment.amount)}…</p>}

        {payment?.status === 'FAILED' && (
          <>
            <Alert kind="error">{payment.failureReason ?? 'The payment could not be taken.'}</Alert>
            {canRetry && (
              <div className="flex flex-wrap gap-2">
                <Button
                  loading={busy}
                  onClick={() => pay({ purpose: 'RIDE', rideId, description: 'RideX ride', onPaid: async () => { setSettled(false); await refresh() } })}
                >
                  Pay now
                </Button>
                <Button variant="secondary" onClick={retry} loading={retrying}>
                  Try again{payment.attempts > 1 ? ` (attempt ${payment.attempts + 1})` : ''}
                </Button>
              </div>
            )}
          </>
        )}
        {dialog}
      </CardContent>
    </Card>
  )
}

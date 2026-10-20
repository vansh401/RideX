import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { AppNotification, Payment } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatINR } from '@/lib/format'
import { PAYMENT_LABEL, PAYMENT_TONE, isSettled } from '@/lib/payments'
import { usePolling } from '@/lib/usePolling'
import { useSubscription } from '@/realtime/useRealtime'

/**
 * The payment of a completed ride. It does not exist the instant the ride completes: an event travels to the
 * payment service, which charges the (mock) gateway and saves the result. So this card asks every few seconds
 * until the payment is settled, and also wakes up the moment the payment notification arrives.
 */
export function PaymentCard({ rideId, canRetry }: { rideId: string; canRetry: boolean }) {
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

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <p className="font-semibold text-fg">Payment</p>
          {payment ? (
            <Badge tone={PAYMENT_TONE[payment.status]}>{PAYMENT_LABEL[payment.status]}</Badge>
          ) : (
            <Badge tone="warning">Processing</Badge>
          )}
        </div>

        {!payment && <p className="text-sm text-fg-muted">The payment is being created. This takes a moment.</p>}

        {payment?.status === 'SUCCEEDED' && (
          <p className="text-sm text-fg-secondary">
            <span className="tabular font-semibold text-fg">{formatINR(payment.amount)}</span> was charged to your test card.
            <span className="mt-1 block text-xs text-fg-muted">Reference {payment.gatewayReference}</span>
          </p>
        )}

        {payment?.status === 'PENDING' && <p className="text-sm text-fg-muted">Charging {formatINR(payment.amount)}…</p>}

        {payment?.status === 'FAILED' && (
          <>
            <Alert kind="error">{payment.failureReason ?? 'The charge was declined.'}</Alert>
            {canRetry && (
              <Button onClick={retry} loading={retrying}>
                Try again{payment.attempts > 1 ? ` (attempt ${payment.attempts + 1})` : ''}
              </Button>
            )}
          </>
        )}
        <p className="text-xs text-fg-muted">Payments use a test gateway: no real money moves.</p>
      </CardContent>
    </Card>
  )
}

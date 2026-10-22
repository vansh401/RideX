import { useCallback, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { OnlineOrder } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { formatINR } from '@/lib/format'

/**
 * Paying online, for both providers behind one function.
 *
 *   RAZORPAY  the real thing (free in test mode): the provider's own window opens (card, UPI, netbanking), and our server never sees
 *             card details. When it finishes we send the provider's three values to our server, which checks the SIGNATURE.
 *   MOCK      no keys configured: a small "test checkout" window stands in, with a fake payment id and a fixed signature.
 *
 * Either way the server decides whether the payment is real. This code only starts the checkout and reports the result.
 */

interface RazorpayResponse {
  razorpay_payment_id: string
  razorpay_order_id: string
  razorpay_signature: string
}

interface RazorpayCheckout {
  open: () => void
  on: (event: string, handler: (response: unknown) => void) => void
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayCheckout
  }
}

let scriptLoading: Promise<void> | null = null

/** Loads Razorpay's checkout script once, only when a payment is actually started. */
function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  scriptLoading ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://checkout.razorpay.com/v1/checkout.js'
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      scriptLoading = null
      reject(new Error('The payment window could not be loaded. Check your connection and try again.'))
    }
    document.head.appendChild(script)
  })
  return scriptLoading
}

export interface PayRequest {
  purpose: 'TOPUP' | 'RIDE'
  amount?: number
  rideId?: string
  /** Shown in the provider's window. */
  description: string
  /** Called after the server confirmed the payment (the wallet is already credited). */
  onPaid: () => void | Promise<void>
}

export function useOnlinePayment(user?: { name?: string; email?: string }): { pay: (request: PayRequest) => Promise<void>; busy: boolean; dialog: ReactNode } {
  const [busy, setBusy] = useState(false)
  const [mock, setMock] = useState<{ order: OnlineOrder; request: PayRequest } | null>(null)
  const finished = useRef(false)

  const confirm = useCallback(async (order: OnlineOrder, request: PayRequest, paymentId: string, signature: string) => {
    try {
      await api.post('/api/payments/online/verify', { orderId: order.orderId, paymentId, signature })
      toast.success(order.purpose === 'RIDE' ? 'Payment received. Thank you!' : `${formatINR(order.amount)} added to your wallet`)
      await request.onPaid()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
      setMock(null)
    }
  }, [])

  const pay = useCallback(
    async (request: PayRequest) => {
      setBusy(true)
      finished.current = false
      let order: OnlineOrder
      try {
        order = (await api.post<OnlineOrder>('/api/payments/online/orders', { purpose: request.purpose, amount: request.amount, rideId: request.rideId })).data
      } catch (err) {
        toast.error(toFriendlyError(err).message)
        setBusy(false)
        return
      }
      if (order.provider === 'MOCK') {
        setMock({ order, request })
        return
      }
      try {
        await loadRazorpay()
        const checkout = new window.Razorpay!({
          key: order.publicKey,
          order_id: order.orderId,
          amount: Math.round(order.amount * 100),
          currency: order.currency,
          name: 'RideX',
          description: request.description,
          prefill: { name: user?.name, email: user?.email },
          theme: { color: '#e9c84a' },
          handler: (response: RazorpayResponse) => {
            finished.current = true
            void confirm(order, request, response.razorpay_payment_id, response.razorpay_signature)
          },
          modal: {
            ondismiss: () => {
              if (!finished.current) setBusy(false)   // closed without paying
            },
          },
        })
        checkout.on('payment.failed', () => {
          toast.error('The payment did not go through. You have not been charged.')
          setBusy(false)
        })
        checkout.open()
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'The payment window could not be opened.')
        setBusy(false)
      }
    },
    [confirm, user?.email, user?.name],
  )

  const dialog = (
    <Dialog open={mock !== null} onOpenChange={(open) => !open && (setMock(null), setBusy(false))}>
      <DialogContent title="Test checkout" description="No real money moves. This stands in for the payment window until real payment keys are configured.">
        {mock && (
          <div className="flex flex-col gap-4">
            <div className="rounded-md border border-border bg-black/20 p-4 text-center">
              <p className="text-sm text-fg-muted">{mock.request.description}</p>
              <p className="tabular mt-1 text-3xl font-bold text-fg">{formatINR(mock.order.amount)}</p>
            </div>
            <Button size="lg" onClick={() => void confirm(mock.order, mock.request, `pay_mock_${Date.now()}`, 'mock-signature')}>
              Pay {formatINR(mock.order.amount)} (test)
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setMock(null)
                setBusy(false)
              }}
            >
              Cancel
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )

  return { pay, busy, dialog }
}

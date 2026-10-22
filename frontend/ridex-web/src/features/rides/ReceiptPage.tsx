import { Printer } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { Receipt } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

function Line({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-4 py-2 ${strong ? 'border-t border-border font-semibold text-fg' : 'text-fg-secondary'}`}>
      <span>{label}</span>
      <span className="tabular">{value}</span>
    </div>
  )
}

/** A receipt for a paid ride. "Print" gives the browser's print dialog, which can also save it as a PDF (the print style makes it black on white). */
export function ReceiptPage() {
  const { rideId } = useParams()
  const { data, error, loading } = usePolling<Receipt>(async () => (await api.get<Receipt>(`/api/payments/rides/${rideId}/receipt`)).data, { intervalMs: 60000 })

  return (
    <>
      <div className="no-print">
        <PageHeader
          eyebrow="Receipt"
          title={data?.number ?? 'Receipt'}
          actions={
            <Button variant="secondary" onClick={() => window.print()}>
              <Printer className="size-4" aria-hidden /> Print or save as PDF
            </Button>
          }
        />
      </div>
      {loading && !data && <Skeleton className="h-64" />}
      {error && !data && <Alert kind="error">This receipt is not available. It exists once a ride is paid, for the people on that ride.</Alert>}
      {data && (
        <Card className="receipt-sheet mx-auto max-w-xl">
          <CardContent className="flex flex-col">
            <p className="text-2xl font-bold tracking-tight text-fg">RideX</p>
            <p className="text-sm text-fg-muted">
              Receipt {data.number} · {formatDateTime(data.issuedAt)}
            </p>
            <div className="mt-4 text-sm">
              <Line label="Ride fare" value={formatINR(data.fare)} />
              {data.discount > 0 && <Line label={`Offer${data.couponCode ? ` (${data.couponCode})` : ''}`} value={`− ${formatINR(data.discount)}`} />}
              <Line label={`Paid${data.method ? ` (${data.method.toLowerCase()})` : ''}`} value={formatINR(data.paid)} strong />
              {data.tip != null && <Line label="Tip to the driver" value={formatINR(data.tip)} />}
              {data.refunded > 0 && <Line label="Refunded" value={`− ${formatINR(data.refunded)}`} />}
            </div>
            <p className="mt-4 text-xs text-fg-muted">Ride reference {data.rideId}. This is a payment receipt for a ride, not a tax invoice.</p>
            <Link to={`/rides/${data.rideId}`} className="no-print mt-3 text-sm font-medium text-accent hover:underline">
              View the ride
            </Link>
          </CardContent>
        </Card>
      )}
    </>
  )
}

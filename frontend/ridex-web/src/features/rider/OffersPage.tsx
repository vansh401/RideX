import { Copy, Gift, Ticket } from 'lucide-react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { Offer } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDate, formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

function describe(o: Offer): string {
  const base = o.discountType === 'PERCENT' ? `${o.value}% off` : `${formatINR(o.value)} off`
  return `${base}${o.maxDiscount != null ? `, up to ${formatINR(o.maxDiscount)}` : ''}${o.minFare != null ? ` on fares from ${formatINR(o.minFare)}` : ''}`
}

/** Offers a rider can use right now. Copy a code, then paste it when booking. */
export function OffersPage() {
  const { data, error } = usePolling(async () => (await api.get<Offer[]>('/api/coupons/offers')).data, { intervalMs: 60000 })

  async function copy(code: string) {
    try {
      await navigator.clipboard.writeText(code)
      toast.success(`${code} copied. Paste it when you book.`)
    } catch {
      toast.error('Could not copy. Select the code and copy it by hand.')
    }
  }

  return (
    <>
      <PageHeader eyebrow="Rider" title="Offers" description="Codes you can use on your next ride." />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      {!data && !error && <Skeleton className="h-40" />}
      {data?.length === 0 && <EmptyState icon={Gift} title="No offers right now" description="New offers appear here. You can also earn wallet money by inviting friends." />}
      <ul className="grid gap-4 sm:grid-cols-2">
        {data?.map((o) => (
          <li key={o.code}>
            <Card interactive className="h-full">
              <CardContent className="flex h-full flex-col gap-3">
                <p className="flex items-center gap-2 text-sm text-fg-muted">
                  <Ticket className="size-4 text-accent" aria-hidden /> Valid until {formatDate(o.validUntil)}
                </p>
                <p className="text-lg font-semibold text-fg">{describe(o)}</p>
                {o.description && <p className="text-sm text-fg-secondary">{o.description}</p>}
                <p className="text-xs text-fg-muted">
                  You can use it {o.usesLeft} more time{o.usesLeft === 1 ? '' : 's'}.
                </p>
                <button type="button" onClick={() => copy(o.code)} className="neu mt-auto flex items-center justify-between rounded-md px-4 py-3" aria-label={`Copy code ${o.code}`}>
                  <span className="tabular text-lg font-bold tracking-widest text-accent">{o.code}</span>
                  <Copy className="size-4 text-fg-muted" aria-hidden />
                </button>
              </CardContent>
            </Card>
          </li>
        ))}
      </ul>
    </>
  )
}

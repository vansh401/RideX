import { Wallet } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Payment, PaymentStatus } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatINR } from '@/lib/format'
import { PAYMENT_LABEL, PAYMENT_TONE } from '@/lib/payments'
import { usePagedList } from '@/lib/usePagedList'
import { GatewayPanel } from './GatewayPanel'

export function AdminPaymentsPage() {
  const [status, setStatus] = useState<PaymentStatus | ''>('')
  const { data, error, loading, setPage } = usePagedList<Payment>('/api/admin/payments', { status }, 10)

  return (
    <>
      <PageHeader eyebrow="Admin" title="Payments" description="Charges made by the test gateway when rides complete." />
      <GatewayPanel />
      <div className="mb-4 max-w-xs">
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value as PaymentStatus | '')}>
          <option value="">All</option>
          <option value="SUCCEEDED">Paid</option>
          <option value="FAILED">Failed</option>
          <option value="PENDING">Processing</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && <EmptyState icon={Wallet} title="No payments" description="Payments appear here after rides complete." />}
      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((p) => (
              <li key={p.id}>
                <Link
                  to={`/rides/${p.rideId}`}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-lg panel border border-border p-4 hover:border-fg-muted"
                >
                  <div className="min-w-0">
                    <p className="tabular font-semibold text-fg">{formatINR(p.amount)}</p>
                    <p className="text-sm text-fg-muted">
                      {formatDateTime(p.createdAt)} · attempt {p.attempts}
                      {p.failureReason ? ` · ${p.failureReason}` : ''}
                    </p>
                  </div>
                  <Badge tone={PAYMENT_TONE[p.status]}>{PAYMENT_LABEL[p.status]}</Badge>
                </Link>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

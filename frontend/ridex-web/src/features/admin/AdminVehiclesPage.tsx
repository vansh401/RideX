import { useState } from 'react'
import type { AdminVehicle, VehicleCategory } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { ApprovalBadge } from '@/components/brand/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { CATEGORY_LABEL } from '@/lib/rides'
import { usePagedList } from '@/lib/usePagedList'

export function AdminVehiclesPage() {
  const [category, setCategory] = useState<VehicleCategory | ''>('')
  const { data, error, loading, setPage } = usePagedList<AdminVehicle>('/api/admin/vehicles', { category }, 10)
  return (
    <>
      <PageHeader eyebrow="Admin" title="Vehicles" />
      <div className="mb-4 max-w-xs">
        <SelectField label="Type" value={category} onChange={(e) => setCategory(e.target.value as VehicleCategory | '')}>
          <option value="">All</option><option value="BIKE">Bike</option><option value="AUTO">Auto</option><option value="CAB">Cab</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((v) => (
              <li key={v.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface p-4">
                <div>
                  <p className="font-semibold text-fg">{v.color} {v.make} {v.model}</p>
                  <p className="tabular text-sm text-fg-muted">{v.registrationNumber} · {CATEGORY_LABEL[v.category]} · {v.driverName}</p>
                </div>
                <div className="flex gap-2">
                  {v.active && <Badge tone="success">Active</Badge>}
                  <ApprovalBadge status={v.driverStatus} />
                </div>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}

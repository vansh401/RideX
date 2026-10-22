import { ArrowLeft, Download } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { AdminDriverDetail, AdminDriverSummary, ApprovalStatus } from '@/api/types'
import { ConfirmDialog } from '@/components/brand/ConfirmDialog'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { ApprovalBadge } from '@/components/brand/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { DOCUMENT_LABEL, saveBlob } from '@/lib/documents'
import { formatDate, formatDateTime } from '@/lib/format'
import { CATEGORY_LABEL } from '@/lib/rides'
import { usePagedList } from '@/lib/usePagedList'
import { BadgeCheck } from 'lucide-react'

export function AdminDriversPage() {
  const [status, setStatus] = useState<ApprovalStatus | ''>('PENDING_APPROVAL')
  const { data, error, loading, setPage } = usePagedList<AdminDriverSummary>('/api/admin/drivers', { status }, 10)

  return (
    <>
      <PageHeader eyebrow="Admin" title="Drivers" description="Review applications before anyone can drive." />
      <div className="mb-4 max-w-xs">
        <SelectField label="Show" value={status} onChange={(e) => setStatus(e.target.value as ApprovalStatus | '')}>
          <option value="PENDING_APPROVAL">Waiting for review</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
          <option value="">Everyone</option>
        </SelectField>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && (
        <EmptyState icon={BadgeCheck} title="Nothing here" description="No drivers match this filter." />
      )}
      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((d) => (
              <li key={d.id}>
                <Link to={`/admin/drivers/${d.id}`} className="flex flex-wrap items-center justify-between gap-3 rounded-lg panel border border-border p-4 hover:border-fg-muted">
                  <div>
                    <p className="font-semibold text-fg">{d.fullName}</p>
                    <p className="text-sm text-fg-muted">{d.email} · applied {formatDate(d.createdAt)}</p>
                  </div>
                  <ApprovalBadge status={d.approvalStatus} />
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

export function AdminDriverDetailPage() {
  const { driverId } = useParams()
  const [driver, setDriver] = useState<AdminDriverDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)

  const reload = useCallback(async () => {
    try {
      setDriver((await api.get<AdminDriverDetail>(`/api/admin/drivers/${driverId}`)).data)
    } catch (err) {
      setError(toFriendlyError(err).message)
    }
  }, [driverId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load
    void reload()
  }, [reload])

  async function approve() {
    try {
      await api.post(`/api/admin/drivers/${driverId}/approve`)
      toast.success('Driver approved')
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  async function reject(reason: string) {
    try {
      await api.post(`/api/admin/drivers/${driverId}/reject`, { reason })
      toast.success('Driver rejected')
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
      throw err
    }
  }

  async function download(documentId: string, filename: string) {
    try {
      const { data } = await api.get<Blob>(`/api/admin/drivers/${driverId}/documents/${documentId}/file`, { responseType: 'blob' })
      saveBlob(data, filename)
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  if (error) return <Alert kind="error">{error}</Alert>
  if (!driver) return <Skeleton className="h-64" />

  return (
    <>
      <Link to="/admin/drivers" className="mb-4 inline-flex items-center gap-1 text-sm text-fg-secondary hover:text-fg">
        <ArrowLeft className="size-4" aria-hidden /> Drivers
      </Link>
      <PageHeader eyebrow="Driver application" title={driver.fullName} actions={<ApprovalBadge status={driver.approvalStatus} />} />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardContent>
            <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
              <dt className="text-fg-muted">Email</dt><dd className="text-fg">{driver.email}</dd>
              <dt className="text-fg-muted">Phone</dt><dd className="tabular text-fg">{driver.phone}</dd>
              <dt className="text-fg-muted">Licence</dt><dd className="tabular text-fg">{driver.licenseNumber}</dd>
              <dt className="text-fg-muted">Expires</dt><dd className="text-fg">{formatDate(driver.licenseExpiry)}</dd>
              {driver.reviewedAt && (<><dt className="text-fg-muted">Reviewed</dt><dd className="text-fg">{formatDateTime(driver.reviewedAt)}</dd></>)}
              {driver.rejectionReason && (<><dt className="text-fg-muted">Reason</dt><dd className="text-fg">{driver.rejectionReason}</dd></>)}
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardContent>
            <p className="mb-3 font-semibold text-fg">Documents</p>
            {driver.documents.length === 0 && <p className="text-sm text-fg-muted">Nothing uploaded.</p>}
            <ul className="flex flex-col gap-2">
              {driver.documents.map((doc) => (
                <li key={doc.id} className="flex items-center justify-between gap-3 text-sm">
                  <span className="text-fg">{DOCUMENT_LABEL[doc.type]}<span className="block text-fg-muted">{doc.originalFilename}</span></span>
                  <Button size="sm" variant="secondary" onClick={() => download(doc.id, doc.originalFilename)}>
                    <Download className="size-4" aria-hidden /> Open
                  </Button>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardContent>
            <p className="mb-3 font-semibold text-fg">Vehicles</p>
            {driver.vehicles.length === 0 && <p className="text-sm text-fg-muted">No vehicles added.</p>}
            <ul className="flex flex-col gap-1 text-sm text-fg">
              {driver.vehicles.map((v) => (
                <li key={v.id}>
                  {v.color} {v.make} {v.model} · <span className="tabular">{v.registrationNumber}</span> · {CATEGORY_LABEL[v.category]}{v.active ? ' · active' : ''}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      </div>

      <div className="mt-6 flex gap-3">
        {driver.approvalStatus !== 'APPROVED' && <Button onClick={approve}>Approve</Button>}
        {driver.approvalStatus !== 'REJECTED' && <Button variant="danger" onClick={() => setRejecting(true)}>Reject</Button>}
      </div>

      <ConfirmDialog
        open={rejecting}
        onOpenChange={setRejecting}
        title="Reject this driver?"
        description="The driver sees your reason and can correct the details."
        confirmLabel="Reject"
        danger
        askReason
        onConfirm={reject}
      />
    </>
  )
}

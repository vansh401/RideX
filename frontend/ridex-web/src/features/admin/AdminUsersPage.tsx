import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { AdminUser, Role } from '@/api/types'
import { ConfirmDialog } from '@/components/brand/ConfirmDialog'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { AccountBadge } from '@/components/brand/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { usePagedList } from '@/lib/usePagedList'

export function AdminUsersPage() {
  const [q, setQ] = useState('')
  const [role, setRole] = useState<Role | ''>('')
  const [status, setStatus] = useState('')
  const [target, setTarget] = useState<AdminUser | null>(null)
  // The search box is applied on submit so typing does not fire a request per key
  const [applied, setApplied] = useState('')
  const { data, error, loading, setPage, reload } = usePagedList<AdminUser>('/api/admin/users', { q: applied, role, status }, 10)

  async function suspend(reason: string) {
    if (!target) return
    try {
      await api.post(`/api/admin/users/${target.id}/suspend`, { reason })
      toast.success('User suspended')
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
      throw err
    }
  }

  async function reinstate(user: AdminUser) {
    try {
      await api.post(`/api/admin/users/${user.id}/reinstate`)
      toast.success('User reinstated')
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Admin" title="Users" />
      <form
        className="mb-4 grid gap-3 sm:grid-cols-[1fr_10rem_12rem_auto] sm:items-end"
        onSubmit={(e) => {
          e.preventDefault()
          setApplied(q.trim())
        }}
      >
        <TextField label="Search name, email or phone" value={q} onChange={(e) => setQ(e.target.value)} />
        <SelectField label="Role" value={role} onChange={(e) => setRole(e.target.value as Role | '')}>
          <option value="">All</option><option value="RIDER">Rider</option><option value="DRIVER">Driver</option><option value="ADMIN">Admin</option>
        </SelectField>
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All</option><option value="ACTIVE">Active</option><option value="PENDING_VERIFICATION">Unverified</option><option value="SUSPENDED">Suspended</option>
        </SelectField>
        <Button type="submit" variant="secondary">Search</Button>
      </form>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-64" />}
      {data && (
        <>
          <ul className="flex flex-col gap-3">
            {data.content.map((u) => (
              <li key={u.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg panel border border-border p-4">
                <div className="min-w-0">
                  <p className="font-semibold text-fg">{u.fullName}</p>
                  <p className="truncate text-sm text-fg-muted">{u.email} · {u.phone}</p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone="accent">{u.role}</Badge>
                  <AccountBadge status={u.status} />
                  {u.role !== 'ADMIN' && (u.status === 'SUSPENDED' ? (
                    <Button size="sm" variant="secondary" onClick={() => reinstate(u)}>Reinstate</Button>
                  ) : (
                    <Button size="sm" variant="ghost" onClick={() => setTarget(u)}>Suspend</Button>
                  ))}
                </div>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}

      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        title={`Suspend ${target?.fullName ?? ''}?`}
        description="They are signed out and cannot log in until reinstated."
        confirmLabel="Suspend"
        danger
        askReason
        onConfirm={suspend}
      />
    </>
  )
}

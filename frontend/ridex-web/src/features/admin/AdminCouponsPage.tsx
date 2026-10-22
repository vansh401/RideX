import { Ticket } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Coupon } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { formatDate, formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 10)

/** Offers: create a code, see how much it has been used, switch it off. Discounts are paid by the platform, not the driver. */
export function AdminCouponsPage() {
  const { data, error, refresh } = usePolling(async () => (await api.get<Coupon[]>('/api/admin/coupons')).data, { intervalMs: 30000 })
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<'PERCENT' | 'FLAT'>('PERCENT')
  const [value, setValue] = useState('10')
  const [maxDiscount, setMaxDiscount] = useState('')
  const [minFare, setMinFare] = useState('')
  const [until, setUntil] = useState(inDays(30))
  const [maxUses, setMaxUses] = useState('')
  const [perUser, setPerUser] = useState('1')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  async function create() {
    setBusy(true)
    setFormError(null)
    try {
      await api.post('/api/admin/coupons', {
        code: code.trim().toUpperCase(),
        description: description || null,
        discountType: type,
        value: Number(value),
        maxDiscount: maxDiscount ? Number(maxDiscount) : null,
        minFare: minFare ? Number(minFare) : null,
        validFrom: new Date().toISOString(),
        validUntil: new Date(`${until}T23:59:59`).toISOString(),
        maxTotalUses: maxUses ? Number(maxUses) : null,
        perUserLimit: Number(perUser) || 1,
      })
      toast.success('Offer created')
      setCode('')
      setDescription('')
      await refresh()
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  async function toggle(c: Coupon) {
    try {
      await api.patch(`/api/admin/coupons/${c.id}/active`, { active: !c.active })
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Admin" title="Offers" description="Coupon codes riders can apply when they book." />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,24rem)_1fr]">
        <Card>
          <CardContent className="flex flex-col gap-3">
            <p className="font-semibold text-fg">New offer</p>
            <TextField label="Code" value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} hint="Letters, digits, - and _" />
            <TextField label="Description" value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)} />
            <div className="grid grid-cols-2 gap-3">
              <SelectField label="Type" value={type} onChange={(e) => setType(e.target.value as 'PERCENT' | 'FLAT')}>
                <option value="PERCENT">Percent</option>
                <option value="FLAT">Flat ₹</option>
              </SelectField>
              <TextField label={type === 'PERCENT' ? 'Percent' : 'Amount (₹)'} type="number" min={1} value={value} onChange={(e) => setValue(e.target.value)} />
              <TextField label="Max discount (₹)" type="number" min={1} value={maxDiscount} onChange={(e) => setMaxDiscount(e.target.value)} />
              <TextField label="Min fare (₹)" type="number" min={0} value={minFare} onChange={(e) => setMinFare(e.target.value)} />
              <TextField label="Valid until" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
              <TextField label="Total uses" type="number" min={1} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} hint="Empty = unlimited" />
              <TextField label="Per rider" type="number" min={1} value={perUser} onChange={(e) => setPerUser(e.target.value)} />
            </div>
            {formError && <Alert kind="error">{formError}</Alert>}
            <Button loading={busy} disabled={!code.trim() || !Number(value)} onClick={create}>Create offer</Button>
          </CardContent>
        </Card>

        <div>
          {!data && <Skeleton className="h-48" />}
          {data?.length === 0 && <EmptyState icon={Ticket} title="No offers yet" description="Create one on the left." />}
          <ul className="flex flex-col gap-3">
            {data?.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface p-4">
                <div className="min-w-0">
                  <p className="flex items-center gap-2 font-semibold text-fg">
                    {c.code} <Badge tone={c.active ? 'success' : 'neutral'}>{c.active ? 'Active' : 'Off'}</Badge>
                  </p>
                  <p className="text-sm text-fg-secondary">
                    {c.discountType === 'PERCENT' ? `${c.value}% off` : `${formatINR(c.value)} off`}
                    {c.maxDiscount != null ? `, up to ${formatINR(c.maxDiscount)}` : ''}
                    {c.minFare != null ? `, min fare ${formatINR(c.minFare)}` : ''}
                  </p>
                  <p className="text-xs text-fg-muted">
                    Used {c.usedCount}{c.maxTotalUses != null ? ` of ${c.maxTotalUses}` : ''} · {c.perUserLimit} per rider · until {formatDate(c.validUntil)}
                  </p>
                </div>
                <Button variant="secondary" size="sm" onClick={() => toggle(c)}>{c.active ? 'Switch off' : 'Switch on'}</Button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </>
  )
}

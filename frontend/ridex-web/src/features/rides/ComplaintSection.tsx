import { LifeBuoy } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Complaint, ComplaintCategory, PageResponse } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { formatDateTime } from '@/lib/format'
import { CATEGORY_OPTIONS, COMPLAINT_STATUS_LABEL, COMPLAINT_STATUS_TONE } from '@/lib/complaints'

/** "Something went wrong with this trip": file a complaint, follow it, and answer the support team. */
export function ComplaintSection({ rideId }: { rideId: string }) {
  const [mine, setMine] = useState<Complaint[]>([])
  const [category, setCategory] = useState<ComplaintCategory>('OTHER')
  const [description, setDescription] = useState('')
  const [reply, setReply] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const { data } = await api.get<PageResponse<Complaint>>('/api/complaints?size=50')
      setMine(data.content.filter((c) => c.rideId === rideId))
    } catch {
      setMine([])
    }
  }, [rideId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load() starts a request
    void load()
  }, [load])

  const open = mine.find((c) => c.status === 'OPEN' || c.status === 'IN_REVIEW')

  async function file() {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/complaints', { rideId, category, description })
      setDescription('')
      toast.success('Complaint sent. We will get back to you here.')
      await load()
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  async function answer(id: string) {
    setBusy(true)
    try {
      await api.post(`/api/complaints/${id}/messages`, { message: reply })
      setReply('')
      await load()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-4">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <LifeBuoy className="size-4 text-accent" aria-hidden /> Help with this trip
        </p>

        {mine.map((c) => (
          <div key={c.id} className="flex flex-col gap-2 rounded-md border border-border p-3 text-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium text-fg">{CATEGORY_OPTIONS.find((o) => o.value === c.category)?.label}</span>
              <Badge tone={COMPLAINT_STATUS_TONE[c.status]}>{COMPLAINT_STATUS_LABEL[c.status]}</Badge>
            </div>
            <p className="text-fg-secondary">{c.description}</p>
            {c.comments.map((m) => (
              <p key={m.id} className="rounded bg-elevated px-2 py-1 text-fg-secondary">
                <span className="font-medium text-fg">{m.authorRole === 'ADMIN' ? 'Support' : 'You'}:</span> {m.message}
                <span className="ml-2 text-xs text-fg-muted">{formatDateTime(m.createdAt)}</span>
              </p>
            ))}
            {c.resolutionNote && <Alert kind={c.status === 'RESOLVED' ? 'success' : 'info'}>{c.resolutionNote}</Alert>}
            {c.refundAmount != null && c.status === 'RESOLVED' && <p className="text-fg">Refund approved: ₹{c.refundAmount}</p>}
            {(c.status === 'OPEN' || c.status === 'IN_REVIEW') && (
              <div className="flex gap-2">
                <input
                  aria-label="Reply to support"
                  className="h-9 flex-1 rounded-md border border-border-strong bg-surface px-3 text-sm"
                  value={reply}
                  maxLength={1000}
                  onChange={(e) => setReply(e.target.value)}
                />
                <Button size="sm" disabled={!reply.trim()} loading={busy} onClick={() => answer(c.id)}>
                  Send
                </Button>
              </div>
            )}
          </div>
        ))}

        {!open && (
          <div className="flex flex-col gap-3">
            <SelectField label="What happened?" value={category} onChange={(e) => setCategory(e.target.value as ComplaintCategory)}>
              {CATEGORY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </SelectField>
            <label className="flex flex-col gap-1.5 text-sm font-medium text-fg">
              Tell us more
              <textarea
                className="min-h-24 rounded-md border border-border-strong bg-surface p-3 text-sm font-normal"
                value={description}
                maxLength={1000}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="At least 10 characters"
              />
            </label>
            {error && <Alert kind="error">{error}</Alert>}
            <Button variant="secondary" loading={busy} disabled={description.trim().length < 10} onClick={file}>
              Send complaint
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

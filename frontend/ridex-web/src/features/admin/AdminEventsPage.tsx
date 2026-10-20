import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import type { AuditEntry, ConsumerLag, DailyCount, DeadLetter, PageResponse } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { TextField } from '@/components/ui/text-field'
import { formatDateTime } from '@/lib/format'
import { dailyChartData, totalLag } from '@/lib/payments'
import { usePolling } from '@/lib/usePolling'
import { Activity, Inbox, MailWarning } from 'lucide-react'

/** The window into the event system: is anything stuck, what failed, what happened to one ride. */
export function AdminEventsPage() {
  const [rideId, setRideId] = useState('')
  const [audit, setAudit] = useState<AuditEntry[] | null>(null)
  const [auditError, setAuditError] = useState<string | null>(null)

  const lag = usePolling(async () => (await api.get<ConsumerLag[]>('/api/admin/events/lag')).data, { intervalMs: 5000 })
  const outbox = usePolling(async () => (await api.get<{ pending: number }>('/api/admin/events/outbox')).data, { intervalMs: 5000 })
  const dead = usePolling(
    async () => (await api.get<PageResponse<DeadLetter>>('/api/admin/events/dead-letters?size=10')).data,
    { intervalMs: 10_000 },
  )
  const daily = usePolling(async () => (await api.get<DailyCount[]>('/api/admin/analytics/ride-status-per-day?days=14')).data, {
    intervalMs: 30_000,
  })

  async function lookUp() {
    setAuditError(null)
    try {
      const { data } = await api.get<PageResponse<AuditEntry>>(`/api/admin/events/audit?key=${encodeURIComponent(rideId.trim())}&size=50`)
      setAudit([...data.content].reverse())   // oldest first reads like a story
    } catch {
      setAuditError('Could not load the events')
    }
  }

  const chart = dailyChartData(daily.data ?? [])
  const waiting = totalLag(lag.data ?? [])

  return (
    <>
      <PageHeader eyebrow="Admin" title="Events" description="What the Kafka consumers are doing, and what failed." />

      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard icon={Activity} label="Messages waiting in consumers (lag)" value={String(waiting)} />
        <StatCard icon={Inbox} label="Events not yet sent to Kafka" value={String(outbox.data?.pending ?? 0)} />
        <StatCard icon={MailWarning} label="Dead letters" value={String(dead.data?.totalElements ?? 0)} />
      </div>
      {(lag.error || outbox.error) && <Alert kind="error" className="mt-4">{lag.error ?? outbox.error}</Alert>}

      <Card className="mt-6">
        <CardContent>
          <p className="mb-1 font-semibold text-fg">Consumer groups</p>
          <p className="mb-3 text-sm text-fg-muted">Lag 0 means the group has handled everything. A number that keeps growing means it cannot keep up.</p>
          <ul className="flex flex-col gap-2 text-sm">
            {lag.data?.map((entry) => (
              <li key={`${entry.group}/${entry.topic}`} className="flex items-center justify-between gap-3">
                <span className="text-fg">
                  <span className="font-semibold">{entry.group}</span> <span className="text-fg-muted">reads {entry.topic}</span>
                </span>
                <Badge tone={entry.lag === 0 ? 'success' : 'warning'}>{entry.lag === 0 ? 'Caught up' : `${entry.lag} behind`}</Badge>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardContent>
          <p className="mb-1 font-semibold text-fg">Rides per day</p>
          <p className="mb-4 text-sm text-fg-muted">Built only from events by the analytics consumer.</p>
          {chart.length === 0 ? (
            <p className="text-sm text-fg-muted">No events yet.</p>
          ) : (
            <div className="h-64" role="img" aria-label="Bar chart of requested, completed and cancelled rides per day">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chart}>
                  <CartesianGrid stroke="#2a2a30" vertical={false} />
                  <XAxis dataKey="day" stroke="#8a8a93" fontSize={12} />
                  <YAxis allowDecimals={false} stroke="#8a8a93" fontSize={12} />
                  <Tooltip contentStyle={{ background: '#16161a', border: '1px solid #2a2a30', borderRadius: 8 }} cursor={{ fill: '#ffffff10' }} />
                  <Legend />
                  <Bar dataKey="requested" name="Requested" fill="#6aa8ff" radius={[6, 6, 0, 0]} />
                  <Bar dataKey="completed" name="Completed" fill="#e8c547" radius={[6, 6, 0, 0]} />
                  <Bar dataKey="cancelled" name="Cancelled" fill="#e5484d" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardContent>
          <p className="mb-1 font-semibold text-fg">Dead letters</p>
          <p className="mb-3 text-sm text-fg-muted">Messages that failed after several tries. They are parked here so they never block the others.</p>
          {dead.data?.content.length === 0 && <p className="text-sm text-fg-muted">None. Everything was handled.</p>}
          <ul className="flex flex-col gap-3">
            {dead.data?.content.map((d) => (
              <li key={d.id} className="rounded-md border border-border p-3 text-sm">
                <p className="text-fg">
                  <span className="font-semibold">{d.failedGroup ?? 'unknown group'}</span> failed on {d.sourceTopic}
                  <span className="text-fg-muted"> · {formatDateTime(d.createdAt)}</span>
                </p>
                <p className="text-danger">{d.error}</p>
                <p className="truncate text-xs text-fg-muted">{d.payload}</p>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardContent className="flex flex-col gap-3">
          <p className="font-semibold text-fg">What happened to a ride?</p>
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-64 flex-1">
              <TextField label="Ride id" value={rideId} onChange={(e) => setRideId(e.target.value)} placeholder="Paste a ride id" />
            </div>
            <Button variant="secondary" onClick={lookUp} disabled={rideId.trim() === ''}>Show events</Button>
          </div>
          {auditError && <Alert kind="error">{auditError}</Alert>}
          {audit && audit.length === 0 && <p className="text-sm text-fg-muted">No events recorded for this id.</p>}
          <ol className="flex flex-col gap-1.5 text-sm">
            {audit?.map((entry) => (
              <li key={entry.id} className="flex flex-wrap justify-between gap-2">
                <span className="text-fg">{entry.type}</span>
                <span className="tabular text-fg-muted">
                  {entry.topic} · partition {entry.partition} · offset {entry.offset} · {formatDateTime(entry.occurredAt)}
                </span>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>
    </>
  )
}

import { Ban, CheckCircle2, IndianRupee, Percent, Timer, Zap } from 'lucide-react'
import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import type { AnalyticsOverview } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { CHART, tooltipStyle } from '@/lib/chartTheme'
import { formatINR } from '@/lib/format'
import { CATEGORY_LABEL } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

interface Day { day: string; requested: number; completed: number; cancelled: number; grossBookings: number }
interface Category { category: keyof typeof CATEGORY_LABEL; rides: number; grossBookings: number; averageFare: number }
interface Hour { hour: number; rides: number }
interface Zone { zone: string; requests: number; completed: number; averageSurge: number }
interface TopDriver { driverUserId: string; rides: number; grossBookings: number }

const tooltip = tooltipStyle

function ChartCard({ title, label, children }: { title: string; label: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardContent>
        <p className="mb-4 font-semibold text-fg">{title}</p>
        <div className="h-64" role="img" aria-label={label}>
          <ResponsiveContainer width="100%" height="100%">{children as React.ReactElement}</ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  )
}

/** Business reports from the analytics service, which builds them from events (never from the other services' tables). */
export function AdminAnalyticsPage() {
  const [days, setDays] = useState(30)
  const q = `?days=${days}`
  const poll = { intervalMs: 60000 }
  const overview = usePolling(async () => (await api.get<AnalyticsOverview>(`/api/admin/analytics/overview${q}`)).data, poll)
  const daily = usePolling(async () => (await api.get<Day[]>(`/api/admin/analytics/daily${q}`)).data, poll)
  const categories = usePolling(async () => (await api.get<Category[]>(`/api/admin/analytics/categories${q}`)).data, poll)
  const hours = usePolling(async () => (await api.get<Hour[]>(`/api/admin/analytics/hours${q}`)).data, poll)
  const zones = usePolling(async () => (await api.get<Zone[]>(`/api/admin/analytics/zones${q}`)).data, poll)
  const top = usePolling(async () => (await api.get<TopDriver[]>(`/api/admin/analytics/top-drivers${q}`)).data, poll)
  const o = overview.data

  return (
    <>
      <PageHeader eyebrow="Admin" title="Reports" description="Numbers built from the event stream, up to a minute behind real time." />
      <div className="mb-4 max-w-xs">
        <SelectField label="Period" value={String(days)} onChange={(e) => setDays(Number(e.target.value))}>
          <option value="7">Last 7 days</option>
          <option value="30">Last 30 days</option>
          <option value="90">Last 90 days</option>
        </SelectField>
      </div>
      {overview.error && <Alert kind="error" className="mb-4">{overview.error}</Alert>}
      {!o ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard icon={CheckCircle2} label={`Completed of ${o.ridesRequested} requested`} value={String(o.ridesCompleted)} />
            <StatCard icon={Ban} label="Cancellation rate" value={`${(o.cancellationRate * 100).toFixed(1)}%`} />
            <StatCard icon={IndianRupee} label="Gross bookings" value={formatINR(o.grossBookings, { whole: true })} />
            <StatCard icon={IndianRupee} label="Collected after offers" value={formatINR(o.collected, { whole: true })} />
            <StatCard icon={Percent} label="Offer discounts given" value={formatINR(o.couponDiscounts, { whole: true })} />
            <StatCard icon={IndianRupee} label="Refunded" value={formatINR(o.refunds, { whole: true })} />
            <StatCard icon={Zap} label="Rides at surge prices" value={String(o.ridesWithSurge)} />
            <StatCard icon={Timer} label="Average time to accept" value={o.averageSecondsToAccept == null ? '—' : `${Math.round(o.averageSecondsToAccept)} s`} />
          </div>
          <p className="mt-3 text-sm text-fg-muted">
            {o.paymentsSucceeded} payments succeeded, {o.paymentsFailed} failed · {o.complaints} complaints
          </p>
        </>
      )}

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        {daily.data && (
          <ChartCard title="Rides per day" label="Line chart of rides requested and completed per day">
            <LineChart data={daily.data.map((d) => ({ ...d, label: d.day.slice(5) }))}>
              <CartesianGrid stroke={CHART.grid} vertical={false} />
              <XAxis dataKey="label" stroke={CHART.axis} fontSize={12} interval="preserveStartEnd" />
              <YAxis allowDecimals={false} stroke={CHART.axis} fontSize={12} />
              <Tooltip contentStyle={tooltip} />
              <Line type="monotone" dataKey="requested" name="Requested" stroke={CHART.axis} dot={false} />
              <Line type="monotone" dataKey="completed" name="Completed" stroke={CHART.accent} dot={false} strokeWidth={2} />
            </LineChart>
          </ChartCard>
        )}
        {daily.data && (
          <ChartCard title="Gross bookings per day (₹)" label="Bar chart of gross bookings per day">
            <BarChart data={daily.data.map((d) => ({ ...d, label: d.day.slice(5) }))}>
              <CartesianGrid stroke={CHART.grid} vertical={false} />
              <XAxis dataKey="label" stroke={CHART.axis} fontSize={12} interval="preserveStartEnd" />
              <YAxis stroke={CHART.axis} fontSize={12} />
              <Tooltip contentStyle={tooltip} cursor={{ fill: 'rgb(128 128 128 / 0.12)' }} />
              <Bar dataKey="grossBookings" name="₹" fill={CHART.accent} radius={[6, 6, 0, 0]} />
            </BarChart>
          </ChartCard>
        )}
        {hours.data && (
          <ChartCard title="Demand by hour of day" label="Bar chart of rides requested per hour">
            <BarChart data={hours.data}>
              <CartesianGrid stroke={CHART.grid} vertical={false} />
              <XAxis dataKey="hour" stroke={CHART.axis} fontSize={12} />
              <YAxis allowDecimals={false} stroke={CHART.axis} fontSize={12} />
              <Tooltip contentStyle={tooltip} cursor={{ fill: 'rgb(128 128 128 / 0.12)' }} />
              <Bar dataKey="rides" name="Rides" fill={CHART.accent} radius={[6, 6, 0, 0]} />
            </BarChart>
          </ChartCard>
        )}
        {categories.data && (
          <ChartCard title="Revenue by vehicle type (₹)" label="Bar chart of gross bookings per vehicle category">
            <BarChart data={categories.data.map((c) => ({ ...c, name: CATEGORY_LABEL[c.category] ?? c.category }))}>
              <CartesianGrid stroke={CHART.grid} vertical={false} />
              <XAxis dataKey="name" stroke={CHART.axis} fontSize={12} />
              <YAxis stroke={CHART.axis} fontSize={12} />
              <Tooltip contentStyle={tooltip} cursor={{ fill: 'rgb(128 128 128 / 0.12)' }} />
              <Bar dataKey="grossBookings" name="₹" fill={CHART.accent} radius={[6, 6, 0, 0]} />
            </BarChart>
          </ChartCard>
        )}
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card>
          <CardContent>
            <p className="mb-3 font-semibold text-fg">Busiest zones</p>
            {zones.data?.length === 0 && <p className="text-sm text-fg-muted">No data yet.</p>}
            <ul className="flex flex-col gap-2 text-sm">
              {zones.data?.map((z) => (
                <li key={z.zone} className="flex justify-between gap-2">
                  <span className="text-fg">Zone {z.zone}</span>
                  <span className="tabular text-fg-muted">{z.requests} requests · {z.completed} done · surge {z.averageSurge}x</span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <p className="mb-3 font-semibold text-fg">Top drivers</p>
            {top.data?.length === 0 && <p className="text-sm text-fg-muted">No data yet.</p>}
            <ul className="flex flex-col gap-2 text-sm">
              {top.data?.map((d) => (
                <li key={d.driverUserId} className="flex justify-between gap-2">
                  <span className="truncate font-mono text-xs text-fg-secondary">{d.driverUserId}</span>
                  <span className="tabular text-fg-muted">{d.rides} rides · {formatINR(d.grossBookings, { whole: true })}</span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      </div>
    </>
  )
}

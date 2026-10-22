import { Gift, IndianRupee, Route, TrendingUp } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import type { EarningsSummary } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CHART, tooltipStyle } from '@/lib/chartTheme'
import { formatDateTime, formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

/** What the driver has earned: today, the week, the month, a 30-day chart and the latest trips. */
export function EarningsPage() {
  const { data, error } = usePolling(async () => (await api.get<EarningsSummary>('/api/driver/earnings')).data, { intervalMs: 30000 })

  return (
    <>
      <PageHeader eyebrow="Driver" title="Earnings" description="Your share of each fare after the platform commission, plus any bonuses." />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      {!data ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard icon={IndianRupee} label={`Today · ${data.tripsToday} trips`} value={formatINR(data.today)} />
            <StatCard icon={TrendingUp} label={`Last 7 days · ${data.trips7Days} trips`} value={formatINR(data.last7Days)} />
            <StatCard icon={Route} label={`Last 30 days · ${data.trips30Days} trips`} value={formatINR(data.last30Days)} />
            <StatCard icon={Gift} label="Bonuses (30 days)" value={formatINR(data.bonuses30Days)} />
          </div>
          <p className="mt-3 text-sm text-fg-muted">Balance on your account: <span className="tabular font-semibold text-fg">{formatINR(data.balance)}</span></p>

          <Card className="mt-6">
            <CardContent>
              <p className="mb-4 font-semibold text-fg">Last 30 days</p>
              <div className="h-64" role="img" aria-label="Bar chart of earnings per day">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={data.daily.map((d) => ({ ...d, label: d.day.slice(5) }))}>
                    <CartesianGrid stroke={CHART.grid} vertical={false} />
                    <XAxis dataKey="label" stroke={CHART.axis} fontSize={12} interval={4} />
                    <YAxis stroke={CHART.axis} fontSize={12} />
                    <Tooltip contentStyle={tooltipStyle} cursor={{ fill: '#ffffff10' }} />
                    <Bar dataKey="amount" name="Earned (₹)" fill={CHART.accent} radius={[6, 6, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>

          <p className="mb-3 mt-6 font-semibold text-fg">Recent trips</p>
          {data.recent.length === 0 ? (
            <EmptyState icon={Route} title="No earnings yet" description="Complete a trip and it appears here." />
          ) : (
            <ul className="flex flex-col gap-2">
              {data.recent.map((t) => (
                <li key={`${t.rideId}${t.at}`}>
                  <Link to={`/rides/${t.rideId}`} className="flex items-center justify-between rounded-lg panel border border-border p-3 hover:border-fg-muted">
                    <span className="text-sm text-fg-secondary">{formatDateTime(t.at)}</span>
                    <span className="tabular font-semibold text-fg">{formatINR(t.amount)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  )
}

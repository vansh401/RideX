import { BadgeCheck, Car, IndianRupee, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import type { AdminStats } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CHART, tooltipStyle } from '@/lib/chartTheme'
import { formatINR } from '@/lib/format'
import { STATUS_LABEL } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

export function AdminOverview() {
  const { data, error } = usePolling(async () => (await api.get<AdminStats>('/api/admin/stats')).data, { intervalMs: 15000 })

  const chart = data
    ? Object.entries(data.ridesByStatus)
        .map(([status, count]) => ({ status: STATUS_LABEL[status as keyof typeof STATUS_LABEL] ?? status, count }))
        .filter((row) => row.count > 0)
    : []
  const users = data ? Object.values(data.usersByRole).reduce((a, b) => a + b, 0) : 0

  return (
    <>
      <PageHeader eyebrow="Admin" title="Admin console" description="Live numbers from the database." />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      {!data ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard icon={Users} label={`Users (${data.suspendedUsers} suspended)`} value={String(users)} />
            <StatCard icon={Car} label="Drivers online" value={String(data.onlineDrivers)} />
            <StatCard icon={BadgeCheck} label="Applications to review" value={String(data.pendingDriverApplications)} />
            <StatCard icon={IndianRupee} label="Completed revenue" value={formatINR(data.completedRevenue, { whole: true })} />
          </div>
          {data.pendingDriverApplications > 0 && (
            <Alert kind="info" className="mt-4">
              {data.pendingDriverApplications} driver application(s) waiting.{' '}
              <Link to="/admin/drivers" className="font-semibold underline">Review them</Link>
            </Alert>
          )}
          <Card className="mt-6">
            <CardContent>
              <p className="mb-1 font-semibold text-fg">Rides by status</p>
              <p className="mb-4 text-sm text-fg-muted">{data.ridesInLast24Hours} requested in the last 24 hours</p>
              {chart.length === 0 ? (
                <p className="text-sm text-fg-muted">No rides yet.</p>
              ) : (
                <div className="h-64" role="img" aria-label="Bar chart of rides by status">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={chart}>
                      <CartesianGrid stroke={CHART.grid} vertical={false} />
                      <XAxis dataKey="status" stroke={CHART.axis} fontSize={12} />
                      <YAxis allowDecimals={false} stroke={CHART.axis} fontSize={12} />
                      <Tooltip contentStyle={tooltipStyle} cursor={{ fill: '#ffffff10' }} />
                      <Bar dataKey="count" fill={CHART.accent} radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </>
  )
}

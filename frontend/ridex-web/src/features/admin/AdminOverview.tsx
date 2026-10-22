import {
  ArrowRight,
  BarChart3,
  Car,
  CheckCircle2,
  IndianRupee,
  LifeBuoy,
  ShieldAlert,
  Ticket,
  UserCheck,
  Users,
  Wallet,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import type { AdminStats, PageResponse, Ride, SosAlert } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { RideStatusBadge } from '@/components/brand/StatusBadge'
import { StatCard } from '@/components/brand/StatCard'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CHART, tooltipStyle } from '@/lib/chartTheme'
import { formatINR } from '@/lib/format'
import { isActiveRide } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

interface Day { day: string; requested: number; completed: number; grossBookings: number }
interface Hour { hour: number; rides: number }
interface RuleSummary { open: number }

/** Each side data source fails on its own: a missing analytics service empties two cards, not the page. */
async function optional<T>(request: () => Promise<T>): Promise<T | null> {
  try {
    return await request()
  } catch {
    return null
  }
}

function ChartCard({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return (
    <Card className="min-w-0">
      <CardContent className="p-5">
        <p className="font-semibold text-fg">{title}</p>
        <p className="mb-3 text-sm text-fg-muted">{description}</p>
        {children}
      </CardContent>
    </Card>
  )
}

function Unavailable() {
  return <p className="grid h-44 place-items-center text-sm text-fg-muted">No data to show yet.</p>
}

function AttentionRow({ icon: Icon, label, count, to, tone }: { icon: LucideIcon; label: string; count: number | null; to: string; tone: string }) {
  return (
    <Link
      to={to}
      className={`group flex items-center justify-between gap-3 rounded-md border px-3.5 py-3 transition-[transform,background-color] duration-(--dur-base) hover:translate-x-0.5 ${tone}`}
    >
      <span className="flex items-center gap-3 text-sm font-medium text-fg">
        <Icon className="size-4 shrink-0" aria-hidden /> {label}
      </span>
      <span className="tabular rounded-full bg-black/25 px-2.5 py-0.5 text-sm font-bold text-fg">{count ?? '–'}</span>
    </Link>
  )
}

function QuickLink({ to, icon: Icon, label }: { to: string; icon: LucideIcon; label: string }) {
  return (
    <Link
      to={to}
      className="neu group flex items-center justify-between gap-3 rounded-md px-3.5 py-3 text-sm font-medium text-fg-secondary hover:text-fg"
    >
      <span className="flex items-center gap-3">
        <Icon className="size-4 text-accent" aria-hidden /> {label}
      </span>
      <ArrowRight className="size-4 transition-transform duration-(--dur-base) group-hover:translate-x-1" aria-hidden />
    </Link>
  )
}

export function AdminOverview() {
  const poll = { intervalMs: 20000 }
  const stats = usePolling(async () => (await api.get<AdminStats>('/api/admin/stats')).data, { intervalMs: 15000 })
  const daily = usePolling(() => optional(async () => (await api.get<Day[]>('/api/admin/analytics/daily?days=7')).data), poll)
  const hours = usePolling(() => optional(async () => (await api.get<Hour[]>('/api/admin/analytics/hours?days=7')).data), poll)
  const rides = usePolling(() => optional(async () => (await api.get<PageResponse<Ride>>('/api/admin/rides?size=30')).data), poll)
  const complaints = usePolling(() => optional(async () => (await api.get<PageResponse<unknown>>('/api/admin/complaints?status=OPEN&size=1')).data), poll)
  const sos = usePolling(() => optional(async () => (await api.get<PageResponse<SosAlert>>('/api/admin/sos?status=OPEN&size=3')).data), { intervalMs: 10000 })
  const fraud = usePolling(() => optional(async () => (await api.get<RuleSummary[]>('/api/admin/fraud/summary')).data), poll)

  const s = stats.data
  const ongoing = (rides.data?.content ?? []).filter((ride) => isActiveRide(ride.status)).slice(0, 4)
  const openFraud = fraud.data ? fraud.data.reduce((sum, rule) => sum + Number(rule.open), 0) : null
  const week = (daily.data ?? []).map((d) => ({ ...d, label: d.day.slice(5) }))
  const hourly = (hours.data ?? []).map((h) => ({ ...h, label: `${h.hour}h` }))

  return (
    <>
      <PageHeader eyebrow="Admin" title="Admin console" description="Live numbers from the database and the event stream." />
      {stats.error && <Alert kind="error" className="mb-4">{stats.error}</Alert>}

      {/* Row 1: the four numbers to glance at */}
      {!s ? (
        <Skeleton className="h-32" />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard icon={Users} label={`Total riders (${s.suspendedUsers} suspended users)`} value={String(s.usersByRole.RIDER ?? 0)} />
          <StatCard icon={Car} label={`Total drivers (${s.onlineDrivers} online now)`} value={String(s.usersByRole.DRIVER ?? 0)} />
          <StatCard icon={Zap} label="Rides requested, last 24 hours" value={String(s.ridesInLast24Hours)} />
          <StatCard icon={IndianRupee} label="Completed revenue" value={formatINR(s.completedRevenue, { whole: true })} />
        </div>
      )}

      {/* Row 2: two charts side by side, each in its own card */}
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <ChartCard title="Weekly revenue" description="Gross bookings per day, last 7 days">
          {week.length === 0 ? (
            <Unavailable />
          ) : (
            <div className="h-44" role="img" aria-label="Area chart of gross bookings per day for the last 7 days">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={week} margin={{ left: -12, right: 4, top: 6 }}>
                  <defs>
                    <linearGradient id="rev" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={CHART.accent} stopOpacity={0.55} />
                      <stop offset="100%" stopColor={CHART.accent} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke={CHART.grid} vertical={false} />
                  <XAxis dataKey="label" stroke={CHART.axis} fontSize={12} tickLine={false} axisLine={false} />
                  <YAxis stroke={CHART.axis} fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip contentStyle={tooltipStyle} formatter={(value) => [formatINR(Number(value)), 'Gross bookings']} />
                  <Area type="monotone" dataKey="grossBookings" stroke={CHART.accent} strokeWidth={2.5} fill="url(#rev)" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </ChartCard>

        <ChartCard title="Hourly rides" description="Rides requested by hour of day, last 7 days">
          {hourly.length === 0 ? (
            <Unavailable />
          ) : (
            <div className="h-44" role="img" aria-label="Bar chart of rides requested per hour of the day">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={hourly} margin={{ left: -12, right: 4, top: 6 }}>
                  <CartesianGrid stroke={CHART.grid} vertical={false} />
                  <XAxis dataKey="label" stroke={CHART.axis} fontSize={11} tickLine={false} axisLine={false} interval={2} />
                  <YAxis allowDecimals={false} stroke={CHART.axis} fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip contentStyle={tooltipStyle} cursor={{ fill: '#ffffff10' }} />
                  <Bar dataKey="rides" name="Rides" fill={CHART.secondary} radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </ChartCard>
      </div>

      {/* Row 3: what is happening now, what needs a decision, where to go next */}
      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <Card className="min-w-0">
          <CardContent className="flex h-full flex-col p-5">
            <p className="mb-3 font-semibold text-fg">Ongoing rides</p>
            {ongoing.length === 0 ? (
              <p className="grid flex-1 place-items-center py-6 text-sm text-fg-muted">No rides in progress right now.</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {ongoing.map((ride) => (
                  <li key={ride.id}>
                    <Link to={`/rides/${ride.id}`} className="flex items-center justify-between gap-3 rounded-md bg-white/[0.04] px-3 py-2.5 hover:bg-white/[0.08]">
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-semibold text-fg">{ride.rider.fullName}</span>
                        <span className="block truncate text-xs text-fg-muted">
                          {ride.pickup.address} → {ride.dropoff.address}
                        </span>
                      </span>
                      <RideStatusBadge status={ride.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            <Link to="/admin/rides" className="mt-auto pt-3 text-center text-sm font-semibold text-accent hover:underline">
              View all rides
            </Link>
          </CardContent>
        </Card>

        <Card className="min-w-0">
          <CardContent className="p-5">
            <p className="mb-3 font-semibold text-fg">Needs attention</p>
            {sos.data && sos.data.content.length > 0 && (
              <Link to="/admin/safety" className="mb-3 block rounded-md border border-danger/40 bg-danger/10 px-3.5 py-3 text-sm text-fg hover:bg-danger/15" role="alert">
                <span className="flex items-center gap-2 font-semibold text-danger">
                  <ShieldAlert className="size-4" aria-hidden /> {sos.data.totalElements} open SOS alert{sos.data.totalElements === 1 ? '' : 's'}
                </span>
                <span className="mt-1 block text-fg-secondary">
                  {sos.data.content.map((a) => a.raisedByName).join(', ')} need{sos.data.totalElements === 1 ? 's' : ''} help. Open the alert.
                </span>
              </Link>
            )}
            <div className="flex flex-col gap-2.5">
              <AttentionRow icon={UserCheck} label="Drivers awaiting approval" count={s?.pendingDriverApplications ?? null} to="/admin/drivers" tone="border-warning/25 bg-warning/10" />
              <AttentionRow icon={LifeBuoy} label="Open complaints" count={complaints.data ? complaints.data.totalElements : null} to="/admin/complaints" tone="border-danger/25 bg-danger/10" />
              <AttentionRow icon={ShieldAlert} label="Fraud signals to review" count={openFraud} to="/admin/fraud" tone="border-info/25 bg-info/10" />
            </div>
            {s && s.pendingDriverApplications === 0 && complaints.data?.totalElements === 0 && openFraud === 0 && (
              <p className="mt-3 flex items-center gap-2 text-sm text-success">
                <CheckCircle2 className="size-4" aria-hidden /> Nothing is waiting for you.
              </p>
            )}
          </CardContent>
        </Card>

        <Card className="min-w-0">
          <CardContent className="p-5">
            <p className="mb-3 font-semibold text-fg">Quick actions</p>
            <div className="flex flex-col gap-2.5">
              <QuickLink to="/admin/drivers" icon={UserCheck} label="Review driver applications" />
              <QuickLink to="/admin/coupons" icon={Ticket} label="Manage offers" />
              <QuickLink to="/admin/reports" icon={BarChart3} label="Open full reports" />
              <QuickLink to="/admin/payouts" icon={Wallet} label="Driver payouts" />
              <QuickLink to="/admin/finance" icon={BarChart3} label="Finance and ledger" />
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  )
}

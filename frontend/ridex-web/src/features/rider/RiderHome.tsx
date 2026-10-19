import { Car, History } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { PageResponse, Ride } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { PageHeader } from '@/components/brand/PageHeader'
import { RideStatusBadge } from '@/components/brand/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatDateTime, formatINR } from '@/lib/format'
import { isActiveRide } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

export function RiderHome() {
  const { user } = useAuth()
  const { data, error } = usePolling(
    async () => (await api.get<PageResponse<Ride>>('/api/rides?size=3')).data,
    { intervalMs: 10000 },
  )
  const active = data?.content.find((r) => isActiveRide(r.status))

  return (
    <>
      <PageHeader eyebrow="Rider" title={`Hello, ${user?.fullName.split(' ')[0] ?? 'there'}`} description="Where to today?" />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}

      <Card className="mb-6">
        <CardContent className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Car className="size-6 text-accent" aria-hidden />
            <p className="font-semibold text-fg">{active ? 'You have a ride in progress' : 'Need a ride?'}</p>
          </div>
          <Button asChild>
            <Link to={active ? `/rides/${active.id}` : '/rider/book'}>{active ? 'View ride' : 'Book a ride'}</Link>
          </Button>
        </CardContent>
      </Card>

      <div className="mb-3 flex items-center justify-between">
        <p className="flex items-center gap-2 font-semibold text-fg"><History className="size-4 text-accent" aria-hidden /> Recent rides</p>
        <Link to="/rider/rides" className="text-sm text-accent hover:underline">See all</Link>
      </div>
      {data?.content.length === 0 && <p className="text-sm text-fg-muted">No rides yet. Your first one will show up here.</p>}
      <ul className="flex flex-col gap-3">
        {data?.content.map((ride) => (
          <li key={ride.id}>
            <Link to={`/rides/${ride.id}`} className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface p-4 hover:border-fg-muted">
              <div className="min-w-0 flex-1">
                <p className="truncate text-fg">{ride.dropoff.address}</p>
                <p className="text-sm text-fg-muted">{formatDateTime(ride.requestedAt)}</p>
              </div>
              <RideStatusBadge status={ride.status} />
              <span className="tabular font-bold text-fg">{formatINR(ride.fare.total)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}

import { Link, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { TripView } from '@/api/types'
import { Logo } from '@/components/brand/Logo'
import { RideStatusBadge } from '@/components/brand/StatusBadge'
import { RideMap, type MapMarker } from '@/components/map/RideMap'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

/**
 * Follow a trip somebody shared with you. No login: the link itself is the key, it expires after 24 hours, and the page shows only what
 * a friend needs (where the car is, the driver's first name, the vehicle and plate). It refreshes every few seconds.
 */
export function TripPage() {
  const { token } = useParams()
  const { data, error } = usePolling<TripView>(async () => (await api.get<TripView>(`/api/public/trips/${token}`)).data, { intervalMs: 5000 })

  const markers: MapMarker[] = data
    ? [
        { id: 'pickup', kind: 'pickup', latitude: data.pickupLatitude, longitude: data.pickupLongitude },
        { id: 'dropoff', kind: 'dropoff', latitude: data.dropoffLatitude, longitude: data.dropoffLongitude },
        ...(data.position ? [{ id: 'driver', kind: 'driver' as const, latitude: data.position.latitude, longitude: data.position.longitude }] : []),
      ]
    : []

  return (
    <div className="mx-auto flex min-h-screen max-w-3xl flex-col gap-4 px-4 py-6">
      <Link to="/" aria-label="RideX home">
        <Logo />
      </Link>
      <h1 className="text-2xl font-bold tracking-tight text-gradient">Following a trip</h1>
      {error && !data && <Alert kind="error">This link is not valid or has expired. Ask the person to share the trip again.</Alert>}
      {!data && !error && <Skeleton className="h-72" />}
      {data && (
        <>
          <RideMap markers={markers} showRoute label="Map of the shared trip" className="h-72 sm:h-96" />
          <Card>
            <CardContent className="flex flex-col gap-3">
              <div className="flex items-center justify-between gap-3">
                <p className="font-semibold text-fg">{data.driverFirstName ? `Driver: ${data.driverFirstName}` : 'Looking for a driver'}</p>
                <RideStatusBadge status={data.status} />
              </div>
              {data.vehicle && (
                <p className="text-sm text-fg-secondary">
                  {data.vehicle.description} · <span className="tabular font-semibold text-fg">{data.vehicle.registrationNumber}</span>
                </p>
              )}
              <p className="text-sm text-fg-secondary">
                <span className="text-fg-muted">From</span> {data.pickupAddress}
              </p>
              <p className="text-sm text-fg-secondary">
                <span className="text-fg-muted">To</span> {data.dropoffAddress}
              </p>
              <p className="text-xs text-fg-muted">This link works until {formatDateTime(data.expiresAt)}. Refreshes by itself.</p>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

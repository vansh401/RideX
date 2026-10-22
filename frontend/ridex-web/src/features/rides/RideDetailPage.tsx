import { ArrowLeft, Phone } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { DriverPosition, LiveRideUpdate, Ride, RideStatus, TimelineEntry } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { ConfirmDialog } from '@/components/brand/ConfirmDialog'
import { PageHeader } from '@/components/brand/PageHeader'
import { RideStatusBadge } from '@/components/brand/StatusBadge'
import { RideMap, type MapMarker } from '@/components/map/RideMap'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import {
  CATEGORY_LABEL,
  STATUS_LABEL,
  driverCanCancel,
  formatDistance,
  formatDuration,
  isActiveRide,
  riderCanCancel,
} from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'
import { useRealtime, useSubscription } from '@/realtime/useRealtime'
import { FareBreakdown } from './FareBreakdown'
import { RideProgress } from './RideProgress'
import { PaymentCard } from './PaymentCard'
import { RatingForm } from './RatingForm'
import { ChatPanel } from './ChatPanel'
import { ComplaintSection } from './ComplaintSection'
import { SafetyPanel } from './SafetyPanel'
import { RatingValue } from './StarRating'

/** Chat opens once a driver has accepted, and stays for a short while after the trip. */
const CHAT_OPEN: RideStatus[] = ['DRIVER_ACCEPTED', 'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED']

/** One ride, for whoever is looking at it. The server decides what each role may see and do. */
export function RideDetailPage() {
  const { rideId } = useParams()
  const { user } = useAuth()
  const role = user?.role
  const [cancelOpen, setCancelOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [timeline, setTimeline] = useState<TimelineEntry[]>([])

  const { state: connection } = useRealtime()
  const live = connection === 'live'

  // While the live connection works, changes are PUSHED to us, so the periodic refresh is only a safety net
  // (slow). If the connection drops, we go back to asking every few seconds, so nothing is ever missed.
  const { data: ride, error, loading, refresh } = usePolling<Ride>(
    async () => (await api.get<Ride>(`/api/rides/${rideId}`)).data,
    { intervalMs: live ? 20_000 : 4000, enabled: Boolean(rideId) },
  )

  // The push says "ride X changed"; the details come from the REST API (it knows what this person may see)
  useSubscription<LiveRideUpdate>('/user/queue/ride-updates', (update) => {
    if (update.rideId === rideId) void refresh()
  })

  const status = ride?.status
  const driverOnTheWay =
    role !== 'DRIVER' && (status === 'DRIVER_ACCEPTED' || status === 'DRIVER_ARRIVED' || status === 'RIDE_STARTED')

  // The driver's position: the last known one first, then every update the driver's app streams
  const [driverPosition, setDriverPosition] = useState<DriverPosition | null>(null)
  useSubscription<DriverPosition>(`/topic/rides/${rideId}/location`, (position) => setDriverPosition(position), driverOnTheWay && Boolean(rideId))
  useEffect(() => {
    if (!driverOnTheWay || !rideId) return
    let cancelled = false
    api
      .get<DriverPosition | ''>(`/api/rides/${rideId}/driver-location`)
      .then((response) => {
        if (!cancelled && response.data) setDriverPosition(response.data)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [driverOnTheWay, rideId])
  // The audit trail is reloaded whenever the status changes
  useEffect(() => {
    if (!rideId || !status) return
    api
      .get<TimelineEntry[]>(`/api/rides/${rideId}/timeline`)
      .then((response) => setTimeline(response.data))
      .catch(() => undefined)
  }, [rideId, status])

  const markers = useMemo<MapMarker[]>(() => {
    if (!ride) return []
    const list: MapMarker[] = [
      { id: 'pickup', kind: 'pickup', latitude: ride.pickup.latitude, longitude: ride.pickup.longitude },
      { id: 'dropoff', kind: 'dropoff', latitude: ride.dropoff.latitude, longitude: ride.dropoff.longitude },
    ]
    if (driverOnTheWay && driverPosition) {
      list.push({ id: 'driver', kind: 'driver', latitude: driverPosition.latitude, longitude: driverPosition.longitude })
    }
    return list
  }, [ride, driverOnTheWay, driverPosition])

  async function act(action: 'arrived' | 'start' | 'complete', done: string) {
    setBusy(true)
    try {
      await api.post(`/api/rides/${rideId}/${action}`)
      toast.success(done)
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
      await refresh()
    } finally {
      setBusy(false)
    }
  }

  async function nextStop() {
    setBusy(true)
    try {
      await api.post(`/api/rides/${rideId}/stops/next`)
      toast.success('Stop reached')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  async function cancel(reason: string) {
    try {
      await api.post(`/api/rides/${rideId}/cancel`, reason ? { reason } : {})
      toast.success('Ride cancelled')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
      throw err
    }
  }

  const back = role === 'ADMIN' ? '/admin/rides' : role === 'DRIVER' ? '/driver/history' : '/rider/rides'

  if (loading && !ride) {
    return <Skeleton className="h-96" />
  }
  if (!ride) {
    return (
      <>
        <PageHeader title="Ride not found" />
        <Alert kind="error">{error ?? 'This ride does not exist or is not yours.'}</Alert>
      </>
    )
  }

  const active = isActiveRide(ride.status)
  const canCancel = role === 'RIDER' ? riderCanCancel(ride.status) : role === 'DRIVER' ? driverCanCancel(ride.status) : false
  const other = role === 'DRIVER' ? ride.rider : ride.driver

  return (
    <>
      <Link to={back} className="mb-4 inline-flex items-center gap-1 text-sm text-fg-secondary hover:text-fg">
        <ArrowLeft className="size-4" aria-hidden /> Back
      </Link>
      <PageHeader
        eyebrow={`Ride · ${CATEGORY_LABEL[ride.category]}`}
        title={STATUS_LABEL[ride.status]}
        actions={<RideStatusBadge status={ride.status} />}
      />

      {error && <Alert kind="error" className="mb-4">{error}</Alert>}

      {ride.status !== 'CANCELLED' && ride.status !== 'SCHEDULED' && (
        <Card className="mb-6">
          <CardContent>
            <RideProgress status={ride.status} />
          </CardContent>
        </Card>
      )}

      {ride.status === 'SCHEDULED' && ride.scheduledFor && (
        <Alert kind="info" className="mb-6">
          Booked for {formatDateTime(ride.scheduledFor)}. We start looking for a driver shortly before that time, and you can cancel for free until then.
        </Alert>
      )}

      {ride.status === 'CANCELLED' && (
        <Alert kind="info" className="mb-6">
          Cancelled by {ride.cancelledBy?.toLowerCase() ?? 'the system'}
          {ride.cancelReason ? `: ${ride.cancelReason}` : '.'}
        </Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,24rem)]">
        <div className="flex flex-col gap-6">
          <RideMap markers={markers} showRoute label="Map of the ride" className="h-72 sm:h-96" />

          <Card>
            <CardContent className="flex flex-col gap-4">
              <div>
                <p className="eyebrow">Pickup</p>
                <p className="text-fg">{ride.pickup.address}</p>
              </div>
              <div>
                <p className="eyebrow">Destination</p>
                <p className="text-fg">{ride.dropoff.address}</p>
              </div>
              <p className="tabular text-sm text-fg-muted">
                {formatDistance(ride.distanceKm)} · about {formatDuration(ride.durationMinutes)} · requested {formatDateTime(ride.requestedAt)}
              </p>
            </CardContent>
          </Card>

          {ride.stops && ride.stops.length > 0 && (
            <Card>
              <CardContent>
                <p className="mb-3 font-semibold text-fg">Stops on the way</p>
                <ol className="flex flex-col gap-2 text-sm">
                  {ride.stops.map((stop) => (
                    <li key={stop.position} className="flex justify-between gap-2">
                      <span className="text-fg">{stop.position}. {stop.address}</span>
                      <span className="text-fg-muted">{stop.arrivedAt ? 'Reached' : 'Waiting'}</span>
                    </li>
                  ))}
                </ol>
              </CardContent>
            </Card>
          )}

          {timeline.length > 0 && (
            <Card>
              <CardContent>
                <p className="mb-3 font-semibold text-fg">Timeline</p>
                <ol className="flex flex-col gap-2 text-sm">
                  {timeline.map((entry, i) => (
                    <li key={i} className="flex flex-wrap justify-between gap-2">
                      <span className="text-fg">
                        {STATUS_LABEL[entry.to]}
                        <span className="text-fg-muted"> · {entry.actorRole.toLowerCase()}{entry.note ? ` · ${entry.note}` : ''}</span>
                      </span>
                      <time className="tabular text-fg-muted" dateTime={entry.at}>{formatDateTime(entry.at)}</time>
                    </li>
                  ))}
                </ol>
              </CardContent>
            </Card>
          )}
        </div>

        <div className="flex flex-col gap-6">
          {other && (
            <Card>
              <CardContent className="flex flex-col gap-2">
                <p className="eyebrow">{role === 'DRIVER' ? 'Your rider' : 'Your driver'}</p>
                <p className="text-lg font-semibold text-fg">{other.fullName}</p>
                {role !== 'DRIVER' && ride.driver && <RatingValue average={ride.driver.ratingAverage} count={ride.driver.ratingCount} />}
                {ride.vehicle && role !== 'DRIVER' && (
                  <p className="text-sm text-fg-secondary">
                    {ride.vehicle.color} {ride.vehicle.make} {ride.vehicle.model} ·{' '}
                    <span className="tabular font-semibold text-fg">{ride.vehicle.registrationNumber}</span>
                  </p>
                )}
                {other.phone && active && (
                  <a href={`tel:${other.phone}`} className="inline-flex items-center gap-2 text-sm text-accent hover:underline">
                    <Phone className="size-4" aria-hidden /> {other.phone}
                  </a>
                )}
              </CardContent>
            </Card>
          )}

          {!other && active && role === 'RIDER' && (
            <Alert kind="info">We are looking for a nearby driver. This page updates by itself.</Alert>
          )}

          <Card>
            <CardContent>
              <p className="mb-3 font-semibold text-fg">Fare</p>
              <FareBreakdown fare={ride.fare} />
              {ride.couponCode && <p className="mt-3 text-sm text-fg-secondary">Offer {ride.couponCode} is applied when the trip ends.</p>}
              <p className="mt-3 text-xs text-fg-muted">Charged automatically when the trip ends (wallet first, then card).</p>
            </CardContent>
          </Card>

          {role === 'DRIVER' && (
            <div className="flex flex-col gap-3">
              {ride.status === 'DRIVER_ACCEPTED' && (
                <Button size="lg" loading={busy} onClick={() => act('arrived', 'Rider notified')}>I have arrived</Button>
              )}
              {ride.status === 'DRIVER_ARRIVED' && (
                <Button size="lg" loading={busy} onClick={() => act('start', 'Trip started')}>Start trip</Button>
              )}
              {ride.status === 'RIDE_STARTED' && (ride.stopsDone ?? 0) < (ride.stops?.length ?? 0) && (
                <Button size="lg" loading={busy} onClick={nextStop}>Reached next stop</Button>
              )}
              {ride.status === 'RIDE_STARTED' && (ride.stopsDone ?? 0) >= (ride.stops?.length ?? 0) && (
                <Button size="lg" loading={busy} onClick={() => act('complete', 'Trip completed')}>Complete trip</Button>
              )}
            </div>
          )}

          {(role === 'RIDER' || role === 'DRIVER') && active && <SafetyPanel rideId={ride.id} />}

          {(role === 'RIDER' || role === 'DRIVER') && other && CHAT_OPEN.includes(ride.status) && (
            <ChatPanel rideId={ride.id} otherName={other.fullName.split(' ')[0]} />
          )}

          {canCancel && (
            <Button variant="danger" onClick={() => setCancelOpen(true)}>Cancel ride</Button>
          )}

          {ride.status === 'RIDE_COMPLETED' && <PaymentCard rideId={ride.id} canRetry={role === 'RIDER'} />}

          {(ride.status === 'RIDE_COMPLETED' || ride.status === 'RIDE_STARTED') && (role === 'RIDER' || role === 'DRIVER') && (
            <ComplaintSection rideId={ride.id} />
          )}

          {ride.status === 'RIDE_COMPLETED' && (role === 'RIDER' || role === 'DRIVER') && (
            <RatingForm rideId={ride.id} role={role} existing={ride.myRating} onSaved={refresh} />
          )}
        </div>
      </div>

      <ConfirmDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="Cancel this ride?"
        description={
          role === 'RIDER' && ride.status === 'DRIVER_ARRIVED'
            ? 'Your driver is already at the pickup, so a small cancellation fee is taken from your wallet if it holds enough. This cannot be undone.'
            : 'This cannot be undone.'
        }
        confirmLabel="Cancel ride"
        danger
        askReason
        reasonRequired={false}
        onConfirm={cancel}
      />
    </>
  )
}

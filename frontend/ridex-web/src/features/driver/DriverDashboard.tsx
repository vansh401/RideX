import { Check, Circle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Availability, DriverDocument, DriverProfile, LatLng, Ride, Vehicle } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { ApprovalBadge } from '@/components/brand/StatusBadge'
import { RideMap, type MapMarker } from '@/components/map/RideMap'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { TextField } from '@/components/ui/text-field'
import { formatINR } from '@/lib/format'
import { CATEGORY_LABEL, formatDistance, secondsUntil } from '@/lib/rides'
import { usePolling } from '@/lib/usePolling'

const HEARTBEAT_MS = 10_000

interface Snapshot {
  profile: DriverProfile | null
  documents: DriverDocument[]
  vehicles: Vehicle[]
  availability: Availability | null
  ride: Ride | null
}

async function load(): Promise<Snapshot> {
  const [profile, documents, vehicles, availability, ride] = await Promise.all([
    api.get<DriverProfile>('/api/drivers/me').then((r) => r.data).catch(() => null),
    api.get<DriverDocument[]>('/api/drivers/me/documents').then((r) => r.data).catch(() => []),
    api.get<Vehicle[]>('/api/drivers/me/vehicles').then((r) => r.data).catch(() => []),
    api.get<Availability>('/api/drivers/me/availability').then((r) => r.data).catch(() => null),
    api.get<Ride | ''>('/api/rides/current').then((r) => (r.data || null)).catch(() => null),
  ])
  return { profile, documents, vehicles, availability, ride }
}

function Step({ done, to, children }: { done: boolean; to: string; children: string }) {
  return (
    <li className="flex items-center gap-3">
      {done ? <Check className="size-5 text-success" aria-hidden /> : <Circle className="size-5 text-fg-muted" aria-hidden />}
      <Link to={to} className={done ? 'text-fg-secondary line-through' : 'text-fg hover:underline'}>
        {children}
      </Link>
    </li>
  )
}

/** Driver home: onboarding checklist, the online switch, the incoming offer and the current trip. */
export function DriverDashboard() {
  const navigate = useNavigate()
  const { data, error, refresh } = usePolling(load, { intervalMs: 5000 })
  const [position, setPosition] = useState<LatLng | null>(null)
  const [manualLat, setManualLat] = useState('12.9716')
  const [manualLng, setManualLng] = useState('77.5946')
  const [busy, setBusy] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const positionRef = useRef(position)

  useEffect(() => {
    positionRef.current = position
  })

  const online = data?.availability?.online ?? false

  // Share the live location while online (browser GPS when allowed; otherwise the manual point)
  const heartbeat = useCallback(async () => {
    const point = positionRef.current
    if (!point) return
    try {
      await api.put('/api/drivers/me/availability/location', point)
    } catch {
      // a missed beat is harmless; the next one follows
    }
  }, [])

  useEffect(() => {
    if (!online) return
    const timer = window.setInterval(() => {
      if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          (p) => {
            positionRef.current = { latitude: Number(p.coords.latitude.toFixed(6)), longitude: Number(p.coords.longitude.toFixed(6)) }
            void heartbeat()
          },
          () => void heartbeat(),
          { timeout: 5000 },
        )
      } else {
        void heartbeat()
      }
    }, HEARTBEAT_MS)
    return () => window.clearInterval(timer)
  }, [online, heartbeat])

  // Counts down the offer timer
  const offered = data?.ride?.status === 'DRIVER_ASSIGNED'
  useEffect(() => {
    if (!offered) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [offered])

  function chosenPoint(): LatLng | null {
    const latitude = Number(manualLat)
    const longitude = Number(manualLng)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null
    return { latitude, longitude }
  }

  async function goOnline() {
    const point = position ?? chosenPoint()
    if (!point) {
      toast.error('Enter a valid latitude and longitude')
      return
    }
    setBusy(true)
    try {
      await api.post('/api/drivers/me/availability/online', point)
      setPosition(point)
      positionRef.current = point
      toast.success('You are online')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  function useGps() {
    navigator.geolocation?.getCurrentPosition(
      (p) => {
        const point = { latitude: Number(p.coords.latitude.toFixed(6)), longitude: Number(p.coords.longitude.toFixed(6)) }
        setPosition(point)
        setManualLat(String(point.latitude))
        setManualLng(String(point.longitude))
      },
      () => toast.error('Could not read your location. Type it in instead.'),
      { timeout: 8000 },
    )
  }

  async function goOffline() {
    setBusy(true)
    try {
      await api.post('/api/drivers/me/availability/offline')
      toast.success('You are offline')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  async function respond(rideId: string, accept: boolean) {
    setBusy(true)
    try {
      if (accept) {
        await api.post(`/api/rides/${rideId}/accept`)
        void navigate(`/rides/${rideId}`)
      } else {
        await api.post(`/api/rides/${rideId}/reject`)
        toast.success('Declined. It will go to the next driver.')
        await refresh()
      }
    } catch (err) {
      toast.error(toFriendlyError(err).message)
      await refresh()
    } finally {
      setBusy(false)
    }
  }

  if (!data) {
    return <PageHeader eyebrow="Driver" title="Driver dashboard" />
  }

  const { profile, documents, vehicles, availability, ride } = data
  const approved = profile?.approvalStatus === 'APPROVED'
  const hasActiveVehicle = vehicles.some((v) => v.active)
  const secondsLeft = secondsUntil(ride?.offerExpiresAt ?? null, now)
  const inTrip = ride && ride.status !== 'DRIVER_ASSIGNED'
  const markers: MapMarker[] = position ? [{ id: 'me', kind: 'me', latitude: position.latitude, longitude: position.longitude }] : []
  if (ride?.status === 'DRIVER_ASSIGNED') {
    markers.push({ id: 'pickup', kind: 'pickup', latitude: ride.pickup.latitude, longitude: ride.pickup.longitude })
  }

  return (
    <>
      <PageHeader
        eyebrow="Driver"
        title="Driver dashboard"
        actions={profile ? <ApprovalBadge status={profile.approvalStatus} /> : undefined}
      />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}

      {!approved && (
        <Card className="mb-6">
          <CardContent>
            <p className="mb-3 font-semibold text-fg">Get ready to drive</p>
            <ol className="flex flex-col gap-2">
              <Step done={Boolean(profile)} to="/profile">Add your licence details</Step>
              <Step done={documents.length >= 4} to="/driver/documents">Upload your 4 documents</Step>
              <Step done={vehicles.length > 0} to="/driver/vehicles">Add a vehicle</Step>
              <Step done={approved} to="/profile">Wait for approval from the RideX team</Step>
            </ol>
            {profile?.approvalStatus === 'REJECTED' && (
              <Alert kind="error" className="mt-4">
                Your application was rejected{profile.rejectionReason ? `: ${profile.rejectionReason}` : '.'} Fix the details and upload again.
              </Alert>
            )}
          </CardContent>
        </Card>
      )}

      {ride?.status === 'DRIVER_ASSIGNED' && (
        <Card className="mb-6 ring-2 ring-accent/60">
          <CardContent className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <p className="text-lg font-bold text-fg">New ride request</p>
              <span className="tabular rounded-full bg-accent-subtle px-3 py-1 font-bold text-accent" aria-live="polite">
                {secondsLeft}s
              </span>
            </div>
            <p className="text-fg">{ride.pickup.address} → {ride.dropoff.address}</p>
            <p className="tabular text-sm text-fg-muted">
              {CATEGORY_LABEL[ride.category]} · {formatDistance(ride.distanceKm)} · {formatINR(ride.fare.total)}
            </p>
            <div className="flex gap-3">
              <Button className="flex-1" loading={busy} disabled={secondsLeft === 0} onClick={() => respond(ride.id, true)}>Accept</Button>
              <Button className="flex-1" variant="secondary" loading={busy} onClick={() => respond(ride.id, false)}>Decline</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {inTrip && (
        <Alert kind="info" className="mb-6">
          You have a trip in progress.{' '}
          <Link to={`/rides/${ride.id}`} className="font-semibold underline">Open it</Link>
        </Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardContent className="flex flex-col gap-4">
            <p className="font-semibold text-fg">{online ? 'You are online' : 'You are offline'}</p>
            {!approved && <p className="text-sm text-fg-muted">You can go online once you are approved and have an active vehicle.</p>}
            {approved && !hasActiveVehicle && (
              <Alert kind="info">Choose an active vehicle first. <Link to="/driver/vehicles" className="underline">Vehicles</Link></Alert>
            )}
            {!online && approved && hasActiveVehicle && (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <TextField label="Latitude" inputMode="decimal" value={manualLat} onChange={(e) => { setManualLat(e.target.value); setPosition(null) }} />
                  <TextField label="Longitude" inputMode="decimal" value={manualLng} onChange={(e) => { setManualLng(e.target.value); setPosition(null) }} />
                </div>
                <div className="flex gap-3">
                  <Button variant="secondary" onClick={useGps}>Use GPS</Button>
                  <Button className="flex-1" loading={busy} onClick={goOnline}>Go online</Button>
                </div>
              </>
            )}
            {online && (
              <>
                <p className="text-sm text-fg-secondary">
                  Sharing your location every {HEARTBEAT_MS / 1000} seconds. New requests appear here automatically.
                </p>
                <Button variant="secondary" loading={busy} onClick={goOffline}>Go offline</Button>
              </>
            )}
            {availability?.lastSeenAt && <p className="text-xs text-fg-muted">Last location update received</p>}
          </CardContent>
        </Card>

        <RideMap markers={markers} label="Your location" className="h-72" />
      </div>
    </>
  )
}

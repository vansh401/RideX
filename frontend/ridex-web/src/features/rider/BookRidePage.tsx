import { Bike, Car, CarTaxiFront, LocateFixed, MapPin } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { CouponPreview, FareEstimate, RiderProfile, Ride, VehicleCategory } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { RideMap, type MapMarker } from '@/components/map/RideMap'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Select } from '@/components/ui/select'
import { TextField } from '@/components/ui/text-field'
import { FareBreakdown } from '@/features/rides/FareBreakdown'
import { cn } from '@/lib/cn'
import { formatINR } from '@/lib/format'
import { couponReason } from '@/lib/complaints'
import { CATEGORY_LABEL, formatDistance, formatDuration } from '@/lib/rides'

interface Spot {
  address: string
  latitude: number
  longitude: number
}

type Target = 'pickup' | 'dropoff' | 'stop'

const MAX_STOPS = 3

/** The value for a datetime-local input: local time, minutes precision, N minutes from now. */
function localInput(minutesFromNow: number): string {
  const d = new Date(Date.now() + minutesFromNow * 60_000)
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset())
  return d.toISOString().slice(0, 16)
}

const ICON: Record<VehicleCategory, typeof Car> = { BIKE: Bike, AUTO: CarTaxiFront, CAB: Car }

const round6 = (n: number) => Math.round(n * 1e6) / 1e6

/** Book a ride: choose both points (map, saved place or current location), compare fares, request. */
export function BookRidePage() {
  const navigate = useNavigate()
  const [pickup, setPickup] = useState<Spot | null>(null)
  const [dropoff, setDropoff] = useState<Spot | null>(null)
  const [target, setTarget] = useState<Target>('pickup')
  const [places, setPlaces] = useState<RiderProfile['savedPlaces']>([])
  const [fares, setFares] = useState<FareEstimate[] | null>(null)
  const [category, setCategory] = useState<VehicleCategory>('CAB')
  const [error, setError] = useState<string | null>(null)
  const [quoting, setQuoting] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const [stops, setStops] = useState<Spot[]>([])
  const [coupon, setCoupon] = useState('')
  const [couponInfo, setCouponInfo] = useState<CouponPreview | null>(null)
  const [later, setLater] = useState(false)
  const [when, setWhen] = useState(() => localInput(60))

  // A rider with a ride in progress goes straight to it (the server allows only one at a time)
  useEffect(() => {
    let cancelled = false
    api
      .get<Ride | ''>('/api/rides/current')
      .then((response) => {
        if (!cancelled && response.data) void navigate(`/rides/${response.data.id}`, { replace: true })
      })
      .catch(() => undefined)
    api
      .get<RiderProfile>('/api/riders/me')
      .then((response) => !cancelled && setPlaces(response.data.savedPlaces))
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [navigate])

  // Price the trip whenever both points are known
  useEffect(() => {
    if (!pickup || !dropoff) return
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a request is starting, so show it
    setQuoting(true)
    api
      .post<FareEstimate[]>('/api/fares/estimate', {
        pickup: { latitude: pickup.latitude, longitude: pickup.longitude },
        dropoff: { latitude: dropoff.latitude, longitude: dropoff.longitude },
        stops: stops.map((s) => ({ latitude: s.latitude, longitude: s.longitude })),
      })
      .then((response) => {
        if (cancelled) return
        setFares(response.data)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setFares(null)
        setError(toFriendlyError(err).message)
      })
      .finally(() => !cancelled && setQuoting(false))
    return () => {
      cancelled = true
    }
  }, [pickup, dropoff, stops])

  function setSpot(which: Target, spot: Spot | null) {
    if (which === 'pickup') setPickup(spot)
    else setDropoff(spot)
    if (spot === null || !(which === 'pickup' ? dropoff : pickup)) setFares(null)
  }

  function onMapPick(point: { latitude: number; longitude: number }) {
    const latitude = round6(point.latitude)
    const longitude = round6(point.longitude)
    if (target === 'stop') {
      if (stops.length < MAX_STOPS) setStops([...stops, { address: `Stop ${stops.length + 1} (${latitude.toFixed(4)}, ${longitude.toFixed(4)})`, latitude, longitude }])
      setTarget('dropoff')
      return
    }
    setSpot(target, { address: `Pinned location (${latitude.toFixed(4)}, ${longitude.toFixed(4)})`, latitude, longitude })
    if (target === 'pickup' && !dropoff) setTarget('dropoff')
  }

  function useMyLocation() {
    if (!navigator.geolocation) {
      toast.error('This browser cannot share your location. Tap the map instead.')
      return
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const latitude = round6(position.coords.latitude)
        const longitude = round6(position.coords.longitude)
        setSpot('pickup', { address: 'My current location', latitude, longitude })
        setTarget('dropoff')
      },
      () => toast.error('Could not read your location. Tap the map instead.'),
      { enableHighAccuracy: true, timeout: 8000 },
    )
  }

  function pickSaved(which: Target, id: string) {
    const place = places.find((p) => p.id === id)
    if (!place) return
    setSpot(which, { address: place.address, latitude: place.latitude, longitude: place.longitude })
  }

  async function checkCoupon() {
    if (!chosen || !coupon.trim()) {
      setCouponInfo(null)
      return
    }
    try {
      const { data } = await api.get<CouponPreview>(`/api/coupons/${encodeURIComponent(coupon.trim())}/preview?fare=${chosen.total}`)
      setCouponInfo(data)
    } catch (err) {
      setCouponInfo(null)
      setError(toFriendlyError(err).message)
    }
  }

  async function request() {
    if (!pickup || !dropoff) return
    setRequesting(true)
    setError(null)
    try {
      const { data } = await api.post<Ride>('/api/rides', {
        category,
        pickup: { address: pickup.address, location: { latitude: pickup.latitude, longitude: pickup.longitude } },
        dropoff: { address: dropoff.address, location: { latitude: dropoff.latitude, longitude: dropoff.longitude } },
        couponCode: coupon.trim() || undefined,
        scheduledFor: later ? new Date(when).toISOString() : undefined,
        stops: stops.length ? stops.map((s) => ({ address: s.address, location: { latitude: s.latitude, longitude: s.longitude } })) : undefined,
      })
      void navigate(`/rides/${data.id}`)
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      setRequesting(false)
    }
  }

  const markers = useMemo<MapMarker[]>(() => {
    const list: MapMarker[] = []
    if (pickup) list.push({ id: 'pickup', kind: 'pickup', latitude: pickup.latitude, longitude: pickup.longitude })
    if (dropoff) list.push({ id: 'dropoff', kind: 'dropoff', latitude: dropoff.latitude, longitude: dropoff.longitude })
    return list
  }, [pickup, dropoff])

  const chosen = fares?.find((f) => f.category === category) ?? null

  return (
    <>
      <PageHeader eyebrow="Rider" title="Book a ride" description="Pick where you are and where you are going." />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,26rem)_1fr]">
        <div className="flex flex-col gap-4">
          {(['pickup', 'dropoff'] as const).map((which) => {
            const spot = which === 'pickup' ? pickup : dropoff
            const active = target === which
            return (
              <Card key={which} className={cn(active && 'ring-2 ring-accent/50')}>
                <CardContent className="flex flex-col gap-3">
                  <div className="flex items-center justify-between gap-2">
                    <p className="flex items-center gap-2 font-semibold text-fg">
                      <MapPin className="size-4 text-accent" aria-hidden />
                      {which === 'pickup' ? 'Pickup' : 'Destination'}
                    </p>
                    <Button variant={active ? 'primary' : 'secondary'} size="sm" onClick={() => setTarget(which)}>
                      {active ? 'Tap the map' : 'Choose on map'}
                    </Button>
                  </div>
                  <TextField
                    label="Address or landmark"
                    value={spot?.address ?? ''}
                    maxLength={255}
                    placeholder={spot ? undefined : 'Choose on the map, or use a saved place'}
                    onChange={(e) => spot && setSpot(which, { ...spot, address: e.target.value })}
                    disabled={!spot}
                    hint={spot ? `${spot.latitude.toFixed(5)}, ${spot.longitude.toFixed(5)}` : undefined}
                  />
                  <div className="flex flex-wrap items-center gap-2">
                    {places.length > 0 && (
                      <Select
                        aria-label={`Saved place for ${which}`}
                        className="h-9 w-auto text-sm"
                        value=""
                        onChange={(e) => pickSaved(which, e.target.value)}
                      >
                        <option value="">Saved places…</option>
                        {places.map((p) => (
                          <option key={p.id} value={p.id}>
                            {p.label}
                          </option>
                        ))}
                      </Select>
                    )}
                    {which === 'pickup' && (
                      <Button variant="ghost" size="sm" onClick={useMyLocation}>
                        <LocateFixed className="size-4" aria-hidden /> Use my location
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>
            )
          })}

          {pickup && dropoff && (
            <Card>
              <CardContent className="flex flex-col gap-2">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-semibold text-fg">Stops ({stops.length}/{MAX_STOPS})</p>
                  <Button variant={target === 'stop' ? 'primary' : 'secondary'} size="sm" disabled={stops.length >= MAX_STOPS} onClick={() => setTarget('stop')}>
                    {target === 'stop' ? 'Tap the map' : 'Add a stop'}
                  </Button>
                </div>
                {stops.map((s, i) => (
                  <div key={i} className="flex items-center justify-between gap-2 text-sm text-fg-secondary">
                    <span>{s.address}</span>
                    <Button variant="ghost" size="sm" onClick={() => setStops(stops.filter((_, j) => j !== i))}>Remove</Button>
                  </div>
                ))}
                <p className="text-xs text-fg-muted">Each stop adds distance and about 3 minutes of waiting to the fare.</p>
              </CardContent>
            </Card>
          )}

          {error && <Alert kind="error">{error}</Alert>}

          {pickup && dropoff && (
            <Card>
              <CardContent className="flex flex-col gap-4">
                <p className="font-semibold text-fg">Choose a ride</p>
                {quoting && !fares && <p className="text-sm text-fg-muted">Calculating fares…</p>}
                {fares && (
                  <div role="radiogroup" aria-label="Vehicle type" className="flex flex-col gap-2">
                    {fares.map((fare) => {
                      const Icon = ICON[fare.category]
                      const selected = fare.category === category
                      return (
                        <button
                          key={fare.category}
                          type="button"
                          role="radio"
                          aria-checked={selected}
                          onClick={() => setCategory(fare.category)}
                          className={cn(
                            'flex items-center gap-4 rounded-md border p-3 text-left transition-colors',
                            selected ? 'border-accent bg-accent-subtle' : 'border-border-strong hover:border-fg-muted',
                          )}
                        >
                          <Icon className="size-6 text-accent" aria-hidden />
                          <span className="flex-1">
                            <span className="block font-semibold text-fg">{CATEGORY_LABEL[fare.category]}</span>
                            <span className="text-sm text-fg-muted">
                              {formatDistance(fare.distanceKm)} · {formatDuration(fare.durationMinutes)}
                            </span>
                          </span>
                          <span className="tabular text-lg font-bold text-fg">{formatINR(fare.total)}</span>
                        </button>
                      )
                    })}
                  </div>
                )}
                {chosen && <FareBreakdown fare={chosen} minimumFareApplied={chosen.minimumFareApplied} />}
                {chosen && chosen.surgeMultiplier > 1 && !later && (
                  <Alert kind="info">Busy right now: fares are {chosen.surgeMultiplier.toFixed(2)}x. Schedule for later to avoid surge pricing.</Alert>
                )}
                <div className="flex gap-2">
                  <TextField label="Offer code" className="uppercase" value={coupon} maxLength={40} onChange={(e) => { setCoupon(e.target.value); setCouponInfo(null) }} />
                  <Button variant="secondary" size="sm" className="mt-7" disabled={!chosen || !coupon.trim()} onClick={checkCoupon}>Apply</Button>
                </div>
                {couponInfo && (couponInfo.eligible
                  ? <Alert kind="success">You save {formatINR(couponInfo.discount)} on this ride.</Alert>
                  : <Alert kind="error">{couponReason(couponInfo.reason)}</Alert>)}
                <label className="flex items-center gap-2 text-sm text-fg">
                  <input type="checkbox" checked={later} onChange={(e) => setLater(e.target.checked)} /> Schedule for later
                </label>
                {later && (
                  <TextField label="Pickup time" type="datetime-local" min={localInput(15)} max={localInput(7 * 24 * 60)} value={when} onChange={(e) => setWhen(e.target.value)} hint="Between 15 minutes and 7 days from now" />
                )}
                <Button size="lg" disabled={!chosen} loading={requesting} onClick={request}>
                  {chosen ? `${later ? 'Schedule' : 'Request'} ${CATEGORY_LABEL[category]} · ${formatINR(chosen.total)}` : 'Request ride'}
                </Button>
                <p className="text-xs text-fg-muted">Your wallet is used first, then your test card, when the trip ends. No real money moves.</p>
              </CardContent>
            </Card>
          )}
        </div>

        <RideMap
          markers={markers}
          showRoute
          onPick={onMapPick}
          label="Map: tap to set the pickup or destination"
          className="h-[28rem] lg:sticky lg:top-6 lg:h-[36rem]"
        />
      </div>
    </>
  )
}

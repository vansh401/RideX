import { Flame, MapPin } from 'lucide-react'
import { api } from '@/api/client'
import type { SurgeZone } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { usePolling } from '@/lib/usePolling'

/**
 * Where demand is high right now (the price multiplier is above normal), busiest first. Zones are about 2 km squares; "Open map" shows
 * the centre of the zone. When nothing is surging the list is empty, which is the normal, quiet case.
 */
export function BusyAreasCard() {
  const { data } = usePolling(async () => (await api.get<SurgeZone[]>('/api/drivers/surge-zones')).data, { intervalMs: 30000 })

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <Flame className="size-4 text-accent" aria-hidden /> Busy areas
        </p>
        {!data && <p className="text-sm text-fg-muted">Checking demand…</p>}
        {data?.length === 0 && <p className="text-sm text-fg-muted">Demand is normal everywhere right now. Busy areas show up here when riders outnumber drivers.</p>}
        <ul className="flex flex-col gap-2">
          {data?.map((z) => (
            <li key={z.zone} className="flex items-center justify-between gap-3 rounded-md bg-white/[0.04] px-3 py-2.5">
              <span className="flex items-center gap-2 text-sm text-fg">
                <MapPin className="size-4 text-fg-muted" aria-hidden />
                <a
                  className="hover:underline"
                  href={`https://www.openstreetmap.org/?mlat=${z.latitude}&mlon=${z.longitude}#map=14/${z.latitude}/${z.longitude}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  Open map ({z.latitude.toFixed(3)}, {z.longitude.toFixed(3)})
                </a>
              </span>
              <Badge tone="warning">{z.multiplier.toFixed(2)}x fares</Badge>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

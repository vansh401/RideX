import { lazy, Suspense } from 'react'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { MapViewProps } from './MapView'

// The map library is large, so it is downloaded only when a screen with a map is opened.
const MapView = lazy(() => import('./MapView'))

export function RideMap(props: MapViewProps) {
  return (
    <Suspense fallback={<Skeleton className={cn('min-h-64', props.className)} />}>
      <MapView {...props} />
    </Suspense>
  )
}

export type { MapMarker } from './MapView'

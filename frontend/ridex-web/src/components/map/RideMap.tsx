import { lazy, Suspense } from 'react'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import { useTheme } from '@/lib/theme'
import type { MapViewProps } from './MapView'

// The map library is large, so it is downloaded only when a screen with a map is opened.
const MapView = lazy(() => import('./MapView'))

export function RideMap(props: MapViewProps) {
  const { theme } = useTheme()
  return (
    <Suspense fallback={<Skeleton className={cn('min-h-64', props.className)} />}>
      {/* A new key rebuilds the map with the other basemap when the theme changes */}
      <MapView key={theme} {...props} />
    </Suspense>
  )
}

export type { MapMarker } from './MapView'

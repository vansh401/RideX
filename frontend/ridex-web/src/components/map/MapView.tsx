import * as maplibregl from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { useTheme, type Theme } from '@/lib/theme'

export interface MapMarker {
  id: string
  kind: 'pickup' | 'dropoff' | 'driver' | 'me'
  latitude: number
  longitude: number
}

export interface MapViewProps {
  markers: MapMarker[]
  /** Draw a dashed line between the pickup and the destination. */
  showRoute?: boolean
  /** Called with the clicked point; leave out for a read-only map. */
  onPick?: (point: { latitude: number; longitude: number }) => void
  className?: string
  label?: string
}

// Vite must bundle the map's background worker itself; without this the library cannot find it
// and the map stays blank ("Worker failed to load").
maplibregl.setWorkerUrl(workerUrl)

// Free base maps from CARTO over OpenStreetMap data (no API key): "Positron" (light, quiet) for the light theme and "Dark Matter" for the dark one.
// The attribution they require is shown on the map by MapLibre.
const STYLE_URL: Record<Theme, string> = {
  light: 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json',
  dark: 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
}
const ROUTE_COLOR: Record<Theme, string> = { light: '#8a6410', dark: '#e9c84a' }
const DEFAULT_CENTER: [number, number] = [77.5946, 12.9716] // Bengaluru, as [longitude, latitude]

function markerElement(kind: MapMarker['kind'], theme: Theme): HTMLElement {
  const el = document.createElement('div')
  el.setAttribute('aria-hidden', 'true')
  const light = theme === 'light'
  const ring = light ? '#ffffff' : '#060709'
  const ink = light ? '#16130d' : '#f6f6f3'
  const gold = light ? '#8a6410' : '#e9c84a'
  const base = `width:22px;height:22px;border:3px solid ${ring};box-shadow:0 2px 10px rgb(0 0 0 / ${light ? '.35' : '.7'});`
  if (kind === 'pickup') {
    el.style.cssText = `${base}border-radius:50%;background:${ink};box-shadow:0 0 0 6px ${light ? 'rgb(22 19 13 / .12)' : 'rgb(246 246 243 / .14)'},0 2px 10px rgb(0 0 0 / ${light ? '.35' : '.7'});`
  } else if (kind === 'dropoff') {
    el.style.cssText = `${base}border-radius:6px;background:${gold};box-shadow:0 0 0 6px ${light ? 'rgb(138 100 16 / .2)' : 'rgb(233 200 74 / .18)'},0 2px 10px rgb(0 0 0 / ${light ? '.35' : '.7'});`
  } else if (kind === 'driver') {
    el.style.cssText = `${base}border-radius:8px;background:${ink};outline:2px solid ${gold};box-shadow:0 0 14px ${light ? 'rgb(138 100 16 / .5)' : 'rgb(233 200 74 / .55)'};`
  } else {
    el.style.cssText = `${base}border-radius:50%;background:${light ? '#2559a8' : '#72aeff'};box-shadow:0 0 0 6px rgb(114 174 255 / .2);`
  }
  return el
}

/** An interactive map. Loaded lazily (see RideMap) because the map library is large. */
export default function MapView({ markers, showRoute = false, onPick, className, label = 'Map' }: MapViewProps) {
  const container = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const markerRefs = useRef(new Map<string, maplibregl.Marker>())
  const onPickRef = useRef(onPick)
  const { theme } = useTheme()
  const [ready, setReady] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    onPickRef.current = onPick
  })

  // Create the map once
  useEffect(() => {
    if (!container.current) return
    let map: maplibregl.Map
    try {
      map = new maplibregl.Map({ container: container.current, style: STYLE_URL[theme], center: DEFAULT_CENTER, zoom: 11.5 })
    } catch {
      // No WebGL (old device, blocked): the screens still work through the coordinate fields
      window.setTimeout(() => setFailed(true), 0)
      return
    }
    mapRef.current = map
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    map.on('load', () => {
      map.addSource('route', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      // A soft halo under the dashes makes the route read clearly on a dark map
      map.addLayer({
        id: 'route-halo',
        type: 'line',
        source: 'route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ROUTE_COLOR[theme], 'line-width': 12, 'line-opacity': 0.14, 'line-blur': 6 },
      })
      map.addLayer({
        id: 'route-line',
        type: 'line',
        source: 'route',
        paint: { 'line-color': ROUTE_COLOR[theme], 'line-width': 4, 'line-dasharray': [1.5, 2] },
      })
      setReady(true)
    })
    map.on('click', (event) => onPickRef.current?.({ latitude: event.lngLat.lat, longitude: event.lngLat.lng }))
    const markersForCleanup = markerRefs.current
    return () => {
      markersForCleanup.clear()
      map.remove()
      mapRef.current = null
      setReady(false)
    }
  }, [theme])

  // Keep the markers in step with the props
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const seen = new Set<string>()
    for (const marker of markers) {
      seen.add(marker.id)
      const existing = markerRefs.current.get(marker.id)
      if (existing) {
        existing.setLngLat([marker.longitude, marker.latitude])
      } else {
        const created = new maplibregl.Marker({ element: markerElement(marker.kind, theme) })
          .setLngLat([marker.longitude, marker.latitude])
          .addTo(map)
        markerRefs.current.set(marker.id, created)
      }
    }
    for (const [id, marker] of markerRefs.current) {
      if (!seen.has(id)) {
        marker.remove()
        markerRefs.current.delete(id)
      }
    }

    if (markers.length >= 2) {
      const bounds = new maplibregl.LngLatBounds()
      markers.forEach((m) => bounds.extend([m.longitude, m.latitude]))
      map.fitBounds(bounds, { padding: 70, maxZoom: 15, duration: 400 })
    } else if (markers.length === 1) {
      map.easeTo({ center: [markers[0].longitude, markers[0].latitude], zoom: Math.max(map.getZoom(), 13.5), duration: 400 })
    }
  }, [markers, ready, theme])

  // The dashed route line
  useEffect(() => {
    const map = mapRef.current
    if (!map || !ready) return
    const source = map.getSource('route') as maplibregl.GeoJSONSource | undefined
    const pickup = markers.find((m) => m.kind === 'pickup')
    const dropoff = markers.find((m) => m.kind === 'dropoff')
    const coordinates = showRoute && pickup && dropoff
      ? [[pickup.longitude, pickup.latitude], [dropoff.longitude, dropoff.latitude]]
      : []
    source?.setData({
      type: 'FeatureCollection',
      features: coordinates.length
        ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } }]
        : [],
    })
  }, [markers, showRoute, ready, theme])

  if (failed) {
    return (
      <div className={cn('grid place-items-center rounded-lg border border-border bg-surface p-6 text-center text-sm text-fg-secondary', className)}>
        The map could not start on this device (it needs WebGL). You can still use the coordinate fields.
      </div>
    )
  }

  return (
    <div
      ref={container}
      role="application"
      aria-label={label}
      className={cn('overflow-hidden rounded-xl border border-border bg-surface shadow-raised', className)}
    />
  )
}

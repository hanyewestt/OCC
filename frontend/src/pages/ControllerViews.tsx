import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ChevronDown, ChevronRight, Minus, Plus, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import type { Arrival, BunchingAlert, DemoRun, PageResult, RouteDirection, RoutePositions } from '@/lib/api'
import { bunchingArrivals } from '@/lib/bunching'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import type { Decision } from '@/lib/api'
import type { PagedCollection } from '@/lib/paged-collection'
import type { Resource } from './ControllerPage'
import { groupRouteIncidents, type RouteIncident, formatGap, gapSeverity, routeStopDistances, linkIncidentToPosition, routeKey, type IncidentStripLink } from '@/lib/route-strip'
import {
  type LatLng,
  computeBusLatLngAlongPolyline,
  getBundledRouteGeometry,
  loadRouteGeometry,
} from '@/lib/route-geometry'

export function timestamp(value: string | null | undefined) {
  return formatSgtDateTime(value)
}

export function DataMessage({ error, loading, empty, children }: {
  error: string; loading: boolean; empty: boolean; children: React.ReactNode
}) {
  return loading ? <p role="status" className="py-6 text-sm text-muted-foreground">Loading…</p> :
    error ? <p role="alert" className="py-6 text-sm text-destructive">{error} Use Refresh to try again.</p> :
      empty ? <p className="py-6 text-sm text-muted-foreground">{children}</p> : null
}

type ArrivalCollection = {
  items: Arrival[]; latestPage: PageResult<Arrival> | null; isLoading: boolean; isLoadingMore: boolean
  hasMore: boolean; error: unknown; refreshError: boolean; reload: () => void; loadNext: () => void
  onScroll: React.UIEventHandler<HTMLDivElement>
}

export function ControllerOverview({ collection, search, onSearch, sortBy, sortDirection, onSort }: {
  collection: ArrivalCollection; search: string; onSearch: (value: string) => void
  sortBy: 'arrival' | 'stopName'; sortDirection: 'asc' | 'desc'; onSort: (value: 'arrival' | 'stopName') => void
}) {
  const [searchInput, setSearchInput] = useState(search)
  const data = collection.latestPage ? { ...collection.latestPage, items: collection.items } : null
  const initialError = collection.error !== null && !collection.items.length ? 'Unable to load arrivals.' : ''
  const loadMoreError = collection.error !== null && collection.items.length > 0
  const loadCategory = (value: string | null) => value === null ? '—' : ({
    SEA: 'Seats Available', SDA: 'Standing Available', LSD: 'Limited Standing',
  }[value] ?? value)
  return <section id="arrivals" aria-labelledby="arrivals-title" className="scroll-mt-20 space-y-4">
    <div><h2 id="arrivals-title" tabIndex={-1} className="w-fit font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring">Current bus arrivals</h2></div>
    <form role="search" onSubmit={event => { event.preventDefault(); onSearch(searchInput.trim()) }} className="flex flex-wrap items-end gap-2">
      <div className="w-full max-w-sm space-y-1.5">
        <label htmlFor="arrival-stop-search" className="text-sm font-medium">Search bus stop name</label>
        <Input id="arrival-stop-search" type="search" value={searchInput} onChange={event => setSearchInput(event.target.value)} />
      </div>
      <Button type="submit" variant="outline">Search</Button>
      {(searchInput || search) && <Button type="button" variant="ghost" onClick={() => { setSearchInput(''); onSearch('') }}>Clear</Button>}
    </form>
    <DataMessage error={initialError} loading={collection.isLoading && !data} empty={!data?.items.length}>
      {search ? 'No arrivals match this stop name' : 'No current arrivals.'}
    </DataMessage>
    {!!data?.items.length && <Table containerProps={{ role: 'region', 'aria-label': 'Current bus arrivals', tabIndex: 0,
      onScroll: collection.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[42rem]">
      <TableCaption className="sr-only">Current bus arrivals</TableCaption>
      <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-1 [&_th]:bg-background">
        <TableRow><TableHead>Service</TableHead>
          <TableHead aria-sort={sortBy === 'stopName' ? sortDirection === 'asc' ? 'ascending' : 'descending' : undefined}>
            <Button type="button" variant="ghost" size="sm"
              aria-label={`Sort stop names ${sortBy === 'stopName' && sortDirection === 'asc' ? 'descending' : 'ascending'}`}
              onClick={() => onSort('stopName')}>Stop <span aria-hidden="true">{sortBy === 'stopName' ? sortDirection === 'asc' ? '↑' : '↓' : '↕'}</span></Button>
          </TableHead>
          <TableHead aria-sort={sortBy === 'arrival' ? sortDirection === 'asc' ? 'ascending' : 'descending' : undefined}>
            <Button type="button" variant="ghost" size="sm"
              aria-label={`Sort arrival times ${sortBy === 'arrival' && sortDirection === 'asc' ? 'descending' : 'ascending'}`}
              onClick={() => onSort('arrival')}>Arrival <span aria-hidden="true">{sortBy === 'arrival' ? sortDirection === 'asc' ? '↑' : '↓' : '↕'}</span></Button>
          </TableHead>
          <TableHead>Load</TableHead><TableHead>Fetched</TableHead></TableRow>
      </TableHeader>
      <TableBody>{data.items.map(arrival => <TableRow key={arrival.observationId}>
        <TableCell className="font-medium">{arrival.serviceId}</TableCell>
        <TableCell>{`${arrival.stopName?.trim() || `Stop ${arrival.stopCode}`} (${arrival.stopCode})`}</TableCell>
        <TableCell>{timestamp(arrival.estimatedArrivalAt)}</TableCell>
        <TableCell>{loadCategory(arrival.loadCategory)}</TableCell>
        <TableCell className="text-muted-foreground">{timestamp(arrival.fetchedAt)}</TableCell>
      </TableRow>)}</TableBody>
    </Table>}
    {loadMoreError && <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-destructive">
      <span>{collection.refreshError ? 'Unable to refresh arrivals.' : 'Unable to load more arrivals.'}</span>
      <Button variant="outline" size="sm" disabled={collection.isLoading || collection.isLoadingMore} onClick={collection.refreshError ? collection.reload : collection.loadNext}>{collection.isLoading || collection.isLoadingMore ? 'Loading…' : collection.refreshError ? 'Retry arrivals' : 'Retry loading more arrivals'}</Button>
    </div>}
    {collection.hasMore && !loadMoreError && <Button variant="outline" disabled={collection.isLoading || collection.isLoadingMore} onClick={collection.loadNext}>
      {collection.isLoadingMore ? 'Loading more arrivals…' : 'Load more arrivals'}
    </Button>}
    {collection.error !== null && !collection.items.length && <Button variant="outline" disabled={collection.isLoading} onClick={collection.reload}>{collection.isLoading ? 'Retrying arrivals…' : 'Retry arrivals'}</Button>}
  </section>
}

export const TILE_SIZE = 256
export const MIN_ZOOM = 11
export const MAX_ZOOM = 18
export const DEFAULT_CENTER = { lat: 1.3521, lng: 103.8198 }

export function validCoord(lat: number | null | undefined, lng: number | null | undefined): boolean {
  return lat !== null && lat !== undefined && lng !== null && lng !== undefined &&
    Number.isFinite(lat) && Number.isFinite(lng) &&
    lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 &&
    !(lat === 0 && lng === 0)
}

export function latLngToWorld(lat: number, lng: number, zoom: number): { x: number; y: number } {
  const worldSize = TILE_SIZE * 2 ** zoom
  const x = ((lng + 180) / 360) * worldSize
  const latRad = Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI / 180
  const y = (1 - Math.asinh(Math.tan(latRad)) / Math.PI) / 2 * worldSize
  return { x, y }
}

export function worldToLatLng(x: number, y: number, zoom: number): { lat: number; lng: number } {
  const worldSize = TILE_SIZE * 2 ** zoom
  const lng = (x / worldSize) * 360 - 180
  const latRad = Math.atan(Math.sinh(Math.PI * (1 - 2 * (y / worldSize))))
  const lat = latRad * 180 / Math.PI
  return { lat, lng }
}

export function computeCorridorFit(
  points: Array<{ latitude: number; longitude: number }>,
  width: number,
  height: number,
  padding: number = 48,
): { center: { lat: number; lng: number }; zoom: number } {
  if (points.length === 0) {
    return { center: DEFAULT_CENTER, zoom: 12 }
  }
  const minLat = Math.min(...points.map(p => p.latitude))
  const maxLat = Math.max(...points.map(p => p.latitude))
  const minLng = Math.min(...points.map(p => p.longitude))
  const maxLng = Math.max(...points.map(p => p.longitude))
  const center = { lat: (minLat + maxLat) / 2, lng: (minLng + maxLng) / 2 }

  if (points.length === 1 || (minLat === maxLat && minLng === maxLng)) {
    return { center, zoom: 15 }
  }

  const availW = Math.max(80, width - padding * 2)
  const availH = Math.max(80, height - padding * 2)

  let bestZoom = MIN_ZOOM
  for (let z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
    const p1 = latLngToWorld(minLat, minLng, z)
    const p2 = latLngToWorld(maxLat, maxLng, z)
    const spanX = Math.abs(p2.x - p1.x)
    const spanY = Math.abs(p2.y - p1.y)
    if (spanX <= availW && spanY <= availH) {
      bestZoom = z
      break
    }
  }
  return { center, zoom: Math.max(12, Math.min(16, bestZoom)) }
}

export function computeBusLatLng(
  route: RouteDirection,
  distanceAlongRouteM: number,
  routeLengthM: number,
  polylineCoords?: LatLng[] | null,
): { latitude: number; longitude: number } | null {
  if (polylineCoords && polylineCoords.length >= 2) {
    const pt = computeBusLatLngAlongPolyline(polylineCoords, distanceAlongRouteM, routeLengthM)
    if (pt) return pt
  }

  const validStops = route.stops.filter(s => validCoord(s.latitude, s.longitude))
  if (validStops.length < 2) return null

  const { distancesM } = routeStopDistances(route, routeLengthM)
  const target = Math.max(0, Math.min(routeLengthM, distanceAlongRouteM))

  let nextIndex = distancesM.findIndex(d => d >= target)
  if (nextIndex < 0) nextIndex = route.stops.length - 1
  const next = Math.max(1, Math.min(nextIndex, route.stops.length - 1))
  const prev = next - 1
  const dPrev = distancesM[prev]
  const dNext = distancesM[next]
  const denom = dNext - dPrev
  const ratio = denom > 0 ? Math.max(0, Math.min(1, (target - dPrev) / denom)) : 0

  const stopPrev = route.stops[prev]
  const stopNext = route.stops[next]
  if (!validCoord(stopPrev.latitude, stopPrev.longitude) || !validCoord(stopNext.latitude, stopNext.longitude)) {
    return null
  }
  return {
    latitude: stopPrev.latitude! + ratio * (stopNext.latitude! - stopPrev.latitude!),
    longitude: stopPrev.longitude! + ratio * (stopNext.longitude! - stopPrev.longitude!),
  }
}

export type PositionedIncident = RouteIncident & { stripLink: IncidentStripLink | null }

export interface BunchingMapProps {
  routes: RouteDirection[]
  affectedStopCodes: Set<string>
  buses: Array<{ serviceId: string; directionId: number; routeLengthM: number; bus: RoutePositions['directions'][number]['buses'][number] }>
  selectedIncident?: PositionedIncident | null
  incidents?: PositionedIncident[]
  onSelectIncident?: (incidentId: string | null) => void
  routeGeometries?: Record<string, LatLng[]>
}

export function BunchingMap({
  routes,
  affectedStopCodes,
  buses,
  selectedIncident = null,
  incidents = [],
  onSelectIncident,
  routeGeometries,
}: BunchingMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [loadedGeometries, setLoadedGeometries] = useState<Record<string, LatLng[]>>({})

  const activeGeometries = useMemo(() => {
    const result: Record<string, LatLng[]> = { ...loadedGeometries, ...(routeGeometries ?? {}) }
    for (const route of routes) {
      const key = `${route.serviceId}-${route.directionId}`
      if (!result[key]) {
        const bundled = getBundledRouteGeometry(route.serviceId, route.directionId)
        if (bundled) {
          result[key] = bundled
        }
      }
    }
    return result
  }, [routes, loadedGeometries, routeGeometries])

  useEffect(() => {
    let cancelled = false
    for (const route of routes) {
      const key = `${route.serviceId}-${route.directionId}`
      if (!activeGeometries[key]) {
        loadRouteGeometry(route.serviceId, route.directionId).then(pts => {
          if (!cancelled && pts) {
            setLoadedGeometries(prev => ({ ...prev, [key]: pts }))
          }
        }).catch(() => {})
      }
    }
    return () => { cancelled = true }
  }, [routes, activeGeometries])
  const [containerSize, setContainerSize] = useState<{ width: number; height: number }>({ width: 0, height: 0 })
  const [isDragging, setIsDragging] = useState(false)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const updateSize = () => {
      const rect = el.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0) {
        setContainerSize({ width: Math.round(rect.width), height: Math.round(rect.height) })
      }
    }
    updateSize()
    const observer = new ResizeObserver(updateSize)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const width = containerSize.width || 800
  const height = containerSize.height || 384

  const selectedStopCodes = useMemo(() => new Set(selectedIncident?.stopCodes ?? []), [selectedIncident])

  const selectedRouteKeys = useMemo(() => {
    if (!selectedIncident) return new Set<string>()
    return new Set(routes
      .filter(r => r.serviceId === selectedIncident.serviceId &&
        (selectedIncident.directionId === null || r.directionId === selectedIncident.directionId))
      .map(r => `${r.serviceId}-${r.directionId}`))
  }, [selectedIncident, routes])

  const selectedBuses = useMemo(() => {
    if (!selectedIncident || !selectedIncident.stripLink) return new Set<string>()
    const set = new Set<string>()
    set.add(`${selectedIncident.serviceId}-${selectedIncident.stripLink.frontOrder}`)
    set.add(`${selectedIncident.serviceId}-${selectedIncident.stripLink.followingOrder}`)
    return set
  }, [selectedIncident])

  const allStops = useMemo(() => {
    const map = new Map<string, { stopCode: string; description: string | null; latitude: number; longitude: number; affected: boolean }>()
    for (const route of routes) {
      for (const stop of route.stops) {
        if (validCoord(stop.latitude, stop.longitude) && !map.has(stop.stopCode)) {
          map.set(stop.stopCode, {
            stopCode: stop.stopCode,
            description: stop.description,
            latitude: stop.latitude!,
            longitude: stop.longitude!,
            affected: affectedStopCodes.has(stop.stopCode),
          })
        }
      }
    }
    return [...map.values()]
  }, [routes, affectedStopCodes])

  const busMarkers = useMemo(() => buses.flatMap(({ serviceId, directionId, routeLengthM, bus }) => {
    const route = routes.find(candidate => candidate.serviceId === serviceId && candidate.directionId === directionId)
    if (!route) return []
    const key = `${serviceId}-${directionId}`
    const geom = activeGeometries[key]
    const coords = computeBusLatLng(route, bus.distanceAlongRouteM, routeLengthM, geom)
    if (!coords) return []
    return [{
      ...coords,
      serviceId,
      directionId,
      order: bus.order,
      label: `Estimated bus ${bus.order} on service ${serviceId}`,
    }]
  }), [buses, routes, activeGeometries])

  const corridorFocusPoints = useMemo(() => {
    if (allStops.length > 0) {
      return allStops.map(s => ({ latitude: s.latitude, longitude: s.longitude }))
    }
    return busMarkers.map(b => ({ latitude: b.latitude, longitude: b.longitude }))
  }, [allStops, busMarkers])

  const selectedFocusPoints = useMemo(() => {
    if (!selectedIncident) return []
    const stopPts = allStops.filter(s => selectedStopCodes.has(s.stopCode)).map(s => ({ latitude: s.latitude, longitude: s.longitude }))
    const busPts = busMarkers.filter(b => selectedBuses.has(`${b.serviceId}-${b.order}`)).map(b => ({ latitude: b.latitude, longitude: b.longitude }))
    const combined = [...stopPts, ...busPts]
    return combined.length > 0 ? combined : stopPts
  }, [selectedIncident, allStops, selectedStopCodes, busMarkers, selectedBuses])

  const fitKey = useMemo(() => {
    const rKey = routes.map(r => `${r.serviceId}-${r.directionId}`).sort().join(',')
    const aKey = [...affectedStopCodes].sort().join(',')
    return `${rKey}:${aKey}`
  }, [routes, affectedStopCodes])

  const initialFit = useMemo(() => {
    return computeCorridorFit(corridorFocusPoints, width, height, 48)
  }, [corridorFocusPoints, width, height])

  const selectedFit = useMemo(() => {
    if (!selectedFocusPoints.length) return null
    return computeCorridorFit(selectedFocusPoints, width, height, 60)
  }, [selectedFocusPoints, width, height])

  const [manualView, setManualView] = useState<{ center: { lat: number; lng: number }; zoom: number } | null>(null)
  const [prevFitKey, setPrevFitKey] = useState(fitKey)
  const [prevSelectedId, setPrevSelectedId] = useState<string | null | undefined>(selectedIncident?.id)

  if (prevFitKey !== fitKey) {
    setPrevFitKey(fitKey)
    setManualView(null)
  }
  if (prevSelectedId !== selectedIncident?.id) {
    setPrevSelectedId(selectedIncident?.id)
    setManualView(null)
  }

  const effectiveFit = selectedFit ?? initialFit
  const center = manualView ? manualView.center : effectiveFit.center
  const zoom = manualView ? manualView.zoom : effectiveFit.zoom

  const stateRef = useRef({ center, zoom, width, height })
  useEffect(() => {
    stateRef.current = { center, zoom, width, height }
  }, [center, zoom, width, height])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      if (Math.abs(e.deltaY) < 10) return
      const { center: curCenter, zoom: curZoom, width: w, height: h } = stateRef.current
      const direction = e.deltaY < 0 ? 1 : -1
      const targetZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, curZoom + direction))
      if (targetZoom === curZoom) return

      const rect = el.getBoundingClientRect()
      const mouseX = e.clientX - rect.left
      const mouseY = e.clientY - rect.top
      const halfW = w / 2
      const halfH = h / 2

      const centerWorld = latLngToWorld(curCenter.lat, curCenter.lng, curZoom)
      const mouseWorld = {
        x: centerWorld.x + (mouseX - halfW),
        y: centerWorld.y + (mouseY - halfH),
      }
      const mouseLatLng = worldToLatLng(mouseWorld.x, mouseWorld.y, curZoom)

      const newMouseWorld = latLngToWorld(mouseLatLng.lat, mouseLatLng.lng, targetZoom)
      const newCenterWorld = {
        x: newMouseWorld.x - (mouseX - halfW),
        y: newMouseWorld.y - (mouseY - halfH),
      }
      setManualView({ center: worldToLatLng(newCenterWorld.x, newCenterWorld.y, targetZoom), zoom: targetZoom })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  const drag = useRef<{ pointerId: number; startX: number; startY: number; startCenterWorld: { x: number; y: number } } | null>(null)

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    if ((event.target as Element).closest('button, [role="button"]')) return
    const centerWorld = latLngToWorld(center.lat, center.lng, zoom)
    drag.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startCenterWorld: centerWorld,
    }
    setIsDragging(true)
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!drag.current || drag.current.pointerId !== event.pointerId) return
    const dx = event.clientX - drag.current.startX
    const dy = event.clientY - drag.current.startY
    const newCenterWorld = {
      x: drag.current.startCenterWorld.x - dx,
      y: drag.current.startCenterWorld.y - dy,
    }
    setManualView({ center: worldToLatLng(newCenterWorld.x, newCenterWorld.y, zoom), zoom })
  }

  const onPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    if (drag.current?.pointerId === event.pointerId) {
      drag.current = null
      setIsDragging(false)
    }
  }

  const handleZoomIn = () => setManualView({ center, zoom: Math.min(MAX_ZOOM, zoom + 1) })
  const handleZoomOut = () => setManualView({ center, zoom: Math.max(MIN_ZOOM, zoom - 1) })
  const handleResetView = () => setManualView(null)

  const onStopClick = (stopCode: string) => {
    if (!onSelectIncident) return
    const corridorServiceIds = new Set(routes.map(r => r.serviceId))
    const matching = incidents.find(i => corridorServiceIds.has(i.serviceId) && i.stopCodes.includes(stopCode))
      ?? incidents.find(i => i.stopCodes.includes(stopCode))
    if (matching) {
      onSelectIncident(selectedIncident?.id === matching.id ? null : matching.id)
      document.getElementById(`incident-${matching.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }

  const onBusClick = (serviceId: string, order: number) => {
    if (!onSelectIncident) return
    const matching = incidents.find(i =>
      i.stripLink && (i.stripLink.frontOrder === order || i.stripLink.followingOrder === order) && i.serviceId === serviceId
    )
    if (matching) {
      onSelectIncident(selectedIncident?.id === matching.id ? null : matching.id)
      document.getElementById(`incident-${matching.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const panStep = 60
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      const cw = latLngToWorld(center.lat, center.lng, zoom)
      setManualView({ center: worldToLatLng(cw.x - panStep, cw.y, zoom), zoom })
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      const cw = latLngToWorld(center.lat, center.lng, zoom)
      setManualView({ center: worldToLatLng(cw.x + panStep, cw.y, zoom), zoom })
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      const cw = latLngToWorld(center.lat, center.lng, zoom)
      setManualView({ center: worldToLatLng(cw.x, cw.y - panStep, zoom), zoom })
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      const cw = latLngToWorld(center.lat, center.lng, zoom)
      setManualView({ center: worldToLatLng(cw.x, cw.y + panStep, zoom), zoom })
    } else if (event.key === '+' || event.key === '=') {
      event.preventDefault()
      handleZoomIn()
    } else if (event.key === '-' || event.key === '_') {
      event.preventDefault()
      handleZoomOut()
    }
  }

  if (allStops.length < 2) {
    return <p className="rounded-lg border p-4 text-sm text-muted-foreground">Stop coordinates are unavailable for this detection.</p>
  }

  const centerWorld = latLngToWorld(center.lat, center.lng, zoom)
  const halfW = width / 2
  const halfH = height / 2

  const project = (latitude: number, longitude: number) => {
    const pt = latLngToWorld(latitude, longitude, zoom)
    return {
      x: pt.x - centerWorld.x + halfW,
      y: pt.y - centerWorld.y + halfH,
    }
  }

  const minWorldX = centerWorld.x - halfW
  const maxWorldX = centerWorld.x + halfW
  const minWorldY = centerWorld.y - halfH
  const maxWorldY = centerWorld.y + halfH

  const firstTileX = Math.floor(minWorldX / TILE_SIZE)
  const lastTileX = Math.floor(maxWorldX / TILE_SIZE)
  const firstTileY = Math.max(0, Math.floor(minWorldY / TILE_SIZE))
  const lastTileY = Math.min(2 ** zoom - 1, Math.floor(maxWorldY / TILE_SIZE))

  const tiles = []
  for (let tx = firstTileX; tx <= lastTileX; tx++) {
    for (let ty = firstTileY; ty <= lastTileY; ty++) {
      const wrappedTileX = ((tx % 2 ** zoom) + 2 ** zoom) % 2 ** zoom
      tiles.push({
        key: `${zoom}-${tx}-${ty}`,
        left: tx * TILE_SIZE - minWorldX,
        top: ty * TILE_SIZE - minWorldY,
        href: `https://www.onemap.gov.sg/maps/tiles/Default/${zoom}/${wrappedTileX}/${ty}.png`,
      })
    }
  }

  const routePolylines = routes.map(route => {
    const key = `${route.serviceId}-${route.directionId}`
    const geom = activeGeometries[key]
    const pts = (geom && geom.length > 1)
      ? geom.map(p => project(p.latitude, p.longitude))
      : route.stops
          .filter(stop => validCoord(stop.latitude, stop.longitude))
          .map(stop => project(stop.latitude!, stop.longitude!))
    return {
      key,
      points: pts.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '),
      count: pts.length,
    }
  }).filter(line => line.count > 1)

  const projectedStops = allStops.map(stop => ({
    ...stop,
    ...project(stop.latitude, stop.longitude),
  }))

  const projectedBuses = busMarkers.map(bus => ({
    ...bus,
    ...project(bus.latitude, bus.longitude),
  }))

  return (
    <div className="overflow-hidden rounded-lg border bg-muted/10">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
        <div className="space-y-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-medium">Affected corridor map</h3>
            {selectedIncident && (
              <span className={cn(
                "inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold",
                selectedIncident.historical
                  ? "bg-muted text-muted-foreground"
                  : "bg-primary/10 text-primary"
              )}>
                Service {selectedIncident.serviceId}
                {selectedIncident.directionId !== null && ` · Dir ${selectedIncident.directionId}`}
                {selectedIncident.gapSeconds !== null && ` (${formatGap(selectedIncident.gapSeconds)})`}
                {selectedIncident.historical && ' · Resolved'}
              </span>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            {selectedIncident
              ? selectedIncident.historical
                ? `Historical incident · Buses have already departed the affected stop${selectedIncident.stopCodes.length === 1 ? '' : 's'}. Map reflects current live bus positions.`
                : !selectedIncident.stripLink
                  ? `Active detection · No current bus position estimate is linked to this corridor segment (buses have traversed this segment or observation cycle has rolled over).`
                  : `Focusing on ${selectedIncident.stopCodes.length} affected stop${selectedIncident.stopCodes.length === 1 ? '' : 's'}. Click any stop or bus to select or switch incident.`
              : 'Stops are sourced from route topology; buses are current position estimates. Click a stop or bus to focus.'}
          </p>
        </div>
        <div className="flex items-center gap-3 text-xs text-muted-foreground">
          {selectedIncident && onSelectIncident && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => onSelectIncident(null)}
              className="h-7 text-xs"
            >
              Fit corridor
            </Button>
          )}
          <span><span className="mr-1 inline-block size-2 rounded-full bg-destructive" />Affected stop</span>
          <span><span className="mr-1 inline-block size-2 rounded-full bg-primary" />Estimated bus</span>
          {selectedIncident && <span><span className="mr-1 inline-block size-2 rounded-full bg-amber-500" />Bunched bus</span>}
        </div>
      </div>
      <div
        ref={containerRef}
        tabIndex={0}
        role="region"
        aria-label="Interactive affected corridor map. Use arrow keys to pan, plus and minus to zoom."
        className={cn(
          "relative h-[24rem] touch-none overflow-hidden bg-slate-100 dark:bg-slate-900 select-none focus:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          isDragging ? "cursor-grabbing" : "cursor-grab"
        )}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => { drag.current = null; setIsDragging(false) }}
        onKeyDown={onKeyDown}
      >
        <div className="absolute inset-0 pointer-events-none select-none">
          {tiles.map(tile => (
            <img
              key={tile.key}
              src={tile.href}
              alt=""
              draggable={false}
              className="absolute select-none pointer-events-none"
              style={{
                left: `${tile.left}px`,
                top: `${tile.top}px`,
                width: `${TILE_SIZE}px`,
                height: `${TILE_SIZE}px`,
              }}
              loading="eager"
              decoding="async"
              onError={e => { (e.currentTarget as HTMLElement).style.opacity = '0' }}
            />
          ))}
        </div>

        <svg
          className="absolute inset-0 h-full w-full pointer-events-none select-none"
          role="img"
          aria-label="OneMap of affected bus stops and estimated buses"
        >
          {routePolylines.map(line => {
            const isSelectedRoute = selectedRouteKeys.size > 0 && selectedRouteKeys.has(line.key)
            const isDimmed = selectedIncident && !isSelectedRoute
            return (
              <g key={line.key} opacity={isDimmed ? 0.35 : 1} className="transition-opacity duration-300">
                <polyline
                  points={line.points}
                  fill="none"
                  stroke="white"
                  strokeWidth={isSelectedRoute ? 10 : 8}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  opacity={isSelectedRoute ? 0.95 : 0.85}
                />
                <polyline
                  points={line.points}
                  fill="none"
                  stroke={isSelectedRoute ? '#2563eb' : '#1d4ed8'}
                  strokeWidth={isSelectedRoute ? 5 : 4}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </g>
            )
          })}
          {projectedStops.map(stop => {
            const isSelectedStop = selectedStopCodes.has(stop.stopCode)
            const isDimmed = selectedIncident && !isSelectedStop
            const isAffected = stop.affected
            const hasIncident = isAffected && incidents.some(i => i.stopCodes.includes(stop.stopCode))

            return (
              <g
                key={stop.stopCode}
                role={hasIncident && onSelectIncident ? "button" : undefined}
                tabIndex={hasIncident && onSelectIncident ? 0 : undefined}
                aria-label={`${isAffected ? 'Affected ' : ''}stop ${stop.description || stop.stopCode} (${stop.stopCode})${isSelectedStop ? ' - selected incident' : ''}`}
                className={cn(
                  "pointer-events-auto transition-opacity duration-300",
                  hasIncident && onSelectIncident ? "cursor-pointer outline-none focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-700" : "cursor-default"
                )}
                opacity={isDimmed ? 0.35 : 1}
                onClick={() => onStopClick(stop.stopCode)}
                onPointerDown={e => e.stopPropagation()}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onStopClick(stop.stopCode)
                  }
                }}
              >
                <title>{`${isAffected ? 'Affected ' : ''}stop ${stop.description || stop.stopCode} (${stop.stopCode})${isSelectedStop ? ' [Selected incident]' : ''}${hasIncident && onSelectIncident ? ' - Click to view incident' : ''}`}</title>
                {isSelectedStop && (
                  <circle
                    cx={stop.x}
                    cy={stop.y}
                    r={14}
                    fill="none"
                    stroke="#ef4444"
                    strokeWidth="2.5"
                    opacity="0.85"
                    className="animate-pulse"
                  />
                )}
                <circle
                  cx={stop.x}
                  cy={stop.y}
                  r={isSelectedStop ? 9 : isAffected ? 8 : 4.5}
                  fill="white"
                  className="drop-shadow-sm"
                />
                <circle
                  cx={stop.x}
                  cy={stop.y}
                  r={isSelectedStop ? 7 : isAffected ? 6 : 3}
                  fill={isAffected ? '#dc2626' : '#475569'}
                />
              </g>
            )
          })}
          {projectedBuses.map((bus, index) => {
            const busKey = `${bus.serviceId}-${bus.order}`
            const isSelectedBus = selectedBuses.has(busKey)
            const isDimmed = selectedIncident && !isSelectedBus
            const hasIncident = incidents.some(i =>
              i.stripLink && (i.stripLink.frontOrder === bus.order || i.stripLink.followingOrder === bus.order) && i.serviceId === bus.serviceId
            )

            return (
              <g
                key={`bus-${bus.serviceId}-${bus.order}-${index}`}
                role={hasIncident && onSelectIncident ? "button" : undefined}
                tabIndex={hasIncident && onSelectIncident ? 0 : undefined}
                aria-label={`${bus.label}${isSelectedBus ? ' - Bunched pair for selected incident' : ''}`}
                className={cn(
                  "pointer-events-auto transition-opacity duration-300",
                  hasIncident && onSelectIncident ? "cursor-pointer outline-none focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-700" : "cursor-default"
                )}
                opacity={isDimmed ? 0.35 : 1}
                onClick={() => onBusClick(bus.serviceId, bus.order)}
                onPointerDown={e => e.stopPropagation()}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onBusClick(bus.serviceId, bus.order)
                  }
                }}
              >
                <title>{`${bus.label}${isSelectedBus ? ' [Bunched pair for selected incident]' : ''}${hasIncident && onSelectIncident ? ' - Click to view incident' : ''}`}</title>
                {isSelectedBus && (
                  <circle
                    cx={bus.x}
                    cy={bus.y}
                    r="18"
                    fill="none"
                    stroke="#f59e0b"
                    strokeWidth="2.5"
                    className="animate-pulse"
                  />
                )}
                <circle cx={bus.x} cy={bus.y} r={isSelectedBus ? "14" : "13"} fill="white" className="drop-shadow" />
                <circle
                  cx={bus.x}
                  cy={bus.y}
                  r={isSelectedBus ? "11" : "10"}
                  fill={isSelectedBus ? "#ea580c" : "#0284c7"}
                  stroke="#0c1a2b"
                  strokeWidth={isSelectedBus ? 2 : 1.5}
                />
                <text
                  x={bus.x}
                  y={bus.y}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fill="white"
                  fontSize={isSelectedBus ? "11" : "10"}
                  fontWeight="bold"
                  className="select-none pointer-events-none"
                >
                  {bus.order}
                </text>
              </g>
            )
          })}
        </svg>

        <div className="absolute right-3 top-3 flex flex-col gap-1 rounded-md border bg-background/90 backdrop-blur-xs p-1 shadow-sm z-10">
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Zoom in map"
            disabled={zoom >= MAX_ZOOM}
            onClick={handleZoomIn}
            className="h-8 w-8"
          >
            <Plus aria-hidden="true" className="size-4" />
          </Button>
          <div className="text-center text-[10px] font-medium text-muted-foreground select-none" aria-hidden="true">
            {zoom}z
          </div>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Zoom out map"
            disabled={zoom <= MIN_ZOOM}
            onClick={handleZoomOut}
            className="h-8 w-8"
          >
            <Minus aria-hidden="true" className="size-4" />
          </Button>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Reset map view"
            disabled={manualView === null}
            onClick={handleResetView}
            className="h-8 w-8 border-t rounded-none"
          >
            <RotateCcw aria-hidden="true" className="size-3.5" />
          </Button>
        </div>
      </div>
      <p className="border-t px-4 py-2 text-right text-[11px] text-muted-foreground">
        Basemap © <a className="underline underline-offset-2" href="https://www.onemap.gov.sg/" target="_blank" rel="noreferrer">OneMap</a> · SLA
      </p>
      {selectedIncident && !selectedIncident.stripLink && (
        <p className="border-t px-4 py-3 text-xs text-muted-foreground">
          {selectedIncident.historical
            ? 'This incident has resolved and bunched buses have departed. No active bunched bus position estimate is present for this past stop.'
            : 'No current bus position estimate is linked to this corridor segment (buses have traversed this segment or observation cycle has rolled over).'}
        </p>
      )}
      {!selectedIncident && !busMarkers.length && (
        <p className="border-t px-4 py-3 text-xs text-muted-foreground">
          No current bus position estimate is available for this corridor.
        </p>
      )}
    </div>
  )
}

export function ControllerDisruptions({ liveAlerts, run, loading, routes = [], positions = [], pollIntervalSeconds = 60 }: {
  liveAlerts: PagedCollection<BunchingAlert, PageResult<BunchingAlert>>
  run: Resource<DemoRun>
  loading: boolean
  routes?: RouteDirection[]
  positions?: RoutePositions[]
  pollIntervalSeconds?: number
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const incidentParam = searchParams.get('incident')
  const serviceParam = searchParams.get('service')
  const directionParam = searchParams.get('direction')
  const statusParam = searchParams.get('status')
  const statusFilter: 'active' | 'all' = statusParam === 'all' ? 'all' : 'active'
  const [viewMode, setViewMode] = useState<'grouped' | 'flat'>('grouped')
  const [expandedIncidents, setExpandedIncidents] = useState<Set<string>>(new Set())
  
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const error = run.error || (!simulation && liveAlerts.error !== null && !liveAlerts.items.length ? 'Unable to load this data.' : '')
  const items = useMemo(() => (loading || error || !simulation && liveAlerts.isLoading && !liveAlerts.latestPage
    ? [] : simulation ? simulation.alerts : liveAlerts.items), [loading, error, simulation, liveAlerts.isLoading, liveAlerts.latestPage, liveAlerts.items])

  // Compute grouped incidents
  const nowMs = Date.parse(liveAlerts.lastSuccessAt ?? '') || 0
  const incidents = useMemo(() => {
    if (viewMode !== 'grouped' || !items.length) return []
    return groupRouteIncidents(items, routes, nowMs, pollIntervalSeconds, 20)
  }, [viewMode, items, routes, nowMs, pollIntervalSeconds])

  type PositionSnapshot = {
    position: RoutePositions['directions'][number]
    computedAt?: string
    sourceObservedAt?: string | null
    maxSourceAgeSeconds: number
  }

  const positionByRoute = useMemo(() => new Map<string, PositionSnapshot>(positions.flatMap(snapshot =>
    snapshot.directions.map(direction => [
      `${snapshot.serviceId}:${direction.operator}:${direction.directionId}`,
      { position: direction, computedAt: snapshot.computedAt, sourceObservedAt: snapshot.sourceObservedAt,
        maxSourceAgeSeconds: snapshot.maxSourceAgeSeconds },
    ] as const))), [positions])

  const positionedIncidents = useMemo<PositionedIncident[]>(() => incidents.map(incident => {
    const route = routes.find(candidate => routeKey(candidate) === incident.routeKey)
    const snapshot = route ? positionByRoute.get(routeKey(route)) : undefined
    return {
      ...incident,
      stripLink: !simulation && route && snapshot
        ? linkIncidentToPosition(incident, route, snapshot.position,
          (snapshot.sourceObservedAt || snapshot.computedAt) ?? undefined, snapshot.maxSourceAgeSeconds) : null,
    }
  }), [incidents, routes, positionByRoute, simulation])

  const selectedIncident = useMemo(() => {
    if (incidentParam) {
      return positionedIncidents.find(i => i.id === incidentParam) ?? null
    }
    return null
  }, [incidentParam, positionedIncidents])

  const activeIncidents = useMemo(() => positionedIncidents.filter(i => !i.historical), [positionedIncidents])
  const historicalIncidents = useMemo(() => positionedIncidents.filter(i => i.historical), [positionedIncidents])

  // When filtered to active, we still preserve the selected incident if the user explicitly clicked/deep-linked it
  const displayedIncidents = useMemo(() => {
    if (statusFilter === 'all') return positionedIncidents
    if (selectedIncident?.historical && !activeIncidents.some(i => i.id === selectedIncident.id)) {
      return [selectedIncident, ...activeIncidents]
    }
    return activeIncidents
  }, [statusFilter, positionedIncidents, activeIncidents, selectedIncident])

  const selectedCorridorKey = useMemo(() => {
    if (serviceParam && directionParam) {
      return `${serviceParam}:${directionParam}`
    }
    if (serviceParam) {
      const match = routes.find(r => r.serviceId === serviceParam)
      return match ? `${match.serviceId}:${match.directionId}` : 'all'
    }
    return 'all'
  }, [serviceParam, directionParam, routes])

  const corridorRoutes = useMemo(() => {
    if (selectedCorridorKey === 'all') return routes
    const [svc, dir] = selectedCorridorKey.split(':')
    return routes.filter(r => r.serviceId === svc && String(r.directionId) === dir)
  }, [selectedCorridorKey, routes])

  const handleSelectCorridor = (key: 'all' | string) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (key === 'all') {
        next.delete('service')
        next.delete('direction')
        next.delete('incident')
      } else {
        const [svc, dir] = key.split(':')
        next.set('service', svc)
        if (dir) next.set('direction', dir)
        if (selectedIncident && (selectedIncident.serviceId !== svc || String(selectedIncident.directionId) !== dir)) {
          next.delete('incident')
        }
      }
      return next
    }, { replace: true })
  }

  const handleSelectIncident = (incidentId: string | null) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (!incidentId) {
        next.delete('incident')
      } else {
        next.set('incident', incidentId)
        const incident = positionedIncidents.find(i => i.id === incidentId)
        if (incident) {
          next.set('service', incident.serviceId)
          if (incident.directionId !== null) next.set('direction', String(incident.directionId))
        }
      }
      return next
    }, { replace: true })
  }

  const handleSetStatusFilter = (newStatus: 'active' | 'all') => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (newStatus === 'all') {
        next.set('status', 'all')
      } else {
        next.delete('status')
      }
      return next
    }, { replace: true })
  }

  const mapIncidents = useMemo(() => {
    const base = statusFilter === 'all'
      ? positionedIncidents
      : (selectedIncident ? [selectedIncident, ...activeIncidents] : activeIncidents)
    if (selectedCorridorKey === 'all') return base
    const [svc, dir] = selectedCorridorKey.split(':')
    return base.filter(i => i.serviceId === svc && (i.directionId === null || String(i.directionId) === dir))
  }, [statusFilter, positionedIncidents, selectedIncident, activeIncidents, selectedCorridorKey])

  const corridorFilteredIncidents = useMemo(() => {
    if (selectedCorridorKey === 'all') return displayedIncidents
    const [svc, dir] = selectedCorridorKey.split(':')
    return displayedIncidents.filter(i =>
      i.serviceId === svc && (i.directionId === null || String(i.directionId) === dir)
    )
  }, [selectedCorridorKey, displayedIncidents])

  const affectedStopCodes = useMemo(() => new Set(mapIncidents.flatMap(incident => incident.stopCodes)), [mapIncidents])

  const mapBuses = useMemo(() => positions.flatMap(snapshot => snapshot.directions
    .filter(direction => corridorRoutes.some(route =>
      route.serviceId === snapshot.serviceId && route.directionId === direction.directionId
    ))
    .flatMap(direction => direction.buses.map(bus => ({
      serviceId: snapshot.serviceId, directionId: direction.directionId, routeLengthM: direction.routeLengthM, bus,
    })))), [positions, corridorRoutes])

  const flatItems = useMemo(() => {
    if (selectedCorridorKey === 'all') return items
    const [svc, dir] = selectedCorridorKey.split(':')
    return items.filter(alert => alert.serviceId === svc && (alert.directionId === null || String(alert.directionId) === dir))
  }, [selectedCorridorKey, items])

  const toggleIncident = (incidentId: string) => {
    setExpandedIncidents(prev => {
      const next = new Set(prev)
      if (next.has(incidentId)) {
        next.delete(incidentId)
      } else {
        next.add(incidentId)
      }
      return next
    })
  }

  const renderAlertRow = (alert: BunchingAlert) => (
    <TableRow key={alert.eventId}>
      <TableCell className="font-medium">{alert.serviceId}</TableCell>
      <TableCell>
        <p className="font-medium">{alert.stopName || 'Stop name unavailable'}</p>
        <p className="mt-1 text-xs text-muted-foreground">{alert.stopRoadName ? `${alert.stopRoadName} · ` : ''}{alert.stopCode}</p>
      </TableCell>
      <TableCell>{alert.directionId ? `Direction ${alert.directionId}` : 'Unavailable'}</TableCell>
      {bunchingArrivals(alert).map((value, index) => (
        <TableCell key={index}>
          {value === 'No prediction' || value === 'Unavailable' ? (
            <span className="text-muted-foreground">{value}</span>
          ) : timestamp(value)}
        </TableCell>
      ))}
      <TableCell>{alert.action}</TableCell>
      <TableCell>{timestamp(alert.detectedAt)}</TableCell>
      <TableCell>{dataClassificationLabel(alert.classification)}</TableCell>
    </TableRow>
  )

  const renderIncidentCard = (incident: PositionedIncident) => {
    const isExpanded = expandedIncidents.has(incident.id)
    const isSelected = selectedIncident?.id === incident.id
    const severity = gapSeverity(incident.gapSeconds)
    const gapText = incident.gapSeconds !== null ? formatGap(incident.gapSeconds) : 'Unknown gap'
    
    return (
      <div
        id={`incident-${incident.id}`}
        key={incident.id}
        className={cn(
          "scroll-mt-24 space-y-2 rounded-lg border p-4 transition-all duration-200",
          isSelected
            ? "border-primary ring-2 ring-primary/20 bg-primary/5 shadow-xs"
            : "hover:border-muted-foreground/30 hover:bg-muted/10"
        )}
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div
            className="flex-1 space-y-2 cursor-pointer"
            onClick={() => handleSelectIncident(isSelected ? null : incident.id)}
            role="button"
            tabIndex={0}
            onKeyDown={e => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                handleSelectIncident(isSelected ? null : incident.id)
              }
            }}
            aria-label={`${isSelected ? 'Deselect' : 'Select'} incident for Service ${incident.serviceId}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-semibold flex items-center gap-2">
                Service {incident.serviceId}
                {incident.directionId !== null && ` · Direction ${incident.directionId}`}
                {isSelected && (
                  <span className="rounded bg-primary px-1.5 py-0.5 text-[10px] font-bold text-primary-foreground tracking-wide uppercase">
                    Focused on map
                  </span>
                )}
              </h3>
              <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${
                severity === 'close' ? 'bg-destructive/10 text-destructive' :
                severity === 'moderate' ? 'bg-amber-700/10 text-amber-700 dark:bg-amber-400/10 dark:text-amber-400' :
                'bg-muted text-muted-foreground'
              }`}>
                {gapText}
              </span>
              <span className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                {incident.stopCodes.length} {incident.stopCodes.length === 1 ? 'stop' : 'stops'} affected
              </span>
              {incident.historical ? (
                <span className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground font-medium">
                  Resolved · Buses departed
                </span>
              ) : incident.stripLink ? (
                <span className="rounded-md bg-sky-500/10 text-sky-700 dark:text-sky-300 px-2 py-0.5 text-xs font-medium">
                  Buses #{incident.stripLink.frontOrder} & #{incident.stripLink.followingOrder}
                </span>
              ) : (
                <span className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground font-medium">
                  Position unlinked
                </span>
              )}
            </div>
            <p className="text-sm text-muted-foreground">
              <span className="font-medium text-foreground">{incident.stopNames[0]}</span>
              {incident.stopNames.length > 1 && (
                <> → <span className="font-medium text-foreground">{incident.stopNames[incident.stopNames.length - 1]}</span></>
              )}
            </p>
            <p className="text-xs text-muted-foreground">
              Detected {timestamp(incident.latestDetectedAt)}
              {incident.historical && ' · Headway prediction has elapsed'}
            </p>
            {incident.historical && isSelected && (
              <p className="text-xs text-muted-foreground bg-muted/40 rounded px-2.5 py-1.5 border border-muted-foreground/20">
                This detection has resolved. The bunched buses have already passed this stop, so live position tracking is no longer active for this stop.
              </p>
            )}
            {!incident.stripLink && !incident.historical && isSelected && (
              <p className="text-xs text-muted-foreground bg-muted/40 rounded px-2.5 py-1.5 border border-muted-foreground/20">
                No active bus position linked for this incident. This incident was detected from arrival forecasts; the bunched buses have already traversed this corridor segment or the observation cycle has rolled over.
              </p>
            )}
          </div>
          
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant={isSelected ? "secondary" : "outline"}
              size="sm"
              onClick={() => handleSelectIncident(isSelected ? null : incident.id)}
            >
              {isSelected ? "Unfocus map" : "Locate on map"}
            </Button>

            {!incident.historical ? (
              <Button asChild size="sm" variant="default">
                <Link
                  to={`/controller/planning?service=${encodeURIComponent(incident.serviceId)}&stop=${encodeURIComponent(incident.stopCodes[0] ?? '')}&incident=${encodeURIComponent(incident.id)}${incident.alerts[0]?.eventId ? `&event=${encodeURIComponent(incident.alerts[0].eventId)}` : ''}`}
                  title={`Open mitigation planning for Service ${incident.serviceId}`}
                >
                  Plan mitigation →
                </Link>
              </Button>
            ) : (
              <Button asChild size="sm" variant="outline">
                <Link
                  to={`/controller/planning?service=${encodeURIComponent(incident.serviceId)}&stop=${encodeURIComponent(incident.stopCodes[0] ?? '')}&incident=${encodeURIComponent(incident.id)}${incident.alerts[0]?.eventId ? `&event=${encodeURIComponent(incident.alerts[0].eventId)}` : ''}`}
                  title={`View mitigation planning history for Service ${incident.serviceId}`}
                >
                  View planning →
                </Link>
              </Button>
            )}

            <Button
              variant="ghost"
              size="sm"
              onClick={() => toggleIncident(incident.id)}
              aria-expanded={isExpanded}
              aria-label={`${isExpanded ? 'Collapse' : 'Expand'} stop details for service ${incident.serviceId}`}
            >
              {isExpanded ? (
                <>
                  <ChevronDown className="mr-1 h-4 w-4" aria-hidden="true" />
                  Hide stops
                </>
              ) : (
                <>
                  <ChevronRight className="mr-1 h-4 w-4" aria-hidden="true" />
                  Show stops
                </>
              )}
            </Button>
          </div>
        </div>
        
        {isExpanded && (
          <div className="mt-4 overflow-x-auto rounded-md border">
            <Table className="min-w-[60rem]">
              <TableCaption className="sr-only">Stop-level details for this incident</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Service</TableHead>
                  <TableHead>Bus stop</TableHead>
                  <TableHead>Direction</TableHead>
                  <TableHead>Bus 1 arrival</TableHead>
                  <TableHead>Bus 2 arrival</TableHead>
                  <TableHead>Bus 3 arrival</TableHead>
                  <TableHead>Event</TableHead>
                  <TableHead>Detected</TableHead>
                  <TableHead>Source</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {incident.alerts.map(alert => renderAlertRow(alert))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    )
  }

  return <div className="space-y-10">
    <section aria-labelledby="bunching-title" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h2 id="bunching-title" className="font-medium">Bunching detections</h2>
          {viewMode === 'grouped' && !!positionedIncidents.length && (
            <div className="flex items-center gap-1 rounded-lg border p-0.5 bg-muted/20 text-xs">
              <Button
                variant={statusFilter === 'active' ? 'secondary' : 'ghost'}
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleSetStatusFilter('active')}
                aria-pressed={statusFilter === 'active'}
              >
                Active ({activeIncidents.length})
              </Button>
              <Button
                variant={statusFilter === 'all' ? 'secondary' : 'ghost'}
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleSetStatusFilter('all')}
                aria-pressed={statusFilter === 'all'}
              >
                All ({positionedIncidents.length})
              </Button>
            </div>
          )}
        </div>
        <div className="flex items-center gap-1 rounded-lg border p-1 bg-muted/20">
          <Button
            variant={viewMode === 'grouped' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setViewMode('grouped')}
            aria-pressed={viewMode === 'grouped'}
          >
            Grouped
          </Button>
          <Button
            variant={viewMode === 'flat' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setViewMode('flat')}
            aria-pressed={viewMode === 'flat'}
          >
            Flat
          </Button>
        </div>
      </div>
      
      <DataMessage error={error} loading={loading || !simulation && liveAlerts.isLoading && !liveAlerts.latestPage} empty={!items.length}>No bunching detections.</DataMessage>
      
      {viewMode === 'grouped' && routes.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-muted/20 p-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mr-1">Corridor:</span>
            <Button
              variant={selectedCorridorKey === 'all' ? 'secondary' : 'ghost'}
              size="sm"
              className={cn(
                "h-8 text-xs font-medium",
                selectedCorridorKey === 'all' && "shadow-xs border"
              )}
              onClick={() => handleSelectCorridor('all')}
              aria-pressed={selectedCorridorKey === 'all'}
            >
              All Corridors ({routes.length})
            </Button>
            {routes.map(route => {
              const key = `${route.serviceId}:${route.directionId}`
              const isSelected = selectedCorridorKey === key
              const corridorIncidents = positionedIncidents.filter(i =>
                i.serviceId === route.serviceId && (i.directionId === null || i.directionId === route.directionId)
              )
              const activeCorridorIncidents = corridorIncidents.filter(i => !i.historical)
              const alertCount = statusFilter === 'active' ? activeCorridorIncidents.length : corridorIncidents.length
              const terminus = route.stops[route.stops.length - 1]?.description || `Dir ${route.directionId}`
              const isLoop = route.stops[0]?.stopCode === route.stops[route.stops.length - 1]?.stopCode
              const label = `Svc ${route.serviceId} · ${isLoop ? 'Loop' : 'to ' + terminus}`

              return (
                <Button
                  key={key}
                  variant={isSelected ? 'secondary' : 'ghost'}
                  size="sm"
                  className={cn(
                    "h-8 text-xs font-medium flex items-center gap-1.5",
                    isSelected && "shadow-xs border"
                  )}
                  onClick={() => handleSelectCorridor(key)}
                  aria-pressed={isSelected}
                >
                  <span>{label}</span>
                  {alertCount > 0 && (
                    <span className="rounded-full bg-destructive px-1.5 py-0.2 text-[10px] font-bold text-white">
                      {alertCount}
                    </span>
                  )}
                </Button>
              )
            })}
          </div>
          {selectedCorridorKey !== 'all' && (
            <Button
              variant="ghost"
              size="sm"
              className="h-8 text-xs text-muted-foreground hover:text-foreground"
              onClick={() => handleSelectCorridor('all')}
            >
              Clear corridor filter
            </Button>
          )}
        </div>
      )}

      {viewMode === 'grouped' && !!corridorRoutes.length && (
        <BunchingMap
          routes={corridorRoutes}
          affectedStopCodes={affectedStopCodes}
          buses={mapBuses}
          selectedIncident={selectedIncident}
          incidents={positionedIncidents}
          onSelectIncident={handleSelectIncident}
        />
      )}

      {viewMode === 'grouped' && !!positionedIncidents.length && statusFilter === 'active' && activeIncidents.length === 0 && (
        <div className="rounded-lg border p-6 text-center space-y-2 bg-muted/10">
          <p className="text-sm font-medium">No active bunching disruptions</p>
          <p className="text-xs text-muted-foreground">
            All {historicalIncidents.length} recent bunching detection{historicalIncidents.length === 1 ? ' has' : 's have'} already resolved (buses departed).
          </p>
          <Button variant="outline" size="sm" onClick={() => handleSetStatusFilter('all')}>
            View {historicalIncidents.length} resolved / past detection{historicalIncidents.length === 1 ? '' : 's'}
          </Button>
        </div>
      )}

      {viewMode === 'grouped' && !!positionedIncidents.length && (statusFilter !== 'active' || activeIncidents.length > 0) && corridorFilteredIncidents.length === 0 && (
        <div className="rounded-lg border p-6 text-center space-y-2 bg-muted/10">
          <p className="text-sm font-medium">No {statusFilter === 'active' ? 'active ' : ''}bunching disruptions on this corridor</p>
          <p className="text-xs text-muted-foreground">
            Buses on this route are operating normally with expected headways.
          </p>
          <Button variant="outline" size="sm" onClick={() => handleSelectCorridor('all')}>
            View all corridors
          </Button>
        </div>
      )}

      {viewMode === 'grouped' && !!corridorFilteredIncidents.length && (
        <div className="space-y-3">
          {corridorFilteredIncidents.map(incident => renderIncidentCard(incident))}
        </div>
      )}
      
      {viewMode === 'flat' && !!items.length && (
        <Table containerProps={{ role: 'region', 'aria-label': 'Bunching detections', tabIndex: 0,
          onScroll: simulation ? undefined : liveAlerts.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[60rem]">
          <TableCaption className="sr-only">Bunching detections</TableCaption>
          <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-1 [&_th]:bg-background">
            <TableRow>
              <TableHead>Service</TableHead>
              <TableHead>Bus stop</TableHead>
              <TableHead>Direction</TableHead>
              <TableHead>Bus 1 arrival</TableHead>
              <TableHead>Bus 2 arrival</TableHead>
              <TableHead>Bus 3 arrival</TableHead>
              <TableHead>Event</TableHead>
              <TableHead>Detected</TableHead>
              <TableHead>Source</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {flatItems.map(alert => renderAlertRow(alert))}
          </TableBody>
        </Table>
      )}
      
      {!simulation && !loading && liveAlerts.latestPage && liveAlerts.latestPage.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {liveAlerts.latestPage.totalItems} detections.</p>}
      {!simulation && <PagedCollectionControls label="bunching detections" hasItems={!!liveAlerts.items.length} hasMore={liveAlerts.hasMore}
        isLoading={liveAlerts.isLoading} isLoadingMore={liveAlerts.isLoadingMore} error={liveAlerts.error} refreshError={liveAlerts.refreshError} reload={liveAlerts.reload} loadNext={liveAlerts.loadNext} />}
    </section>
  </div>
}

export function ControllerAudit({ decisions, loading }: {
  decisions: PagedCollection<Decision, PageResult<Decision>>; loading: boolean
}) {
  const items = !loading ? decisions.items : []
  const error = decisions.error !== null && !decisions.items.length ? 'Unable to load this data.' : ''
  return <div className="space-y-10">
    <section aria-labelledby="decisions-title" className="space-y-4"><h2 id="decisions-title" className="font-medium">Controller decisions</h2>
      <DataMessage error={error} loading={loading || decisions.isLoading && !decisions.latestPage} empty={!items.length}>No controller decisions have been saved.</DataMessage>
      {!!items.length && <Table containerProps={{ role: 'region', 'aria-label': 'Controller decisions', tabIndex: 0,
        onScroll: decisions.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[48rem]">
        <TableCaption className="sr-only">Controller decisions</TableCaption>
        <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-1 [&_th]:bg-background"><TableRow><TableHead>Decision</TableHead><TableHead>Recorded</TableHead><TableHead>Controller</TableHead><TableHead>Replacement instructions</TableHead><TableHead>Rationale</TableHead><TableHead>Recommendation ID</TableHead></TableRow></TableHeader>
        <TableBody>{items.map(decision => <TableRow key={decision.recommendationId}><TableCell className="font-medium">{decision.action}</TableCell><TableCell>{timestamp(decision.decidedAt)}</TableCell><TableCell className="text-muted-foreground">{decision.actorSubject}</TableCell><TableCell>{decision.overrideInstructions.length ? <ul className="space-y-2">{decision.overrideInstructions.map((instruction, index) => <li key={`${instruction.busReference}-${index}`}><span className="font-medium">{instruction.busReference}</span><span className="block text-xs text-muted-foreground">{instruction.instruction}</span></li>)}</ul> : '—'}</TableCell><TableCell className="max-w-72 text-muted-foreground">{decision.controllerRationale ?? '—'}</TableCell><TableCell className="text-xs text-muted-foreground">{decision.recommendationId}</TableCell></TableRow>)}</TableBody>
      </Table>}
      {decisions.latestPage && decisions.latestPage.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {decisions.latestPage.totalItems} decisions.</p>}
      <PagedCollectionControls label="decisions" hasItems={!!decisions.items.length} hasMore={decisions.hasMore}
        isLoading={decisions.isLoading} isLoadingMore={decisions.isLoadingMore} error={decisions.error} refreshError={decisions.refreshError} reload={decisions.reload} loadNext={decisions.loadNext} />
    </section>
    <section aria-labelledby="ai-audit-title" className="space-y-2 border-t pt-6"><h2 id="ai-audit-title" className="font-medium">AI audit logs</h2><p className="text-sm text-muted-foreground">Not connected yet. AI audit records will appear here when available.</p></section>
  </div>
}

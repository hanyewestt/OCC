import { useMemo, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import type { BunchingAlert, DemoRun, PageResult, RouteDirection } from '@/lib/api'
import {
  arrivalGaps, formatGap, gapSeverity, placeRouteAlerts, routeKey,
  ROUTE_STRIP_GAP_SEVERITY, tightestArrivalGap, type PositionedRouteAlert,
} from '@/lib/route-strip'
import { bunchingArrivals } from '@/lib/bunching'
import { DataMessage, timestamp } from './ControllerViews'
import type { Resource } from './ControllerPage'

type Props = {
  alerts: Resource<PageResult<BunchingAlert>>
  run: Resource<DemoRun>
  stops: Resource<RouteDirection[]>
  loading: boolean
}

const stopSpacing = 140
const leftPadding = 56
const noEvents: BunchingAlert[] = []
const noRoutes: RouteDirection[] = []

function stopNameLines(name: string | null) {
  const words = name?.trim().split(/\s+/).filter(Boolean) ?? []
  if (!words.length) return ['Name unavailable']
  const lines: string[] = []
  let line = ''
  for (const word of words) {
    if (line && `${line} ${word}`.length > 22) {
      lines.push(line)
      line = word
    } else {
      line = line ? `${line} ${word}` : word
    }
  }
  if (line) lines.push(line)
  return lines
}

function alertLabel(alert: BunchingAlert) {
  const gap = tightestArrivalGap(alert)
  return `Service ${alert.serviceId}, direction ${alert.directionId ?? 'unavailable'}, bunching detected at stop ${alert.stopCode}; tightest predicted gap ${gap === null ? 'unavailable' : `${gap} seconds`}; detected ${timestamp(alert.detectedAt)}. Press Enter or Space to view details.`
}

function RouteRow({ route, events, selectedId, onSelect }: {
  route: RouteDirection; events: PositionedRouteAlert[]
  selectedId: string | null; onSelect: (alert: BunchingAlert) => void
}) {
  const markers = new Map<number, BunchingAlert[]>()
  events.forEach(({ alert, positions }) => positions.forEach(position => {
    const atStop = markers.get(position) ?? []
    atStop.push(alert)
    markers.set(position, atStop)
  }))

  const maxMarkers = Math.max(1, ...[...markers.values()].map(value => value.length))
  const routeY = 48 + (maxMarkers - 1) * 22
  const maxNameLines = Math.max(1, ...route.stops.map(stop => stopNameLines(stop.description).length))
  const height = routeY + 42 + maxNameLines * 11
  const width = Math.max(620, leftPadding * 2 + Math.max(0, route.stops.length - 1) * stopSpacing)
  const xFor = (position: number) => leftPadding + position * stopSpacing

  return <div className="border-b last:border-b-0">
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 pt-4">
      <h3 className="text-sm font-medium">Service {route.serviceId} · Direction {route.directionId}</h3>
      <span className="text-xs text-muted-foreground">{route.operator} · {route.stops.length} stops</span>
    </div>
    <div className="max-w-full overflow-x-auto px-2 pb-3 pt-1">
      <svg role="group" aria-label={`Service ${route.serviceId}, direction ${route.directionId}, ordered route stops and bunching detections`}
        width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="block max-w-none">
        <line x1={leftPadding - 22} y1={routeY} x2={xFor(route.stops.length - 1) + 22} y2={routeY}
          stroke="var(--border)" strokeWidth="4" strokeLinecap="round" />
        {route.stops.map((stop, index) => <g key={`${stop.stopCode}:${stop.stopSequence}`}>
          <title>{stop.description || `Stop ${stop.stopCode}`}</title>
          <circle cx={xFor(index)} cy={routeY} r="5" fill="var(--background)" stroke="var(--muted-foreground)" strokeWidth="2" />
          <text x={xFor(index)} y={routeY + 18} textAnchor="middle" fontSize="10" fill="currentColor" className="font-medium text-foreground">{stop.stopCode}</text>
          <text x={xFor(index)} y={routeY + 33} textAnchor="middle" fontSize="9" fill="currentColor" className="text-muted-foreground">
            {stopNameLines(stop.description).map((line, lineIndex) => <tspan key={lineIndex} x={xFor(index)} dy={lineIndex === 0 ? 0 : 11}>{line}</tspan>)}
          </text>
          {(markers.get(index) ?? []).map((alert, markerIndex) => {
            const y = 18 + markerIndex * 22
            const gap = tightestArrivalGap(alert)
            const severity = gapSeverity(gap)
            const style = severity ? ROUTE_STRIP_GAP_SEVERITY[severity] : null
            const selected = selectedId === alert.eventId
            return <g key={`${alert.eventId}:${markerIndex}`} role="button" tabIndex={0} aria-pressed={selected}
              aria-label={alertLabel(alert)}
              className={`cursor-pointer outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring ${style?.markerClassName ?? 'text-muted-foreground'}`}
              onClick={() => onSelect(alert)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onSelect(alert) }
              }}>
              <title>{alertLabel(alert)}</title>
              <line x1={xFor(index)} y1={y + 11} x2={xFor(index)} y2={routeY - 7} stroke="currentColor" strokeWidth="1.5" />
              <rect x={xFor(index) - 31} y={y - 10} width="62" height="20" rx="10" fill="var(--background)"
                stroke="currentColor" strokeWidth={selected ? 3 : 2} />
              <text x={xFor(index)} y={y + 4} textAnchor="middle" fontSize="10" fontWeight="600" fill="currentColor">
                {formatGap(gap)}
              </text>
            </g>
          })}
        </g>)}
      </svg>
    </div>
  </div>
}

export function ControllerRouteStrip({ alerts, run, stops, loading }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const eventError = run.error || (!simulation ? alerts.error : '')
  const events = loading || eventError ? noEvents : simulation ? simulation.alerts : alerts.data?.items ?? noEvents
  const routes = !loading ? stops.data ?? noRoutes : noRoutes

  const { eventsByRoute, unplaced } = useMemo(() => placeRouteAlerts(events, routes), [events, routes])

  const selected = events.find(alert => alert.eventId === selectedId) ?? null
  const selectedStop = selected && routes.flatMap(route => route.serviceId === selected.serviceId &&
    (selected.directionId == null || route.directionId === selected.directionId) ? route.stops : [])
    .find(stop => stop.stopCode === selected.stopCode)
  const selectedOccurrences = selected ? routes.flatMap(route => route.serviceId === selected.serviceId &&
    (selected.directionId == null || route.directionId === selected.directionId) ? route.stops : [])
    .filter(stop => stop.stopCode === selected.stopCode).length : 0
  const routeError = stops.error && routes.length ? 'Some route references could not be loaded; available routes are shown.' : stops.error

  return <section aria-labelledby="route-strip-title" className="space-y-4">
    <div>
      <h2 id="route-strip-title" className="font-medium">Route strip</h2>
      <p className="mt-1 text-xs text-muted-foreground">Stops follow imported route order. Markers show gaps between saved arrival predictions; bus positions and vehicle identities are not available here.</p>
      {simulation && <p className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">Simulation events; not live incidents.</p>}
      {!simulation && <p className="mt-1 text-xs text-muted-foreground">Live detections are observation only.</p>}
    </div>

    {!routes.length ? <DataMessage error={stops.error || 'Route reference data is unavailable.'} loading={loading} empty={false}>Route reference data is unavailable.</DataMessage> : <>
      <DataMessage error={eventError} loading={loading} empty={!events.length}>No bunching detections.</DataMessage>
      {routeError && routes.length > 0 && <p role="status" className="text-xs text-amber-700 dark:text-amber-400">{routeError}</p>}
      <Card className="overflow-hidden">
        <div>
          {routes.map(route => <RouteRow key={routeKey(route)} route={route} events={eventsByRoute.get(routeKey(route)) ?? []}
            selectedId={selectedId} onSelect={alert => setSelectedId(alert.eventId)} />)}
        </div>
        <CardContent className="flex flex-wrap gap-x-5 gap-y-2 border-t py-3 text-xs text-muted-foreground">
          {Object.entries(ROUTE_STRIP_GAP_SEVERITY).map(([key, severity]) => <span key={key} className="inline-flex items-center gap-2">
            <span className={`size-2.5 rounded-full ${severity.legendClassName}`} aria-hidden="true" />{severity.shortLabel}
          </span>)}
          <span className="inline-flex items-center gap-2"><span className="size-2.5 rounded-full border-2 border-muted-foreground" aria-hidden="true" />Bus stop</span>
        </CardContent>
      </Card>

      {unplaced.length > 0 && <p role="status" className="text-xs text-muted-foreground">{unplaced.length} detection{unplaced.length === 1 ? '' : 's'} could not be placed because the stop or direction did not match the route reference.</p>}

      <Card aria-live="polite">
        <CardContent className="space-y-3 py-4">
          {selected ? <>
            <div>
              <h3 className="font-medium">Service {selected.serviceId} · Direction {selected.directionId ?? 'unavailable'} · {selected.stopName || selectedStop?.description || selected.stopCode}</h3>
              <p className="text-xs text-muted-foreground">{selected.stopRoadName || selectedStop?.roadName ? `${selected.stopRoadName || selectedStop?.roadName} · ` : ''}{selected.stopCode}</p>
            </div>
            <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <div><dt className="text-xs text-muted-foreground">Arrival predictions</dt><dd className="mt-1">{bunchingArrivals(selected).map((value, index) => <span key={index} className="block">Bus {index + 1}: {value === 'Unavailable' || value === 'No prediction' ? value : timestamp(value)}</span>)}</dd></div>
              <div><dt className="text-xs text-muted-foreground">Predicted gaps</dt><dd className="mt-1">{arrivalGaps(selected).map(gap => {
                const severity = gap.seconds === null ? null : gapSeverity(gap.seconds)
                return <span key={gap.label} className={`block ${severity ? ROUTE_STRIP_GAP_SEVERITY[severity].markerClassName : ''}`}>
                  {gap.label}: {gap.seconds === null ? 'Unavailable' : `${gap.seconds} seconds (${ROUTE_STRIP_GAP_SEVERITY[severity!].label})`}
                </span>
              })}</dd></div>
              <div><dt className="text-xs text-muted-foreground">Detected</dt><dd className="mt-1">{timestamp(selected.detectedAt)}</dd></div>
              <div><dt className="text-xs text-muted-foreground">Classification</dt><dd className="mt-1">{selected.classification}</dd></div>
            </dl>
            {selectedOccurrences > 1 && <p className="text-xs text-muted-foreground">This stop appears more than once on the route; the detection does not identify which pass.</p>}
          </> : <p className="text-sm text-muted-foreground">Select a gap marker with a mouse, Enter or Space to see its saved arrival times and detection details.</p>}
        </CardContent>
      </Card>
    </>}
  </section>
}

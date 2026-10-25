import { useEffect, useMemo, useRef, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import type { Arrival, BunchingAlert, DemoRun, PageResult, RouteDirection, RoutePositionDirection, RoutePositions } from '@/lib/api'
import { formatSgtTime } from '@/lib/time'
import {
  arrivalDirectionMatches, estimateRouteHeadways, etaFallbacksForRoute, formatGap, gapSeverity, groupRouteIncidents,
  linkIncidentToPosition, oldestStopDataAgeSeconds, projectCoordinateToRoute, routeKey, routeStopDistances, ROUTE_STRIP_GAP_SEVERITY,
  routeDestination, type IncidentStripLink, type RouteDestination, type RouteIncident,
} from '@/lib/route-strip'
import { DataMessage } from './ControllerViews'
import type { Resource } from './ControllerPage'

type Props = {
  alerts: Resource<PageResult<BunchingAlert>>
  run: Resource<DemoRun>
  stops: Resource<RouteDirection[]>
  positions: Resource<RoutePositions[]>
  arrivals: Resource<Arrival[]>
  pollIntervalSeconds: number
  incidentGapToleranceSeconds: number
  lastUpdated: string
  loading: boolean
  fetchStopArrivals: (route: RouteDirection, stopCode: string, signal: AbortSignal) => Promise<Arrival[]>
  fetchStopName: (stopCode: string) => Promise<string | null>
}

type PositionSnapshot = { position: RoutePositionDirection; computedAt: string }
type PositionedIncident = RouteIncident & { stripLink: IncidentStripLink | null }

const stopSpacing = 140
const leftPadding = 64
const noAlerts: BunchingAlert[] = []
const noRoutes: RouteDirection[] = []
const noPositionSnapshots: RoutePositions[] = []

function destinationText(destination: RouteDestination, referenceStopNames: Map<string, string | null>) {
  if (destination.status === 'unavailable') return 'Destination unavailable'
  if (destination.status === 'multiple') return 'Multiple destinations'
  const stopName = destination.stopName || referenceStopNames.get(destination.stopCode)
  return `Towards ${stopName || `stop ${destination.stopCode}`}`
}

function stopNameLines(name: string | null) {
  const words = name?.trim().split(/\s+/).filter(Boolean) ?? []
  if (!words.length) return ['Name unavailable']
  const lines: string[] = []
  let line = ''
  for (const word of words) {
    if (line && `${line} ${word}`.length > 17) {
      lines.push(line)
      line = word
    } else line = line ? `${line} ${word}` : word
  }
  if (line) lines.push(line)
  return lines
}

function pairKey(frontOrder: number, followingOrder: number) {
  return `${frontOrder}:${followingOrder}`
}

function clockTime(value: string) {
  return formatSgtTime(value)
}

function formatDistance(meters: number | null) {
  if (meters === null) return 'Unavailable'
  return meters >= 1_000 ? `${(meters / 1_000).toFixed(1)} km apart` : `${meters} m apart`
}

function RouteRow({ route, destination, snapshot, arrivals, routes, incidents, showPositions, nowMs, pollIntervalSeconds, lastUpdated, fetchStopArrivals }: {
  route: RouteDirection
  destination: string
  snapshot?: PositionSnapshot
  arrivals: Arrival[]
  routes: RouteDirection[]
  incidents: PositionedIncident[]
  showPositions: boolean
  nowMs: number
  pollIntervalSeconds: number
  lastUpdated: string
  fetchStopArrivals: Props['fetchStopArrivals']
}) {
  const [selectedStopCode, setSelectedStopCode] = useState<string | null>(null)
  const [selectedArrivals, setSelectedArrivals] = useState<Arrival[]>([])
  const [selectedStopLoading, setSelectedStopLoading] = useState(false)
  const [selectedStopError, setSelectedStopError] = useState('')
  const stopRequest = useRef<AbortController | null>(null)
  useEffect(() => {
    if (!selectedStopCode) return
    const controller = new AbortController()
    stopRequest.current?.abort()
    stopRequest.current = controller
    void fetchStopArrivals(route, selectedStopCode, controller.signal).then(values => {
      if (controller.signal.aborted) return
      const currentTime = nowMs
      const next = values.filter(arrival => arrival.serviceId === route.serviceId && arrival.stopCode === selectedStopCode &&
        arrivalDirectionMatches(arrival, route, routes) && arrival.estimatedArrivalAt &&
        Date.parse(arrival.estimatedArrivalAt) > currentTime)
        .sort((first, second) => Date.parse(first.estimatedArrivalAt!) - Date.parse(second.estimatedArrivalAt!))
        .slice(0, 3)
      setSelectedArrivals(next)
    }).catch(() => {
      if (!controller.signal.aborted) setSelectedStopError('Unable to load arrivals for this stop. Try refreshing.')
    }).finally(() => {
      if (!controller.signal.aborted) setSelectedStopLoading(false)
    })
    return () => controller.abort()
  }, [selectedStopCode, lastUpdated, nowMs, route, routes, fetchStopArrivals])

  const toggleStopSelection = (stopCode: string) => {
    const closing = selectedStopCode === stopCode
    stopRequest.current?.abort()
    setSelectedStopCode(closing ? null : stopCode)
    setSelectedArrivals([])
    setSelectedStopError('')
    setSelectedStopLoading(!closing)
  }

  const position = showPositions ? snapshot?.position : undefined
  const buses = position?.buses ?? []
  const routeLengthM = position?.routeLengthM && position.routeLengthM > 0 ? position.routeLengthM : 100_000
  const routeDistances = routeStopDistances(route, routeLengthM)
  const routeSpan = Math.max(0, route.stops.length - 1) * stopSpacing
  const width = Math.max(620, leftPadding * 2 + routeSpan)
  const uncertaintyPxFor = (uncertaintyM: number) => routeLengthM > 0
    ? Math.min(40, Math.max(3, uncertaintyM / routeLengthM * routeSpan)) : 3
  const xForDistance = (distance: number) => {
    const boundedDistance = Math.max(0, Math.min(routeLengthM, distance))
    if (routeDistances.hasGeometry && route.stops.length > 1) {
      let segment = 0
      while (segment < route.stops.length - 2 && boundedDistance > routeDistances.distancesM[segment + 1]) segment++
      const startDistance = routeDistances.distancesM[segment]
      const endDistance = routeDistances.distancesM[segment + 1]
      const fraction = endDistance > startDistance
        ? Math.max(0, Math.min(1, (boundedDistance - startDistance) / (endDistance - startDistance))) : 0
      return leftPadding + (segment + fraction) * stopSpacing
    }
    return leftPadding + (routeLengthM > 0 ? boundedDistance / routeLengthM * routeSpan : 0)
  }
  const xForStop = (index: number) => leftPadding + index * stopSpacing
  const busLaneIntervals: Array<Array<{ start: number; end: number }>> = []
  const busLanes = buses.map(bus => {
    const x = xForDistance(bus.distanceAlongRouteM)
    const halfWidth = Math.max(15, uncertaintyPxFor(bus.uncertaintyM))
    const start = x - halfWidth - 4
    const end = x + halfWidth + 4
    let lane = busLaneIntervals.findIndex(intervals => intervals.every(interval => end < interval.start || start > interval.end))
    if (lane < 0) lane = busLaneIntervals.length
    busLaneIntervals[lane] ??= []
    busLaneIntervals[lane].push({ start, end })
    return lane
  })
  const stopLabels = route.stops.map(stop => stopNameLines(stop.description))
  const selectedStop = route.stops.find(stop => stop.stopCode === selectedStopCode)
  const selectedPoints = selectedArrivals.map((arrival, index) => ({
    arrival,
    label: `Next ${index + 1}`,
    projection: arrival.latitude != null && arrival.longitude != null
      ? projectCoordinateToRoute(route, arrival.latitude, arrival.longitude, routeLengthM) : null,
  }))
  const selectedFeedFetchedAt = selectedArrivals.map(arrival => Date.parse(arrival.fetchedAt))
    .filter(Number.isFinite).sort((first, second) => second - first)[0]
  const pointLabelWidths = selectedPoints.map(point => {
    const etaSeconds = Math.max(0, Math.round((Date.parse(point.arrival.estimatedArrivalAt ?? '') - nowMs) / 1000))
    return Math.max(52, `${point.label} · ${formatGap(etaSeconds)}`.length * 5.3 + 12)
  })
  const pointLaneIntervals: Array<Array<{ start: number; end: number }>> = []
  const pointLanes = selectedPoints.map((point, pointIndex) => {
    if (!point.projection) return 0
    const x = xForDistance(point.projection.distanceAlongRouteM)
    const halfWidth = pointLabelWidths[pointIndex] / 2
    const start = x - halfWidth
    const end = x + halfWidth
    let assignedLane = pointLaneIntervals.findIndex(intervals =>
      intervals.every(interval => end < interval.start - 8 || start > interval.end + 8))
    if (assignedLane < 0) assignedLane = pointLaneIntervals.length
    pointLaneIntervals[assignedLane] ??= []
    pointLaneIntervals[assignedLane].push({ start, end })
    return assignedLane
  })
  const routeY = (buses.length ? 64 + Math.max(0, busLaneIntervals.length - 1) * 24 : 108)
    + pointLaneIntervals.length * 22
  const labelLaneEnds: number[] = []
  const labelLanes = route.stops.map((_, index) => {
    const labelWidth = Math.max(36, ...stopLabels[index].map(line => line.length * 5.2))
    const x = xForStop(index)
    let lane = labelLaneEnds.findIndex(end => x - labelWidth / 2 >= end + 8)
    if (lane < 0) lane = labelLaneEnds.length
    labelLaneEnds[lane] = x + labelWidth / 2
    return lane
  })
  const labelLaneCount = Math.max(1, labelLaneEnds.length)
  const maxLabelLines = Math.max(1, ...stopLabels.map(lines => lines.length))
  const labelLineHeight = 12
  const labelLaneHeight = maxLabelLines * labelLineHeight + 8
  const labelsStartY = routeY + 48
  const labelsEndY = labelsStartY + (labelLaneCount - 1) * labelLaneHeight + (maxLabelLines - 1) * labelLineHeight + 8
  const etaFallbacks = position ? etaFallbacksForRoute(route, routes, arrivals, buses, routeLengthM, nowMs,
    pollIntervalSeconds * 2) : []
  const headways = estimateRouteHeadways(buses, etaFallbacks)
  const linksByPair = new Map<string, PositionedIncident[]>()
  incidents.forEach(incident => {
    if (incident.routeKey !== routeKey(route) || !incident.stripLink) return
    const key = pairKey(incident.stripLink.frontOrder, incident.stripLink.followingOrder)
    const current = linksByPair.get(key) ?? []
    current.push(incident)
    linksByPair.set(key, current)
  })
  const gapLaneIntervals: Array<Array<{ start: number; end: number }>> = []
  const gapLanes = headways.map(headway => {
    const front = buses.find(bus => bus.order === headway.frontOrder)
    const following = buses.find(bus => bus.order === headway.followingOrder)
    if (!front || !following) return 0
    const center = (xForDistance(front.distanceAlongRouteM) + xForDistance(following.distanceAlongRouteM)) / 2
    const label = headway.source === 'same-stop-eta' ? `Same-stop ETA ${formatGap(headway.seconds)}` :
      headway.source === 'estimate' ? `~${formatGap(headway.seconds)} estimate` : formatDistance(headway.distanceMeters)
    const halfWidth = Math.max(54, label.length * 6 + 14) / 2
    const start = center - halfWidth
    const end = center + halfWidth
    let lane = gapLaneIntervals.findIndex(intervals => intervals.every(interval => end < interval.start || start > interval.end))
    if (lane < 0) lane = gapLaneIntervals.length
    gapLaneIntervals[lane] ??= []
    gapLaneIntervals[lane].push({ start, end })
    return lane
  })
  const maxGapLanes = gapLaneIntervals.length
  const gapStartY = labelsEndY + 14
  const gapY = (index: number) => gapStartY + gapLanes[index] * 24
  const height = Math.max(labelsEndY + 18, gapStartY + maxGapLanes * 24 + 12)

  return <div className="border-b last:border-b-0">
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 pt-4">
      <div>
        <h3 className="text-sm font-medium">Service {route.serviceId} · {destination}</h3>
        {position && position.suppressedBusCount ? <p role="status" className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">
          {buses.length} feed-supported buses · {position.suppressedBusCount} extra estimate hidden
        </p> : null}
      </div>
      <div className="flex flex-col items-start text-xs text-muted-foreground sm:items-end">
        <span>{route.operator} · {route.stops.length} stops</span>
        <span>Positions updated {snapshot ? clockTime(snapshot.computedAt) : 'unavailable'}</span>
      </div>
    </div>
    <div className="max-w-full overflow-x-auto px-2 pb-3 pt-1">
      <svg role="group" aria-label={`Service ${route.serviceId}, ${destination}, ${route.operator}, direction ${route.directionId}, approximate bus positions and headways`}
        width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="block max-w-none">
        <line x1={leftPadding - 22} y1={routeY} x2={leftPadding + routeSpan + 22} y2={routeY}
          stroke="var(--border)" strokeWidth="4" strokeLinecap="round" />
        {route.stops.map((stop, index) => {
          const x = xForStop(index)
          const selected = selectedStopCode === stop.stopCode
          return <g key={`${stop.stopCode}:${stop.stopSequence}`} role="button" tabIndex={0}
            aria-label={`${stop.description || `Stop ${stop.stopCode}`}, stop ${stop.stopCode}. Select to show the next three arrival locations.`}
            aria-pressed={selected} className="cursor-pointer outline-none focus-visible:stroke-sky-700"
            onClick={() => toggleStopSelection(stop.stopCode)}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                toggleStopSelection(stop.stopCode)
              }
            }}>
            <title>{stop.description || `Stop ${stop.stopCode}`}. Select to show the next three arrival locations.</title>
            <circle cx={x} cy={routeY} r={selected ? 7 : 5} fill={selected ? 'var(--primary)' : 'var(--background)'} stroke={selected ? 'var(--primary)' : 'var(--muted-foreground)'} strokeWidth="2" />
            <text x={x} y={routeY + 30} textAnchor="middle" fontSize="10" fontWeight="600" fill="currentColor" className="text-foreground">{stop.stopCode}</text>
            <text x={x} y={labelsStartY + labelLanes[index] * labelLaneHeight} textAnchor="middle" fontSize="9" fill="currentColor" className="text-muted-foreground">
              {stopLabels[index].map((line, lineIndex) => <tspan key={lineIndex} x={x} dy={lineIndex === 0 ? 0 : labelLineHeight}>{line}</tspan>)}
            </text>
          </g>
        })}
        {headways.map((headway, index) => {
          const front = buses.find(bus => bus.order === headway.frontOrder)
          const following = buses.find(bus => bus.order === headway.followingOrder)
          if (!front || !following) return null
          const firstX = xForDistance(front.distanceAlongRouteM)
          const secondX = xForDistance(following.distanceAlongRouteM)
          const center = (firstX + secondX) / 2
          const y = gapY(index)
          const severity = gapSeverity(headway.seconds)
          const style = severity ? ROUTE_STRIP_GAP_SEVERITY[severity] : null
          const label = headway.source === 'same-stop-eta' ? `Same-stop ETA ${formatGap(headway.seconds)}` :
            headway.source === 'estimate' ? `~${formatGap(headway.seconds)} estimate` : formatDistance(headway.distanceMeters)
          const labelWidth = Math.max(54, label.length * 6 + 14)
          const linked = linksByPair.get(pairKey(headway.frontOrder, headway.followingOrder)) ?? []
          const sourceLabel = headway.source === 'distance' ? 'Approximate position separation; time headway unavailable.' :
            headway.source === 'estimate' ? 'Approximate time from position gap divided by estimated speed.' :
              headway.source === 'same-stop-eta' ? `Arrival-time difference at stop ${headway.etaStopCode}; both readings came from the same poll snapshot.` :
                'Position separation unavailable.'
          const description = `Estimated buses ${headway.frontOrder} and ${headway.followingOrder}: ${label}. ${sourceLabel}${linked.length ? ` Linked to ${linked.length} incident${linked.length === 1 ? '' : 's'}.` : ''}`
          return <g key={pairKey(headway.frontOrder, headway.followingOrder)} role="img" aria-label={description}>
            <title>{description}</title>
            <path d={`M${Math.min(firstX, secondX)} ${y - 5}V${y}H${Math.max(firstX, secondX)}V${y - 5}`}
              fill="none" stroke={style ? `currentColor` : 'var(--muted-foreground)'} strokeWidth="2"
              className={style?.markerClassName ?? 'text-muted-foreground'} />
            <rect x={center - labelWidth / 2} y={y + 3} width={labelWidth} height="18" rx="9"
              fill="var(--background)" stroke="currentColor" strokeWidth="1.5"
              className={style?.markerClassName ?? 'text-muted-foreground'} />
            <text x={center} y={y + 16} textAnchor="middle" fontSize="9" fontWeight="600" fill="currentColor"
              className={style?.markerClassName ?? 'text-muted-foreground'}>{label}</text>
            {linked.length > 0 && <circle cx={center + labelWidth / 2 - 1} cy={y + 4} r="4" fill="var(--destructive)" />}
          </g>
        })}
        {buses.map((bus, index) => {
          const x = xForDistance(bus.distanceAlongRouteM)
          const uncertaintyPx = uncertaintyPxFor(bus.uncertaintyM)
          const description = `Estimated bus ${bus.order}, approximate position ${Math.round(bus.distanceAlongRouteM)} metres along the route, plus or minus ${Math.round(bus.uncertaintyM)} metres. This is an order estimate, not a verified vehicle identity.`
          const markerY = routeY - busLanes[index] * 24
          const uncertaintyY = markerY + 6
          return <g key={bus.order} role="img" aria-label={description} className="text-sky-700 dark:text-sky-400">
            <title>{description}</title>
            <rect x={x - uncertaintyPx} y={uncertaintyY} width={uncertaintyPx * 2} height="5" rx="2.5" fill="currentColor" opacity=".55" />
            <line x1={x - uncertaintyPx} y1={uncertaintyY - 2} x2={x - uncertaintyPx} y2={uncertaintyY + 7} stroke="currentColor" strokeWidth="1.25" />
            <line x1={x + uncertaintyPx} y1={uncertaintyY - 2} x2={x + uncertaintyPx} y2={uncertaintyY + 7} stroke="currentColor" strokeWidth="1.25" />
            <rect x={x - 15} y={markerY - 28} width="30" height="22" rx="5" fill="var(--background)" stroke="currentColor" strokeWidth="2" />
            <text x={x} y={markerY - 13} textAnchor="middle" fontSize="10" fontWeight="600" fill="currentColor">#{bus.order}</text>
          </g>
        })}
        {selectedPoints.map((point, index) => {
          if (!point.projection) return null
          const x = xForDistance(point.projection.distanceAlongRouteM)
          const labelY = routeY - Math.max(0, busLaneIntervals.length - 1) * 24 - 56 - pointLanes[index] * 22
          const etaSeconds = Math.max(0, Math.round((Date.parse(point.arrival.estimatedArrivalAt ?? '') - nowMs) / 1000))
          const label = `${point.label} · ${formatGap(etaSeconds)}`
          const labelWidth = Math.max(52, label.length * 5.3 + 12)
          const description = `${label}, DataMall reported location projected onto the route, approximately ${Math.round(point.projection.offsetMeters)} metres from the straight route line.`
          return <g key={point.arrival.observationId} role="img" aria-label={description} className="text-violet-700 dark:text-violet-400">
            <title>{description}</title>
            <line x1={x} y1={labelY + 18} x2={x} y2={routeY - 7} stroke="currentColor" strokeWidth="1.5" />
            <rect x={x - labelWidth / 2} y={labelY} width={labelWidth} height="18" rx="9" fill="var(--background)" stroke="currentColor" strokeWidth="1.5" />
            <text x={x} y={labelY + 12} textAnchor="middle" fontSize="9" fontWeight="600" fill="currentColor">{label}</text>
            <circle cx={x} cy={routeY} r="6" fill="currentColor" stroke="var(--background)" strokeWidth="2" />
          </g>
        })}
      </svg>
    </div>
    {selectedStopCode && <div className="mx-4 mb-3 rounded-lg border bg-muted/20 p-3" role="status" aria-live="polite">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 className="text-sm font-medium">Next arrivals at {selectedStop?.description || `Stop ${selectedStopCode}`}</h4>
        <span className="text-xs text-muted-foreground">Stop {selectedStopCode} · Service {route.serviceId}, direction {route.directionId}
          {selectedFeedFetchedAt != null ? ` · Feed updated ${formatSgtTime(new Date(selectedFeedFetchedAt).toISOString())}` : ''}</span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">Purple dots use the location reported with each arrival prediction, projected onto this straight route line. They do not identify a physical bus.</p>
      {selectedStopLoading ? <p className="mt-2 text-sm text-muted-foreground">Loading latest arrivals…</p> : selectedStopError ?
        <p className="mt-2 text-sm text-amber-700 dark:text-amber-400">{selectedStopError}</p> : selectedPoints.length ?
          <ol className="mt-2 grid gap-2 text-sm sm:grid-cols-3">
            {selectedPoints.map(point => {
              const etaSeconds = Math.max(0, Math.round((Date.parse(point.arrival.estimatedArrivalAt ?? '') - nowMs) / 1000))
              return <li key={point.arrival.observationId} className="rounded-md border bg-background p-2">
                <strong>{point.label}: {formatGap(etaSeconds)}</strong>
                <span className="mt-1 block text-xs text-muted-foreground">{point.projection
                  ? `Reported location · ${Math.round(point.projection.offsetMeters)} m from route line`
                  : 'Arrival time available · location unavailable'}</span>
              </li>
            })}
          </ol> : <p className="mt-2 text-sm text-muted-foreground">No upcoming arrival predictions are available for this stop and direction.</p>}
    </div>}
    {showPositions && !position && <p role="status" className="px-4 pb-3 text-xs text-muted-foreground">No current position estimate for this direction.</p>}
    {position && !routeDistances.hasGeometry && <p className="px-4 pb-3 text-xs text-muted-foreground">Route stops use even spacing because stop coordinates are incomplete; bus positions remain approximate.</p>}
  </div>
}

export function ControllerRouteStrip({ alerts, run, stops, positions, arrivals, pollIntervalSeconds, incidentGapToleranceSeconds, lastUpdated,
  loading, fetchStopArrivals, fetchStopName }: Props) {
  const [selectedRouteKey, setSelectedRouteKey] = useState('')
  const [referenceStopNames, setReferenceStopNames] = useState<{
    fetcher: Props['fetchStopName']; names: Map<string, string | null>
  } | null>(null)
  const stopNameRequests = useRef(new WeakMap<Props['fetchStopName'], Map<string, Promise<string | null>>>())
  const simulationRun = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const events = simulationRun ? simulationRun.alerts : alerts.data?.items ?? noAlerts
  const routes = stops.data ?? noRoutes
  const routeKeys = routes.map(routeKey)
  const [previousRouteKeys, setPreviousRouteKeys] = useState(routeKeys)
  // Keep the user's choice through data refreshes, but reset it if that route identity disappears.
  if (routeKeys.length !== previousRouteKeys.length || routeKeys.some((key, index) => key !== previousRouteKeys[index])) {
    setPreviousRouteKeys(routeKeys)
    if (selectedRouteKey && !routeKeys.includes(selectedRouteKey)) setSelectedRouteKey(routeKeys[0] ?? '')
  }
  const selectedRoute = routes.find(route => routeKey(route) === selectedRouteKey) ?? routes[0]
  const nowMs = Date.parse(lastUpdated) || 0
  const currentPositions = positions.data ?? noPositionSnapshots
  const positionByRoute = useMemo(() => new Map<string, PositionSnapshot>(currentPositions.flatMap(snapshot =>
    snapshot.directions.map(direction => [
      `${snapshot.serviceId}:${direction.operator}:${direction.directionId}`,
      { position: direction, computedAt: snapshot.computedAt },
    ] as const))), [currentPositions])
  const pollSeconds = currentPositions.length ? Math.min(...currentPositions.map(snapshot => snapshot.refreshIntervalSeconds)) : pollIntervalSeconds
  const incidents = useMemo(() => groupRouteIncidents(events, routes, nowMs, pollSeconds, incidentGapToleranceSeconds),
    [events, routes, nowMs, pollSeconds, incidentGapToleranceSeconds])
  const positionedIncidents = useMemo(() => incidents.map(incident => {
    const route = routes.find(candidate => routeKey(candidate) === incident.routeKey)
    const snapshot = route ? positionByRoute.get(routeKey(route)) : undefined
    return {
      ...incident,
      stripLink: !simulationRun && route
        ? linkIncidentToPosition(incident, route, snapshot?.position, snapshot?.computedAt, pollSeconds) : null,
    }
  }), [incidents, routes, positionByRoute, pollSeconds, simulationRun])

  const routeDestinations = useMemo(() => new Map(routes.map(route => [
    routeKey(route), arrivals.error ? { status: 'unavailable' } as const : routeDestination(route, routes, arrivals.data ?? []),
  ])), [routes, arrivals.data, arrivals.error])
  const externalStopCodes = useMemo(() => [...new Set([...routeDestinations.values()]
    .filter((destination): destination is Extract<RouteDestination, { status: 'resolved' }> =>
      destination.status === 'resolved' && !destination.stopName)
    .map(destination => destination.stopCode))], [routeDestinations])
  useEffect(() => {
    if (!externalStopCodes.length) return
    let active = true
    const cached = stopNameRequests.current.get(fetchStopName) ?? new Map<string, Promise<string | null>>()
    stopNameRequests.current.set(fetchStopName, cached)
    const loadName = async (stopCode: string) => {
      let request = cached.get(stopCode)
      if (!request) {
        request = fetchStopName(stopCode).then(name => name?.trim() || null)
        request.catch(() => cached.delete(stopCode))
        cached.set(stopCode, request)
      }
      try { return [stopCode, await request] as const }
      catch { return [stopCode, null] as const }
    }
    void Promise.all(externalStopCodes.map(loadName)).then(entries => {
      if (active) {
        setReferenceStopNames(previous => ({
          fetcher: fetchStopName,
          names: new Map([...(previous?.fetcher === fetchStopName ? previous.names : []), ...entries]),
        }))
      }
    })
    return () => { active = false }
  }, [externalStopCodes, fetchStopName])
  const stopNames = referenceStopNames?.fetcher === fetchStopName ? referenceStopNames.names : new Map<string, string | null>()
  const sourceAgeSeconds = selectedRoute ? oldestStopDataAgeSeconds(arrivals.data ?? [], [selectedRoute], nowMs) : null
  const stale = sourceAgeSeconds !== null && sourceAgeSeconds > pollSeconds * 2
  const routeError = stops.error && routes.length ? 'Some route references could not be loaded; available routes are shown.' : stops.error
  const dataError = [positions.error, arrivals.error].filter(Boolean).join(' ')

  return <section aria-labelledby="route-strip-title" className="space-y-4">
    <div>
      <h2 id="route-strip-title" className="font-medium">Approximate Live Route Strip</h2>
      {simulationRun && <p className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">Simulation alerts · route positions remain live.</p>}
      {positions.error && <p role="status" className="mt-1 text-xs text-amber-700 dark:text-amber-400">Estimated positions could not be refreshed; showing available estimates.</p>}
      {arrivals.error && <p role="status" className="mt-1 text-xs text-amber-700 dark:text-amber-400">Route arrivals are incomplete; destinations and same-stop gaps may be unavailable.</p>}
      {stale && <p role="status" className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">Arrival data is stale; treat positions and headways as approximate.</p>}
      {dataError && !stale && sourceAgeSeconds === null && <p role="status" className="mt-1 text-xs text-amber-700 dark:text-amber-400">Current source data is unavailable; estimates cannot be confirmed.</p>}
      {routeError && <p role="status" className="mt-1 text-xs text-amber-700 dark:text-amber-400">{routeError}</p>}
    </div>

    {!routes.length ? <DataMessage error={stops.error || 'Route reference data is unavailable.'} loading={loading} empty={false}>Route reference data is unavailable.</DataMessage> : <>
      <div className="flex flex-wrap items-center gap-3">
        <label htmlFor="route-strip-selector" className="text-sm font-medium">Route</label>
        <select id="route-strip-selector" aria-label="Route" value={selectedRoute ? routeKey(selectedRoute) : ''}
          onChange={event => setSelectedRouteKey(event.target.value)}
          className="h-10 min-w-64 max-w-full rounded-md border bg-background px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring">
          {routes.map(route => <option key={routeKey(route)} value={routeKey(route)}>
            {`Service ${route.serviceId} · ${destinationText(routeDestinations.get(routeKey(route)) ?? { status: 'unavailable' }, stopNames)} · ${route.operator} · Direction ${route.directionId}`}
          </option>)}
        </select>
      </div>
      <Card className="overflow-hidden">
        {selectedRoute && <RouteRow key={routeKey(selectedRoute)} route={selectedRoute}
          destination={destinationText(routeDestinations.get(routeKey(selectedRoute)) ?? { status: 'unavailable' }, stopNames)}
          snapshot={positionByRoute.get(routeKey(selectedRoute))} arrivals={arrivals.data ?? []} routes={routes}
          incidents={positionedIncidents} showPositions nowMs={nowMs} pollIntervalSeconds={pollSeconds}
          lastUpdated={lastUpdated} fetchStopArrivals={fetchStopArrivals} />}
        <CardContent className="flex flex-wrap gap-x-5 gap-y-2 border-t py-3 text-xs text-muted-foreground">
          {Object.entries(ROUTE_STRIP_GAP_SEVERITY).map(([key, severity]) => <span key={key} className="inline-flex items-center gap-2">
            <span className={`size-2.5 rounded-full ${severity.legendClassName}`} aria-hidden="true" />{severity.shortLabel}
          </span>)}
          <span className="inline-flex items-center gap-2"><span className="size-2.5 rounded-full border-2 border-muted-foreground" aria-hidden="true" />Bus stop</span>
          <span className="inline-flex items-center gap-2"><span className="size-2.5 rounded-full border-2 border-sky-700 dark:border-sky-400" aria-hidden="true" />Estimated bus · uncertainty bar</span>
          <span className="inline-flex items-center gap-2"><span className="size-2.5 rounded-full bg-violet-700 dark:bg-violet-400" aria-hidden="true" />Selected-stop arrival location</span>
          <span className="inline-flex items-center gap-2"><span className="size-2.5 rounded-full bg-destructive" aria-hidden="true" />Incident linked by service, direction, time and stop position</span>
        </CardContent>
      </Card>
    </>}
  </section>
}

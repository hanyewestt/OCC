import type { Arrival, BunchingAlert, RouteDirection, RoutePositionBus, RoutePositionDirection, RoutePositions } from './api'
import { bunchingArrivals } from './bunching'

export const ROUTE_STRIP_GAP_SEVERITY = {
  close: {
    label: 'Under 90 seconds',
    shortLabel: 'Under 90 s',
    markerClassName: 'text-destructive',
    legendClassName: 'bg-destructive',
  },
  moderate: {
    label: '90 to 180 seconds',
    shortLabel: '90–180 s',
    markerClassName: 'text-amber-700 dark:text-amber-400',
    legendClassName: 'bg-amber-700 dark:bg-amber-400',
  },
  spaced: {
    label: 'Over 180 seconds',
    shortLabel: 'Over 180 s',
    markerClassName: 'text-emerald-700 dark:text-emerald-400',
    legendClassName: 'bg-emerald-700 dark:bg-emerald-400',
  },
} as const

export type GapSeverity = keyof typeof ROUTE_STRIP_GAP_SEVERITY
export type EstimatedRouteHeadway = {
  frontOrder: number
  followingOrder: number
  seconds: number | null
  distanceMeters: number | null
  source: 'distance' | 'estimate' | 'same-stop-eta' | 'unavailable'
  etaStopCode?: string
}
export type EtaHeadwayFallback = { seconds: number | null; stopCode: string }
export type RouteIncident = {
  id: string
  serviceId: string
  directionId: number | null
  routeKey: string | null
  alerts: BunchingAlert[]
  stopCodes: string[]
  stopNames: string[]
  firstDetectedAt: string
  latestDetectedAt: string
  gapSeconds: number | null
  ageSeconds: number
  historical: boolean
  latestStopIndex: number | null
}
export type IncidentStripLink = { frontOrder: number; followingOrder: number; stopCode: string; match: 'evidence' | 'approximate' }
export type PositionEvidenceAlignment = {
  arrivals: Arrival[]
  status: 'aligned' | 'different-sweep' | 'evidence-unavailable' | 'linkage-only' | 'no-arrivals'
}
export type PositionSourceFreshness = { oldestAgeSeconds: number | null; unavailableCount: number }

export function routeKey(route: RouteDirection) {
  return `${route.serviceId}:${route.operator}:${route.directionId}`
}

export function routePositionSnapshotIsDisplayed(snapshot: RoutePositions, routes: RouteDirection[]): boolean {
  return snapshot.directions.some(direction => routes.some(route => route.serviceId === snapshot.serviceId &&
    route.operator === direction.operator && route.directionId === direction.directionId))
}

export function gapSeverity(seconds: number | null): GapSeverity | null {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return null
  if (seconds < 90) return 'close'
  if (seconds <= 180) return 'moderate'
  return 'spaced'
}

export function formatGap(seconds: number | null): string {
  if (seconds === null) return 'Unavailable'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return remainingSeconds ? `${minutes}m ${remainingSeconds}s` : `${minutes}m`
}

function differenceSeconds(first: string, second: string): number | null {
  const firstTime = Date.parse(first)
  const secondTime = Date.parse(second)
  if (!Number.isFinite(firstTime) || !Number.isFinite(secondTime)) return null
  return Math.round(Math.abs(secondTime - firstTime) / 1000)
}

export function arrivalGaps(alert: BunchingAlert): { label: 'Bus 1–2' | 'Bus 2–3'; seconds: number | null }[] {
  const [first, second, third] = bunchingArrivals(alert)
  return [
    { label: 'Bus 1–2', seconds: differenceSeconds(first, second) },
    { label: 'Bus 2–3', seconds: differenceSeconds(second, third) },
  ]
}

export function tightestArrivalGap(alert: BunchingAlert): number | null {
  const known = arrivalGaps(alert).flatMap(gap => gap.seconds === null ? [] : [gap.seconds])
  return known.length ? Math.min(...known) : null
}

export function estimateRouteHeadways(buses: RoutePositionBus[], etaFallbacks: EtaHeadwayFallback[] = []): EstimatedRouteHeadway[] {
  const ordered = [...buses].sort((first, second) => first.order - second.order)
  return ordered.slice(0, -1).map((front, index) => {
    const following = ordered[index + 1]
    const distanceGap = front.distanceAlongRouteM - following.distanceAlongRouteM
    const eta = etaFallbacks[index]
    const validEta = eta !== undefined && eta.seconds !== null && Number.isFinite(eta.seconds) && eta.seconds >= 0
    const meanSpeed = front.speedMps !== null && following.speedMps !== null
      ? (front.speedMps + following.speedMps) / 2 : null
    const estimatedSeconds = meanSpeed !== null && Number.isFinite(meanSpeed) && meanSpeed > 0 && distanceGap >= 0
      ? Math.round(distanceGap / meanSpeed) : null
    return {
      frontOrder: front.order,
      followingOrder: following.order,
      seconds: validEta ? eta.seconds : estimatedSeconds,
      distanceMeters: Number.isFinite(distanceGap) && distanceGap >= 0 ? Math.round(distanceGap) : null,
      source: validEta ? 'same-stop-eta' as const : estimatedSeconds !== null ? 'estimate' as const :
        Number.isFinite(distanceGap) && distanceGap >= 0 ? 'distance' as const : 'unavailable' as const,
      ...(validEta ? { etaStopCode: eta.stopCode } : {}),
    }
  })
}

function alertRoute(alert: BunchingAlert, routes: RouteDirection[]) {
  const matchingStops = routes.filter(route => route.serviceId === alert.serviceId &&
    route.stops.some(stop => stop.stopCode === alert.stopCode))
  const directions = new Set(matchingStops.map(route => route.directionId))
  const candidates = alert.directionId == null
    ? directions.size === 1 ? matchingStops : []
    : matchingStops.filter(route => route.directionId === alert.directionId)
  if (candidates.length !== 1) return { route: undefined, directionId: alert.directionId ?? null, stopIndex: null }
  const route = candidates[0]
  const indices = route.stops.flatMap((stop, index) => stop.stopCode === alert.stopCode ? [index] : [])
  return { route, directionId: route.directionId, stopIndex: indices.length === 1 ? indices[0] : null }
}

type ResolvedAlert = {
  alert: BunchingAlert
  route: RouteDirection | undefined
  directionId: number | null
  stopIndex: number | null
  gapSeconds: number | null
}

function similarGaps(first: number | null, second: number | null, toleranceSeconds: number) {
  return first !== null && second !== null && Math.abs(first - second) <= toleranceSeconds
}

export function groupRouteIncidents(alerts: BunchingAlert[], routes: RouteDirection[], nowMs: number,
  pollIntervalSeconds: number, gapToleranceSeconds: number): RouteIncident[] {
  const resolved: ResolvedAlert[] = alerts.map(alert => ({
    alert,
    ...alertRoute(alert, routes),
    gapSeconds: tightestArrivalGap(alert),
  }))
  const parent = resolved.map((_, index) => index)
  const find = (index: number): number => parent[index] === index ? index : (parent[index] = find(parent[index]))
  const unite = (first: number, second: number) => { parent[find(first)] = find(second) }
  const windowMs = Math.max(0, pollIntervalSeconds * 2_000)

  for (let first = 0; first < resolved.length; first++) {
    const left = resolved[first]
    const leftDetectedAt = Date.parse(left.alert.detectedAt)
    if (!left.route || left.stopIndex === null || left.directionId === null || !Number.isFinite(leftDetectedAt)) continue
    for (let second = first + 1; second < resolved.length; second++) {
      const right = resolved[second]
      const rightDetectedAt = Date.parse(right.alert.detectedAt)
      if (!right.route || right.stopIndex === null || right.directionId === null ||
        !Number.isFinite(rightDetectedAt) ||
        left.alert.serviceId !== right.alert.serviceId || left.directionId !== right.directionId ||
        routeKey(left.route) !== routeKey(right.route) || Math.abs(left.stopIndex - right.stopIndex) > 1 ||
        Math.abs(leftDetectedAt - rightDetectedAt) > windowMs ||
        !similarGaps(left.gapSeconds, right.gapSeconds, gapToleranceSeconds)) continue
      unite(first, second)
    }
  }

  const groups = new Map<number, ResolvedAlert[]>()
  resolved.forEach((event, index) => {
    const root = find(index)
    const group = groups.get(root) ?? []
    group.push(event)
    groups.set(root, group)
  })

  return [...groups.values()].map(group => {
    const ordered = [...group].sort((first, second) => Date.parse(first.alert.detectedAt) - Date.parse(second.alert.detectedAt))
    const first = ordered[0]
    const latest = ordered.at(-1)!
    const firstDetectedAt = first.alert.detectedAt
    const latestDetectedAt = latest.alert.detectedAt
    const ageSeconds = Math.max(0, Math.floor((nowMs - Date.parse(firstDetectedAt)) / 1000))
    const firstBusEta = bunchingArrivals(latest.alert)[0]
    const firstBusEtaMs = Date.parse(firstBusEta)
    const passedPrediction = Number.isFinite(firstBusEtaMs) && firstBusEtaMs <= nowMs
    const gaps = ordered.flatMap(event => event.gapSeconds === null ? [] : [event.gapSeconds])
    const gapSeconds = gaps.length ? Math.round(gaps.reduce((sum, value) => sum + value, 0) / gaps.length) : null
    const uniqueStops = [...new Map(ordered.map(event => [event.alert.stopCode, event])).values()]
      .sort((left, right) => (left.stopIndex ?? Number.MAX_SAFE_INTEGER) - (right.stopIndex ?? Number.MAX_SAFE_INTEGER))
    return {
      id: ordered.map(event => event.alert.eventId).sort().join(':'),
      serviceId: first.alert.serviceId,
      directionId: first.directionId,
      routeKey: first.route ? routeKey(first.route) : null,
      alerts: ordered.map(event => event.alert),
      stopCodes: uniqueStops.map(event => event.alert.stopCode),
      stopNames: uniqueStops.map(event => event.alert.stopName || event.route?.stops[event.stopIndex ?? -1]?.description || event.alert.stopCode),
      firstDetectedAt,
      latestDetectedAt,
      gapSeconds,
      ageSeconds,
      historical: ageSeconds > pollIntervalSeconds * 3 || passedPrediction,
      latestStopIndex: latest.stopIndex,
    }
  }).sort((first, second) => Date.parse(second.latestDetectedAt) - Date.parse(first.latestDetectedAt))
}

function validCoordinate(latitude: number | null, longitude: number | null) {
  return latitude !== null && longitude !== null && Number.isFinite(latitude) && Number.isFinite(longitude) &&
    latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 && !(latitude === 0 && longitude === 0)
}

function haversineMeters(firstLatitude: number, firstLongitude: number, secondLatitude: number, secondLongitude: number) {
  const radians = (degrees: number) => degrees * Math.PI / 180
  const latitudeDelta = radians(secondLatitude - firstLatitude)
  const longitudeDelta = radians(secondLongitude - firstLongitude)
  const value = Math.sin(latitudeDelta / 2) ** 2 + Math.cos(radians(firstLatitude)) * Math.cos(radians(secondLatitude)) *
    Math.sin(longitudeDelta / 2) ** 2
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value))
}

export function routeStopDistances(route: RouteDirection, routeLengthM: number): { distancesM: number[]; hasGeometry: boolean } {
  const coordinates = route.stops.map(stop => validCoordinate(stop.latitude, stop.longitude))
  if (route.stops.length < 2 || coordinates.some(valid => !valid) || !Number.isFinite(routeLengthM) || routeLengthM <= 0) {
    return { distancesM: route.stops.map((_, index) => route.stops.length <= 1 ? 0 : routeLengthM * index / (route.stops.length - 1)), hasGeometry: false }
  }
  const distances = [0]
  for (let index = 1; index < route.stops.length; index++) {
    const first = route.stops[index - 1]
    const second = route.stops[index]
    distances.push(distances[index - 1] + haversineMeters(first.latitude!, first.longitude!, second.latitude!, second.longitude!))
  }
  const total = distances.at(-1) ?? 0
  return {
    distancesM: total > 0 ? distances.map(distance => distance / total * routeLengthM) : distances,
    hasGeometry: total > 0,
  }
}

export function arrivalDirectionMatches(arrival: Arrival, route: RouteDirection, routes: RouteDirection[]) {
  if (arrival.serviceId !== route.serviceId || arrival.operator !== route.operator) return false
  if (arrival.directionId !== null && arrival.directionId !== undefined) return arrival.directionId === route.directionId
  const directions = new Set(routes.filter(candidate => candidate.serviceId === arrival.serviceId && candidate.operator === arrival.operator &&
    candidate.stops.some(stop => stop.stopCode === arrival.stopCode)).map(candidate => candidate.directionId))
  return directions.size === 1 && directions.has(route.directionId)
}

export function alignArrivalsToPositionEvidence(arrivals: Arrival[], buses: RoutePositionBus[]): PositionEvidenceAlignment {
  const evidenceObservationIds = new Set(buses.flatMap(bus => bus.positionEvidenceObservationIds ?? []))
  if (evidenceObservationIds.size === 0) return { arrivals: [], status: 'evidence-unavailable' }
  if (arrivals.length === 0) return { arrivals: [], status: 'no-arrivals' }
  const aligned = arrivals.filter(arrival => evidenceObservationIds.has(arrival.observationId))
  if (aligned.length) return { arrivals: aligned, status: 'aligned' }
  const auditObservationIds = new Set(buses.flatMap(bus => bus.evidenceObservationIds ?? []))
  return {
    arrivals: [],
    status: arrivals.some(arrival => auditObservationIds.has(arrival.observationId)) ? 'linkage-only' : 'different-sweep',
  }
}

export function routePositionEvidenceDescription(bus: RoutePositionBus, displayedBusCount: number): string {
  const auditEvidenceIds = new Set(bus.evidenceObservationIds ?? [])
  const positionEvidenceIds = new Set(bus.positionEvidenceObservationIds ?? [])
  const positionEvidenceCount = positionEvidenceIds.size
  const linkageOnlyCount = [...auditEvidenceIds].filter(observationId => !positionEvidenceIds.has(observationId)).length
  const supportingStopCount = Number.isInteger(bus.supportingStopCount) && (bus.supportingStopCount ?? 0) > 0
    ? bus.supportingStopCount! : null
  const multiplicity = displayedBusCount === 1
    ? 'The displayed source sweep supports one distinct bus estimate.'
    : `The displayed source sweep supports ${displayedBusCount} distinct bus estimates.`
  const positionSupport = positionEvidenceCount
    ? `Its blue-position interval uses ${positionEvidenceCount} in-band source observation${positionEvidenceCount === 1 ? '' : 's'}.`
    : 'Position-specific source evidence is unavailable.'
  const stopSupport = supportingStopCount === null
    ? 'Track stop coverage is unavailable.'
    : `Track evidence spans ${supportingStopCount} stop${supportingStopCount === 1 ? '' : 's'}.`
  const auditSupport = linkageOnlyCount
    ? ` ${linkageOnlyCount} additional linkage observation${linkageOnlyCount === 1 ? '' : 's'} remain available for audit and do not place the selected-stop overlay.` : ''
  return `${multiplicity} ${positionSupport} ${stopSupport}${auditSupport} Physical vehicle identity remains unresolved; the marker number is route order only.`
}

export type RouteDestination =
  | { status: 'unavailable' }
  | { status: 'multiple' }
  | { status: 'resolved'; stopCode: string; stopName: string | null }

export function routeDestination(route: RouteDirection, routes: RouteDirection[], arrivals: Arrival[]): RouteDestination {
  const codes = new Set(arrivals.flatMap(arrival => {
    const code = arrival.destinationCode?.trim()
    return code && arrivalDirectionMatches(arrival, route, routes) ? [code] : []
  }))
  if (codes.size > 1) return { status: 'multiple' }
  const liveStopCode = codes.values().next().value
  const fallbackStop = route.stops[route.stops.length - 1]
  const stopCode = liveStopCode || fallbackStop?.stopCode
  if (!stopCode) return { status: 'unavailable' }
  const stopName = route.stops.find(stop => stop.stopCode === stopCode)?.description?.trim()
    || (fallbackStop?.stopCode === stopCode ? fallbackStop.description?.trim() : null)
    || null
  return { status: 'resolved', stopCode, stopName }
}

export function oldestStopDataAgeSeconds(arrivals: Arrival[], routes: RouteDirection[], nowMs: number): number | null {
  const latestByRouteStop = new Map<string, number>()
  for (const arrival of arrivals) {
    const fetchedAt = Date.parse(arrival.fetchedAt)
    if (!Number.isFinite(fetchedAt)) continue
    for (const route of routes) {
      if (route.serviceId !== arrival.serviceId ||
        !route.stops.some(stop => stop.stopCode === arrival.stopCode) ||
        !arrivalDirectionMatches(arrival, route, routes)) continue
      const key = `${routeKey(route)}:${arrival.stopCode}`
      latestByRouteStop.set(key, Math.max(latestByRouteStop.get(key) ?? Number.NEGATIVE_INFINITY, fetchedAt))
    }
  }
  if (latestByRouteStop.size === 0) return null
  const oldestLatestFetch = Math.min(...latestByRouteStop.values())
  return Math.max(0, (nowMs - oldestLatestFetch) / 1000)
}

export function positionSourceFreshness(snapshots: RoutePositions[], nowMs: number): PositionSourceFreshness {
  const observedAtValues = snapshots.map(snapshot => Date.parse(snapshot.sourceObservedAt ?? ''))
  const validObservedAtValues = observedAtValues.filter(Number.isFinite)
  return {
    oldestAgeSeconds: validObservedAtValues.length
      ? Math.max(0, (nowMs - Math.min(...validObservedAtValues)) / 1000) : null,
    unavailableCount: observedAtValues.length - validObservedAtValues.length,
  }
}

export function positionSourceIsStale(snapshot: RoutePositions, nowMs: number): boolean {
  const observedAtMs = Date.parse(snapshot.sourceObservedAt ?? '')
  return Number.isFinite(observedAtMs) && Number.isFinite(snapshot.maxSourceAgeSeconds)
    && snapshot.maxSourceAgeSeconds > 0
    && Math.max(0, (nowMs - observedAtMs) / 1000) > snapshot.maxSourceAgeSeconds
}

export type ProjectedRoutePoint = { distanceAlongRouteM: number; offsetMeters: number }
export type PositionEvidencePlacement = {
  busOrder: number
  displayDistanceAlongRouteM: number
  rawProjection: ProjectedRoutePoint | null
}

export function projectCoordinateToRoute(route: RouteDirection, latitude: number, longitude: number,
  routeLengthM: number): ProjectedRoutePoint | null {
  if (!validCoordinate(latitude, longitude) || !Number.isFinite(routeLengthM) || routeLengthM <= 0) return null
  const routeDistances = routeStopDistances(route, routeLengthM)
  if (!routeDistances.hasGeometry || route.stops.length < 2) return null

  const referenceLatitude = latitude * Math.PI / 180
  const toMeters = (pointLatitude: number, pointLongitude: number) => ({
    x: pointLongitude * Math.PI / 180 * 6_371_000 * Math.cos(referenceLatitude),
    y: pointLatitude * Math.PI / 180 * 6_371_000,
  })
  const point = toMeters(latitude, longitude)
  let closest: ProjectedRoutePoint | null = null
  for (let index = 0; index < route.stops.length - 1; index++) {
    const firstStop = route.stops[index]
    const secondStop = route.stops[index + 1]
    if (!validCoordinate(firstStop.latitude, firstStop.longitude) || !validCoordinate(secondStop.latitude, secondStop.longitude)) continue
    const first = toMeters(firstStop.latitude!, firstStop.longitude!)
    const second = toMeters(secondStop.latitude!, secondStop.longitude!)
    const dx = second.x - first.x
    const dy = second.y - first.y
    const lengthSquared = dx * dx + dy * dy
    if (lengthSquared === 0) continue
    const ratio = Math.max(0, Math.min(1, ((point.x - first.x) * dx + (point.y - first.y) * dy) / lengthSquared))
    const projectedX = first.x + ratio * dx
    const projectedY = first.y + ratio * dy
    const offsetMeters = Math.hypot(point.x - projectedX, point.y - projectedY)
    const distanceAlongRouteM = routeDistances.distancesM[index] +
      ratio * (routeDistances.distancesM[index + 1] - routeDistances.distancesM[index])
    if (!closest || offsetMeters < closest.offsetMeters) closest = { distanceAlongRouteM, offsetMeters }
  }
  return closest
}

export function placeArrivalAtPositionEvidence(arrival: Arrival, buses: RoutePositionBus[], route: RouteDirection,
  routeLengthM: number): PositionEvidencePlacement | null {
  const bus = buses.find(candidate => candidate.positionEvidenceObservationIds?.includes(arrival.observationId))
  if (!bus) return null
  const rawProjection = arrival.latitude != null && arrival.longitude != null
    ? projectCoordinateToRoute(route, arrival.latitude, arrival.longitude, routeLengthM) : null
  return { busOrder: bus.order, displayDistanceAlongRouteM: bus.distanceAlongRouteM, rawProjection }
}

function markerEvidenceObservationIds(bus: RoutePositionBus): string[] {
  return bus.positionEvidenceObservationIds ?? bus.evidenceObservationIds ?? []
}

export function etaFallbacksForRoute(route: RouteDirection, routes: RouteDirection[], arrivals: Arrival[], buses: RoutePositionBus[],
  routeLengthM: number, nowMs: number, maxAgeSeconds: number): EtaHeadwayFallback[] {
  const routeDistances = routeStopDistances(route, routeLengthM)
  const arrivalsByObservationId = new Map<string, Arrival>()
  for (const arrival of arrivals) {
    if (arrival.serviceId !== route.serviceId || !arrival.estimatedArrivalAt || !arrivalDirectionMatches(arrival, route, routes)) continue
    const fetchedAt = Date.parse(arrival.fetchedAt)
    const eta = Date.parse(arrival.estimatedArrivalAt)
    if (!Number.isFinite(fetchedAt) || fetchedAt > nowMs || nowMs - fetchedAt > maxAgeSeconds * 1000 ||
      !Number.isFinite(eta) || eta <= nowMs) continue
    arrivalsByObservationId.set(arrival.observationId, arrival)
  }

  const orderedBuses = [...buses].sort((first, second) => first.order - second.order)
  return orderedBuses.slice(0, -1).map((front, index) => {
    const following = orderedBuses[index + 1]
    const frontIds = new Set(markerEvidenceObservationIds(front))
    const followingIds = new Set(markerEvidenceObservationIds(following))
    const sharedStops = new Map<string, { stopDistance: number; frontEta: number; followingEta: number }>()
    for (const id of frontIds) {
      const first = arrivalsByObservationId.get(id)
      if (!first || !followingIds.size) continue
      for (const followingId of followingIds) {
        const second = arrivalsByObservationId.get(followingId)
        if (!second || first.stopCode !== second.stopCode || first.fetchedAt !== second.fetchedAt) continue
        const stopIndex = route.stops.findIndex(stop => stop.stopCode === first.stopCode)
        if (stopIndex < 0) continue
        const stopDistance = routeDistances.distancesM[stopIndex] ?? 0
        if (stopDistance < front.distanceAlongRouteM - front.uncertaintyM) continue
        const frontEta = Date.parse(first.estimatedArrivalAt!)
        const followingEta = Date.parse(second.estimatedArrivalAt!)
        if (followingEta < frontEta || followingEta - frontEta > 30 * 60_000) continue
        const existing = sharedStops.get(first.stopCode)
        if (!existing || stopDistance < existing.stopDistance) {
          sharedStops.set(first.stopCode, { stopDistance, frontEta, followingEta })
        }
      }
    }

    const closest = [...sharedStops.entries()].sort((first, second) => first[1].stopDistance - second[1].stopDistance)[0]
    if (closest) {
      const seconds = Math.round((closest[1].followingEta - closest[1].frontEta) / 1000)
      if (seconds >= 0) return { seconds, stopCode: closest[0] }
    }
    return { seconds: null, stopCode: '' }
  })
}

export function linkIncidentToPosition(incident: RouteIncident, route: RouteDirection,
  position: RoutePositionDirection | undefined, positionComputedAt: string | undefined, maxSourceAgeSeconds: number): IncidentStripLink | null {
  if (!position || incident.routeKey !== routeKey(route) || incident.latestStopIndex === null ||
    position.buses.length < 2 || !routeStopDistances(route, position.routeLengthM).hasGeometry) return null
  const computedAt = Date.parse(positionComputedAt ?? '')
  if (!Number.isFinite(computedAt)) return null
  const stop = route.stops[incident.latestStopIndex]
  if (!stop || route.stops.filter(value => value.stopCode === stop.stopCode).length !== 1) return null
  const { distancesM } = routeStopDistances(route, position.routeLengthM)
  const stopDistance = distancesM[incident.latestStopIndex]
  const ordered = [...position.buses].sort((first, second) => first.order - second.order)

  // Prefer a direct link: bunching evidence and a route marker share the same
  // normalized arrival observation IDs. This is stronger than matching by
  // proximity, while still describing observations rather than physical buses.
  for (const alert of incident.alerts) {
    const evidence = alertEvidence(alert)
    if (!evidence || Math.abs(computedAt - evidence.fetchedAt) > maxSourceAgeSeconds * 1_000) continue
    for (const pair of evidence.bunchedPairs) {
      const front = ordered.find(bus => markerEvidenceObservationIds(bus).includes(pair.leadingObservationId))
      const following = ordered.find(bus => markerEvidenceObservationIds(bus).includes(pair.followingObservationId))
      // The IDs are from the alert's arrival snapshot at this exact stop.
      // They establish the observation link even when the predicted arrival is
      // still some distance away from the stop.
      if (front && following && front.order !== following.order) {
        return { frontOrder: front.order, followingOrder: following.order, stopCode: stop.stopCode, match: 'evidence' }
      }
    }
  }

  const latestAlert = incident.alerts.at(-1)
  const sourceFetchedAt = latestAlert ? alertEvidence(latestAlert)?.fetchedAt : undefined
  const comparisonTime = sourceFetchedAt ?? (latestAlert?.fetchedAt ? Date.parse(latestAlert.fetchedAt) : Date.parse(incident.latestDetectedAt))
  if (!Number.isFinite(comparisonTime) || Math.abs(computedAt - comparisonTime) > maxSourceAgeSeconds * 1_000) return null
  const ageAtSnapshotSeconds = Math.max(0, (computedAt - comparisonTime) / 1000)
  for (let index = 0; index < ordered.length - 1; index++) {
    const front = ordered[index]
    const following = ordered[index + 1]
    const frontAtDetection = front.distanceAlongRouteM - (front.speedMps ?? 0) * ageAtSnapshotSeconds
    const followingAtDetection = following.distanceAlongRouteM - (following.speedMps ?? 0) * ageAtSnapshotSeconds
    const low = Math.min(frontAtDetection, followingAtDetection) - front.uncertaintyM - following.uncertaintyM
    const high = Math.max(frontAtDetection, followingAtDetection) + front.uncertaintyM + following.uncertaintyM
    if (stopDistance >= low && stopDistance <= high) {
      return { frontOrder: front.order, followingOrder: following.order, stopCode: stop.stopCode, match: 'approximate' }
    }
  }
  return null
}

function alertEvidence(alert: BunchingAlert): { fetchedAt: number; bunchedPairs: { leadingObservationId: string; followingObservationId: string }[] } | null {
  if (!alert.evidence) return null
  try {
    const value = JSON.parse(alert.evidence) as {
      snapshot?: { fetchedAt?: string }
      groups?: Array<{ headways?: Array<{ leadingObservationId?: string; followingObservationId?: string; isBunched?: boolean }> }>
    }
    const fetchedAt = Date.parse(value.snapshot?.fetchedAt ?? '')
    if (!Number.isFinite(fetchedAt)) return null
    const bunchedPairs = (value.groups ?? []).flatMap(group => (group.headways ?? [])
      .filter(headway => headway.isBunched === true && headway.leadingObservationId && headway.followingObservationId)
      .map(headway => ({ leadingObservationId: headway.leadingObservationId!, followingObservationId: headway.followingObservationId! })))
    return { fetchedAt, bunchedPairs }
  } catch {
    return null
  }
}

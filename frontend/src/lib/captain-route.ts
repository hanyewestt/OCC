import type { CaptainRoute, CaptainRouteStop } from './api'

export type RouteProgress = {
  passedCount: number
  last: CaptainRouteStop | null
  next: CaptainRouteStop | null
  ahead: CaptainRouteStop[]
}

/** Splits the route around the bus position; an unknown position shows every stop as ahead. */
export function routeProgress(route: Pick<CaptainRoute, 'stops' | 'position'>): RouteProgress {
  const { stops, position } = route
  const lastIndex = stops.findIndex(stop => stop.sequence === position.lastStopSequence)
  const nextIndex = stops.findIndex(stop => stop.sequence === position.nextStopSequence)
  if (position.source !== 'SIMULATION' || (lastIndex < 0 && nextIndex < 0)) {
    return { passedCount: 0, last: null, next: null, ahead: stops }
  }
  const aheadFrom = nextIndex >= 0 ? nextIndex + 1 : lastIndex + 1
  return {
    passedCount: Math.max(0, (nextIndex >= 0 ? nextIndex : lastIndex + 1) - 1),
    last: lastIndex >= 0 ? stops[lastIndex] : null,
    next: nextIndex >= 0 ? stops[nextIndex] : null,
    ahead: stops.slice(aheadFrom),
  }
}

export type CompactStops = { shown: CaptainRouteStop[]; hiddenCount: number; terminal: CaptainRouteStop | null }

/**
 * Like an on-board unit: the next few stops ahead, then the route's terminal, with the stops in
 * between counted rather than listed.
 */
export function compactStops(ahead: CaptainRouteStop[], visible: number): CompactStops {
  if (ahead.length <= visible + 1) return { shown: ahead, hiddenCount: 0, terminal: null }
  return { shown: ahead.slice(0, visible), hiddenCount: ahead.length - visible - 1, terminal: ahead[ahead.length - 1] }
}

export function stopName(stop: CaptainRouteStop): string {
  return stop.description || stop.roadName || `Stop ${stop.stopCode}`
}

/** Distance between two route stops, when both have a distance along the route. */
export function distanceText(from: CaptainRouteStop, to: CaptainRouteStop): string | null {
  if (from.distanceKm === null || to.distanceKm === null || to.distanceKm < from.distanceKm) return null
  const kilometres = to.distanceKm - from.distanceKm
  return kilometres < 1 ? `${Math.round(kilometres * 1000)} m` : `${kilometres.toFixed(1)} km`
}

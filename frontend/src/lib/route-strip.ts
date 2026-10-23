import type { BunchingAlert, RouteDirection, RoutePositionBus } from './api'
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
export type ArrivalGap = { label: 'Bus 1–2' | 'Bus 2–3'; seconds: number | null }
export type PositionedRouteAlert = { alert: BunchingAlert; positions: number[] }
export type RouteAlertPlacement = { eventsByRoute: Map<string, PositionedRouteAlert[]>; unplaced: BunchingAlert[] }
export type EstimatedRouteHeadway = {
  frontOrder: number
  followingOrder: number
  seconds: number | null
  source: 'position' | 'eta' | 'unavailable'
}

export function routeKey(route: RouteDirection) {
  return `${route.serviceId}:${route.operator}:${route.directionId}`
}

export function placeRouteAlerts(alerts: BunchingAlert[], routes: RouteDirection[]): RouteAlertPlacement {
  const eventsByRoute = new Map<string, PositionedRouteAlert[]>()
  const unplaced: BunchingAlert[] = []

  for (const alert of alerts) {
    const candidates = routes.filter(route => route.serviceId === alert.serviceId &&
      route.stops.some(stop => stop.stopCode === alert.stopCode))
    const matching = alert.directionId == null
      ? [...new Set(candidates.map(route => route.directionId))].length === 1 ? candidates : []
      : candidates.filter(route => route.directionId === alert.directionId)
    if (!matching.length) { unplaced.push(alert); continue }

    for (const route of matching) {
      const positions = route.stops.flatMap((stop, index) => stop.stopCode === alert.stopCode ? [index] : [])
      if (!positions.length) { unplaced.push(alert); continue }
      const key = routeKey(route)
      const atRoute = eventsByRoute.get(key) ?? []
      atRoute.push({ alert, positions })
      eventsByRoute.set(key, atRoute)
    }
  }

  return { eventsByRoute, unplaced }
}

export function gapSeverity(seconds: number | null): GapSeverity | null {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return null
  if (seconds < 90) return 'close'
  if (seconds <= 180) return 'moderate'
  return 'spaced'
}

function differenceSeconds(first: string, second: string): number | null {
  const firstTime = Date.parse(first)
  const secondTime = Date.parse(second)
  if (!Number.isFinite(firstTime) || !Number.isFinite(secondTime)) return null
  return Math.round(Math.abs(secondTime - firstTime) / 1000)
}

export function arrivalGaps(alert: BunchingAlert): ArrivalGap[] {
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

export function formatGap(seconds: number | null): string {
  if (seconds === null) return 'Gap unavailable'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return remainingSeconds ? `${minutes}m ${remainingSeconds}s` : `${minutes}m`
}

export function estimateRouteHeadways(buses: RoutePositionBus[], etaFallbackSeconds: (number | null)[] = []): EstimatedRouteHeadway[] {
  const ordered = [...buses].sort((first, second) => first.order - second.order)
  return ordered.slice(0, -1).map((front, index) => {
    const following = ordered[index + 1]
    const meanSpeed = front.speedMps !== null && following.speedMps !== null
      ? (front.speedMps + following.speedMps) / 2 : null
    const distanceGap = front.distanceAlongRouteM - following.distanceAlongRouteM
    if (meanSpeed !== null && Number.isFinite(meanSpeed) && meanSpeed > 0 && Number.isFinite(distanceGap) && distanceGap >= 0) {
      return { frontOrder: front.order, followingOrder: following.order, seconds: Math.round(distanceGap / meanSpeed), source: 'position' as const }
    }

    const etaSeconds = etaFallbackSeconds[index]
    return {
      frontOrder: front.order,
      followingOrder: following.order,
      seconds: etaSeconds !== undefined && etaSeconds !== null && Number.isFinite(etaSeconds) && etaSeconds >= 0
        ? etaSeconds : null,
      source: etaSeconds !== undefined && etaSeconds !== null && Number.isFinite(etaSeconds) && etaSeconds >= 0
        ? 'eta' as const : 'unavailable' as const,
    }
  })
}

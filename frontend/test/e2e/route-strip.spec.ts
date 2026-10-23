import { expect, test } from '@playwright/test'
import type { BunchingAlert, RouteDirection, RouteStop } from '../../src/lib/api'
import { arrivalGaps, estimateRouteHeadways, formatGap, gapSeverity, placeRouteAlerts, tightestArrivalGap } from '../../src/lib/route-strip'

function makeAlert(overrides: Partial<BunchingAlert> = {}): BunchingAlert {
  return {
    eventId: 'alert-1', action: 'OPENED', classification: 'OBSERVATION_ONLY', serviceId: '190',
    stopCode: 'A01', detectedAt: '2026-10-23T04:00:00Z', directionId: 1,
    evidence: JSON.stringify({ snapshot: { classification: 'OBSERVATION_ONLY', arrivals: [
      { serviceId: '190', stopCode: 'A01', directionId: 1, visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:01:00Z' },
      { serviceId: '190', stopCode: 'A01', directionId: 1, visitOrder: 2, estimatedArrivalAt: '2026-10-23T04:02:00Z' },
      { serviceId: '190', stopCode: 'A01', directionId: 1, visitOrder: 3, estimatedArrivalAt: '2026-10-23T04:03:00Z' },
    ] } }),
    ...overrides,
  }
}

function makeRoute(directionId: number, stopCodes: string[]): RouteDirection {
  const stops: RouteStop[] = stopCodes.map((stopCode, index) => ({
    stopCode, roadName: 'Sample Road', description: `Stop ${stopCode}`, latitude: 1.38, longitude: 103.75,
    stopSequence: index + 1, distanceKm: index * 0.2,
  }))
  return { serviceId: '190', operator: 'SMRT', directionId, stops }
}

test('gap helper calculates saved prediction gaps and severity boundaries', () => {
  const alert = makeAlert()
  expect(arrivalGaps(alert)).toEqual([{ label: 'Bus 1–2', seconds: 60 }, { label: 'Bus 2–3', seconds: 60 }])
  expect(tightestArrivalGap(alert)).toBe(60)
  expect(gapSeverity(89)).toBe('close')
  expect(gapSeverity(90)).toBe('moderate')
  expect(gapSeverity(180)).toBe('moderate')
  expect(gapSeverity(181)).toBe('spaced')
  expect(gapSeverity(null)).toBeNull()
  expect(formatGap(125)).toBe('2m 5s')
})

test('headway uses distance and mean speed, then falls back to the ETA gap', () => {
  const buses = [
    { order: 2, distanceAlongRouteM: 3_500, uncertaintyM: 70, speedMps: null },
    { order: 1, distanceAlongRouteM: 4_300, uncertaintyM: 70, speedMps: 6.2 },
    { order: 3, distanceAlongRouteM: 2_500, uncertaintyM: 50, speedMps: null },
  ]

  expect(estimateRouteHeadways(buses, [120, 180])).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: 120, source: 'eta' },
    { frontOrder: 2, followingOrder: 3, seconds: 180, source: 'eta' },
  ])
  expect(estimateRouteHeadways([
    { ...buses[0], order: 2, speedMps: 4.2 },
    { ...buses[1], order: 1, speedMps: 6.2 },
  ])).toEqual([{ frontOrder: 1, followingOrder: 2, seconds: 154, source: 'position' }])
  expect(estimateRouteHeadways(buses)).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: null, source: 'unavailable' },
    { frontOrder: 2, followingOrder: 3, seconds: null, source: 'unavailable' },
  ])
})

test('directionless alerts place only when their stop identifies one direction', () => {
  const direction1 = makeRoute(1, ['44009', '44251'])
  const direction2 = makeRoute(2, ['44009', '10499'])
  const sharedStopWithoutDirection = makeAlert({ eventId: 'shared', stopCode: '44009', directionId: null })
  const uniqueStopWithoutDirection = makeAlert({ eventId: 'unique', stopCode: '44251', directionId: null })
  const sharedStopWithDirection = makeAlert({ eventId: 'directed', stopCode: '44009', directionId: 2 })

  const placement = placeRouteAlerts(
    [sharedStopWithoutDirection, uniqueStopWithoutDirection, sharedStopWithDirection],
    [direction1, direction2],
  )

  expect(placement.unplaced.map(alert => alert.eventId)).toEqual(['shared'])
  expect(placement.eventsByRoute.get('190:SMRT:1')?.map(event => event.alert.eventId)).toEqual(['unique'])
  expect(placement.eventsByRoute.get('190:SMRT:2')?.map(event => event.alert.eventId)).toEqual(['directed'])
})

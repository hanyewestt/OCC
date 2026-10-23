import { expect, test } from '@playwright/test'
import type { Arrival, BunchingAlert, RouteDirection, RoutePositionDirection, RouteStop } from '../../src/lib/api'
import {
  arrivalGaps, estimateRouteHeadways, etaFallbacksForRoute, formatGap, gapSeverity, groupRouteIncidents,
  linkIncidentToPosition, oldestStopDataAgeSeconds, projectCoordinateToRoute, routeStopDistances, tightestArrivalGap,
} from '../../src/lib/route-strip'

const now = Date.parse('2026-10-23T04:00:20Z')

function makeAlert(overrides: Partial<BunchingAlert> = {}): BunchingAlert {
  const alert = {
    eventId: 'alert-1', action: 'OPENED', classification: 'OBSERVATION_ONLY', serviceId: '190',
    stopCode: 'A01', detectedAt: '2026-10-23T04:00:00Z', directionId: 1,
    evidence: '',
    ...overrides,
  }
  return {
    ...alert,
    evidence: alert.evidence || JSON.stringify({ snapshot: { classification: alert.classification, arrivals: [1, 2, 3].map(visitOrder => ({
      serviceId: alert.serviceId, stopCode: alert.stopCode, directionId: alert.directionId, visitOrder,
      estimatedArrivalAt: `2026-10-23T04:0${visitOrder}:00Z`,
    })) } }),
  }
}

function makeRoute(directionId = 1, stopCodes = ['A01', 'B01', 'C01']): RouteDirection {
  const stops: RouteStop[] = stopCodes.map((stopCode, index) => ({
    stopCode, roadName: 'Sample Road', description: `Stop ${stopCode}`,
    latitude: 1.38 + index * 0.01, longitude: 103.75,
    stopSequence: index + 1, distanceKm: index * 1.1,
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

test('uses same-snapshot ETA first and estimates time from distance and speed otherwise', () => {
  const buses = [
    { order: 2, distanceAlongRouteM: 3_500, uncertaintyM: 70, speedMps: null },
    { order: 1, distanceAlongRouteM: 4_300, uncertaintyM: 70, speedMps: 6.2 },
    { order: 3, distanceAlongRouteM: 2_500, uncertaintyM: 50, speedMps: null },
  ]

  expect(estimateRouteHeadways(buses, [
    { seconds: 120, stopCode: 'B01' }, { seconds: 180, stopCode: 'C01' },
  ])).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: 120, distanceMeters: 800, source: 'same-stop-eta', etaStopCode: 'B01' },
    { frontOrder: 2, followingOrder: 3, seconds: 180, distanceMeters: 1_000, source: 'same-stop-eta', etaStopCode: 'C01' },
  ])
  expect(estimateRouteHeadways(buses)).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: null, distanceMeters: 800, source: 'distance' },
    { frontOrder: 2, followingOrder: 3, seconds: null, distanceMeters: 1_000, source: 'distance' },
  ])
  expect(estimateRouteHeadways([
    { order: 1, distanceAlongRouteM: 4_300, uncertaintyM: 50, speedMps: 6 },
    { order: 2, distanceAlongRouteM: 3_700, uncertaintyM: 50, speedMps: 6 },
  ])).toEqual([{ frontOrder: 1, followingOrder: 2, seconds: 100, distanceMeters: 600, source: 'estimate' }])
})

test('ETA fallback links exact source observations at their next shared stop', () => {
  const route = makeRoute()
  const buses = [
    { order: 1, distanceAlongRouteM: 1_100, uncertaintyM: 50, speedMps: null, evidenceObservationIds: ['A01-1', 'B01-1'] },
    { order: 2, distanceAlongRouteM: 800, uncertaintyM: 50, speedMps: null, evidenceObservationIds: ['A01-2', 'B01-2'] },
    { order: 3, distanceAlongRouteM: 500, uncertaintyM: 50, speedMps: null, evidenceObservationIds: ['A01-3', 'B01-3'] },
  ]
  const makeArrival = (stopCode: string, visitOrder: number, estimatedArrivalAt: string): Arrival => ({
    observationId: `${stopCode}-${visitOrder}`, serviceId: '190', stopCode, operator: 'SMRT', directionId: 1,
    visitOrder, estimatedArrivalAt, fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null,
  })
  const arrivals = [
    ...[1, 2, 3].map(visitOrder => makeArrival('A01', visitOrder, `2026-10-23T04:0${visitOrder}:00Z`)),
    makeArrival('B01', 1, '2026-10-23T04:01:00Z'),
    makeArrival('B01', 2, '2026-10-23T04:02:00Z'),
    makeArrival('B01', 3, '2026-10-23T04:04:00Z'),
  ]

  const fallbacks = etaFallbacksForRoute(route, [route], arrivals, buses, 2_224, now, 120)

  expect(fallbacks).toEqual([{ seconds: 60, stopCode: 'B01' }, { seconds: 120, stopCode: 'B01' }])
  expect(estimateRouteHeadways(buses, fallbacks)).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: 60, distanceMeters: 300, source: 'same-stop-eta', etaStopCode: 'B01' },
    { frontOrder: 2, followingOrder: 3, seconds: 120, distanceMeters: 300, source: 'same-stop-eta', etaStopCode: 'B01' },
  ])
})

test('05039 replay retains all three close buses and shows the 94 second same-stop ETA gap', () => {
  const route = makeRoute(1, ['A01', '05039', '05019'])
  const snapshotAt = '2026-10-23T04:00:10Z'
  const arrivals: Arrival[] = [132, 226, 603].map((seconds, index) => ({
    observationId: `05039-next-${index + 1}`, serviceId: '190', stopCode: '05039', operator: 'SMRT', directionId: 1,
    visitOrder: index + 1, estimatedArrivalAt: new Date(now + seconds * 1000).toISOString(),
    fetchedAt: snapshotAt, loadCategory: null,
  }))
  const buses = arrivals.map((arrival, index) => ({
    order: index + 1, distanceAlongRouteM: 1_000, uncertaintyM: 50, speedMps: null,
    evidenceObservationIds: [arrival.observationId],
  }))
  const fallbacks = etaFallbacksForRoute(route, [route], arrivals, buses, 2_224, now, 120)

  expect(buses).toHaveLength(3)
  expect(fallbacks[0]).toEqual({ seconds: 94, stopCode: '05039' })
  expect(estimateRouteHeadways(buses, fallbacks)[0]).toEqual({
    frontOrder: 1, followingOrder: 2, seconds: 94, distanceMeters: 0,
    source: 'same-stop-eta', etaStopCode: '05039',
  })
})

test('adjacent alerts with a matching time and gap are grouped, but separated alerts are not', () => {
  const route = makeRoute()
  const adjacent = makeAlert({ eventId: 'adjacent', stopCode: 'B01', detectedAt: '2026-10-23T04:00:10Z' })
  const distant = makeAlert({ eventId: 'distant', stopCode: 'C01', detectedAt: '2026-10-23T04:02:30Z' })
  const incidents = groupRouteIncidents([makeAlert(), adjacent, distant], [route], now, 60, 20)

  expect(incidents).toHaveLength(2)
  expect(incidents.find(incident => incident.alerts.length === 2)?.stopCodes).toEqual(['A01', 'B01'])
  expect(incidents.find(incident => incident.alerts.length === 2)?.historical).toBe(false)
})

test('incidents become historical after three poll cycles or after Bus 1 ETA passes', () => {
  const route = makeRoute(1, ['A01'])
  const aged = makeAlert({ eventId: 'aged', detectedAt: '2026-10-23T03:55:00Z', evidence: JSON.stringify({
    snapshot: { classification: 'OBSERVATION_ONLY', arrivals: [1, 2, 3].map(visitOrder => ({
      serviceId: '190', stopCode: 'A01', directionId: 1, visitOrder,
      estimatedArrivalAt: '2026-10-23T04:30:00Z',
    })) },
  }) })
  const passed = makeAlert({ eventId: 'passed', detectedAt: '2026-10-23T04:00:00Z', evidence: JSON.stringify({
    snapshot: { classification: 'OBSERVATION_ONLY', arrivals: [1, 2, 3].map(visitOrder => ({
      serviceId: '190', stopCode: 'A01', directionId: 1, visitOrder,
      estimatedArrivalAt: '2026-10-23T04:00:10Z',
    })) },
  }) })
  const incidents = groupRouteIncidents([aged, passed], [route], now, 60, 20)

  expect(incidents.find(incident => incident.id === 'aged')?.historical).toBe(true)
  expect(incidents.find(incident => incident.id === 'passed')?.historical).toBe(true)
})

test('incident links only to a plausible same-direction bus pair at the same time', () => {
  const route = makeRoute()
  const alert = makeAlert({ stopCode: 'B01' })
  const incident = groupRouteIncidents([alert], [route], now, 60, 20)[0]
  const position: RoutePositionDirection = {
    serviceId: '190', operator: 'SMRT', directionId: 1, routeLengthM: 2_224,
    buses: [
      { order: 1, distanceAlongRouteM: 1_800, uncertaintyM: 70, speedMps: 5 },
      { order: 2, distanceAlongRouteM: 1_000, uncertaintyM: 70, speedMps: 5 },
    ],
  }
  const computedAt = '2026-10-23T04:00:20Z'

  expect(routeStopDistances(route, position.routeLengthM).hasGeometry).toBe(true)
  expect(linkIncidentToPosition(incident, route, position, computedAt, 60)).toEqual({
    frontOrder: 1, followingOrder: 2, stopCode: 'B01', match: 'approximate',
  })
  expect(linkIncidentToPosition(incident, { ...route, directionId: 2 }, position, computedAt, 60)).toBeNull()
  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:05:00Z', 60)).toBeNull()
})

test('projects a reported arrival coordinate onto the nearest route segment', () => {
  const route = makeRoute(1, ['A01', 'B01'])
  const projected = projectCoordinateToRoute(route, 1.385, 103.75, 1_112)

  expect(projected?.distanceAlongRouteM).toBeCloseTo(556, 0)
  expect(projected?.offsetMeters).toBeLessThan(1)
  expect(projectCoordinateToRoute(route, 0, 0, 1_112)).toBeNull()
})

test('links an incident by its exact bunching observation evidence when both markers match', () => {
  const route = makeRoute()
  const alert = makeAlert({ stopCode: 'B01', evidence: JSON.stringify({
    snapshot: { fetchedAt: '2026-10-23T04:00:10Z', arrivals: [] },
    groups: [{ headways: [{ leadingObservationId: 'obs-lead', followingObservationId: 'obs-follow', isBunched: true }] }],
  }) })
  const incident = groupRouteIncidents([alert], [route], now, 60, 20)[0]
  const position: RoutePositionDirection = {
    serviceId: '190', operator: 'SMRT', directionId: 1, routeLengthM: 2_224,
    buses: [
      { order: 1, distanceAlongRouteM: 1_180, uncertaintyM: 100, speedMps: 5, evidenceObservationIds: ['obs-lead'] },
      { order: 2, distanceAlongRouteM: 1_050, uncertaintyM: 100, speedMps: 5, evidenceObservationIds: ['obs-follow'] },
    ],
  }

  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:00:20Z', 60)).toEqual({
    frontOrder: 1, followingOrder: 2, stopCode: 'B01', match: 'evidence',
  })
})

test('freshness checks each stop’s newest observation rather than the newest record overall', () => {
  const route = makeRoute()
  const makeArrival = (stopCode: string, fetchedAt: string): Arrival => ({
    observationId: `${stopCode}-${fetchedAt}`, serviceId: '190', stopCode, operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:01:00Z', fetchedAt, loadCategory: null,
  })
  const arrivals = [
    makeArrival('A01', '2026-10-23T03:59:30Z'),
    makeArrival('B01', '2026-10-23T03:57:00Z'),
    makeArrival('B01', '2026-10-23T03:58:10Z'),
  ]

  expect(oldestStopDataAgeSeconds(arrivals, [route], now)).toBe(130)
})

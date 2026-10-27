import { expect, test } from '@playwright/test'
import type { Arrival, BunchingAlert, RouteDirection, RoutePositionDirection, RoutePositions, RouteStop } from '../../src/lib/api'
import {
  alignArrivalsToPositionEvidence, arrivalDirectionMatches, arrivalGaps, estimateRouteHeadways, etaFallbacksForRoute, formatGap, gapSeverity,
  groupRouteIncidents, linkIncidentToPosition, oldestStopDataAgeSeconds, placeArrivalAtPositionEvidence,
  positionSourceFreshness, positionSourceIsStale, projectCoordinateToRoute, routePositionEvidenceDescription,
  routeDestination, routePositionSnapshotIsDisplayed, routeStopDistances, tightestArrivalGap,
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

function makeArrival(overrides: Partial<Arrival> = {}): Arrival {
  return {
    observationId: 'arrival-1', serviceId: '190', stopCode: 'A01', operator: 'SMRT', directionId: 1,
    estimatedArrivalAt: '2026-10-23T04:01:00Z', fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null,
    ...overrides,
  }
}

test('resolves one matching destination, ignores opposite directions and collapses duplicate codes', () => {
  const route = makeRoute()
  const otherDirection = makeRoute(2, ['D01', 'E01', 'F01'])

  expect(routeDestination(route, [route, otherDirection], [
    makeArrival({ destinationCode: 'C01' }),
    makeArrival({ observationId: 'arrival-2', stopCode: 'B01', destinationCode: 'C01' }),
    makeArrival({ observationId: 'arrival-3', directionId: 2, destinationCode: 'F01' }),
  ])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
  // Falls back to static terminus Stop C01 when only another direction has live arrivals
  expect(routeDestination(route, [route, otherDirection], [
    makeArrival({ directionId: 2, destinationCode: 'F01' }),
  ])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
  const repeatedStop = makeRoute(2, ['A01', 'D01', 'E01'])
  // Falls back to static terminus Stop C01 when arrival direction is ambiguous
  expect(routeDestination(route, [route, repeatedStop], [
    makeArrival({ directionId: null, destinationCode: 'C01' }),
  ])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
})

test('falls back to route terminus when arrivals are missing or empty, and handles empty stops', () => {
  const route = makeRoute()
  expect(routeDestination(route, [route], [])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
  expect(routeDestination(route, [route], [makeArrival({ destinationCode: null })])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
  const emptyRoute = { ...route, stops: [] }
  expect(routeDestination(emptyRoute, [emptyRoute], [])).toEqual({ status: 'unavailable' })
  expect(routeDestination(emptyRoute, [emptyRoute], [makeArrival({ destinationCode: null })])).toEqual({ status: 'unavailable' })
})

test('reports conflicting destinations, short-trip overrides and unknown-name destinations', () => {
  const route = makeRoute()

  // Conflicting live destinations
  expect(routeDestination(route, [route], [
    makeArrival({ destinationCode: 'B01' }), makeArrival({ observationId: 'arrival-2', destinationCode: 'C01' }),
  ])).toEqual({ status: 'multiple' })
  // Short-trip override to intermediate stop B01
  expect(routeDestination(route, [route], [makeArrival({ destinationCode: 'B01' })]))
    .toEqual({ status: 'resolved', stopCode: 'B01', stopName: 'Stop B01' })
  // External/unknown destination code not in route.stops
  expect(routeDestination(route, [route], [makeArrival({ destinationCode: 'Z99' })]))
    .toEqual({ status: 'resolved', stopCode: 'Z99', stopName: null })
})

test('arrival matching keeps operators with the same service and direction distinct', () => {
  const route = makeRoute()
  const otherOperator = { ...route, operator: 'SBS Transit' }
  expect(arrivalDirectionMatches(makeArrival({ operator: 'SBS Transit' }), route, [route, otherOperator])).toBe(false)
  // Unmatched other-operator arrivals fall back to route terminus Stop C01
  expect(routeDestination(route, [route, otherOperator], [
    makeArrival({ operator: 'SBS Transit', destinationCode: 'Z99' }),
  ])).toEqual({ status: 'resolved', stopCode: 'C01', stopName: 'Stop C01' })
})

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
  const olderArrivals = arrivals.map(arrival => ({ ...arrival, fetchedAt: '2026-10-23T03:58:50Z' }))

  expect(fallbacks).toEqual([{ seconds: 60, stopCode: 'B01' }, { seconds: 120, stopCode: 'B01' }])
  expect(etaFallbacksForRoute(route, [route], olderArrivals, buses, 2_224, now, 120)).toEqual(fallbacks)
  expect(etaFallbacksForRoute(route, [route], olderArrivals, buses, 2_224, now, 60)).toEqual([
    { seconds: null, stopCode: '' }, { seconds: null, stopCode: '' },
  ])
  expect(estimateRouteHeadways(buses, fallbacks)).toEqual([
    { frontOrder: 1, followingOrder: 2, seconds: 60, distanceMeters: 300, source: 'same-stop-eta', etaStopCode: 'B01' },
    { frontOrder: 2, followingOrder: 3, seconds: 120, distanceMeters: 300, source: 'same-stop-eta', etaStopCode: 'B01' },
  ])
})

test('ETA fallback uses marker-position evidence instead of shared circular scoring evidence', () => {
  const route = makeRoute(1, ['A01', 'B01'])
  const arrivals: Arrival[] = [
    { observationId: 'front', serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1,
      visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:02:00Z', fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null },
    { observationId: 'following', serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1,
      visitOrder: 2, estimatedArrivalAt: '2026-10-23T04:03:00Z', fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null },
  ]
  const sharedAuditEvidence = ['front', 'following']
  const buses = [
    { order: 1, distanceAlongRouteM: 900, uncertaintyM: 50, speedMps: null,
      evidenceObservationIds: sharedAuditEvidence, positionEvidenceObservationIds: ['front'] },
    { order: 2, distanceAlongRouteM: 800, uncertaintyM: 50, speedMps: null,
      evidenceObservationIds: sharedAuditEvidence, positionEvidenceObservationIds: ['following'] },
  ]

  expect(etaFallbacksForRoute(route, [route], arrivals, buses, 1_112, now, 120))
    .toEqual([{ seconds: 60, stopCode: 'B01' }])
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
    operator: 'SMRT', directionId: 1, routeLengthM: 2_224,
    buses: [
      { order: 1, distanceAlongRouteM: 1_800, uncertaintyM: 70, speedMps: 5 },
      { order: 2, distanceAlongRouteM: 1_000, uncertaintyM: 70, speedMps: 5 },
    ],
  }
  const computedAt = '2026-10-23T04:00:20Z'

  expect(routeStopDistances(route, position.routeLengthM).hasGeometry).toBe(true)
  expect(linkIncidentToPosition(incident, route, position, computedAt, 120)).toEqual({
    frontOrder: 1, followingOrder: 2, stopCode: 'B01', match: 'approximate',
  })
  expect(linkIncidentToPosition(incident, { ...route, directionId: 2 }, position, computedAt, 120)).toBeNull()
  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:05:00Z', 120)).toBeNull()
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
    operator: 'SMRT', directionId: 1, routeLengthM: 2_224,
    buses: [
      { order: 1, distanceAlongRouteM: 1_180, uncertaintyM: 100, speedMps: 5,
        evidenceObservationIds: ['obs-lead', 'obs-follow'], positionEvidenceObservationIds: ['obs-lead'] },
      { order: 2, distanceAlongRouteM: 1_050, uncertaintyM: 100, speedMps: 5,
        evidenceObservationIds: ['obs-lead', 'obs-follow'], positionEvidenceObservationIds: ['obs-follow'] },
    ],
  }

  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:00:20Z', 120)).toEqual({
    frontOrder: 1, followingOrder: 2, stopCode: 'B01', match: 'evidence',
  })
  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:01:40Z', 120)).toEqual({
    frontOrder: 1, followingOrder: 2, stopCode: 'B01', match: 'evidence',
  })
  expect(linkIncidentToPosition(incident, route, position, '2026-10-23T04:01:40Z', 60)).toBeNull()
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

test('newer arrivals do not mask a stale blue-position source sweep', () => {
  const route = makeRoute(1, ['A01'])
  const arrival: Arrival = {
    observationId: 'fresh-arrival', serviceId: '190', stopCode: 'A01', operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:05:00Z', fetchedAt: '2026-10-23T04:00:15Z', loadCategory: null,
  }
  const positionSnapshots: RoutePositions[] = [{
    serviceId: '190', computedAt: '2026-10-23T04:00:20Z', sourceObservedAt: '2026-10-23T03:57:00Z',
    sourcePollCycleId: 'stale-cycle', refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120, directions: [],
  }]

  expect(oldestStopDataAgeSeconds([arrival], [route], now)).toBe(5)
  expect(positionSourceFreshness(positionSnapshots, now)).toEqual({ oldestAgeSeconds: 200, unavailableCount: 0 })
})

test('position freshness uses the oldest displayed source time and reports unavailable timestamps', () => {
  const snapshots: RoutePositions[] = [
    { serviceId: '190', computedAt: '2026-10-23T04:00:20Z', sourceObservedAt: '2026-10-23T03:59:50Z', refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120, directions: [] },
    { serviceId: '972', computedAt: '2026-10-23T04:00:20Z', sourceObservedAt: '2026-10-23T03:58:20Z', refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120, directions: [] },
    { serviceId: '858', computedAt: '2026-10-23T04:00:20Z', sourceObservedAt: null, refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120, directions: [] },
  ]

  expect(positionSourceFreshness(snapshots, now)).toEqual({ oldestAgeSeconds: 120, unavailableCount: 1 })
})

test('position staleness uses the backend-configured source-age limit', () => {
  const snapshot: RoutePositions = {
    serviceId: '190', computedAt: '2026-10-23T04:00:20Z',
    sourceObservedAt: '2026-10-23T03:58:50Z', refreshIntervalSeconds: 60,
    maxSourceAgeSeconds: 120, directions: [],
  }

  expect(positionSourceIsStale(snapshot, now)).toBe(false)
  expect(positionSourceIsStale({ ...snapshot, maxSourceAgeSeconds: 60 }, now)).toBe(true)
})

test('displayed position snapshots match service from the root response rather than the direction payload', () => {
  const snapshot: RoutePositions = {
    serviceId: '190', computedAt: '2026-10-23T04:00:20Z', sourceObservedAt: '2026-10-23T04:00:10Z',
    refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120,
    directions: [{ operator: 'SMRT', directionId: 1, routeLengthM: 2_224, buses: [] }],
  }

  expect(routePositionSnapshotIsDisplayed(snapshot, [makeRoute(1)])).toBe(true)
  expect(routePositionSnapshotIsDisplayed(snapshot, [{ ...makeRoute(1), serviceId: '972' }])).toBe(false)
})

test('selected-stop overlays retain only observations from the displayed position sweep', () => {
  const makeArrival = (observationId: string, fetchedAt: string): Arrival => ({
    observationId, serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:05:00Z', fetchedAt, loadCategory: null,
  })
  const sourceObservation = makeArrival('source-cycle-bus-1', '2026-10-23T04:00:10Z')
  const newerObservation = makeArrival('newer-cycle-bus-1', '2026-10-23T04:00:40Z')
  const buses = [{
    order: 1, distanceAlongRouteM: 1_000, uncertaintyM: 50, speedMps: null,
    evidenceObservationIds: [sourceObservation.observationId, 'boundary-linkage'],
    positionEvidenceObservationIds: [sourceObservation.observationId], supportingStopCount: 2,
  }]

  expect(alignArrivalsToPositionEvidence([newerObservation, sourceObservation], buses)).toEqual({
    arrivals: [sourceObservation], status: 'aligned',
  })
  expect(alignArrivalsToPositionEvidence([newerObservation], buses)).toEqual({
    arrivals: [], status: 'different-sweep',
  })
  expect(alignArrivalsToPositionEvidence([], buses)).toEqual({ arrivals: [], status: 'no-arrivals' })
})

test('selected-stop alignment reports missing evidence instead of treating a new feed as position evidence', () => {
  const arrival: Arrival = {
    observationId: 'newer-cycle-bus-1', serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:05:00Z', fetchedAt: '2026-10-23T04:00:40Z', loadCategory: null,
  }
  const buses = [{ order: 1, distanceAlongRouteM: 1_000, uncertaintyM: 50, speedMps: null }]

  expect(alignArrivalsToPositionEvidence([arrival], buses)).toEqual({
    arrivals: [], status: 'evidence-unavailable',
  })
})

test('position evidence descriptions separate confirmed multiplicity from unresolved vehicle identity', () => {
  const description = routePositionEvidenceDescription({
    order: 2, distanceAlongRouteM: 900, uncertaintyM: 70, speedMps: null,
    evidenceObservationIds: ['a', 'b'], positionEvidenceObservationIds: ['a'], supportingStopCount: 2,
  }, 3)

  expect(description).toContain('supports 3 distinct bus estimates')
  expect(description).toContain('1 in-band source observation')
  expect(description).toContain('Track evidence spans 2 stops')
  expect(description).toContain('1 additional linkage observation')
  expect(description).toContain('Physical vehicle identity remains unresolved')
  expect(routePositionEvidenceDescription({
    order: 1, distanceAlongRouteM: 900, uncertaintyM: 70, speedMps: null,
  }, 1)).toContain('Position-specific source evidence is unavailable')
})

test('boundary linkage evidence cannot place a selected-stop overlay outside the blue interval', () => {
  const makeArrival = (observationId: string): Arrival => ({
    observationId, serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:05:00Z', fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null,
  })
  const boundaryLinkage = makeArrival('old-boundary-linkage')
  const inBandPosition = makeArrival('current-in-band-position')
  const buses = [{
    order: 1, distanceAlongRouteM: 1_000, uncertaintyM: 50, speedMps: null,
    evidenceObservationIds: [boundaryLinkage.observationId, inBandPosition.observationId],
    positionEvidenceObservationIds: [inBandPosition.observationId], supportingStopCount: 2,
  }]

  expect(alignArrivalsToPositionEvidence([boundaryLinkage], buses)).toEqual({
    arrivals: [], status: 'linkage-only',
  })
  expect(alignArrivalsToPositionEvidence([inBandPosition], buses)).toEqual({
    arrivals: [inBandPosition], status: 'aligned',
  })
})

test('repeated-route evidence uses the backend-selected occurrence while retaining raw coordinate offset', () => {
  const route = makeRoute(1, ['A01', 'B01', 'A01'])
  route.stops[2] = { ...route.stops[0], stopSequence: 3, distanceKm: 32 }
  const arrival: Arrival = {
    observationId: 'circular-position', serviceId: '190', stopCode: 'A01', operator: 'SMRT', directionId: 1,
    visitOrder: 1, estimatedArrivalAt: '2026-10-23T04:05:00Z', fetchedAt: '2026-10-23T04:00:10Z', loadCategory: null,
    latitude: route.stops[0].latitude, longitude: (route.stops[0].longitude ?? 103.75) + 0.0001,
  }
  const buses = [{
    order: 1, distanceAlongRouteM: 31_950, uncertaintyM: 50, speedMps: null,
    evidenceObservationIds: [arrival.observationId], positionEvidenceObservationIds: [arrival.observationId],
    supportingStopCount: 1,
  }]

  const placement = placeArrivalAtPositionEvidence(arrival, buses, route, 32_000)

  expect(placement?.displayDistanceAlongRouteM).toBe(31_950)
  expect(placement?.rawProjection?.distanceAlongRouteM).toBeCloseTo(0, 0)
  expect(placement?.rawProjection?.offsetMeters).toBeGreaterThan(5)
  expect(placement?.rawProjection?.offsetMeters).toBeLessThan(20)
  expect(placement!.displayDistanceAlongRouteM - placement!.rawProjection!.distanceAlongRouteM).toBeGreaterThan(30_000)
})

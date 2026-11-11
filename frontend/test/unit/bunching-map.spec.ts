import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { expect, test } from '@playwright/test'
import type { BunchingAlert, RouteDirection } from '../../src/lib/api'
import {
  areBusesConfirmedBunched,
  clusterProjectedBuses,
  computeBusLatLng,
  computeCorridorFit,
  DEFAULT_CENTER,
  filterMapBuses,
  filterMapRoutes,
  latLngToWorld,
  MAX_ZOOM,
  MIN_ZOOM,
  TILE_SIZE,
  validCoord,
  worldToLatLng,
  distanceMeters,
  BunchingMap,
  categorizeHazard,
  type MapHazard,
} from '../../src/pages/ControllerViews' 

test('validCoord validates latitude and longitude ranges and excludes origin', () => {
  expect(validCoord(1.3521, 103.8198)).toBe(true)
  expect(validCoord(0, 0)).toBe(false)
  expect(validCoord(null, 103.8)).toBe(false)
  expect(validCoord(1.3, undefined)).toBe(false)
  expect(validCoord(95, 103.8)).toBe(false)
  expect(validCoord(1.3, 190)).toBe(false)
  expect(validCoord(Number.NaN, 103.8)).toBe(false)
})

test('latLngToWorld and worldToLatLng perform accurate round-trip projections', () => {
  const points = [
    { lat: 1.3521, lng: 103.8198 },
    { lat: 1.2800, lng: 103.7400 },
    { lat: 1.3850, lng: 103.8500 },
  ]

  for (const zoom of [MIN_ZOOM, 13, 15, MAX_ZOOM]) {
    for (const { lat, lng } of points) {
      const world = latLngToWorld(lat, lng, zoom)
      const restored = worldToLatLng(world.x, world.y, zoom)
      expect(Math.abs(restored.lat - lat)).toBeLessThan(1e-7)
      expect(Math.abs(restored.lng - lng)).toBeLessThan(1e-7)
    }
  }
})

test('latLngToWorld maps Singapore center to expected Slippy tile at zoom 14', () => {
  const world = latLngToWorld(1.3521, 103.8198, 14)
  const tileX = Math.floor(world.x / TILE_SIZE)
  const tileY = Math.floor(world.y / TILE_SIZE)

  expect(tileX).toBe(12916)
  expect(tileY).toBe(8130)
})

test('computeCorridorFit returns default Singapore view when points are empty', () => {
  const fit = computeCorridorFit([], 800, 384)
  expect(fit.center).toEqual(DEFAULT_CENTER)
  expect(fit.zoom).toBe(12)
})

test('computeCorridorFit centers on single point with street-level zoom', () => {
  const fit = computeCorridorFit([{ latitude: 1.38, longitude: 103.75 }], 800, 384)
  expect(fit.center.lat).toBeCloseTo(1.38, 5)
  expect(fit.center.lng).toBeCloseTo(103.75, 5)
  expect(fit.zoom).toBe(15)
})

test('computeCorridorFit fits a tight bunching corridor into available viewport', () => {
  // Points spanning ~1.5 km along a route corridor
  const corridor = [
    { latitude: 1.378, longitude: 103.745 },
    { latitude: 1.383, longitude: 103.752 },
    { latitude: 1.388, longitude: 103.758 },
  ]
  const fit = computeCorridorFit(corridor, 800, 384)

  expect(fit.center.lat).toBeCloseTo((1.378 + 1.388) / 2, 4)
  expect(fit.center.lng).toBeCloseTo((103.745 + 103.758) / 2, 4)
  // Tight corridor fits comfortably within zoom 14-16
  expect(fit.zoom).toBeGreaterThanOrEqual(14)
  expect(fit.zoom).toBeLessThanOrEqual(16)
})

test('computeCorridorFit clamps long route view so full span is contained', () => {
  // Points spanning ~20 km across the island
  const islandSpan = [
    { latitude: 1.280, longitude: 103.850 },
    { latitude: 1.385, longitude: 103.740 },
  ]
  const fit = computeCorridorFit(islandSpan, 800, 384)

  expect(fit.zoom).toBe(12)
  const p1 = latLngToWorld(1.280, 103.850, fit.zoom)
  const p2 = latLngToWorld(1.385, 103.740, fit.zoom)
  const spanY = Math.abs(p2.y - p1.y)
  // Height must fit within 384 - 96 = 288px
  expect(spanY).toBeLessThanOrEqual(384)
})

test('computeBusLatLng places estimated bus accurately along route segments', () => {
  const route: RouteDirection = {
    serviceId: '190',
    operator: 'SMRT',
    directionId: 1,
    stops: [
      { stopCode: 'A01', roadName: 'Road A', description: 'Stop 1', latitude: 1.30, longitude: 103.80, stopSequence: 1, distanceKm: 0 },
      { stopCode: 'A02', roadName: 'Road B', description: 'Stop 2', latitude: 1.32, longitude: 103.82, stopSequence: 2, distanceKm: 2.0 },
    ],
  }
  const routeLengthM = 2000

  // At beginning of segment
  const atStart = computeBusLatLng(route, 0, routeLengthM)
  expect(atStart?.latitude).toBeCloseTo(1.30, 5)
  expect(atStart?.longitude).toBeCloseTo(103.80, 5)

  // Halfway between stops
  const halfway = computeBusLatLng(route, 1000, routeLengthM)
  expect(halfway?.latitude).toBeCloseTo(1.31, 5)
  expect(halfway?.longitude).toBeCloseTo(103.81, 5)

  // At terminus
  const atEnd = computeBusLatLng(route, 2000, routeLengthM)
  expect(atEnd?.latitude).toBeCloseTo(1.32, 5)
  expect(atEnd?.longitude).toBeCloseTo(103.82, 5)

  // Out of bounds clamps safely to route extremities
  const beforeStart = computeBusLatLng(route, -100, routeLengthM)
  expect(beforeStart?.latitude).toBeCloseTo(1.30, 5)
  expect(beforeStart?.longitude).toBeCloseTo(103.80, 5)

  const beyondEnd = computeBusLatLng(route, 3000, routeLengthM)
  expect(beyondEnd?.latitude).toBeCloseTo(1.32, 5)
  expect(beyondEnd?.longitude).toBeCloseTo(103.82, 5)
})

test('computeCorridorFit accommodates custom padding when framing an incident corridor', () => {
  const incidentPoints = [
    { latitude: 1.340, longitude: 103.840 },
    { latitude: 1.345, longitude: 103.845 },
  ]
  const standardFit = computeCorridorFit(incidentPoints, 800, 400, 40)
  const generousFit = computeCorridorFit(incidentPoints, 800, 400, 100)

  expect(standardFit.center.lat).toBeCloseTo((1.340 + 1.345) / 2, 4)
  expect(generousFit.center.lat).toBeCloseTo((1.340 + 1.345) / 2, 4)
  // Generous padding requires equal or smaller zoom level to ensure margin clearance
  expect(generousFit.zoom).toBeLessThanOrEqual(standardFit.zoom)
})

test('computeBusLatLng returns null when route lacks valid coordinates', () => {
  const route: RouteDirection = {
    serviceId: '190',
    operator: 'SMRT',
    directionId: 1,
    stops: [
      { stopCode: 'A01', roadName: null, description: null, latitude: null, longitude: null, stopSequence: 1, distanceKm: null },
      { stopCode: 'A02', roadName: null, description: null, latitude: null, longitude: null, stopSequence: 2, distanceKm: null },
    ],
  }
  expect(computeBusLatLng(route, 500, 1000)).toBeNull()
})

test('clusterProjectedBuses combines overlapping buses on the same service into a cluster pill', () => {
  const buses = [
    { serviceId: '972', directionId: 1, order: 2, x: 100, y: 100, label: 'Bus 2', latitude: 1.38, longitude: 103.76 },
    { serviceId: '972', directionId: 1, order: 3, x: 110, y: 105, label: 'Bus 3', latitude: 1.381, longitude: 103.761 },
    { serviceId: '972', directionId: 1, order: 1, x: 300, y: 300, label: 'Bus 1', latitude: 1.37, longitude: 103.75 },
  ]
  const selectedBuses = new Set(['972-2', '972-3'])
  const clusters = clusterProjectedBuses(buses, selectedBuses, [], 28)

  expect(clusters).toHaveLength(2)
  const bunched = clusters.find(c => c.buses.length === 2)
  expect(bunched).toBeDefined()
  expect(bunched?.text).toBe('2 · 3')
  expect(bunched?.isSelected).toBe(true)
  expect(bunched?.x).toBeCloseTo(105, 1)
  expect(bunched?.y).toBeCloseTo(102.5, 1)

  const single = clusters.find(c => c.buses.length === 1)
  expect(single).toBeDefined()
  expect(single?.text).toBe('1')
  expect(single?.isSelected).toBe(false)
})

test('clusterProjectedBuses does not group buses from different services', () => {
  const buses = [
    { serviceId: '972', directionId: 1, order: 1, x: 100, y: 100, label: '972 Bus 1', latitude: 1.38, longitude: 103.76 },
    { serviceId: '190', directionId: 1, order: 1, x: 105, y: 105, label: '190 Bus 1', latitude: 1.38, longitude: 103.76 },
  ]
  const clusters = clusterProjectedBuses(buses, new Set(), [], 28)

  expect(clusters).toHaveLength(2)
  expect(clusters.map(c => c.serviceId)).toEqual(['972', '190'])
})

test('clusterProjectedBuses does not merge nearby buses if they are not confirmed bunched by an alert', () => {
  // Buses 4, 7, 8 on Service 190 are close to each other at a terminal, but have no bunching alerts
  const buses = [
    { serviceId: '190', directionId: 1, order: 4, x: 100, y: 100, label: 'Bus 4', latitude: 1.28, longitude: 103.85 },
    { serviceId: '190', directionId: 1, order: 7, x: 104, y: 104, label: 'Bus 7', latitude: 1.281, longitude: 103.851 },
    { serviceId: '190', directionId: 1, order: 8, x: 108, y: 106, label: 'Bus 8', latitude: 1.282, longitude: 103.852 },
  ]
  const clusters = clusterProjectedBuses(buses, new Set(), [], 28)

  // Must NOT merge into [ 4 · 7 · 8 ] because no bunching alert exists between them
  expect(clusters).toHaveLength(3)
  expect(clusters.every(c => c.buses.length === 1)).toBe(true)
  expect(clusters.every(c => !c.hasIncident)).toBe(true)
  expect(clusters.map(c => c.text)).toEqual(['4', '7', '8'])
})

test('clusterProjectedBuses does not merge nearby buses traveling in opposite directions', () => {
  // Bus 2 Direction 1 and Bus 2 Direction 2 near Choa Chu Kang interchange
  const buses = [
    { serviceId: '190', directionId: 1, order: 2, x: 100, y: 100, label: 'Bus 2 Dir 1', latitude: 1.38, longitude: 103.75 },
    { serviceId: '190', directionId: 2, order: 2, x: 105, y: 105, label: 'Bus 2 Dir 2', latitude: 1.381, longitude: 103.751 },
  ]
  const clusters = clusterProjectedBuses(buses, new Set(), [], 28)

  // Must NOT merge into [ 2 · 2 ] because directionId differs
  expect(clusters).toHaveLength(2)
  expect(clusters.every(c => c.buses.length === 1)).toBe(true)
})

test('areBusesConfirmedBunched validates bunching link between candidate buses', () => {
  const b1 = { serviceId: '972', directionId: 1, order: 2, x: 0, y: 0, label: '2', latitude: 1, longitude: 103 }
  const b2 = { serviceId: '972', directionId: 1, order: 3, x: 0, y: 0, label: '3', latitude: 1, longitude: 103 }
  const b3 = { serviceId: '972', directionId: 1, order: 4, x: 0, y: 0, label: '4', latitude: 1, longitude: 103 }

  const incidents = [
    {
      id: 'inc-1',
      serviceId: '972',
      directionId: 1,
      routeKey: '972-1',
      alerts: [],
      stopCodes: ['44101'],
      stopNames: ['Senja'],
      firstDetectedAt: '2026-11-09T10:00:00Z',
      latestDetectedAt: '2026-11-09T10:05:00Z',
      gapSeconds: 45,
      ageSeconds: 30,
      historical: false,
      latestStopIndex: 5,
      stripLink: { frontOrder: 2, followingOrder: 3, stopCode: '44101', match: 'evidence' as const },
    },
  ]

  // Buses 2 and 3 match active incident
  expect(areBusesConfirmedBunched(b1, b2, incidents, new Set())).toBe(true)
  expect(areBusesConfirmedBunched(b2, b1, incidents, new Set())).toBe(true)

  // Buses 3 and 4 do NOT match active incident
  expect(areBusesConfirmedBunched(b2, b3, incidents, new Set())).toBe(false)

  // Selected buses match explicitly
  expect(areBusesConfirmedBunched(b2, b3, [], new Set(['972-3', '972-4']))).toBe(true)
})

test('areBusesConfirmedBunched matches multi-bus bunching from alert gaps when stripLink is null', () => {
  const b1 = { serviceId: '190', directionId: 1, order: 1, x: 0, y: 0, label: '1', latitude: 1, longitude: 103 }
  const b2 = { serviceId: '190', directionId: 1, order: 2, x: 0, y: 0, label: '2', latitude: 1, longitude: 103 }
  const b3 = { serviceId: '190', directionId: 1, order: 3, x: 0, y: 0, label: '3', latitude: 1, longitude: 103 }

  const alertWithGaps = {
    eventId: 'evt-sim-1',
    action: 'OPENED',
    classification: 'OBSERVATION_ONLY',
    serviceId: '190',
    stopCode: '44251',
    directionId: 1,
    detectedAt: '2026-11-11T03:19:06Z',
    stopName: 'Bt Panjang Stn/Blk 604',
    stopRoadName: 'Bt Panjang Rd',
    evidence: JSON.stringify({
      snapshot: {
        arrivals: [
          { observationId: 'obs-1', visitOrder: 1, estimatedArrivalAt: '2026-11-11T03:20:00Z' },
          { observationId: 'obs-2', visitOrder: 2, estimatedArrivalAt: '2026-11-11T03:20:45Z' },
          { observationId: 'obs-3', visitOrder: 3, estimatedArrivalAt: '2026-11-11T03:21:30Z' },
        ],
      },
      groups: [
        {
          headways: [
            { isBunched: true, leadingObservationId: 'obs-1', followingObservationId: 'obs-2' },
            { isBunched: true, leadingObservationId: 'obs-2', followingObservationId: 'obs-3' },
          ],
        },
      ],
    }),
  } as unknown as BunchingAlert

  const incident = {
    id: 'inc-sim-1',
    serviceId: '190',
    directionId: 1,
    routeKey: '190-1',
    alerts: [alertWithGaps],
    stopCodes: ['44251'],
    stopNames: ['Bt Panjang Stn/Blk 604'],
    firstDetectedAt: '2026-11-11T03:19:06Z',
    latestDetectedAt: '2026-11-11T03:19:06Z',
    gapSeconds: 45,
    ageSeconds: 0,
    historical: false,
    latestStopIndex: 1,
    stripLink: null, // stripLink is unlinked in simulation or unlinked state
  }

  expect(areBusesConfirmedBunched(b1, b2, [incident], new Set())).toBe(true)
  expect(areBusesConfirmedBunched(b2, b3, [incident], new Set())).toBe(true)
})

test('clusterProjectedBuses does not highlight unrelated buses when stripLink resolves to different bus orders', () => {
  const alertAtStop = {
    eventId: 'evt-44271',
    action: 'OPENED',
    classification: 'OBSERVATION_ONLY',
    serviceId: '190',
    stopCode: '44271',
    directionId: 2,
    detectedAt: '2026-11-11T14:06:05Z',
    evidence: JSON.stringify({
      snapshot: {
        arrivals: [
          { observationId: 'obs-lead', visitOrder: 1, estimatedArrivalAt: '2026-11-11T14:08:46Z' },
          { observationId: 'obs-4', visitOrder: 2, estimatedArrivalAt: '2026-11-11T14:17:23Z' },
          { observationId: 'obs-5', visitOrder: 3, estimatedArrivalAt: '2026-11-11T14:17:33Z' },
        ],
      },
      groups: [
        {
          headways: [
            { isBunched: false, leadingObservationId: 'obs-lead', followingObservationId: 'obs-4' },
            { isBunched: true, leadingObservationId: 'obs-4', followingObservationId: 'obs-5' },
          ],
        },
      ],
    }),
  } as unknown as BunchingAlert

  const incident = {
    id: 'inc-190-2',
    serviceId: '190',
    directionId: 2,
    routeKey: '190:SMRT:2',
    alerts: [alertAtStop],
    stopCodes: ['44271'],
    stopNames: ['Bet Blks 13/14'],
    firstDetectedAt: '2026-11-11T14:06:05Z',
    latestDetectedAt: '2026-11-11T14:06:05Z',
    gapSeconds: 10,
    ageSeconds: 0,
    historical: false,
    latestStopIndex: 31,
    stripLink: { frontOrder: 4, followingOrder: 5, stopCode: '44271', match: 'evidence' as const },
  }

  const buses = [
    { serviceId: '190', directionId: 2, order: 2, x: 100, y: 100, label: 'Bus 2', latitude: 1.35, longitude: 103.78 },
    { serviceId: '190', directionId: 2, order: 3, x: 200, y: 200, label: 'Bus 3', latitude: 1.36, longitude: 103.77 },
    { serviceId: '190', directionId: 2, order: 4, x: 300, y: 300, label: 'Bus 4', latitude: 1.37, longitude: 103.76 },
    { serviceId: '190', directionId: 2, order: 5, x: 305, y: 305, label: 'Bus 5', latitude: 1.371, longitude: 103.761 },
  ]

  const clusters = clusterProjectedBuses(buses, new Set(), [incident], 28)

  const bunched45 = clusters.find(c => c.text === '4 · 5')
  expect(bunched45).toBeDefined()
  expect(bunched45?.hasIncident).toBe(true)

  const bus2 = clusters.find(c => c.text === '2')
  const bus3 = clusters.find(c => c.text === '3')
  expect(bus2).toBeDefined()
  expect(bus2?.hasIncident).toBe(false)
  expect(bus3).toBeDefined()
  expect(bus3?.hasIncident).toBe(false)
})

test('filterMapBuses returns all fleet buses when viewing a single corridor', () => {
  const buses = [
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 1, distanceAlongRouteM: 1000 } },
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 2, distanceAlongRouteM: 2000 } },
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 3, distanceAlongRouteM: 3000 } },
  ]
  const incidentBusKeys = new Set(['190-2'])
  const result = filterMapBuses(buses, false, incidentBusKeys)
  expect(result).toHaveLength(3)
  expect(result.map(b => b.bus.order)).toEqual([1, 2, 3])
})

test('filterMapBuses filters strictly to bunched buses when viewing All Corridors', () => {
  const buses = [
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 1, distanceAlongRouteM: 1000 } },
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 2, distanceAlongRouteM: 2000 } },
    { serviceId: '972', directionId: 1, routeLengthM: 8000, bus: { order: 2, distanceAlongRouteM: 2200 } },
    { serviceId: '972', directionId: 1, routeLengthM: 8000, bus: { order: 3, distanceAlongRouteM: 2250 } },
  ]
  const incidentBusKeys = new Set(['972-2', '972-3'])
  const result = filterMapBuses(buses, true, incidentBusKeys)
  expect(result).toHaveLength(2)
  expect(result.map(b => `${b.serviceId}-${b.bus.order}`)).toEqual(['972-2', '972-3'])
})

test('filterMapBuses returns empty array when All Corridors has no active bunching detections', () => {
  const buses = [
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 1, distanceAlongRouteM: 1000 } },
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 2, distanceAlongRouteM: 2000 } },
    { serviceId: '190', directionId: 1, routeLengthM: 10000, bus: { order: 3, distanceAlongRouteM: 3000 } },
  ]
  const emptyIncidentBusKeys = new Set<string>()
  const result = filterMapBuses(buses, true, emptyIncidentBusKeys)
  expect(result).toEqual([])
})

test('filterMapRoutes returns all corridor routes when viewing a single corridor', () => {
  const routes = [
    { serviceId: '190', directionId: 1 },
  ]
  const result = filterMapRoutes(routes, false, new Set())
  expect(result).toHaveLength(1)
})

test('filterMapRoutes returns only disrupted routes in All Corridors hotspot mode', () => {
  const routes = [
    { serviceId: '190', directionId: 1 },
    { serviceId: '190', directionId: 2 },
    { serviceId: '972', directionId: 1 },
  ]
  const disruptedRouteKeys = new Set(['972-1'])
  const result = filterMapRoutes(routes, true, disruptedRouteKeys)
  expect(result).toHaveLength(1)
  expect(result[0].serviceId).toBe('972')
})

test('filterMapRoutes returns empty array in All Corridors when no routes are disrupted', () => {
  const routes = [
    { serviceId: '190', directionId: 1 },
    { serviceId: '190', directionId: 2 },
    { serviceId: '972', directionId: 1 },
  ]
  const result = filterMapRoutes(routes, true, new Set())
  expect(result).toEqual([])
})

test('distanceMeters calculates accurate ground distance between Singapore coordinates', () => {
  // Teck Whye Ave (1.380255, 103.755075) to Opp Phoenix Stn (1.378000, 103.758000)
  const d = distanceMeters(1.380255, 103.755075, 1.378000, 103.758000)
  expect(d).toBeGreaterThan(300)
  expect(d).toBeLessThan(500)

  // Same point
  expect(distanceMeters(1.35, 103.8, 1.35, 103.8)).toBe(0)
})

test('categorizeHazard correctly categorizes incident types and messages', () => {
  expect(categorizeHazard({ incidentType: 'Accident', message: 'Accident on PIE' })).toBe('accident')
  expect(categorizeHazard({ incidentType: 'Vehicle Breakdown', message: 'Car breakdown on lane 3' })).toBe('breakdown')
  expect(categorizeHazard({ incidentType: 'Roadwork', message: 'Road Works on BKE' })).toBe('roadwork')
  expect(categorizeHazard({ incidentType: 'Heavy Traffic', message: 'Jam after Clementi' })).toBe('traffic')
  expect(categorizeHazard({ incidentType: 'Flood', message: 'Flash flood at Dunearn' })).toBe('flood')
  expect(categorizeHazard({ incidentType: 'Obstacle', message: 'Debris on road' })).toBe('other')
})

test('BunchingMap renders differentiated hazard icons, layer toggles, and legend items', () => {
  const routes: RouteDirection[] = [
    {
      serviceId: '190',
      directionId: 1,
      operator: 'SMRT',
      stops: [
        { stopCode: '44251', stopSequence: 1, latitude: 1.380255, longitude: 103.755075, description: 'Teck Whye Ave', roadName: 'Teck Whye Ave', distanceKm: 0 },
        { stopCode: '44269', stopSequence: 2, latitude: 1.382000, longitude: 103.758000, description: 'Phoenix Stn', roadName: 'Choa Chu Kang Rd', distanceKm: 0.5 },
      ],
    },
  ]

  const hazards: MapHazard[] = [
    {
      id: 'haz-1',
      latitude: 1.380255,
      longitude: 103.755075,
      incidentType: 'Vehicle Breakdown',
      message: 'Breakdown on Teck Whye Ave',
    },
    {
      id: 'haz-2',
      latitude: 1.382000,
      longitude: 103.758000,
      incidentType: 'Roadwork',
      message: 'Road Works on Choa Chu Kang Rd',
    },
    {
      id: 'haz-invalid',
      latitude: Number.NaN,
      longitude: 103.755075,
      incidentType: 'Roadwork',
      message: 'Invalid coordinate hazard',
    },
  ]

  const html = renderToString(
    createElement(BunchingMap, {
      routes,
      affectedStopCodes: new Set(['44251']),
      buses: [],
      hazards,
    })
  )

  // Map controls contain layer toggles
  expect(html).toContain('Layers:')
  expect(html).toContain('Stops')
  expect(html).toContain('Buses')
  expect(html).toContain('Hazards')

  // Category filter toggles
  expect(html).toContain('Breakdown')
  expect(html).toContain('Roadwork')

  // Differentiated legend items
  expect(html).toContain('Vehicle breakdown')
  expect(html).toContain('Roadwork')

  // Valid hazard markers rendered with custom SVGs
  expect(html).toContain('Hazard: Vehicle Breakdown - Breakdown on Teck Whye Ave')
  expect(html).toContain('Hazard: Roadwork - Road Works on Choa Chu Kang Rd')

  // Roadwork renders traffic cone with polygon
  expect(html).toContain('polygon')

  // Invalid hazard is filtered out
  expect(html).not.toContain('Invalid coordinate hazard')
})

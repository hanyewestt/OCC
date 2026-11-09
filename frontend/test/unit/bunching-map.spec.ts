import { expect, test } from '@playwright/test'
import type { RouteDirection } from '../../src/lib/api'
import {
  computeBusLatLng,
  computeCorridorFit,
  DEFAULT_CENTER,
  latLngToWorld,
  MAX_ZOOM,
  MIN_ZOOM,
  TILE_SIZE,
  validCoord,
  worldToLatLng,
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

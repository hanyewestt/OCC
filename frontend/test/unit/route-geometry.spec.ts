import { expect, test } from '@playwright/test'
import type { RouteDirection } from '../../src/lib/api'
import {
  decodePolyline,
  haversineDistanceM,
  computeBusLatLngAlongPolyline,
  computePolylineDirectionArrows,
  ensurePolylineFollowsRoute,
  getBundledRouteGeometry,
} from '../../src/lib/route-geometry'
import { computeBusLatLng } from '../../src/pages/ControllerViews'

test('decodePolyline decodes standard encoded polyline strings to coordinates', () => {
  // Encoded polyline for three points: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
  const encoded = '_p~iF~ps|U_ulLnnqC_mqNvxq`@'
  const points = decodePolyline(encoded)
  expect(points).toHaveLength(3)
  expect(points[0].latitude).toBeCloseTo(38.5, 5)
  expect(points[0].longitude).toBeCloseTo(-120.2, 5)
  expect(points[1].latitude).toBeCloseTo(40.7, 5)
  expect(points[1].longitude).toBeCloseTo(-120.95, 5)
  expect(points[2].latitude).toBeCloseTo(43.252, 5)
  expect(points[2].longitude).toBeCloseTo(-126.453, 5)

  // Empty string returns empty array
  expect(decodePolyline('')).toEqual([])
})

test('haversineDistanceM calculates accurate distance in meters', () => {
  const p1 = { latitude: 1.293, longitude: 103.852 }
  const p2 = { latitude: 1.304, longitude: 103.832 }
  const dist = haversineDistanceM(p1, p2)
  // Distance between City Hall and Orchard is approximately 2.5km
  expect(dist).toBeGreaterThan(2400)
  expect(dist).toBeLessThan(2600)

  // Identical points have 0 distance
  expect(haversineDistanceM(p1, p1)).toBe(0)
})

test('computeBusLatLngAlongPolyline interpolates along road polyline accurately', () => {
  const polyline = [
    { latitude: 1.300, longitude: 103.800 },
    { latitude: 1.305, longitude: 103.805 },
    { latitude: 1.310, longitude: 103.810 },
    { latitude: 1.320, longitude: 103.820 },
  ]
  const routeLengthM = 3000

  // At beginning of route
  const start = computeBusLatLngAlongPolyline(polyline, 0, routeLengthM)
  expect(start?.latitude).toBeCloseTo(1.300, 5)
  expect(start?.longitude).toBeCloseTo(103.800, 5)

  // At terminus of route
  const end = computeBusLatLngAlongPolyline(polyline, 3000, routeLengthM)
  expect(end?.latitude).toBeCloseTo(1.320, 5)
  expect(end?.longitude).toBeCloseTo(103.820, 5)

  // Out of bounds clamps safely
  const beforeStart = computeBusLatLngAlongPolyline(polyline, -500, routeLengthM)
  expect(beforeStart?.latitude).toBeCloseTo(1.300, 5)
  expect(beforeStart?.longitude).toBeCloseTo(103.800, 5)

  const beyondEnd = computeBusLatLngAlongPolyline(polyline, 5000, routeLengthM)
  expect(beyondEnd?.latitude).toBeCloseTo(1.320, 5)
  expect(beyondEnd?.longitude).toBeCloseTo(103.820, 5)

  // Empty polyline returns null
  expect(computeBusLatLngAlongPolyline([], 1000, routeLengthM)).toBeNull()
})

test('getBundledRouteGeometry provides road-following polylines for core services', () => {
  const dir1_190 = getBundledRouteGeometry('190', 1)
  expect(dir1_190).not.toBeNull()
  expect(dir1_190!.length).toBeGreaterThan(100)
  // Direction 1 runs from Choa Chu Kang (north) to Kampong Bahru Ter (south)
  expect(dir1_190![0].latitude).toBeGreaterThan(1.35)
  expect(dir1_190![dir1_190!.length - 1].latitude).toBeLessThan(1.29)

  const dir2_190 = getBundledRouteGeometry('190', 2)
  expect(dir2_190).not.toBeNull()
  expect(dir2_190!.length).toBeGreaterThan(100)
  // Direction 2 runs from Kampong Bahru Ter (south) to Choa Chu Kang (north)
  expect(dir2_190![0].latitude).toBeLessThan(1.29)
  expect(dir2_190![dir2_190!.length - 1].latitude).toBeGreaterThan(1.35)
  // The reverse trip follows a different stop pattern, not just the same line backwards.
  expect(dir2_190).not.toEqual([...dir1_190!].reverse())

  const dir1_972 = getBundledRouteGeometry('972', 1)
  expect(dir1_972).not.toBeNull()
  expect(dir1_972!.length).toBeGreaterThan(200)

  // Unknown service returns null
  expect(getBundledRouteGeometry('999', 1)).toBeNull()
})

test('computeBusLatLng prioritizes polyline coordinates over straight chords', () => {
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

  // A curved polyline that bows eastward to longitude 103.85
  const curvedPolyline = [
    { latitude: 1.30, longitude: 103.80 },
    { latitude: 1.31, longitude: 103.85 }, // bows outward
    { latitude: 1.32, longitude: 103.82 },
  ]

  // With polyline: midpoint follows the eastward bend
  const withPolyline = computeBusLatLng(route, 1000, routeLengthM, curvedPolyline)
  expect(withPolyline).not.toBeNull()
  expect(withPolyline!.longitude).toBeGreaterThan(103.82)

  // Without polyline: midpoint stays on the straight chord (103.81)
  const withoutPolyline = computeBusLatLng(route, 1000, routeLengthM)
  expect(withoutPolyline).not.toBeNull()
  expect(withoutPolyline!.longitude).toBeCloseTo(103.81, 5)
})
test('computePolylineDirectionArrows returns empty array for short or invalid polylines', () => {
  expect(computePolylineDirectionArrows([])).toEqual([])
  expect(computePolylineDirectionArrows([{ x: 10, y: 10 }])).toEqual([])
  expect(computePolylineDirectionArrows([{ x: 10, y: 10 }, { x: 10, y: 10 }])).toEqual([])
  // Line of length 20px is below minDistancePx (35px)
  expect(computePolylineDirectionArrows([{ x: 0, y: 0 }, { x: 20, y: 0 }])).toEqual([])
})

test('computePolylineDirectionArrows calculates accurate travel angles for cardinal directions', () => {
  // Eastbound (angle 0)
  const east = computePolylineDirectionArrows([{ x: 0, y: 50 }, { x: 150, y: 50 }], { spacingPx: 60 })
  expect(east.length).toBeGreaterThan(0)
  for (const arrow of east) {
    expect(arrow.angle).toBeCloseTo(0, 1)
    expect(arrow.y).toBeCloseTo(50, 1)
  }

  // Southbound (angle 90)
  const south = computePolylineDirectionArrows([{ x: 50, y: 0 }, { x: 50, y: 150 }], { spacingPx: 60 })
  expect(south.length).toBeGreaterThan(0)
  for (const arrow of south) {
    expect(arrow.angle).toBeCloseTo(90, 1)
    expect(arrow.x).toBeCloseTo(50, 1)
  }

  // Westbound (angle 180 or -180)
  const west = computePolylineDirectionArrows([{ x: 150, y: 50 }, { x: 0, y: 50 }], { spacingPx: 60 })
  expect(west.length).toBeGreaterThan(0)
  for (const arrow of west) {
    expect(Math.abs(arrow.angle)).toBeCloseTo(180, 1)
  }

  // Northbound (angle -90)
  const north = computePolylineDirectionArrows([{ x: 50, y: 150 }, { x: 50, y: 0 }], { spacingPx: 60 })
  expect(north.length).toBeGreaterThan(0)
  for (const arrow of north) {
    expect(arrow.angle).toBeCloseTo(-90, 1)
  }
})

test('computePolylineDirectionArrows places midpoint arrow on moderate lengths and filters by viewport', () => {
  // Line of length 50px with spacingPx 75 -> places single midpoint arrow at distance 25
  const mid = computePolylineDirectionArrows([{ x: 0, y: 0 }, { x: 50, y: 0 }], { spacingPx: 75, minDistancePx: 30 })
  expect(mid).toHaveLength(1)
  expect(mid[0].x).toBeCloseTo(25, 1)
  expect(mid[0].angle).toBeCloseTo(0, 1)

  // Viewport filtering excludes points beyond viewport margin
  const withViewport = computePolylineDirectionArrows(
    [{ x: 0, y: 50 }, { x: 500, y: 50 }],
    { spacingPx: 50, viewport: { width: 100, height: 100, margin: 10 } }
  )
  expect(withViewport.every(a => a.x >= -10 && a.x <= 110)).toBe(true)
  expect(withViewport.length).toBeLessThan(computePolylineDirectionArrows([{ x: 0, y: 50 }, { x: 500, y: 50 }], { spacingPx: 50 }).length)
})
test('ensurePolylineFollowsRoute aligns polyline direction with route stops', () => {
  const stops = [
    { latitude: 1.38, longitude: 103.74 }, // North (Choa Chu Kang)
    { latitude: 1.33, longitude: 103.80 },
    { latitude: 1.28, longitude: 103.83 }, // South (Kampong Bahru)
  ]

  // Already in correct forward order
  const forwardPolyline = [
    { latitude: 1.385, longitude: 103.745 },
    { latitude: 1.330, longitude: 103.800 },
    { latitude: 1.276, longitude: 103.833 },
  ]
  const resultForward = ensurePolylineFollowsRoute(forwardPolyline, stops)
  expect(resultForward[0].latitude).toBeCloseTo(1.385, 3)
  expect(resultForward[resultForward.length - 1].latitude).toBeCloseTo(1.276, 3)

  // Polyline provided in reverse order
  const backwardPolyline = [...forwardPolyline].reverse()
  const resultReversed = ensurePolylineFollowsRoute(backwardPolyline, stops)
  // Must automatically reverse to match the forward route stops
  expect(resultReversed[0].latitude).toBeCloseTo(1.385, 3)
  expect(resultReversed[resultReversed.length - 1].latitude).toBeCloseTo(1.276, 3)

  // Short / empty fallback
  expect(ensurePolylineFollowsRoute([], stops)).toEqual([])
  expect(ensurePolylineFollowsRoute(forwardPolyline, [])).toEqual(forwardPolyline)
})

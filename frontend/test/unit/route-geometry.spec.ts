import { expect, test } from '@playwright/test'
import type { RouteDirection } from '../../src/lib/api'
import {
  decodePolyline,
  haversineDistanceM,
  computeBusLatLngAlongPolyline,
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

  const dir2_190 = getBundledRouteGeometry('190', 2)
  expect(dir2_190).not.toBeNull()
  expect(dir2_190!.length).toBeGreaterThan(100)

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

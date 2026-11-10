export interface LatLng {
  latitude: number
  longitude: number
}

/**
 * Pre-bundled road-following encoded polylines from cheeaun/sgbusdata
 * for key OCC monitored corridors (190, 972, 72, 851).
 * Direction 1 is at index 0, Direction 2 is at index 1.
 */
export const BUNDLED_ROUTE_POLYLINES: Record<string, string[]> = {
  '190': [
    '{umGqxexRiABPnJVn@hA`Al@RrH}AmD{PdGmDr@u@pH}Sj@}BMcFbEqPdB_BjBo@zDWbDXfB_@iDgIkFsPkHkRa@mCCiCf@kDb@iAp]sp@l@cDj@cP\\aBVWnA]b@HhCrBlANdJcAxOkCbJgAfZqCjLyC`FgBfFkCpF}DrEoEpEaGvMoU~DsEvG{ExAcBb@{Af@iNb@eDv@eDrAeDrAuBxJcKhf@kd@z_@gc@v@sA|AqEhAoG|@sCnPi_@xGoSzCyEvEqE|@iBd@sCC}C_@kBa@Sk@@a@j@Ap@j@nB^PfGtAnCDzEW~H~ChIjB|@Bn^mC~Ba@xA_A`DgFxFq\\Lc@fAgA`Bs@bN_A|RnG~CZrCoF~J{TzDkKtOmr@MeF\\gBlOeSdHjGdH|CjKpD|E`CpK~Hru@xl@lAvAzEvIFf@hHnJJrBYfADd@a@pE`@rGfAk@Ve@k@cG',
    'uexF_}vxRj@bGWd@gAj@a@sG`@qEEe@XgAKsBiHoJGg@{EwImAwAsu@yl@qK_I}EaCkKqDeH}CeHkGmOdS]fBLdFuOlr@{DjK_KzTsCnF_D[}RoGcN~@aBr@gAfAMb@yFp\\aDfFyA~@_C`@o^lC}@CiIkB_I_D{EVoCEgGuA_@Qk@oB@q@`@k@j@A`@R^jBB|Ce@rC}@hBwEpE{CxEyGnSoPh_@}@rCiAnG}ApEw@rA{_@fc@if@jd@yJbKsAtBsAdDw@dDc@dDg@hNc@zAyAbBwGzE_ErEwMnUqE`GsEnEqF|DgFjCaFfBkLxCgZpCcJfAyOjCeJbAmAOiCsBc@IoA\\WV]`Bk@bPm@bDq]rp@c@hAg@jDBhC`@lCjHjRjFrPhDfIgB^cDY{DVkBn@eB~AcEpPLbFk@|BqH|Ss@t@eGlDlDzPsH|Am@SiAaAWo@QoJhAC',
  ],
  '972': [
    'sdlGyhixR~@Q_@}B_@c@{AY}VvJrAjDG|@[d@iIrBuBz@cAjAyAzEcAl@{AJyDiAm@oAAeAhBuHCeC[iAoAwBaD}DUaAJ{@~BaDnE`D~BfDdGsDXiA?q@q@cEcA_CyBgAyDi@uDoA{HaDu@{@OsBPy@hHeNtAe@~Ad@vJlFvEzEfDjBfET|EB|@Mn@]p@aAH}Bw@yAgBiAs@_AQkAVwBbDTjGQjBT~B~@hAIx@[bFsJn@qBVeBj@cPHy@j@_AnA]b@HhCrBlANdJcAxOkCbJgAfZqCjLyC`FgBfFkCpF}DrEoEpEaGvMoUjBaCrAqAvG{ExAcBb@{Af@iNb@eDv@eDrAeDrAuBxJcKhf@kd@z_@gc@v@sA|AqEhAoG|@sCnPi_@xGoSzCyEvEqE|@iBj@uEYcDOc@a@Sk@@UPMjAj@nBvAh@nE|@zAFnGY~H~ChIjB|@Bn^mC~Ba@xA_A`DgFxFq\\Lc@fAgA`Bs@bN_A|RnG~CZrCoF~J{TzDkKtOmr@MeF\\gB`CgDjCxBkExFAlAlAfBIxBkCvLsC~Eo@bB_Ghc@~AxAJnBQn@q@f@_@?}BaAeBhDyGlH]f@Dd@vBjB_C|CeAtBsBoAeE_Es@_@oKqDqGQyNTeAf@w@tAyFp\\aDfFyA~@qGz@}YrBwBSoG{A_I_D{EVoCEkA\\eCxDoGtFwCzEqG~R_Pr]y@`CaC~LoBzDeb@|e@gl@pi@cBpBiDvGo@nFTrBpDnDl@`ADvAW~@uBnB{MpHoFhEiB~BwMpUqE~FuEnEqF|DeFlCqMhEuJzA{VrBiHfAiThDuFnAkHxBs@x@a@pCKrCm@bDuH`OsC{AcDm@kGPoBIaAj@GxAZfAnCrBv@xAI|Bq@`Ao@\\}@L}ECgEUgDkBwE{EwJmF_Be@uAd@gAvBLFMGaFlJQx@NrBt@z@zH`DtDnAxDh@xBfAbA~Bp@bE?p@YhAqMrHMZCXp@lCDrAwBxJ@dAl@nAxDhAnBU`A_AfA_EbAkAtB{@dH_Bl@]Xq@?g@sAkD|YeLz@hF_AP',
  ],
  '72': [
    'q`mG}cyxRj@RT~AtDIdC]PaFh@{At@{@`FsDjDmAbEm@nAq@f@oAdAmMrAwXCyF}Ige@uC{QsB_gAjNcAfEGv@[d@i@GaWfCgSiAyBjAk@dGr@d@?j@YdEyGbAmHXo@lDwD~DcBcJeI[w@OaA@iFtAeBpAaAnBEbC}BtSdTfANz@MvF}EfBw@rBe@bHa@y@qPyDok@oBuSc@yBeA{BaEkEyC_B}\\qJmZsKgQeF{Au@iC_CeAgBeC}Fe@mBOyBj@eC~@yAv@w@dBw@vB_@|ZeBxC{@pDyBvV{UpEsGlBgFb@{Bp@gQ^Wh`@AfAm@Cyi@n[DBsKq@m@iB[qBqBdLiLCi@dBw@',
    'omgG{jlyRYNqAE[Eg@e@]Jk@n@tAbApCB?fEgDVgAh@iAzAYhBCpLo[E@`l@oX@kFDe@Nw@`Ae@fNcAfEgBhEeB`CkZdYqDxByCz@}ZdBwB^eBv@w@v@_AxAk@dCNxBd@lBdC|FdAfBhC~BzAt@fQdFlZrK|\\pJxC~A`EjEdAzBb@xBnBtSxDnk@x@pPcH`@qDbAsBtAqFbFuSeTeAOu@FgA`AoBDqA`AuAdBAhFN`AZv@bJdI_EbBmDvDYn@cAlHeExGk@Xe@?kFs@s@FMVEl@j@vAA^eCfRBfYaHFuK~@qA`BnB`dAtCzQbIha@b@`FEtCoBx_@cA|IaH|@qDpA{EnDu@z@m@lBuBG',
  ],
  '851': [
    'a|uGcmwxRIvB|i@fAjCVdCn@dtAzh@tKlAhH@|Iw@nD{@bGwBjDkBzBiBbE{Efc@{w@xA_B|BaBxAu@|HgCjCgDn@a@fQcF`L[`Fm@|{@iF|EFhGv@rCjAzIrF|FvB~YtG`F^jEBbJ]vDh@|VnH|h@nDbCCdNeBzAHvAp@`EtFnChBnC\\pF@|Jp@tC}@fDaC~OwEdAi@|DaEjR}HvA[rDE`OhApACxBk@vBeAxKeIlC{@bT}E`CiAxBwAtJgJt@YpCKjJ|CnA?d@OtJsKtGsIpCsH^eBlDmGHk@UgA@aAnB}BvG{JzGzFld@tZp`@rSrK|EtPvIqFpKp_@nZyFfB}@t@eBdCsEpI{B~CaB|@eAlDwFlDwAdCMj@Xn\\w@`Ke@jDo@hBs@vAkFjH`@p@fDh@dDvAzFzEnGbBpCxAnD`DeAlAkApGpEv@',
    '}kyFgysxRiB[`AmFdAmAoDaDqCyAoGcB}DiDaC}AiDeAqDYzGsI|AsD|A{NOgb@mBuCQe@Dm@b@[tFm@~FaB`B}@zCmErDcH~DqDdAoBJuAmHaFgr@_j@Ik@_EuBkKqDeH}C{MoLoVkRwHdHoDnBwAvBiArDgDfIoBhBcOnRsA@cMaEkBPaAl@mMtMuGh@uQ`EmCz@yKdIwBdAyBj@qABgQkAmBFwAZkR|H}D`EeAh@_PvEgD`CuC|@}Jq@qFAoC]oCiBaEuFwAq@{AIeNdBcCB}h@oD}VoHwDi@cJ\\kECaF_@_ZuG}FwB{IsFsCkAiGw@}EG}{@hFaFl@aLZgQbFo@`@kCfD}HfCyAt@}B`ByA~Agc@zw@cEzE{BhBkDjBcGvBoDz@}Iv@iHAuKmAetA{h@eCo@kCW}i@gAHwB',
  ],
}

/**
 * Decode standard Google Encoded Polyline format into latitude/longitude pairs.
 */
export function decodePolyline(encoded: string): LatLng[] {
  const points: LatLng[] = []
  let index = 0
  let lat = 0
  let lng = 0

  while (index < encoded.length) {
    let b: number
    let shift = 0
    let result = 0
    do {
      b = encoded.charCodeAt(index++) - 63
      result |= (b & 0x1f) << shift
      shift += 5
    } while (b >= 0x20)
    const dlat = (result & 1) !== 0 ? ~(result >> 1) : result >> 1
    lat += dlat

    shift = 0
    result = 0
    do {
      b = encoded.charCodeAt(index++) - 63
      result |= (b & 0x1f) << shift
      shift += 5
    } while (b >= 0x20)
    const dlng = (result & 1) !== 0 ? ~(result >> 1) : result >> 1
    lng += dlng

    points.push({
      latitude: lat / 1e5,
      longitude: lng / 1e5,
    })
  }

  return points
}

/**
 * Calculates distance in meters between two coordinates using the Haversine formula.
 */
export function haversineDistanceM(p1: LatLng, p2: LatLng): number {
  const R = 6371000
  const dLat = (p2.latitude - p1.latitude) * (Math.PI / 180)
  const dLng = (p2.longitude - p1.longitude) * (Math.PI / 180)
  const lat1 = p1.latitude * (Math.PI / 180)
  const lat2 = p2.latitude * (Math.PI / 180)

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.sin(dLng / 2) * Math.sin(dLng / 2) * Math.cos(lat1) * Math.cos(lat2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return R * c
}

/**
 * Computes estimated bus latitude/longitude accurately along the polyline path
 * rather than interpolating along a straight chord between distant bus stops.
 */
export function computeBusLatLngAlongPolyline(
  polyline: LatLng[],
  distanceAlongRouteM: number,
  routeLengthM: number,
): LatLng | null {
  if (!polyline || polyline.length === 0) return null
  if (polyline.length === 1 || routeLengthM <= 0) return polyline[0]

  const distances: number[] = [0]
  let total = 0
  for (let i = 0; i < polyline.length - 1; i++) {
    total += haversineDistanceM(polyline[i], polyline[i + 1])
    distances.push(total)
  }

  if (total <= 0) return polyline[0]

  const ratio = Math.max(0, Math.min(1, distanceAlongRouteM / routeLengthM))
  const target = ratio * total

  let nextIdx = -1
  for (let i = 1; i < distances.length; i++) {
    if (distances[i] >= target) {
      nextIdx = i
      break
    }
  }
  if (nextIdx < 1) nextIdx = polyline.length - 1

  const prevIdx = nextIdx - 1
  const dPrev = distances[prevIdx]
  const dNext = distances[nextIdx]
  const denom = dNext - dPrev
  const segRatio = denom > 0 ? (target - dPrev) / denom : 0

  const pPrev = polyline[prevIdx]
  const pNext = polyline[nextIdx]
  return {
    latitude: pPrev.latitude + segRatio * (pNext.latitude - pPrev.latitude),
    longitude: pPrev.longitude + segRatio * (pNext.longitude - pPrev.longitude),
  }
}

// In-memory cache of decoded route points keyed by `${serviceId}-${directionId}`
const decodedRouteCache = new Map<string, LatLng[]>()
let remoteRoutesFetchPromise: Promise<Record<string, string[]>> | null = null

/**
 * Returns bundled road geometry synchronously if available.
 */
export function getBundledRouteGeometry(serviceId: string, directionId: number): LatLng[] | null {
  const cacheKey = `${serviceId}-${directionId}`
  const cached = decodedRouteCache.get(cacheKey)
  if (cached) return cached

  const servicePolylines = BUNDLED_ROUTE_POLYLINES[serviceId]
  if (!servicePolylines || !servicePolylines.length) return null

  // directionId is 1-based (direction 1 -> index 0, direction 2 -> index 1)
  const polylineStr = servicePolylines[directionId - 1] ?? servicePolylines[0]
  if (!polylineStr) return null

  const decoded = decodePolyline(polylineStr)
  decodedRouteCache.set(cacheKey, decoded)
  return decoded
}

/**
 * Asynchronously loads route road geometry for any service and direction.
 * Checks bundled data, then memory cache, and fetches from sgbusdata repository if needed.
 */
export async function loadRouteGeometry(
  serviceId: string,
  directionId: number,
  signal?: AbortSignal,
): Promise<LatLng[] | null> {
  const bundled = getBundledRouteGeometry(serviceId, directionId)
  if (bundled) return bundled

  const cacheKey = `${serviceId}-${directionId}`
  const cached = decodedRouteCache.get(cacheKey)
  if (cached) return cached

  try {
    if (!remoteRoutesFetchPromise) {
      remoteRoutesFetchPromise = (async () => {
        const endpoints = [
          'https://data.busrouter.sg/v1/routes.min.json',
          'https://raw.githubusercontent.com/cheeaun/sgbusdata/main/data/v1/routes.min.json',
        ]
        for (const url of endpoints) {
          try {
            const resp = await fetch(url, { signal })
            if (resp.ok) {
              return (await resp.json()) as Record<string, string[]>
            }
          } catch {
            // Try next endpoint
          }
        }
        return {}
      })()
    }

    const allRoutes = await remoteRoutesFetchPromise
    const servicePolylines = allRoutes[serviceId]
    if (!servicePolylines || !servicePolylines.length) return null

    const polylineStr = servicePolylines[directionId - 1] ?? servicePolylines[0]
    if (!polylineStr) return null

    const decoded = decodePolyline(polylineStr)
    decodedRouteCache.set(cacheKey, decoded)
    return decoded
  } catch {
    return null
  }
}


export interface DirectionArrow {
  x: number
  y: number
  angle: number
}

export interface DirectionArrowOptions {
  spacingPx?: number
  minDistancePx?: number
  viewport?: {
    width: number
    height: number
    margin?: number
  }
}

/**
 * Computes evenly spaced directional arrows (position and rotation in degrees)
 * along a 2D projected polyline in the direction of travel (from first point to last).
 */
export function computePolylineDirectionArrows(
  points: Array<{ x: number; y: number }>,
  options?: DirectionArrowOptions,
): DirectionArrow[] {
  if (!points || points.length < 2) return []

  const spacing = options?.spacingPx ?? 75
  const minDistance = options?.minDistancePx ?? 35

  // Compute cumulative screen distances along the polyline
  const cumDists: number[] = [0]
  for (let i = 0; i < points.length - 1; i++) {
    const p1 = points[i]
    const p2 = points[i + 1]
    const d = Math.hypot(p2.x - p1.x, p2.y - p1.y)
    cumDists.push(cumDists[i] + d)
  }

  const totalDist = cumDists[cumDists.length - 1]
  if (totalDist < minDistance) return []

  const viewport = options?.viewport
  const margin = viewport?.margin ?? 25
  const isInViewport = (x: number, y: number) => {
    if (!viewport) return true
    return (
      x >= -margin &&
      x <= viewport.width + margin &&
      y >= -margin &&
      y <= viewport.height + margin
    )
  }

  const sampleAt = (s: number, delta = 4): DirectionArrow | null => {
    let low = 0
    let high = cumDists.length - 2
    let segIdx = 0
    while (low <= high) {
      const mid = (low + high) >> 1
      if (cumDists[mid] <= s) {
        segIdx = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }

    const segLen = cumDists[segIdx + 1] - cumDists[segIdx]
    const t = segLen > 0 ? (s - cumDists[segIdx]) / segLen : 0
    const pA = points[segIdx]
    const pB = points[segIdx + 1]
    const x = pA.x + t * (pB.x - pA.x)
    const y = pA.y + t * (pB.y - pA.y)

    // Tangent angle around s using small delta
    const s1 = Math.max(0, s - delta)
    const s2 = Math.min(totalDist, s + delta)

    const getPos = (dist: number) => {
      let l = 0
      let r = cumDists.length - 2
      let idx = 0
      while (l <= r) {
        const m = (l + r) >> 1
        if (cumDists[m] <= dist) {
          idx = m
          l = m + 1
        } else {
          r = m - 1
        }
      }
      const len = cumDists[idx + 1] - cumDists[idx]
      const ratio = len > 0 ? (dist - cumDists[idx]) / len : 0
      return {
        x: points[idx].x + ratio * (points[idx + 1].x - points[idx].x),
        y: points[idx].y + ratio * (points[idx + 1].y - points[idx].y),
      }
    }

    const pt1 = getPos(s1)
    const pt2 = getPos(s2)
    let dx = pt2.x - pt1.x
    let dy = pt2.y - pt1.y
    if (Math.hypot(dx, dy) < 0.001) {
      dx = pB.x - pA.x
      dy = pB.y - pA.y
    }
    const angle = Math.round((Math.atan2(dy, dx) * 180) / Math.PI * 10) / 10

    return {
      x: Math.round(x * 10) / 10,
      y: Math.round(y * 10) / 10,
      angle,
    }
  }

  const arrows: DirectionArrow[] = []

  // If the polyline is short but above minDistance, place one arrow at the midpoint
  if (totalDist < spacing) {
    const pt = sampleAt(totalDist / 2)
    if (pt && isInViewport(pt.x, pt.y)) {
      arrows.push(pt)
    }
    return arrows
  }

  // Normal spacing: start at spacing / 2 and place an arrow every `spacing` pixels
  for (let s = spacing / 2; s <= totalDist - 10; s += spacing) {
    const pt = sampleAt(s)
    if (pt && isInViewport(pt.x, pt.y)) {
      arrows.push(pt)
    }
  }

  return arrows
}

/**
 * Ensures that a polyline geometry flows in the same direction as the route stops
 * (from the first stop towards the terminus). If the polyline was provided in
 * reverse order (e.g. from opposite direction feed), it reverses the points.
 */
export function ensurePolylineFollowsRoute(
  polyline: LatLng[],
  stops: Array<{ latitude?: number | null; longitude?: number | null }>,
): LatLng[] {
  if (!polyline || polyline.length < 2 || !stops || stops.length < 2) {
    return polyline
  }
  const validStops = stops.filter(
    s => s.latitude !== null && s.latitude !== undefined && s.longitude !== null && s.longitude !== undefined && Number.isFinite(s.latitude) && Number.isFinite(s.longitude)
  ) as Array<{ latitude: number; longitude: number }>
  if (validStops.length < 2) return polyline

  const sFirst = validStops[0]
  const sLast = validStops[validStops.length - 1]
  const pFirst = polyline[0]
  const pLast = polyline[polyline.length - 1]

  const distForward = haversineDistanceM(pFirst, sFirst) + haversineDistanceM(pLast, sLast)
  const distReversed = haversineDistanceM(pLast, sFirst) + haversineDistanceM(pFirst, sLast)

  // If the reversed polyline matches the first and last stops substantially better, reverse it
  if (distReversed < distForward) {
    return [...polyline].reverse()
  }
  return polyline
}

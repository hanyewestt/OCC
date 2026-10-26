export type Identity = { subject: string; role: 'CONTROLLER' | 'CAPTAIN' | 'ADMIN' | 'UNASSIGNED' }

export class ApiError extends Error {
  constructor(public status: number) {
    super(status === 401 ? 'Your session has expired. Sign in again.' :
      status === 403 ? 'Access is not available for this role.' : 'Unable to verify access. Try again.')
  }
}

export async function identityRequest(path: string, token: string, signal: AbortSignal): Promise<Identity> {
  // This transport must never send a bearer token to an external URL.
  if (!path.startsWith('/api/v1/auth/')) throw new Error('Invalid authentication request.')
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', redirect: 'error', signal,
  })
  if (!response.ok) throw new ApiError(response.status)
  const body = await response.json() as Partial<Identity>
  if (typeof body.subject !== 'string' || !body.subject ||
    !['CONTROLLER', 'CAPTAIN', 'ADMIN', 'UNASSIGNED'].includes(body.role ?? '')) {
    throw new Error('Invalid session response. Try again.')
  }
  return body as Identity
}

export function homeFor(role: Identity['role']): string | null {
  return role === 'CONTROLLER' ? '/controller' : role === 'CAPTAIN' ? '/captain' : null
}

export type PageResult<T> = { items: T[]; page: number; totalItems: number; totalPages: number }
export type Arrival = {
  observationId: string; serviceId: string; stopCode: string; operator: string
  directionId?: number | null; visitOrder?: number | null; visitNumber?: number | null
  estimatedArrivalAt: string | null; fetchedAt: string; loadCategory: string | null
  latitude?: number | null; longitude?: number | null; vehicleType?: string | null
}
export type RouteStop = {
  stopCode: string; roadName: string | null; description: string | null
  latitude: number | null; longitude: number | null; stopSequence: number; distanceKm: number | null
}
export type BusRoute = {
  serviceId: string
  operators: Array<{
    operator: string; category: string | null
    directions: Array<{ directionId: number; stops: RouteStop[] }>
  }>
}
export type RouteDirection = { serviceId: string; operator: string; directionId: number; stops: RouteStop[] }
export type RoutePositionBus = {
  order: number; distanceAlongRouteM: number; uncertaintyM: number; speedMps: number | null
  evidenceObservationIds?: string[]; positionEvidenceObservationIds?: string[]; supportingStopCount?: number
}
export type RoutePositionDirection = {
  operator: string; directionId: number; routeLengthM: number; buses: RoutePositionBus[]
  suppressedBusCount?: number
}
export type RoutePositions = {
  serviceId: string; computedAt: string; refreshIntervalSeconds: number; maxSourceAgeSeconds: number
  directions: RoutePositionDirection[]
  sourcePollCycleId?: string | null; sourceObservedAt?: string | null
}
export type BunchingAlert = {
  eventId: string; action: string; classification: string; serviceId: string
  stopCode: string; detectedAt: string; directionId?: number | null
  fetchedAt?: string; rawObservationId?: string; pollRunId?: string
  stopName?: string | null; stopRoadName?: string | null; evidence?: string | null
}
export type DemoRun = { id: string; status: string; alerts: BunchingAlert[] }
export type Recommendation = {
  recommendationId: string; classification: string; status: string; mitigationType: string
  targetBusReference: string; controlStopCode: string; adjustmentSeconds: number
  rationale: string; likelyCause: string; tradeOffs: string[]; limitations: string[]
}
export type RecommendationExplanation = {
  rationale: string; likelyCause: string; diagnosisConfidence: number
  tradeOffs: string[]; limitations: string[]
}
export type RecommendationImpact = {
  headwayBeforeSeconds: number; headwayAfterSeconds: number
  queueBeforePassengers: number; queueAfterPassengers: number
  passengerMinutesBefore: number; passengerMinutesAfter: number
}
export type PendingRecommendation = {
  recommendationId: string; classification: string; status: string
  serviceId: string; directionId: number; monitoredStopCode: string
  detectedAt: string; createdAt: string
  expectedHeadwaySeconds: number; observedHeadwaySeconds: number
  mitigationType: string; targetBusReference: string; affectedBusReferences: string[]
  controlStopCode: string; adjustmentSeconds: number
  explanation: RecommendationExplanation; expectedImpact: RecommendationImpact
}
export type ControllerBusInstruction = { busReference: string; instruction: string }
export type Decision = {
  recommendationId: string; action: 'APPROVED' | 'REJECTED'; actorSubject: string
  overrideInstructions: ControllerBusInstruction[]; controllerRationale: string | null; decidedAt: string
}

export function routeDirections(routes: BusRoute[]): RouteDirection[] {
  return routes.flatMap(route => route.operators.flatMap(operator => operator.directions.map(direction => ({
    serviceId: route.serviceId, operator: operator.operator, directionId: direction.directionId,
    stops: direction.stops,
  }))))
}

// All dashboard requests stay on the same origin. Null represents a documented
// empty response, never a failed request or an assumed count of zero.
export async function apiRequest<T>(path: string, token: string, signal: AbortSignal,
  options?: { method: 'POST'; body?: unknown }): Promise<T | null> {
  if (!path.startsWith('/api/v1/')) throw new Error('Invalid API request.')
  const response = await fetch(path, {
    method: options?.method ?? 'GET',
    headers: { Authorization: `Bearer ${token}`, ...(options?.body ? { 'Content-Type': 'application/json' } : {}) },
    body: options?.body ? JSON.stringify(options.body) : undefined,
    cache: 'no-store', redirect: 'error', signal,
  })
  if (!response.ok) throw new ApiError(response.status)
  return response.status === 204 ? null : await response.json() as T
}

export async function enrichBunchingStops(alerts: BunchingAlert[], token: string, signal: AbortSignal): Promise<BunchingAlert[]> {
  const stops = new Map<string, Promise<{ description: string | null; roadName: string | null } | null>>()
  return Promise.all(alerts.map(async alert => {
    if (alert.stopName) return alert
    if (!stops.has(alert.stopCode)) {
      stops.set(alert.stopCode, apiRequest<{ description: string | null; roadName: string | null }>(
        `/api/v1/bus-stops/${encodeURIComponent(alert.stopCode)}`, token, signal,
      ).catch(error => {
        if (signal.aborted || (error instanceof ApiError && error.status === 401)) throw error
        // Reference enrichment must not suppress an otherwise valid detection.
        return null
      }))
    }
    const stop = await stops.get(alert.stopCode)
    return { ...alert, stopName: stop?.description, stopRoadName: stop?.roadName }
  }))
}

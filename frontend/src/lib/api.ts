export type Identity = { subject: string; role: 'CONTROLLER' | 'CAPTAIN' | 'ADMIN' | 'UNASSIGNED' }

export type DirectoryAssignment = { demoRunId: string; busId: string; vehicleRegistrationNumber: string; serviceId: string; directionId: number }
export type DirectoryUser = { id: string; displayName?: string | null; name?: string | null; email: string; role: string; available: boolean; createdAt: string; lastSignInAt: string | null; synchronizedAt: string; currentAssignment: DirectoryAssignment | null }
export type DirectoryPage = { items: DirectoryUser[]; page: number; size: number; totalItems: number; totalPages: number; directorySynchronizedAt: string | null }
export type AuditEvent = { id: string; occurredAt: string; category: string; eventType: string; outcome: string; sourceService: string; actorSubject: string | null; actorType: string | null; entityType: string | null; entityId: string | null; runId: string | null; busId: string | null; captainId: string | null; summary: string | null; backfilled: boolean }
export type AuditDetail = AuditEvent & { recordedAt: string; correlationId: string | null; details: Record<string, unknown> }
export type ActiveAssignments = { runId: string | null; status: string | null; items: { busId: string; tripId: string; registrationNumber: string; serviceId: string; directionId: number; captainUserId: string | null; assignmentRevision: number; pendingOperation: { operationId: string; status: string } | null }[] }
export type AssignmentOperation = { operationId: string; status: string; runId: string; busId: string; captainUserId: string | null; expectedVersion: number; createdAt: string; updatedAt: string; result: Record<string, unknown> | null }
export type ProvisioningOperation = { id: string; status: string; email: string; displayName: string; role: string; failureCode: string | null; result: DirectoryUser | null }

export function newIdempotencyKey(): string { return crypto.randomUUID() }
export function ingestionAdminPath(path: string): string { return `/api/v1/ingestion/${path.replace(/^\//, '')}` }

export type InstructionType = 'BE_CAUTIOUS' | 'REDUCE_SPEED' | 'SLOW_DOWN_NEAR_STOPS' | 'HOLD' | 'DWELL_ADJUSTMENT'
export type InstructionParameters = { stopCode: string | null; stopName: string | null; stopSequence: number | null;
  subsequentStopCount: number | null; speedReductionKmh: number | null; durationSeconds: number | null }
export type CaptainInstruction = { id: string; demoRunId: string; busId: string; type: InstructionType;
  parameters: InstructionParameters; message: string; busClassification: string; hazardClassification: string;
  status: string; issuedAt: string; acknowledgementDeadline: string; acknowledgedAt: string | null;
  applicableUntil: string | null; inactiveReason: string | null }
export type Hazard = { disruptionId: string; kind: string; status: string; classification: string; demoRunId: string | null;
  updatedAt: string; facts: { title: string; description: string; instruction: string | null; effectiveUntil: string | null };
  exposures: { busId: string; serviceId: string; stopCode: string; stopName: string; stopSequence: number; basis: string }[]; limitations: string[] }
export type PublicNotice = { id: string; classification: string; title: string; message: string; services: string[];
  locations: string[]; effectiveFrom: string; effectiveUntil: string; publishedAt: string | null }
export type ManagedNotice = PublicNotice & { disruptionId: string; demoRunId: string | null; status: string; withdrawnAt: string | null }
export type NoticeSuggestion = { title: string; message: string; services: string[]; locations: string[]; generator: string }

export class ApiError extends Error {
  constructor(public status: number, detail?: string, public extensions: Record<string, unknown> = {}) {
    super(status === 401 ? 'Your session has expired. Sign in again.' :
      status === 403 ? 'Access is not available for this role.' : detail ?? 'Unable to load or save this data. Try again.')
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
  return role === 'ADMIN' ? '/admin' : role === 'CONTROLLER' ? '/controller' : role === 'CAPTAIN' ? '/captain' : null
}

export type HazardFeedHealth = { kind: string; source: string; scope: string; classification: string; demoRunId: string | null;
  status: 'UNKNOWN' | 'HEALTHY' | 'STALE' | 'DEGRADED' | 'FAILED'; lastAttemptAt: string | null; lastSuccessAt: string | null }
export type PageResult<T> = { items: T[]; page: number; totalItems: number; totalPages: number; serverTime?: string; feeds?: HazardFeedHealth[] }
export type Arrival = {
  observationId: string; serviceId: string; stopCode: string; operator: string
  directionId?: number | null; visitOrder?: number | null; visitNumber?: number | null
  stopName?: string | null; originCode?: string | null; destinationCode?: string | null
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
  eventId: string; alertId?: string | null; bunchingEventId?: string | null; action: string; classification: string; serviceId: string
  stopCode: string; detectedAt: string; directionId?: number | null
  fetchedAt?: string; rawObservationId?: string; pollRunId?: string
  stopName?: string | null; stopRoadName?: string | null; evidence?: string | null
  leadingVehicle?: SimulationVehicle | null; followingVehicle?: SimulationVehicle | null
  affectedObservationIds?: string[]; affectedVehicles?: SimulationVehicle[]
}
export type SimulationVehicle = { registrationNumber: string; operator: string; vehicleType: string; captainCode: string; busId: string }
export type DemoRun = {
  id: string; status: string; alerts: BunchingAlert[]; serviceId: string; directionId: number
  busCount: number; dispatchGapSeconds: number; busGapsSeconds: number[]; monitoredStopCode: string; scenario: 'NORMAL' | 'BUNCHING'
}
export type Recommendation = {
  recommendationId: string; classification: string; status: string; mitigationType: string
  targetBusReference: string; controlStopCode: string; adjustmentSeconds: number
  rationale: string; likelyCause: string; tradeOffs: string[]; limitations: string[]
}
export type RecommendationExplanation = {
  rationale: string; likelyCause: string; diagnosisConfidence: number | null
  tradeOffs: string[]; limitations: string[]; evidenceDetails?: DiagnosisEvidenceDetail[]
}
export type DiagnosisEvidenceDetail = {
  evidenceId: string; incidentType: string; message: string
  reportTime: string | null; fetchedAt: string | null; firstConfirmedAt: string | null
  secondsBeforeFirstConfirmed: number | null; distanceMetersFromSuppliedBusPosition: number | null
  busPositionEvidenceId: string | null; monitoredStopCode: string | null
}
export type RecommendationImpact = {
  headwayBeforeSeconds: number; headwayAfterSeconds: number
  queueBeforePassengers: number; queueAfterPassengers: number
  passengerMinutesBefore: number; passengerMinutesAfter: number
}
export type PendingRecommendation = {
  recommendationId: string; bunchingEventId?: string; classification: string; status: string
  serviceId: string; directionId: number; monitoredStopCode: string
  detectedAt: string; createdAt: string
  expectedHeadwaySeconds: number; observedHeadwaySeconds: number
  mitigationType: string; targetBusReference: string; affectedBusReferences: string[]
  controlStopCode: string; adjustmentSeconds: number
  explanation: RecommendationExplanation; expectedImpact: RecommendationImpact
}
export type ControllerBusInstruction = { busReference: string; instruction: string }
export type EarlyControllerSuggestion = {
  requestId: string; instructions: ControllerBusInstruction[]; controllerRationale: string; actorSubject: string; suggestedAt: string
}
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

export type CaptainRouteStop = {
  sequence: number; stopCode: string; description: string | null; roadName: string | null
  distanceKm: number | null; arrivalAt: string | null; arrivalKind: 'PREDICTED' | 'ESTIMATED' | null
}
export type CaptainRoute = {
  classification: 'SIMULATION'
  bus: { busId: string; registrationNumber: string; serviceId: string; directionId: number }
  stops: CaptainRouteStop[]
  position: {
    source: 'SIMULATION' | 'UNAVAILABLE'; lastStopSequence: number | null
    nextStopSequence: number | null; observedAt: string | null
  }
  pacing: {
    targetHeadwaySeconds: number; ahead: PacingGap | null; behind: PacingGap | null
    observedAt: string; calculatedAt: string
  } | null
}
export type PacingGap = { headwaySeconds: number; state: 'TOO_CLOSE' | 'ON_TARGET' | 'TOO_FAR' }

// All dashboard requests stay on the same origin. Null represents a documented
// empty response, never a failed request or an assumed count of zero.
export async function apiRequest<T>(path: string, token: string | undefined, signal: AbortSignal,
  options?: { method: 'POST' | 'PUT'; body?: unknown; headers?: Record<string, string> }): Promise<T | null> {
  if (!path.startsWith('/api/v1/')) throw new Error('Invalid API request.')
  if (!token && !path.startsWith('/api/v1/planning/public/notices')) throw new Error('Authentication required.')
  const response = await fetch(path, {
    method: options?.method ?? 'GET',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options?.body ? { 'Content-Type': 'application/json' } : {}), ...options?.headers },
    body: options?.body ? JSON.stringify(options.body) : undefined,
    cache: 'no-store', redirect: 'error', signal,
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as Record<string, unknown> | null
    const { detail, ...extensions } = problem ?? {}
    throw new ApiError(response.status, typeof detail === 'string' ? detail : undefined, extensions)
  }
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

export type PlanningHazardRequest = {
  requestId: string; eventId: string; disruptionId: string; eventType: 'HAZARD' | 'TRAFFIC_INCIDENT'
  status: 'PENDING' | 'PROCESSING' | 'FAILED_RETRYABLE' | 'FAILED_TERMINAL'; failureCode: string | null; createdAt: string
}
export type PlanningHazardRequestPage = PageResult<PlanningHazardRequest> & { mode: 'LIVE' | 'DEMO'; demoRunId: string | null }
export type PlanningHazardHistory = Omit<PlanningHazardRequest, 'status'> & {
  status: 'COMPLETED' | 'FAILED_TERMINAL'; title: string; completedAt: string
}

export type PlanningDiagnosisRequest = {
  requestId: string; eventId: string; diagnosisId: string | null
  classification: string; demoRunId: string | null
  serviceId: string | null; directionId: number | null; stopCode: string | null
  affectedBusReferences: string[]
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED_RETRYABLE' | 'FAILED_TERMINAL'
  likelyCause: string | null; confidence: number | null; limitations: string[]
  evidenceDetails: DiagnosisEvidenceDetail[]; failureCode: string | null; policyOutcome: string | null
  createdAt: string
}
export type PlanningDiagnosisRequestPage = PageResult<PlanningDiagnosisRequest> & {
  mode: 'LIVE' | 'DEMO'; demoRunId: string | null
}

export type AiAuditRecord = {
  invocationId: string; purpose: 'DIAGNOSIS' | 'RECOMMENDATION'; requestId: string | null; recommendationId: string | null
  provider: string; model: string; promptVersion: string; schemaVersion: string; attemptType: string | null; attemptNumber: number | null
  startedAt: string; completedAt: string; latencyMillis: number; promptTokens: number | null; completionTokens: number | null; totalTokens: number | null
  outcome: string; failureCategory: string | null; deterministicFallbackUsed: boolean | null
}

export type TriggerDiagnosisRequest = {
  eventId?: string
  requestId?: string
}

export type TriggerDiagnosisResponse = {
  requestId: string
  diagnosisId: string | null
  status: string
  failureCode: string | null
  createdAt: string
  policyOutcome: string | null
}

export async function triggerDiagnosis(
  body: TriggerDiagnosisRequest,
  token: string,
  signal: AbortSignal
): Promise<TriggerDiagnosisResponse | null> {
  return apiRequest<TriggerDiagnosisResponse>('/api/v1/planning/diagnoses/trigger', token, signal, {
    method: 'POST',
    body,
  })
}

export type AdvisoryCandidate = { type: InstructionType; parameters: InstructionParameters; rationale: string }
export type Advisory = { id: string; disruptionId: string; demoRunId: string; busId: string; status: string;
  candidate: AdvisoryCandidate; finalCandidate: AdvisoryCandidate | null; preview: string; hazardClassification: string; limitations: string[]; createdAt: string }
export type AdvisoryGroup = { id: string; disruptionId: string; demoRunId: string; serviceId: string; directionId: number;
  hazardTitle: string; hazardClassification: string; candidate: AdvisoryCandidate; preview: string; members: Advisory[] }

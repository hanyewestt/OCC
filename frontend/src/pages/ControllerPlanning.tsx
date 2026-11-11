import { useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { ApiError, apiRequest, triggerDiagnosis, type BunchingAlert, type ControllerBusInstruction, type Decision, type DiagnosisEvidenceDetail, type EarlyControllerSuggestion, type PendingRecommendation, type PlanningDiagnosisRequest, type PlanningDiagnosisRequestPage, type RouteDirection, type RoutePositions, type RouteStop } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { bunchingArrivals } from '@/lib/bunching'
import { groupRouteIncidents, type RouteIncident } from '@/lib/route-strip'
import type { Planning } from './ControllerPage'
import type { PagedCollection } from '@/lib/paged-collection'
import { BunchingMap, DataMessage, timestamp, type PositionedIncident } from './ControllerViews'
import { adjacentBunchingGaps, bunchedArrivalGaps, formatGap } from '@/lib/route-strip'

function seconds(value: number) {
  const minutes = Math.floor(value / 60)
  const remainder = value % 60
  return minutes ? `${minutes}:${String(remainder).padStart(2, '0')}` : `${remainder}s`
}

function suggestedInstruction(recommendation: PendingRecommendation,
  { mitigationType, adjustmentSeconds }: { mitigationType: string | null; adjustmentSeconds: number } = recommendation) {
  if (mitigationType === 'HOLD') return `Hold for ${adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  if (mitigationType === 'DWELL_ADJUSTMENT') return `Adjust dwell by ${adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  return `Dispatch standby support for ${recommendation.targetBusReference} at stop ${recommendation.controlStopCode}.`
}

function affectedBuses(recommendation: PendingRecommendation) {
  return recommendation.affectedBusReferences.length
    ? recommendation.affectedBusReferences : [recommendation.targetBusReference]
}

function BunchedPairs({ alert }: { alert: BunchingAlert | undefined }) {
  const pairs = alert ? bunchedArrivalGaps(alert) : []
  return pairs.length ? <p className="text-xs text-muted-foreground">{pairs.map(pair =>
    `Buses #${pair.leadingOrder} & #${pair.followingOrder} · ${formatGap(pair.seconds)}`).join(' · ')}</p> : null
}

function recommendationAlert(recommendation: PendingRecommendation, alerts: BunchingAlert[]): BunchingAlert | undefined {
  const byEventId = recommendation.bunchingEventId
    ? alerts.find(alert => (alert.bunchingEventId ?? alert.alertId) === recommendation.bunchingEventId)
    : undefined
  return byEventId ?? alerts.find(alert => alert.serviceId === recommendation.serviceId &&
    alert.directionId === recommendation.directionId && alert.stopCode === recommendation.monitoredStopCode &&
    alert.detectedAt === recommendation.detectedAt)
}

function RecommendationBunchingGaps({ recommendation, alerts }: { recommendation: PendingRecommendation; alerts: BunchingAlert[] }) {
  const alert = recommendationAlert(recommendation, alerts)
  const detectedPairs = alert ? adjacentBunchingGaps(alert) : []
  const buses = affectedBuses(recommendation)
  const predictionGaps = recommendation.currentPredictionGaps
  const pairCount = Math.min(99, detectedPairs.reduce((count, pair) => Math.max(count, pair.followingOrder - 1), Math.max(1, buses.length - 1)))
  const pairs = Array.from({ length: pairCount }, (_, index) => {
    const leadingOrder = index + 1
    const followingOrder = index + 2
    const detected = detectedPairs.find(pair => pair.leadingOrder === leadingOrder && pair.followingOrder === followingOrder)
    return { leadingOrder, followingOrder, seconds: detected?.isBunched ? detected.seconds : null }
  })
  return <div>{!!predictionGaps?.length && <div className="mb-3">
    <p className="text-xs text-muted-foreground">Reviewed prediction gaps</p>
    <ul className="mt-1 space-y-1 font-medium tabular-nums">{predictionGaps.map((gap, index) => {
      const outcomes = recommendation.suggestedOutcomes
      const preceding = outcomes?.find(item => item.busReference === buses[index])?.adjustmentSeconds ?? 0
      const following = outcomes?.find(item => item.busReference === buses[index + 1])?.adjustmentSeconds ?? 0
      return <li key={buses[index + 1]}>{buses[index]} to {buses[index + 1]} · {seconds(gap)}
        {(preceding > 0 || following > 0) && <span className="text-muted-foreground"> · {seconds(gap + following - preceding)} projected</span>}
      </li>
    })}</ul>
  </div>}<p className="text-xs text-muted-foreground">{predictionGaps?.length ? 'Historical confirmation gaps' : 'Observed bunching gaps'}</p>
    <ul className="mt-1 space-y-1 font-medium tabular-nums">{predictionGaps?.length && recommendation.confirmationGaps?.length
      ? recommendation.confirmationGaps.map((gap, index) => <li key={index}>Confirmation {index + 1} · {seconds(gap)}</li>)
      : pairs.map(pair => <li key={`${pair.leadingOrder}-${pair.followingOrder}`}>Buses #{pair.leadingOrder}–{pair.followingOrder}: {pair.seconds === null ? 'NA' : seconds(pair.seconds)}</li>)}</ul>
  </div>
}

function hasAdvisoryOnlyDiagnosis(request: PlanningDiagnosisRequest) {
  return request.policyOutcome === 'LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT'
}

function isSgtToday(isoString: string, currentMs = 0): boolean {
  const timestamp = Date.parse(isoString)
  if (!Number.isFinite(timestamp)) return false
  const date = new Date(timestamp)
  const now = currentMs ? new Date(currentMs) : new Date(timestamp)
  const sgtDateStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Singapore' }).format(date)
  const sgtNowStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Singapore' }).format(now)
  return sgtDateStr === sgtNowStr
}

type ControllerReplacement = { action: 'HOLD' | 'DWELL_ADJUSTMENT' | 'SKIP_STOP' | 'NONE'; adjustmentSeconds: number; noInstructionReason: string }
type BusReplacement = ControllerReplacement & { busReference: string }

function initialInstruction(recommendation: PendingRecommendation): ControllerReplacement {
  const action = recommendation.mitigationType === 'DWELL_ADJUSTMENT' ? 'DWELL_ADJUSTMENT' : 'HOLD'
  return { action, adjustmentSeconds: recommendation.adjustmentSeconds, noInstructionReason: '' }
}

function isBusCaptainAssigned(busReference: string, recommendation: PendingRecommendation): boolean {
  const outcome = recommendation.suggestedOutcomes?.find(item => item.busReference === busReference)
  if (!outcome) return true
  return !outcome.reason?.toLowerCase().includes('no captain')
}

function initialBusInstructions(recommendation: PendingRecommendation): BusReplacement[] {
  const initial = initialInstruction(recommendation)
  const buses = affectedBuses(recommendation)
  return buses.map(busReference => {
    const outcome = recommendation.suggestedOutcomes?.find(item => item.busReference === busReference)
    if (outcome) return { busReference, action: outcome.mitigationType ?? 'NONE',
      adjustmentSeconds: outcome.adjustmentSeconds || 30, noInstructionReason: outcome.reason ?? 'No action required by controller' }
    return buses.length === 1 ? { busReference, ...initial }
      : { busReference, action: 'NONE', adjustmentSeconds: 30, noInstructionReason: 'No action required by controller' }
  })
}

function RecommendationEvidenceList({ items }: { items: DiagnosisEvidenceDetail[] }) {
  if (!items.length) return null
  return <ul className="mt-2 list-disc space-y-3 pl-5 text-xs text-muted-foreground">
    {items.map(item => <li key={item.evidenceId}>
      {item.incidentType && <p className="font-medium text-foreground">{item.incidentType}</p>}
      {item.message && <p>{item.message}</p>}
      {item.distanceMetersFromSuppliedBusPosition !== null && <p>Approx. {Math.round(item.distanceMetersFromSuppliedBusPosition).toLocaleString('en-SG')} m from the supplied bus position near stop {item.monitoredStopCode}.</p>}
      {item.secondsBeforeFirstConfirmed !== null && (
        <p>
          {item.secondsBeforeFirstConfirmed > 0
            ? `Reported ${seconds(item.secondsBeforeFirstConfirmed)} before first confirmed bunching irregularity.`
            : 'Reported at or after first confirmed bunching irregularity.'}
        </p>
      )}
      <p>Incident evidence: {item.evidenceId}{item.busPositionEvidenceId ? ` · Bus-position evidence: ${item.busPositionEvidenceId}` : ''}</p>
    </li>)}
  </ul>
}

type EditingRecommendation = { identity: string; scopeIdentity: string; recommendation: PendingRecommendation; pending?: boolean }

function extractBusOrders(references: string[], alert?: BunchingAlert): number[] {
  const orders = new Set<number>()
  if (alert) {
    for (const pair of bunchedArrivalGaps(alert)) {
      orders.add(pair.leadingOrder)
      orders.add(pair.followingOrder)
    }
  }
  for (const ref of references) {
    const hashMatch = ref.match(/#(\d+)/)
    if (hashMatch) {
      const num = Number(hashMatch[1])
      if (Number.isInteger(num) && num > 0) orders.add(num)
      continue
    }
    const suffixMatch = ref.match(/-(\d+)$/)
    if (suffixMatch) {
      const num = Number(suffixMatch[1])
      if (Number.isInteger(num) && num > 0) orders.add(num)
    }
  }
  return [...orders].sort((a, b) => a - b)
}

export function PlanningCorridorMap({
  routes,
  positions = [],
  serviceId,
  directionId,
  controlStopCode,
  monitoredStopCode,
  corridorStopCodes = [],
  mitigationType,
  busReferences = [],
  alert,
  title,
  subtitle,
}: {
  routes: RouteDirection[]
  positions?: RoutePositions[]
  serviceId: string
  directionId: number | null
  controlStopCode?: string | null
  monitoredStopCode: string
  corridorStopCodes?: string[]
  mitigationType?: string | null
  busReferences?: string[]
  alert?: BunchingAlert
  title?: string
  subtitle?: React.ReactNode
}) {
  const [focusIncident, setFocusIncident] = useState(true)

  const corridorRoutes = useMemo(() => {
    const match = routes.filter(r => r.serviceId === serviceId && (directionId === null || r.directionId === directionId))
    return match.length ? match : routes.filter(r => r.serviceId === serviceId)
  }, [routes, serviceId, directionId])

  const targetStopCode = controlStopCode || monitoredStopCode
  const targetStop = useMemo(() => {
    return corridorRoutes.flatMap(r => r.stops).find(s => s.stopCode === targetStopCode)
  }, [corridorRoutes, targetStopCode])
  const targetStopName = targetStop?.description || (targetStopCode ? `Stop ${targetStopCode}` : 'Corridor stop')

  const affectedStopCodes = useMemo(() => {
    const set = new Set<string>()
    if (targetStopCode) set.add(targetStopCode)
    if (monitoredStopCode) set.add(monitoredStopCode)
    for (const code of corridorStopCodes) set.add(code)
    return set
  }, [targetStopCode, monitoredStopCode, corridorStopCodes])

  const mapBuses = useMemo(() => {
    return positions.flatMap(snapshot => snapshot.directions
      .filter(direction => corridorRoutes.some(route =>
        route.serviceId === snapshot.serviceId && route.directionId === direction.directionId
      ))
      .flatMap(direction => direction.buses.map(bus => ({
        serviceId: snapshot.serviceId,
        directionId: direction.directionId,
        routeLengthM: direction.routeLengthM,
        bus,
      }))))
  }, [positions, corridorRoutes])

  const busOrders = useMemo(() => extractBusOrders(busReferences, alert), [busReferences, alert])

  const selectedBusKeys = useMemo(() => {
    const keys = new Set<string>()
    for (const order of busOrders) {
      keys.add(`${serviceId}-${order}`)
    }
    if (alert) {
      for (const pair of bunchedArrivalGaps(alert)) {
        keys.add(`${serviceId}-${pair.leadingOrder}`)
        keys.add(`${serviceId}-${pair.followingOrder}`)
      }
    }
    return keys
  }, [busOrders, alert, serviceId])

  const selectedIncident = useMemo<PositionedIncident>(() => ({
    id: `planning-${serviceId}-${directionId ?? 1}-${targetStopCode}`,
    serviceId,
    directionId,
    routeKey: `${serviceId}:${directionId ?? 1}`,
    stopCodes: [...affectedStopCodes],
    stopNames: [targetStopName],
    firstDetectedAt: alert?.detectedAt ?? new Date().toISOString(),
    latestDetectedAt: alert?.detectedAt ?? new Date().toISOString(),
    gapSeconds: null,
    ageSeconds: 0,
    latestStopIndex: 0,
    historical: false,
    alerts: alert ? [alert] : [],
    stripLink: null,
  }), [serviceId, directionId, targetStopCode, affectedStopCodes, targetStopName, alert])

  if (!corridorRoutes.length) return null

  const defaultTitle = mitigationType ? 'Assigned control stop & corridor' : 'Affected corridor map'
  const defaultSubtitle = mitigationType ? (
    <span>
      Target control stop: <strong className="text-foreground">{targetStopName}</strong> ({targetStopCode}) · Assigned action: {mitigationType.replaceAll('_', ' ')}
    </span>
  ) : (
    <span>
      Monitored stop: <strong className="text-foreground">{targetStopName}</strong> ({targetStopCode})
      {affectedStopCodes.size > 1 ? ` · ${affectedStopCodes.size} corridor stops affected` : ''}
    </span>
  )

  return (
    <div className="space-y-1">
      <BunchingMap
        routes={corridorRoutes}
        affectedStopCodes={affectedStopCodes}
        buses={mapBuses}
        selectedIncident={focusIncident ? selectedIncident : null}
        incidents={[selectedIncident]}
        onSelectIncident={id => setFocusIncident(id !== null)}
        selectedBusKeys={selectedBusKeys}
        title={title ?? defaultTitle}
        subtitle={subtitle ?? defaultSubtitle}
        mapHeightClassName="h-60 sm:h-64"
      />
    </div>
  )
}

export type CorridorDiagnosisGroup = {
  id: string
  serviceId: string
  directionId: number | null
  stopCodes: string[]
  stopNames: string[]
  corridorIncident: RouteIncident | null
  requests: PlanningDiagnosisRequest[]
  representativeRequest: PlanningDiagnosisRequest | null
  firstDetectedAt: string
  latestDetectedAt: string
  gapSeconds: number | null
  isLive: boolean
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
}

function buildCorridorGroups(
  requests: PlanningDiagnosisRequest[],
  liveCorridors: RouteIncident[],
  routes: RouteDirection[]
): CorridorDiagnosisGroup[] {
  const matchedRequestIds = new Set<string>()
  const groups: CorridorDiagnosisGroup[] = []

  // 1. Live corridor incidents from Disruptions feed
  for (const corridor of liveCorridors) {
    const corridorEventIds = new Set(corridor.alerts.map(a => a.eventId))
    const corridorStopCodes = new Set(corridor.stopCodes)
    const matchingRequests = requests.filter(req => {
      if (matchedRequestIds.has(req.requestId)) return false
      if (corridorEventIds.has(req.eventId)) return true
      if (req.serviceId === corridor.serviceId &&
          (corridor.directionId === null || req.directionId === null || req.directionId === corridor.directionId) &&
          req.stopCode && corridorStopCodes.has(req.stopCode)) {
        return true
      }
      return false
    })

    matchingRequests.forEach(r => matchedRequestIds.add(r.requestId))

    // A detection without a durable Planning request has no actionable identity.
    // Keep it on Disruptions rather than selecting an empty planning detail.
    if (!matchingRequests.length) continue

    const isCompleted = matchingRequests.length > 0 && matchingRequests.every(r => r.status === 'COMPLETED')
    const isProcessing = matchingRequests.some(r => r.status === 'PROCESSING')
    const isFailed = matchingRequests.some(r => r.status.startsWith('FAILED'))
    const status: CorridorDiagnosisGroup['status'] = isCompleted ? 'COMPLETED' : isProcessing ? 'PROCESSING' : isFailed ? 'FAILED' : 'PENDING'

    groups.push({
      id: corridor.id,
      serviceId: corridor.serviceId,
      directionId: corridor.directionId,
      stopCodes: corridor.stopCodes,
      stopNames: corridor.stopNames,
      corridorIncident: corridor,
      requests: matchingRequests,
      representativeRequest: matchingRequests[0] ?? null,
      firstDetectedAt: corridor.firstDetectedAt,
      latestDetectedAt: corridor.latestDetectedAt,
      gapSeconds: corridor.gapSeconds,
      isLive: !corridor.historical,
      status,
    })
  }

  // 2. Remaining requests (historical or non-live sweeps)
  const remaining = requests.filter(r => !matchedRequestIds.has(r.requestId))
  const timeBuckets = new Map<string, PlanningDiagnosisRequest[]>()
  for (const req of remaining) {
    const service = req.serviceId ?? 'unknown'
    const direction = req.directionId ?? 1
    const timeMs = Date.parse(req.createdAt)
    const bucketTime = Number.isFinite(timeMs) ? Math.floor(timeMs / (5 * 60 * 1000)) : 0
    const key = `${service}:${direction}:${bucketTime}`
    const bucket = timeBuckets.get(key) ?? []
    bucket.push(req)
    timeBuckets.set(key, bucket)
  }

  for (const bucket of timeBuckets.values()) {
    const first = bucket[0]
    const service = first.serviceId ?? 'unknown'
    const direction = first.directionId
    const stopCodes = [...new Set(bucket.flatMap(r => r.stopCode ? [r.stopCode] : []))]
    const route = routes.find(r => r.serviceId === service && (direction === null || r.directionId === direction))
    const stopNames = stopCodes.map(code => route?.stops.find((s: RouteStop) => s.stopCode === code)?.description || `Stop ${code}`)

    const isCompleted = bucket.every(r => r.status === 'COMPLETED')
    const isProcessing = bucket.some(r => r.status === 'PROCESSING')
    const isFailed = bucket.some(r => r.status.startsWith('FAILED'))
    const status: CorridorDiagnosisGroup['status'] = isCompleted ? 'COMPLETED' : isProcessing ? 'PROCESSING' : isFailed ? 'FAILED' : 'PENDING'
    const id = bucket.length === 1 ? bucket[0].requestId : bucket.map(r => r.eventId).sort().join(':') || bucket[0].requestId

    groups.push({
      id,
      serviceId: service,
      directionId: direction,
      stopCodes,
      stopNames,
      corridorIncident: null,
      requests: bucket,
      representativeRequest: first,
      firstDetectedAt: first.createdAt,
      latestDetectedAt: bucket.at(-1)!.createdAt,
      gapSeconds: null,
      isLive: false,
      status,
    })
  }

  return groups.sort((a, b) => {
    if (a.isLive !== b.isLive) return a.isLive ? -1 : 1
    return Date.parse(b.latestDetectedAt) - Date.parse(a.latestDetectedAt)
  })
}

export function ControllerPlanning({
  recommendations,
  diagnosisRequests,
  loading,
  modeError,
  mode,
  demoRunId,
  token,
  detectedAlerts,
  positions = [],
  routes = [],
  pollIntervalSeconds = 60,
  nowMs = 0,
  gapToleranceSeconds = 30,
  onPendingChange,
  onRefresh,
  onSaved,
}: {
  recommendations: PagedCollection<PendingRecommendation, Planning['recommendations']>
  diagnosisRequests: PagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>
  loading: boolean
  modeError: string
  mode: 'LIVE' | 'DEMO'
  demoRunId: string | null
  token: string
  detectedAlerts: BunchingAlert[]
  positions?: RoutePositions[]
  routes?: RouteDirection[]
  pollIntervalSeconds?: number
  nowMs?: number
  gapToleranceSeconds?: number
  onRefresh: () => void
  onSaved: () => void
  onPendingChange: (pending: boolean) => void
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedId = searchParams.get('recommendation')
  const requestedRequestId = searchParams.get('request')
  const requestedEventId = searchParams.get('event')
  const requestedStop = searchParams.get('stop')
  const requestedService = searchParams.get('service')
  const requestedIncident = searchParams.get('incident')
  const statusParam = searchParams.get('status')
  const timeParam = searchParams.get('time')
  const explicitScope: 'active' | 'today' | 'all' | null = useMemo(() => {
    if (timeParam === 'all' || statusParam === 'all') return 'all'
    if (timeParam === 'today') return 'today'
    if (timeParam === 'live' || timeParam === 'active' || statusParam === 'active') return 'active'
    return null
  }, [timeParam, statusParam])

  const [filterInput, setFilterInput] = useState<string | null>(null)
  const filter = filterInput ?? searchParams.get('service') ?? ''

  const [message, setMessage] = useState('')
  const [editingRecommendation, setEditingRecommendation] = useState<EditingRecommendation | null>(null)
  const [triggeringRequestId, setTriggeringRequestId] = useState<string | null>(null)
  const [suggestingRequestId, setSuggestingRequestId] = useState<string | null>(null)
  const [selectedCorridorId, setSelectedCorridorId] = useState<string | null>(null)

  useEffect(() => {
    onPendingChange(!!editingRecommendation?.pending || triggeringRequestId !== null || suggestingRequestId !== null)
  }, [editingRecommendation?.pending, onPendingChange, suggestingRequestId, triggeringRequestId])
  useEffect(() => () => onPendingChange(false), [onPendingChange])

  const recommendationPage = recommendations.latestPage
  const recommendationScopeMatches = !modeError && recommendationPage?.mode === mode &&
    (recommendationPage.demoRunId ?? null) === demoRunId
  const recommendationError = modeError ? 'Simulation state is unavailable.' : recommendations.error !== null && !recommendations.items.length
    ? 'Unable to load recommendations.' : recommendationPage && !recommendationScopeMatches && !loading
      ? 'Data selection changed. Refresh before making a decision.' : ''
  const recommendationItems = useMemo(
    () => (!recommendationError && recommendationScopeMatches ? recommendations.items : []),
    [recommendationError, recommendationScopeMatches, recommendations.items]
  )

  const diagnosisPage = diagnosisRequests.latestPage
  const diagnosisScopeMatches = !modeError && diagnosisPage?.mode === mode &&
    (diagnosisPage.demoRunId ?? null) === demoRunId
  const diagnosisError = modeError ? 'Simulation state is unavailable.' : diagnosisRequests.error !== null && !diagnosisRequests.items.length
    ? 'Unable to load bunching requests.' : diagnosisPage && !diagnosisScopeMatches && !loading
      ? 'Data selection changed. Refresh before generating a diagnosis.' : ''

  const incidentEventIds = useMemo(() => {
    const ids = new Set<string>()
    if (requestedEventId) ids.add(requestedEventId)
    if (requestedIncident) requestedIncident.split(':').filter(Boolean).forEach(id => ids.add(id))
    return ids
  }, [requestedEventId, requestedIncident])

  // Live corridors computed with identical logic to Disruptions
  const liveCorridorIncidents = useMemo(() => {
    if (!detectedAlerts.length || !routes.length) return []
    return groupRouteIncidents(detectedAlerts, routes, nowMs, pollIntervalSeconds, gapToleranceSeconds, mode === 'DEMO')
  }, [detectedAlerts, routes, nowMs, pollIntervalSeconds, gapToleranceSeconds, mode])

  // Build corridor groups from diagnosis requests + live corridors
  const allCorridorGroups = useMemo(() => {
    if (diagnosisError || !diagnosisScopeMatches) return []
    return buildCorridorGroups(diagnosisRequests.items, liveCorridorIncidents, routes)
  }, [diagnosisError, diagnosisScopeMatches, diagnosisRequests.items, liveCorridorIncidents, routes])

  // Locate matched corridor from deep-link parameters
  const matchedCorridor = useMemo(() => {
    if (!allCorridorGroups.length) return null
    if (requestedIncident) {
      const incidentIds = new Set(requestedIncident.split(':').filter(Boolean))
      const match = allCorridorGroups.find(g =>
        g.id === requestedIncident ||
        g.requests.some(r => incidentIds.has(r.eventId)) ||
        g.corridorIncident?.alerts.some(a => incidentIds.has(a.eventId))
      )
      if (match) return match
    }
    if (requestedRequestId) {
      const match = allCorridorGroups.find(g => g.requests.some(r => r.requestId === requestedRequestId))
      if (match) return match
    }
    if (requestedEventId) {
      const match = allCorridorGroups.find(g =>
        g.requests.some(r => r.eventId === requestedEventId) ||
        g.corridorIncident?.alerts.some(a => a.eventId === requestedEventId)
      )
      if (match) return match
    }
    if (requestedService && requestedStop) {
      const match = allCorridorGroups.find(g =>
        g.serviceId === requestedService && g.stopCodes.includes(requestedStop)
      )
      if (match) return match
    }
    return null
  }, [allCorridorGroups, requestedIncident, requestedRequestId, requestedEventId, requestedService, requestedStop])

  const scopeFilter: 'active' | 'today' | 'all' = useMemo(() => {
    if (explicitScope) return explicitScope
    // Simulation requests already belong to the selected run. Keep unfinished
    // work visible even when its replay timestamps predate today.
    if (mode === 'DEMO') return 'all'
    // Smart default on deep link: if user linked to an incident that has already cleared on road, default to 'today'
    if (matchedCorridor && !matchedCorridor.isLive) return 'today'
    return 'active'
  }, [explicitScope, matchedCorridor, mode])

  // Filter corridors by scope
  const filteredCorridors = useMemo(() => {
    return allCorridorGroups.filter(group => {
      if (scopeFilter === 'all') return true
      if (scopeFilter === 'active') return group.isLive
      if (scopeFilter === 'today') return isSgtToday(group.latestDetectedAt, nowMs)
      return true
    })
  }, [allCorridorGroups, scopeFilter, nowMs])

  const serviceFilteredCorridors = useMemo(() => {
    return filteredCorridors.filter(g => !filter.trim() || g.serviceId.includes(filter.trim()))
  }, [filteredCorridors, filter])

  const visibleCorridors = useMemo(() => {
    if (matchedCorridor) {
      const scopeMatches =
        scopeFilter === 'all' ||
        (scopeFilter === 'active' && matchedCorridor.isLive) ||
        (scopeFilter === 'today' && isSgtToday(matchedCorridor.latestDetectedAt, nowMs))
      if (scopeMatches && !serviceFilteredCorridors.some(g => g.id === matchedCorridor.id)) {
        return [matchedCorridor, ...serviceFilteredCorridors]
      }
    }
    return serviceFilteredCorridors
  }, [matchedCorridor, scopeFilter, serviceFilteredCorridors, nowMs])

  // Accurate counter metrics
  const activeCount = useMemo(() => {
    return allCorridorGroups.filter(g => g.isLive && (!filter.trim() || g.serviceId.includes(filter.trim()))).length
  }, [allCorridorGroups, filter])

  const todayCount = useMemo(() => {
    return allCorridorGroups.filter(g => isSgtToday(g.latestDetectedAt, nowMs) && (!filter.trim() || g.serviceId.includes(filter.trim()))).length
  }, [allCorridorGroups, filter, nowMs])

  const allCount = useMemo(() => {
    return allCorridorGroups.filter(g => !filter.trim() || g.serviceId.includes(filter.trim())).length
  }, [allCorridorGroups, filter])

  const outdatedCount = useMemo(() => {
    const candidateGroups = allCorridorGroups.filter(g => !filter.trim() || g.serviceId.includes(filter.trim()))
    if (scopeFilter === 'active') return candidateGroups.filter(g => !g.isLive).length
    if (scopeFilter === 'today') return candidateGroups.filter(g => !isSgtToday(g.latestDetectedAt, nowMs)).length
    return 0
  }, [allCorridorGroups, filter, scopeFilter, nowMs])

  // Recommendations matching and filtering
  const matchedRecommendation = useMemo(() => {
    if (recommendationError || !recommendationScopeMatches) return null
    if (requestedId) {
      return recommendationItems.find(item => item.recommendationId === requestedId) ?? null
    }
    if (incidentEventIds.size > 0) {
      return recommendationItems.find(item => incidentEventIds.has(item.recommendationId)) ?? null
    }
    if (requestedService && requestedStop) {
      return recommendationItems.find(item => item.serviceId === requestedService && item.monitoredStopCode === requestedStop) ?? null
    }
    return null
  }, [recommendationError, recommendationScopeMatches, requestedId, incidentEventIds, requestedService, requestedStop, recommendationItems])

  const visibleRecommendations = useMemo(() => {
    if (recommendationError || !recommendationScopeMatches) return []
    return recommendationItems.filter(item => {
      if (scopeFilter === 'all') return true
      if (scopeFilter === 'active') return detectedAlerts.some(a => a.serviceId === item.serviceId && a.stopCode === item.monitoredStopCode)
      if (scopeFilter === 'today') return isSgtToday(item.detectedAt, nowMs)
      return true
    })
  }, [recommendationError, recommendationScopeMatches, recommendationItems, scopeFilter, detectedAlerts, nowMs])

  const visible = visibleRecommendations.filter(item => item.serviceId.includes(filter.trim()))
  const visibleWithRetained = useMemo(() => {
    if (matchedRecommendation) {
      const scopeMatches =
        scopeFilter === 'all' ||
        (scopeFilter === 'active' && detectedAlerts.some(a => a.serviceId === matchedRecommendation.serviceId && a.stopCode === matchedRecommendation.monitoredStopCode)) ||
        (scopeFilter === 'today' && isSgtToday(matchedRecommendation.detectedAt, nowMs))
      if (scopeMatches && !visible.some(item => item.recommendationId === matchedRecommendation.recommendationId)) {
        return [matchedRecommendation, ...visible]
      }
    }
    return visible
  }, [matchedRecommendation, scopeFilter, detectedAlerts, nowMs, visible])

  const scopeIdentity = `${mode}:${demoRunId ?? 'none'}`
  const { error: recommendationLoadError, hasMore: recommendationHasMore, isLoading: recommendationIsLoading,
    isLoadingMore: recommendationIsLoadingMore, loadNext: loadNextRecommendation } = recommendations
  const editingInScope = editingRecommendation?.scopeIdentity === scopeIdentity ? editingRecommendation : null
  const editingItem = editingInScope && recommendationItems.find(item => item.recommendationId === editingInScope.recommendation.recommendationId)
  const editingItemIsLoaded = !!editingItem
  const editNeedsRevalidation = !!editingInScope && !editingItem && (!!modeError || recommendations.isLoading || recommendations.isLoadingMore ||
    recommendationHasMore || recommendationLoadError !== null)

  const isRequestSelection = Boolean(
    requestedRequestId ||
    (!requestedId && matchedCorridor) ||
    (!requestedId && !matchedRecommendation && visibleCorridors.length > 0)
  )

  const selected = editingInScope && !editingItem && (editNeedsRevalidation || editingRecommendation?.pending) ? editingInScope.recommendation
    : (isRequestSelection ? null : (matchedRecommendation ?? visibleWithRetained[0] ?? recommendationItems[0] ?? null))
  const selectedIdentity = selected ? `${scopeIdentity}:${selected.recommendationId}` : null

  const selectedCorridor = useMemo(() => {
    if (!isRequestSelection) return null
    if (selectedCorridorId) {
      return visibleCorridors.find(g => g.id === selectedCorridorId) ?? null
    }
    return matchedCorridor ?? visibleCorridors[0] ?? null
  }, [isRequestSelection, selectedCorridorId, visibleCorridors, matchedCorridor])

  const selectedRequest = useMemo(() => {
    if (!selectedCorridor) return null
    if (requestedRequestId) {
      return selectedCorridor.requests.find(r => r.requestId === requestedRequestId) ?? selectedCorridor.representativeRequest
    }
    if (requestedStop) {
      return selectedCorridor.requests.find(r => r.stopCode === requestedStop) ?? selectedCorridor.representativeRequest
    }
    return selectedCorridor.representativeRequest ?? selectedCorridor.requests[0] ?? null
  }, [selectedCorridor, requestedRequestId, requestedStop])

  const editorUnavailable = !!editingInScope && (!!modeError || recommendationLoadError !== null)
  const editorStale = editingRecommendation !== null && (editingRecommendation.scopeIdentity !== scopeIdentity ||
    !editNeedsRevalidation && selectedIdentity !== editingRecommendation.identity)
  const detailRecommendation = selected
  const detailIdentity = detailRecommendation ? `${scopeIdentity}:${detailRecommendation.recommendationId}` : null
  const selectionIsCurrent = (!recommendations.isLoading || recommendations.items.length > 0) && recommendationLoadError === null && !modeError && !!selected &&
    (recommendationItems.some(item => item.recommendationId === selected.recommendationId) ||
      (editingInScope?.recommendation.recommendationId === selected.recommendationId))

  useEffect(() => {
    if (!editingInScope || editingItemIsLoaded || modeError || recommendationIsLoading || recommendationIsLoadingMore ||
      recommendationLoadError !== null || !recommendationHasMore) return
    loadNextRecommendation()
  }, [editingInScope, editingItemIsLoaded, loadNextRecommendation, modeError, recommendationHasMore, recommendationIsLoading,
    recommendationIsLoadingMore, recommendationLoadError])

  useEffect(() => {
    if (isRequestSelection) {
      if (selectedRequest && selectedRequest.requestId !== requestedRequestId && !requestedEventId && !requestedIncident) {
        setSearchParams(prev => {
          const next = new URLSearchParams(prev)
          next.set('request', selectedRequest.requestId)
          next.delete('recommendation')
          return next
        }, { replace: true })
      }
    } else if (selected && selected.recommendationId !== requestedId && !requestedEventId && !requestedIncident) {
      setSearchParams(prev => {
        const next = new URLSearchParams(prev)
        next.set('recommendation', selected.recommendationId)
        next.delete('request')
        return next
      }, { replace: true })
    }
  }, [isRequestSelection, requestedId, requestedRequestId, requestedEventId, requestedIncident, selected, selectedRequest, setSearchParams])

  function select(recommendationId: string) {
    setEditingRecommendation(null)
    setSelectedCorridorId(null)
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.set('recommendation', recommendationId)
      next.delete('request')
      next.delete('event')
      next.delete('incident')
      return next
    })
  }

  function selectCorridor(group: CorridorDiagnosisGroup) {
    setEditingRecommendation(null)
    setSelectedCorridorId(group.id)
    const rep = group.representativeRequest ?? group.requests[0]
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (rep) next.set('request', rep.requestId)
      next.set('incident', group.id)
      next.set('service', group.serviceId)
      if (group.stopCodes[0]) next.set('stop', group.stopCodes[0])
      next.delete('recommendation')
      next.delete('event')
      return next
    })
  }

  function handleScopeFilter(newScope: 'active' | 'today' | 'all') {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      if (newScope === 'active') {
        next.delete('time')
        next.delete('status')
      } else {
        next.set('time', newScope)
        next.delete('status')
      }
      return next
    })
  }

  async function generateDiagnosis(request: PlanningDiagnosisRequest) {
    if (triggeringRequestId) return
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.set('request', request.requestId)
      return next
    }, { replace: true })
    setTriggeringRequestId(request.requestId)
    setMessage('')
    const controller = new AbortController()
    try {
      const result = await triggerDiagnosis({ requestId: request.requestId }, token, controller.signal)
      if (!controller.signal.aborted) {
        setMessage(result?.status === 'COMPLETED' && hasAdvisoryOnlyDiagnosis(request)
          ? 'Diagnosis generated from live observations. AI mitigation is unavailable because validated operational context is not available.'
          : result?.status === 'COMPLETED'
          ? 'Diagnosis generated. The recommendation is ready for review.'
          : 'Diagnosis generation has started. Refresh to see its latest status.')
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else setMessage(error instanceof ApiError && error.status === 403
        ? 'You do not have permission to generate a diagnosis.'
        : 'Unable to generate a diagnosis. Try again.')
    } finally {
      setTriggeringRequestId(null)
      onRefresh()
    }
  }

  return <section aria-label="Mitigation recommendations" className="space-y-4">
    <DataMessage error={recommendationError || diagnosisError} loading={loading || recommendations.isLoading && !recommendationPage || diagnosisRequests.isLoading && !diagnosisPage} empty={false}>No requests are awaiting a decision.</DataMessage>
    {message && <p role="status" className="text-sm">{message}</p>}
    {editorStale && <p role="status" className="text-sm">This recommendation is no longer current. The editor was closed; review the latest data before making a decision.</p>}
    {editorUnavailable && !editorStale && <p role="status" className="text-sm">Unable to confirm this recommendation is current. Your draft is preserved; retry the check before deciding.</p>}
    <div className="grid items-start gap-4 lg:grid-cols-[minmax(16rem,0.36fr)_minmax(0,1fr)]">
      <Card className="gap-0 py-0">
        <div className="space-y-3 border-b p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 id="awaiting-title" className="text-sm font-medium">Awaiting decision</h2>
          </div>
          <Label htmlFor="service-filter" className="sr-only">Filter by bus service number</Label>
          <Input id="service-filter" inputMode="numeric" placeholder="Filter bus no. e.g. 190" value={filter} onChange={event => setFilterInput(event.target.value)} />
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
            <div className="flex items-center gap-1 rounded-md border p-0.5 text-xs">
              <Button
                type="button"
                variant={scopeFilter === 'active' ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={() => handleScopeFilter('active')}
                aria-pressed={scopeFilter === 'active'}
              >
                Active ({activeCount})
              </Button>
              <Button
                type="button"
                variant={scopeFilter === 'today' ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={() => handleScopeFilter('today')}
                aria-pressed={scopeFilter === 'today'}
              >
                Today ({todayCount})
              </Button>
              <Button
                type="button"
                variant={scopeFilter === 'all' ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={() => handleScopeFilter('all')}
                aria-pressed={scopeFilter === 'all'}
              >
                All time ({allCount})
              </Button>
            </div>
            {outdatedCount > 0 && scopeFilter !== 'all' ? (
              <span className="text-[11px] text-muted-foreground shrink-0">
                {outdatedCount} past hidden
              </span>
            ) : null}
          </div>
          {(requestedIncident || requestedEventId) && (
            <p className="text-xs text-muted-foreground">
              Linked from corridor incident in Disruptions
            </p>
          )}
        </div>
        <ScrollArea role="listbox" aria-label="Recommendations awaiting a decision" tabIndex={0} className="max-h-[28rem]"
          viewportProps={{ onScroll: event => { recommendations.onScroll(event); diagnosisRequests.onScroll(event) } }}><div className="divide-y">
          {visibleCorridors.map(group => <button type="button" role="option" aria-selected={selectedCorridor?.id === group.id} key={group.id} onClick={() => selectCorridor(group)}
            className="min-h-28 w-full space-y-2 px-4 py-4 text-left outline-none transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring aria-selected:bg-muted">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="font-medium">Service {group.serviceId} · Direction {group.directionId ?? 1}</span>
                {group.isLive ? (
                  <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-[11px] font-semibold text-emerald-800 dark:text-emerald-400">
                    Live on route
                  </span>
                ) : (
                  <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
                    Cleared on route
                  </span>
                )}
              </div>
              <span className="shrink-0 text-xs text-muted-foreground">{timestamp(group.latestDetectedAt)}</span>
            </div>
            <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              <span className="font-semibold text-foreground">Corridor: {group.stopCodes.length} {group.stopCodes.length === 1 ? 'stop' : 'stops'}</span>
              {group.gapSeconds !== null && <span>· Gap {seconds(group.gapSeconds)}</span>}
            </div>
            {group.stopNames.length > 0 && (
              <p className="line-clamp-1 text-xs text-muted-foreground">
                {group.stopNames.slice(0, 3).join(' → ')}
                {group.stopNames.length > 3 ? ` → +${group.stopNames.length - 3} more` : ''}
              </p>
            )}
            <p className="text-sm">{group.status === 'COMPLETED'
              ? 'Diagnosis generated; keeping this corridor open for review.'
              : group.status === 'PROCESSING' ? 'Diagnosis is being generated.' : group.status === 'FAILED' ? 'Diagnosis needs to be retried.' : 'Diagnosis has not been generated yet.'}</p>
          </button>)}
          {visible.map(item => <button type="button" role="option" aria-selected={selected?.recommendationId === item.recommendationId} key={item.recommendationId} onClick={() => select(item.recommendationId)}
            className="min-h-28 w-full space-y-2 px-4 py-4 text-left outline-none transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring aria-selected:bg-muted">
            <div className="flex items-start justify-between gap-3"><span className="font-medium">Service {item.serviceId} · Direction {item.directionId}</span><span className="shrink-0 text-xs text-muted-foreground">{timestamp(item.detectedAt)}</span></div>
            <p className="text-xs text-muted-foreground">Stop {item.monitoredStopCode}</p>
            <p className="text-sm">{item.explanation.likelyCause}</p>
            <p className="text-xs text-muted-foreground">Affected buses · {affectedBuses(item).join(', ')}</p>
            <BunchedPairs alert={detectedAlerts.find(alert => alert.serviceId === item.serviceId && alert.directionId === item.directionId &&
              alert.stopCode === item.monitoredStopCode && alert.detectedAt === item.detectedAt)} />
            <p className="text-xs text-muted-foreground">{item.suggestedOutcomes?.length ? `Suggested outcomes for ${item.suggestedOutcomes.length} buses`
              : affectedBuses(item).length > 1 ? 'Refresh for current suggestions'
                : `${item.mitigationType.replaceAll('_', ' ')} · Target: ${item.targetBusReference}`}</p>
          </button>)}
          {!visibleWithRetained.length && !visibleCorridors.length && (
            <div className="space-y-2 p-6 text-center text-sm text-muted-foreground">
              <p>{filter.trim() ? 'No matching bus service.' : scopeFilter === 'active' ? 'No active bunching detected on routes right now.' : 'No bunching requests in this time window.'}</p>
              {todayCount > 0 && scopeFilter === 'active' && (
                <Button variant="outline" size="sm" onClick={() => handleScopeFilter('today')}>
                  View today's incidents ({todayCount})
                </Button>
              )}
              {outdatedCount > 0 && scopeFilter !== 'all' && scopeFilter !== 'active' && (
                <Button variant="outline" size="sm" onClick={() => handleScopeFilter('all')}>
                  View {outdatedCount} past corridor{outdatedCount === 1 ? '' : 's'}
                </Button>
              )}
            </div>
          )}
        </div></ScrollArea>
        <div className="space-y-2 p-3"><PagedCollectionControls label="bunching requests" hasItems={!!diagnosisRequests.items.length} hasMore={diagnosisRequests.hasMore}
          isLoadingMore={diagnosisRequests.isLoadingMore} error={diagnosisRequests.error} refreshError={diagnosisRequests.refreshError} reload={diagnosisRequests.reload} loadNext={diagnosisRequests.loadNext} />
          <PagedCollectionControls label="recommendations" hasItems={!!recommendations.items.length} hasMore={recommendations.hasMore}
            isLoadingMore={recommendations.isLoadingMore} error={recommendations.error} refreshError={recommendations.refreshError} reload={recommendations.reload} loadNext={recommendations.loadNext} /></div>
      </Card>

      {!selectedRequest && detailRecommendation && detailIdentity && <RecommendationDetail key={detailIdentity} selected={detailRecommendation} identity={detailIdentity}
        scopeIdentity={scopeIdentity} token={token} detectedAlerts={detectedAlerts} routes={routes} positions={positions} isCurrent={selectionIsCurrent} validationFailed={editorUnavailable}
        retryCurrentCheck={modeError ? onRefresh : recommendations.reload}
        onSaved={onSaved} onMessage={setMessage} onEditorChange={setEditingRecommendation}
        onRefreshed={item => {
          select(item.recommendationId)
          setEditingRecommendation({ identity: `${scopeIdentity}:${item.recommendationId}`, scopeIdentity, recommendation: item })
          onSaved()
        }} />}
      {selectedRequest && <DiagnosisRequestDetail request={selectedRequest}
        corridor={selectedCorridor}
        routes={routes} positions={positions} detectedAlerts={detectedAlerts}
        detectedBusReferences={detectedBusReferences(selectedRequest, detectedAlerts, selectedCorridor)}
        generating={triggeringRequestId === selectedRequest.requestId} suggesting={suggestingRequestId === selectedRequest.requestId}
        token={token} onSuggestionPending={pending => setSuggestingRequestId(pending ? selectedRequest.requestId : null)}
        onSaved={onSaved} onGenerate={() => { void generateDiagnosis(selectedRequest) }} />}
      {!selectedRequest && !detailRecommendation && selectedCorridor && (
        <Card><CardContent className="space-y-4 p-6">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold">Service {selectedCorridor.serviceId} · Direction {selectedCorridor.directionId ?? 1}</h2>
            {selectedCorridor.isLive ? (
              <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-semibold text-emerald-800 dark:text-emerald-400">
                Live on route
              </span>
            ) : (
              <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                Cleared on route
              </span>
            )}
          </div>
          <p className="text-sm text-muted-foreground">{selectedCorridor.isLive ? `Corridor with ${selectedCorridor.stopCodes.length} affected stops currently active on route.` : `Corridor with ${selectedCorridor.stopCodes.length} affected stops has cleared on route.`}</p>
          <div className="rounded-lg border bg-muted/20 p-3 text-xs text-muted-foreground">
            {selectedCorridor.stopNames.join(' → ')}
          </div>
          <p className="text-sm">Awaiting diagnosis request dispatch from disruption outbox.</p>
        </CardContent></Card>
      )}
    </div>
  </section>
}

function detectedBusReferences(request: PlanningDiagnosisRequest | null, alerts: BunchingAlert[], corridor?: CorridorDiagnosisGroup | null) {
  const corridorAlerts = corridor?.corridorIncident?.alerts ?? alerts
  const simulationReferences = corridorAlerts.flatMap(alert => {
    const match = !request || alert.eventId === request.eventId ||
      (alert.serviceId === request.serviceId && alert.stopCode === request.stopCode)
    if (!match) return []
    return [alert.leadingVehicle, alert.followingVehicle].flatMap(vehicle => {
      const reference = vehicle?.registrationNumber || vehicle?.busId
      return reference ? [reference] : []
    })
  })
  if (simulationReferences.length) {
    return simulationReferences.filter((reference, index, values) => values.indexOf(reference) === index)
  }
  if (request && request.affectedBusReferences.length) {
    return request.affectedBusReferences.filter((reference, index, values) => values.indexOf(reference) === index)
  }
  const alert = corridorAlerts.find(item => request && (item.eventId === request.eventId ||
    (item.serviceId === request.serviceId && item.stopCode === request.stopCode))) ?? alerts[0]
  if (alert) {
    const pairs = bunchedArrivalGaps(alert)
    if (pairs.length) {
      const orders = [...new Set(pairs.flatMap(p => [p.leadingOrder, p.followingOrder]))].sort((a, b) => a - b)
      return orders.map((order, idx) => `Bus #${order} (${idx === 0 ? 'Leading' : 'Following'})`)
    }
    const arrivals = bunchingArrivals(alert)
    const slots: string[] = []
    arrivals.forEach((arr, idx) => {
      if (arr && arr !== 'Unavailable' && arr !== 'No prediction') {
        slots.push(`Bus #${idx + 1} (${idx === 0 ? 'Leading' : 'Following'})`)
      }
    })
    if (slots.length) return slots
  }
  return []
}

function DiagnosisRequestDetail({ request, corridor, routes, positions, detectedAlerts, detectedBusReferences, generating, suggesting, token, onSuggestionPending, onSaved, onGenerate }: {
  request: PlanningDiagnosisRequest; corridor?: CorridorDiagnosisGroup | null; routes: RouteDirection[]; positions: RoutePositions[]; detectedAlerts: BunchingAlert[]; detectedBusReferences: string[]; generating: boolean; suggesting: boolean; token: string
  onSuggestionPending: (pending: boolean) => void; onSaved: () => void; onGenerate: () => void
}) {
  const retry = request.status.startsWith('FAILED')
  const processing = request.status === 'PROCESSING'
  const completed = request.status === 'COMPLETED'
  const advisoryOnly = hasAdvisoryOnlyDiagnosis(request)
  const [editorOpen, setEditorOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [instructions, setInstructions] = useState<ControllerBusInstruction[]>(() => detectedBusReferences.map(busReference => ({ busReference, instruction: '' })))
  const [rationale, setRationale] = useState('')
  const firstInstruction = useRef<HTMLTextAreaElement>(null)

  function openEditor(open: boolean) {
    if (!open) { if (!suggesting) setEditorOpen(false); return }
    setInstructions(detectedBusReferences.map(busReference => ({ busReference, instruction: '' })))
    setRationale(''); setMessage(''); setEditorOpen(true)
  }

  async function saveSuggestion() {
    const prepared = instructions.map(item => ({ busReference: item.busReference.trim(), instruction: item.instruction.trim() }))
    if (!prepared.length || prepared.some(item => !item.busReference || !item.instruction) || !rationale.trim()) {
      setMessage('Enter an action for every detected bus and a controller rationale.')
      return
    }
    onSuggestionPending(true); setMessage('')
    const controller = new AbortController()
    try {
      await apiRequest<EarlyControllerSuggestion>(`/api/v1/planning/diagnoses/${encodeURIComponent(request.requestId)}/controller-suggestion`, token, controller.signal, {
        method: 'POST', body: { instructions: prepared, controllerRationale: rationale.trim() },
      })
      setEditorOpen(false)
      onSaved()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else setMessage(error instanceof ApiError && error.status === 409
        ? 'A different controller suggestion has already been recorded for this incident.'
        : 'Unable to save the controller suggestion. Check the actions and try again.')
    } finally { onSuggestionPending(false) }
  }

  return <Card><CardContent className="space-y-5 px-5">
    <div className="flex flex-wrap items-start justify-between gap-4 border-b pb-4">
      <div>
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Service {request.serviceId ?? 'unknown'} · Direction {request.directionId ?? 'unknown'}</h2>
          {corridor?.isLive ? (
            <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-semibold text-emerald-800 dark:text-emerald-400">
              Live on route
            </span>
          ) : (
            <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
              Cleared on route
            </span>
          )}
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          {corridor && corridor.stopCodes.length > 1
            ? `Lead stop ${request.stopCode ?? 'unknown'} (${corridor.stopCodes.length}-stop corridor) · detected ${timestamp(request.createdAt)}`
            : `Stop ${request.stopCode ?? 'unknown'} · detected ${timestamp(request.createdAt)}`}
        </p>
      </div>
      <span className="text-xs text-muted-foreground">{request.requestId}</span>
    </div>

    {corridor && corridor.stopCodes.length > 1 && (
      <div className="rounded-lg border bg-muted/20 p-3 space-y-1.5">
        <div className="flex items-center justify-between text-xs">
          <span className="font-semibold text-foreground">
            Corridor stretch ({corridor.stopCodes.length} affected stops)
          </span>
          {corridor.gapSeconds !== null && (
            <span className="text-muted-foreground">Headway gap: {seconds(corridor.gapSeconds)}</span>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          {corridor.stopNames.join(' → ')}
        </p>
      </div>
    )}

    <PlanningCorridorMap
      routes={routes}
      positions={positions}
      serviceId={request.serviceId ?? 'unknown'}
      directionId={request.directionId}
      monitoredStopCode={request.stopCode ?? ''}
      corridorStopCodes={corridor?.stopCodes}
      busReferences={detectedBusReferences}
      alert={corridor?.corridorIncident?.alerts[0] ?? detectedAlerts.find(a => a.eventId === request.eventId || (a.serviceId === request.serviceId && a.stopCode === request.stopCode))}
    />

    <div><h3 className="text-sm font-medium">Bunching detection</h3>
      <p className="mt-1 text-sm text-muted-foreground">{completed
        ? advisoryOnly
          ? 'Diagnosis generated from live observations. AI mitigation is unavailable because validated operational context is not available; you can still record controller actions below.'
          : 'Diagnosis generated. This incident remains open here while you review it or wait for the AI mitigation recommendation.'
        : processing ? 'Diagnosis is being generated.' : retry ? 'The previous diagnosis attempt needs to be retried.' : 'Generate a diagnosis to receive an AI mitigation recommendation for this detected bunching event.'}</p>
      {request.failureCode && <p className="mt-2 text-sm text-destructive">Last attempt: {request.failureCode}</p>}</div>
    {completed && <div className="rounded-lg border p-4 space-y-3">
      <div>
        <h3 className="text-sm font-medium">AI diagnosis</h3>
        {request.likelyCause
          ? <p className="mt-2 text-sm">{request.likelyCause}</p>
          : <p className="mt-2 text-sm text-muted-foreground">No supported contributing cause was identified from the available evidence.</p>}
        {request.confidence !== null && request.confidence > 0 && <p className="mt-2 text-xs text-muted-foreground">Confidence: {Math.round(request.confidence * 100)}%</p>}
      </div>
      {!!request.evidenceDetails?.length && (
        <div className="border-t pt-3">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Supporting incident evidence</h4>
          <RecommendationEvidenceList items={request.evidenceDetails} />
        </div>
      )}
      {request.limitations.length > 0 && <div className="border-t pt-3">
        <p className="text-xs font-medium text-muted-foreground">Limitations</p>
        <ul className="mt-1 list-disc space-y-1 pl-5 text-xs text-muted-foreground">
          {request.limitations.map(limitation => <li key={limitation}>{limitation}</li>)}
        </ul>
      </div>}
    </div>}
    <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
      <Dialog open={editorOpen} onOpenChange={openEditor}>
        <DialogTrigger asChild><Button variant="outline" disabled={suggesting || !detectedBusReferences.length}>Suggest actions for detected buses</Button></DialogTrigger>
        <DialogContent className="sm:max-w-3xl" onOpenAutoFocus={event => { event.preventDefault(); firstInstruction.current?.focus() }}>
          <DialogHeader><DialogTitle>Suggest mitigation actions</DialogTitle><DialogDescription>Record an action for each bus detected in this bunching incident. This is available before the AI diagnosis and does not dispatch an instruction.</DialogDescription></DialogHeader>
          {!detectedBusReferences.length && <p role="alert" className="text-sm text-destructive">Detected bus references are unavailable for this incident.</p>}
          {message && <p role="alert" className="rounded-lg border p-3 text-sm">{message}</p>}
          <form className="space-y-4" noValidate onSubmit={event => { event.preventDefault(); void saveSuggestion() }}>
            {instructions.map((item, index) => <div key={item.busReference} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr]">
              <div className="space-y-2"><Label htmlFor={`early-bus-reference-${index}`}>Detected bus</Label><Input id={`early-bus-reference-${index}`} value={item.busReference} readOnly /></div>
              <div className="space-y-2"><Label htmlFor={`early-bus-instruction-${index}`}>Suggested action</Label><textarea ref={index === 0 ? firstInstruction : undefined} id={`early-bus-instruction-${index}`} maxLength={500} rows={2} value={item.instruction} onChange={event => setInstructions(values => values.map((value, itemIndex) => itemIndex === index ? { ...value, instruction: event.target.value } : value))} required className="min-h-16 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring" /></div>
            </div>)}
            <div className="space-y-2"><Label htmlFor="early-controller-rationale">Controller rationale</Label><textarea id="early-controller-rationale" maxLength={1000} rows={3} value={rationale} onChange={event => setRationale(event.target.value)} required className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring" /></div>
            <DialogFooter><Button type="button" variant="outline" disabled={suggesting} onClick={() => setEditorOpen(false)}>Cancel</Button><Button type="submit" disabled={suggesting || !detectedBusReferences.length}>{suggesting ? 'Saving…' : 'Save controller suggestion'}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      {!detectedBusReferences.length && <p className="self-center text-xs text-muted-foreground">Detected bus references are unavailable.</p>}
      {!completed && <Button disabled={generating || processing || suggesting} onClick={onGenerate}>
        {generating ? 'Generating diagnosis…' : retry ? 'Retry diagnosis' : 'Generate diagnosis'}
      </Button>}
    </div>
  </CardContent></Card>
}

function RecommendationDetail({ selected, identity, scopeIdentity, token, detectedAlerts, routes, positions, isCurrent, validationFailed, retryCurrentCheck, onSaved, onMessage, onEditorChange, onRefreshed }: {
  selected: PendingRecommendation; identity: string; scopeIdentity: string; token: string
  detectedAlerts: BunchingAlert[]
  routes: RouteDirection[]
  positions: RoutePositions[]
  isCurrent: boolean; validationFailed: boolean; retryCurrentCheck: () => void
  onSaved: () => void; onMessage: (message: string) => void; onEditorChange: Dispatch<SetStateAction<EditingRecommendation | null>>
  onRefreshed: (item: PendingRecommendation) => void
}) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)
  const hasBusSuggestions = !!selected.suggestedOutcomes?.length
  const showsLegacySuggestion = !hasBusSuggestions && affectedBuses(selected).length === 1
  const [instructions, setInstructions] = useState<BusReplacement[]>(() => initialBusInstructions(selected))
  const [rationale, setRationale] = useState('')
  const [openingDraft, setOpeningDraft] = useState<{ instructions: BusReplacement[]; rationale: string } | null>(null)
  const submitting = useRef(false)
  const mutationRef = useRef<AbortController | null>(null)
  const firstAction = useRef<HTMLSelectElement>(null)
  const dirty = openingDraft !== null && JSON.stringify(instructions) !== JSON.stringify(openingDraft.instructions)

  useEffect(() => () => mutationRef.current?.abort(), [])

  function openEditor() {
    const nextInstructions = initialBusInstructions(selected)
    setInstructions(nextInstructions)
    setRationale('')
    setOpeningDraft({ instructions: nextInstructions, rationale: '' })
    setMessage('')
    setDiscardOpen(false)
    setEditorOpen(true)
    onEditorChange({ identity, scopeIdentity, recommendation: selected })
  }

  function closeEditor(force = false) {
    if (pending && !force) return
    setEditorOpen(false)
    setDiscardOpen(false)
    setInstructions(initialBusInstructions(selected))
    setRationale('')
    setOpeningDraft(null)
    setMessage('')
    onEditorChange(null)
  }

  function requestClose() {
    if (pending) return
    if (dirty) setDiscardOpen(true)
    else closeEditor()
  }

  function changeEditorOpen(open: boolean) {
    if (open) openEditor()
    else requestClose()
  }

  function updateInstruction(index: number, changes: Partial<ControllerReplacement>) {
    setMessage('')
    setInstructions(items => items.map((item, itemIndex) => {
      if (itemIndex !== index) return item
      const updated = { ...item, ...changes }
      if (changes.action === 'NONE' && !updated.noInstructionReason.trim()) {
        updated.noInstructionReason = 'No action required by controller'
      }
      if ((changes.action === 'HOLD' || changes.action === 'DWELL_ADJUSTMENT') && (!updated.adjustmentSeconds || updated.adjustmentSeconds < 1)) {
        updated.adjustmentSeconds = 30
      }
      return updated
    }))
  }

  async function refresh() {
    if (submitting.current || !isCurrent) return
    submitting.current = true
    setPending(true)
    setMessage('')
    onEditorChange({ identity, scopeIdentity, recommendation: selected, pending: true })
    const controller = new AbortController()
    mutationRef.current = controller
    try {
      const item = await apiRequest<PendingRecommendation>(
        `/api/v1/planning/recommendations/${encodeURIComponent(selected.recommendationId)}/refresh`,
        token, controller.signal, { method: 'POST' },
      )
      if (item && !controller.signal.aborted) onRefreshed(item)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else setMessage(error instanceof ApiError ? error.message : 'Unable to refresh the recommendation.')
    } finally {
      submitting.current = false
      if (!controller.signal.aborted) {
        setPending(false)
        onEditorChange(value => value?.identity === identity ? null : value)
      }
    }
  }

  async function decide(action: Decision['action']) {
    if (submitting.current || !isCurrent) return
    if (action === 'REJECTED') {
      const unassignedWithAction = instructions.find(item => !isBusCaptainAssigned(item.busReference, selected) && item.action !== 'NONE')
      if (unassignedWithAction) {
        setMessage(`${unassignedWithAction.busReference} has no captain assigned to receive instructions. Select No instruction for this bus.`)
        return
      }
      if (instructions.some(item => item.action === 'NONE'
        ? !item.noInstructionReason.trim()
        : item.action !== 'SKIP_STOP' && (!Number.isInteger(item.adjustmentSeconds) || item.adjustmentSeconds < 1 || item.adjustmentSeconds > 120))) {
        setMessage('Enter a reason for each bus with no instruction and a 1–120 second duration for each action.')
        return
      }
    }
    submitting.current = true; setPending(true); setMessage('')
    onEditorChange({ identity, scopeIdentity, recommendation: selected, pending: true })
    mutationRef.current?.abort()
    const controller = new AbortController()
    mutationRef.current = controller
    try {
      await apiRequest<Decision>(`/api/v1/planning/recommendations/${encodeURIComponent(selected.recommendationId)}/decision`, token, controller.signal, {
        method: 'POST', body: {
          action,
          replacementAction: null,
          adjustmentSeconds: null,
          replacementInstructions: action === 'REJECTED' ? instructions.map(item => item.action === 'NONE'
            ? { busReference: item.busReference, action: 'NO_INSTRUCTION', adjustmentSeconds: null, noInstructionReason: item.noInstructionReason.trim() }
            : item.action === 'SKIP_STOP' ? { busReference: item.busReference, action: 'SKIP_STOP', adjustmentSeconds: null }
            : { busReference: item.busReference, action: item.action, adjustmentSeconds: item.adjustmentSeconds }) : null,
          controllerRationale: action === 'REJECTED' ? rationale.trim() || null : null,
        },
      })
      if (!controller.signal.aborted) {
        onMessage(action === 'APPROVED' ? 'Recommendation approved and instruction issued to the assigned captain.'
          : instructions.some(item => item.action !== 'NONE') ? 'Bus outcomes saved and selected instructions issued.' : 'No-instruction outcomes saved for all affected buses.')
        closeEditor(true)
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        onMessage(error.message === 'Unable to load or save this data. Try again.'
          ? 'This recommendation has changed or already has a decision. The latest state has been requested.' : error.message)
        closeEditor(true)
      } else setMessage(error instanceof ApiError && error.status === 403 ? 'You do not have permission to record this decision.' : error instanceof ApiError && error.status === 503 ? `Unable to save the decision. ${error.message}` : 'Unable to save the decision. Check the instructions and try again.')
    } finally {
      submitting.current = false
      if (!controller.signal.aborted) {
        setPending(false)
        onEditorChange(value => value?.identity === identity ? { ...value, pending: false } : value)
        onSaved()
      }
    }
  }

  return <Card><CardContent className="space-y-5 px-5">
        {message && <p role="status" className="text-sm">{message}</p>}
        <div className="flex flex-wrap items-start justify-between gap-4 border-b pb-4">
          <div><h2 className="text-lg font-semibold">Service {selected.serviceId} · Direction {selected.directionId}</h2><p className="mt-1 text-xs text-muted-foreground">Stop {selected.monitoredStopCode} · detected {timestamp(selected.detectedAt)}</p></div>
          <span className="text-xs text-muted-foreground">{selected.recommendationId}</span>
        </div>
        <div><h3 className="text-sm font-medium">Affected buses</h3><p className="mt-1 text-sm text-muted-foreground">{affectedBuses(selected).join(', ')}</p></div>

        <div className="flex flex-wrap justify-end gap-2 border-b pb-4">
          {affectedBuses(selected).length > 1 && <p className="w-full text-right text-xs text-muted-foreground">Review an outcome for every affected bus before saving.</p>}
          <Button variant="outline" disabled={pending || !isCurrent} onClick={() => { void refresh() }}>Refresh bus group</Button>
          <Button disabled={pending || !isCurrent || affectedBuses(selected).length > 1} onClick={() => { void decide('APPROVED') }}>Approve recommendation</Button>
          <Dialog open={editorOpen} onOpenChange={changeEditorOpen}>
          <DialogTrigger asChild><Button variant="outline" disabled={pending}>Enter controller recommendation</Button></DialogTrigger>
            <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-3xl"
              onOpenAutoFocus={event => { event.preventDefault(); firstAction.current?.focus() }}
              onEscapeKeyDown={event => { if (pending) event.preventDefault() }}
              onPointerDownOutside={event => { event.preventDefault(); requestClose() }}
              onInteractOutside={event => { event.preventDefault(); requestClose() }}>
              <DialogHeader>
                <DialogTitle>Enter controller recommendation</DialogTitle>
                <DialogDescription>Choose hold, dwell, skip stop or no instruction for each affected bus. Timed actions can be adjusted by up to 120 seconds.</DialogDescription>
              </DialogHeader>
              {affectedBuses(selected).length > 1 && <p className="text-sm text-muted-foreground">Dwell adjustment requires current boarding evidence for the bus.</p>}
              {!isCurrent && <div role="alert" className="rounded-lg border p-3 text-sm">
                <p>{validationFailed ? 'Unable to confirm this recommendation is current. Your draft is preserved; decisions are paused.'
                  : 'Checking that this recommendation is still pending. Decisions are paused.'}</p>
                {validationFailed && <Button type="button" variant="outline" className="mt-3" onClick={retryCurrentCheck}>Retry recommendation check</Button>}
              </div>}
              {message && <p role="alert" className="rounded-lg border p-3 text-sm">{message}</p>}
              <form className="space-y-4" noValidate onSubmit={event => { event.preventDefault(); void decide('REJECTED') }}>
                {instructions.map((item, index) => {
                  const hasCaptain = isBusCaptainAssigned(item.busReference, selected)
                  const isNone = item.action === 'NONE'
                  const unassignedError = !hasCaptain && !isNone
                  const reasonError = isNone && !item.noInstructionReason.trim()
                  const secondsError = !isNone && item.action !== 'SKIP_STOP' && (!Number.isInteger(item.adjustmentSeconds) || item.adjustmentSeconds < 1 || item.adjustmentSeconds > 120)
                  return (
                    <div key={item.busReference} className={`grid gap-3 rounded-lg border p-4 sm:grid-cols-[1fr_8rem] ${unassignedError ? 'border-amber-500/50 bg-amber-500/5' : ''}`}>
                      <div className="space-y-2">
                        <div className="flex items-center justify-between">
                          <Label htmlFor={`bus-action-${index}`}>Action for {item.busReference}</Label>
                          {!hasCaptain && <span className="rounded bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">No captain assigned</span>}
                        </div>
                        <select
                          ref={index === 0 ? firstAction : undefined}
                          id={`bus-action-${index}`}
                          value={item.action}
                          onChange={event => updateInstruction(index, { action: event.target.value as ControllerReplacement['action'] })}
                          className={`h-10 w-full rounded-md border bg-background px-3 text-sm ${unassignedError ? 'border-amber-500' : 'border-input'}`}
                        >
                          <option value="HOLD">Hold at next stop</option>
                          {(affectedBuses(selected).length === 1 || index > 0) && <option value="DWELL_ADJUSTMENT">Extend dwell at stop {selected.controlStopCode}</option>}
                          <option value="SKIP_STOP">Skip stop {selected.controlStopCode}</option>
                          <option value="NONE">No instruction</option>
                        </select>
                        {!hasCaptain && <p className="text-xs text-muted-foreground">No captain is assigned to this bus; movement instructions cannot be delivered.</p>}
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor={`bus-adjustment-${index}`}>Seconds</Label>
                        <Input
                          id={`bus-adjustment-${index}`}
                          type="number"
                          min="1"
                          max="120"
                          step="1"
                          value={item.adjustmentSeconds}
                          onChange={event => updateInstruction(index, { adjustmentSeconds: Number(event.target.value) })}
                          required={item.action === 'HOLD' || item.action === 'DWELL_ADJUSTMENT'}
                          disabled={item.action === 'NONE' || item.action === 'SKIP_STOP'}
                          aria-invalid={secondsError ? 'true' : undefined}
                          className={secondsError ? 'border-destructive' : ''}
                        />
                      </div>
                      {item.action === 'NONE' && (
                        <div className="space-y-2 sm:col-span-2">
                          <Label htmlFor={`bus-no-instruction-reason-${index}`}>Reason no instruction is needed</Label>
                          <Input
                            id={`bus-no-instruction-reason-${index}`}
                            maxLength={480}
                            value={item.noInstructionReason}
                            onChange={event => updateInstruction(index, { noInstructionReason: event.target.value })}
                            required
                            aria-invalid={reasonError ? 'true' : undefined}
                            className={reasonError ? 'border-destructive' : ''}
                          />
                        </div>
                      )}
                    </div>
                  )
                })}
                <DialogFooter>
                  <DialogClose asChild><Button type="button" variant="outline" disabled={pending}>Close</Button></DialogClose>
                  <Button type="button" variant="outline" disabled={pending} onClick={requestClose}>Cancel</Button>
                  <Button type="submit" disabled={pending || !isCurrent}>{pending ? 'Saving…' : hasBusSuggestions ? 'Save reviewed outcomes' : 'Reject AI suggestion & save instructions'}</Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
          <AlertDialog open={discardOpen} onOpenChange={open => { if (!open) setDiscardOpen(false) }}>
            <AlertDialogContent onCloseAutoFocus={event => {
                  if (editorOpen) { event.preventDefault(); firstAction.current?.focus() }
            }}>
              <AlertDialogHeader>
                <AlertDialogTitle>Discard unsaved changes?</AlertDialogTitle>
                <AlertDialogDescription>Your controller recommendation edits will be lost.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep editing</AlertDialogCancel>
                <AlertDialogAction onClick={() => closeEditor()}>Discard changes</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>

        <div className="grid gap-4 rounded-lg bg-muted/50 p-4 sm:grid-cols-[1fr_auto]">
          <div><p className="text-xs text-muted-foreground">Diagnosis</p><p className="mt-1 font-medium">{selected.explanation.likelyCause}</p></div>
          <div className="grid gap-4 text-sm sm:min-w-40">
            <RecommendationBunchingGaps recommendation={selected} alerts={detectedAlerts} />
            <div><p className="text-xs text-muted-foreground">Expected headway</p><p className="mt-1 font-medium tabular-nums">{seconds(selected.expectedHeadwaySeconds)}</p></div>
          </div>
        </div>

        <PlanningCorridorMap
          routes={routes}
          positions={positions}
          serviceId={selected.serviceId}
          directionId={selected.directionId}
          controlStopCode={selected.controlStopCode || selected.monitoredStopCode}
          monitoredStopCode={selected.monitoredStopCode}
          mitigationType={selected.mitigationType}
          busReferences={affectedBuses(selected)}
          alert={recommendationAlert(selected, detectedAlerts)}
        />

        <section aria-labelledby="affected-buses" className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><h3 id="affected-buses" className="text-sm font-medium">Buses bunching</h3>{showsLegacySuggestion && <span className="text-xs text-muted-foreground">Expected gap {seconds(selected.expectedImpact.headwayAfterSeconds)}</span>}</div>
          {!hasBusSuggestions && affectedBuses(selected).length > 1 && <p className="text-sm text-muted-foreground">
            Current suggestions are unavailable. Select Refresh bus group to calculate recovery timing from current predictions.
          </p>}
          <div className="divide-y rounded-lg border">{affectedBuses(selected).map(busReference => {
            const outcome = selected.suggestedOutcomes?.find(item => item.busReference === busReference)
            const instruction = outcome
              ? outcome.mitigationType
                ? suggestedInstruction(selected, outcome)
                : `No instruction. ${outcome.reason ?? ''}`
              : showsLegacySuggestion
                ? suggestedInstruction(selected) : 'No individual instruction proposed.'
            return <div key={busReference} className="grid gap-1 p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr] sm:gap-4"><p className="font-medium">{busReference}</p><p className="text-sm text-muted-foreground">{instruction}</p></div>
          })}</div>
        </section>

        {showsLegacySuggestion && <div className="grid gap-5 border-t pt-4 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Why this action</h3><p className="mt-2 text-sm text-muted-foreground">{selected.explanation.rationale}</p></div><dl className="space-y-2 text-sm"><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Passenger minutes</dt><dd>{selected.expectedImpact.passengerMinutesBefore} → {selected.expectedImpact.passengerMinutesAfter}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Queue passengers</dt><dd>{selected.expectedImpact.queueBeforePassengers} → {selected.expectedImpact.queueAfterPassengers}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Adjustment</dt><dd>{selected.adjustmentSeconds} seconds</dd></div></dl></div>}

        <details className="border-t pt-4"><summary className="cursor-pointer text-sm font-medium">Evidence and limitations</summary>
          <div className="mt-4 space-y-4">
            {!!selected.explanation.evidenceDetails?.length && <div><h3 className="text-sm font-medium">Supporting incident evidence</h3><RecommendationEvidenceList items={selected.explanation.evidenceDetails} /></div>}
            <div className="grid gap-5 sm:grid-cols-2">{showsLegacySuggestion && <div><h3 className="text-sm font-medium">Trade-offs</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.tradeOffs.map((item, index) => <li key={index}>{item}</li>)}</ul></div>}<div><h3 className="text-sm font-medium">Limitations</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></div></div>
          </div>
        </details>

      </CardContent></Card>
}

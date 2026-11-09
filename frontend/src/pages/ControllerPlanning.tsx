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
import { ApiError, apiRequest, triggerDiagnosis, type BunchingAlert, type ControllerBusInstruction, type Decision, type DiagnosisEvidenceDetail, type EarlyControllerSuggestion, type PendingRecommendation, type PlanningDiagnosisRequest, type PlanningDiagnosisRequestPage, type RouteDirection, type RouteStop } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { bunchingArrivals } from '@/lib/bunching'
import { groupRouteIncidents, type RouteIncident } from '@/lib/route-strip'
import type { Planning } from './ControllerPage'
import type { PagedCollection } from '@/lib/paged-collection'
import { DataMessage, timestamp } from './ControllerViews'

function seconds(value: number) {
  const minutes = Math.floor(value / 60)
  const remainder = value % 60
  return minutes ? `${minutes}:${String(remainder).padStart(2, '0')}` : `${remainder}s`
}

function suggestedInstruction(recommendation: PendingRecommendation) {
  if (recommendation.mitigationType === 'HOLD') return `Hold for ${recommendation.adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  if (recommendation.mitigationType === 'DWELL_ADJUSTMENT') return `Adjust dwell by ${recommendation.adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  return `Dispatch standby support for ${recommendation.targetBusReference} at stop ${recommendation.controlStopCode}.`
}

function affectedBuses(recommendation: PendingRecommendation) {
  return recommendation.affectedBusReferences.length
    ? recommendation.affectedBusReferences : [recommendation.targetBusReference]
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

function initialInstructions(recommendation: PendingRecommendation): ControllerBusInstruction[] {
  return affectedBuses(recommendation).slice(0, 3).map((busReference, index) => ({
    busReference,
    instruction: index === 0 ? suggestedInstruction(recommendation) : '',
  }))
}

function RecommendationEvidenceList({ items }: { items: DiagnosisEvidenceDetail[] }) {
  if (!items.length) return null
  return <ul className="mt-2 list-disc space-y-3 pl-5 text-xs text-muted-foreground">
    {items.map(item => <li key={item.evidenceId}>
      {item.incidentType && <p className="font-medium text-foreground">{item.incidentType}</p>}
      {item.message && <p>{item.message}</p>}
      {item.distanceMetersFromSuppliedBusPosition !== null && <p>Approx. {Math.round(item.distanceMetersFromSuppliedBusPosition).toLocaleString('en-SG')} m from the supplied bus position near stop {item.monitoredStopCode}.</p>}
      <p>Incident evidence: {item.evidenceId}{item.busPositionEvidenceId ? ` · Bus-position evidence: ${item.busPositionEvidenceId}` : ''}</p>
    </li>)}
  </ul>
}

type EditingRecommendation = { identity: string; scopeIdentity: string; recommendation: PendingRecommendation; pending?: boolean }

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
  routes = [],
  pollIntervalSeconds = 60,
  nowMs = 0,
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
  routes?: RouteDirection[]
  pollIntervalSeconds?: number
  nowMs?: number
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
    return groupRouteIncidents(detectedAlerts, routes, nowMs, pollIntervalSeconds, 20)
  }, [detectedAlerts, routes, nowMs, pollIntervalSeconds])

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
    // Smart default on deep link: if user linked to an incident that has already cleared on road, default to 'today'
    if (matchedCorridor && !matchedCorridor.isLive) return 'today'
    return 'active'
  }, [explicitScope, matchedCorridor])

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
  const selectionIsCurrent = !recommendations.isLoading && recommendationLoadError === null && !modeError && !!selected && recommendationItems.some(item => item.recommendationId === selected.recommendationId &&
    JSON.stringify(item) === JSON.stringify(selected))

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
    setSearchParams({ request: request.requestId }, { replace: true })
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
                  <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
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
            <p className="text-xs text-muted-foreground">{item.mitigationType.replaceAll('_', ' ')} · Target: {item.targetBusReference}</p>
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
        scopeIdentity={scopeIdentity} token={token} isCurrent={selectionIsCurrent} validationFailed={editorUnavailable}
        retryCurrentCheck={modeError ? onRefresh : recommendations.reload}
        onSaved={onSaved} onMessage={setMessage} onEditorChange={setEditingRecommendation} />}
      {selectedRequest && <DiagnosisRequestDetail request={selectedRequest}
        corridor={selectedCorridor}
        detectedBusReferences={detectedBusReferences(selectedRequest, detectedAlerts, selectedCorridor)}
        generating={triggeringRequestId === selectedRequest.requestId} suggesting={suggestingRequestId === selectedRequest.requestId}
        token={token} onSuggestionPending={pending => setSuggestingRequestId(pending ? selectedRequest.requestId : null)}
        onSaved={onSaved} onGenerate={() => { void generateDiagnosis(selectedRequest) }} />}
      {!selectedRequest && !detailRecommendation && selectedCorridor && (
        <Card><CardContent className="space-y-4 p-6">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold">Service {selectedCorridor.serviceId} · Direction {selectedCorridor.directionId ?? 1}</h2>
            {selectedCorridor.isLive ? (
              <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
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
    const arrivals = bunchingArrivals(alert)
    const slots: string[] = []
    if (arrivals[0] && arrivals[0] !== 'Unavailable' && arrivals[0] !== 'No prediction') slots.push('Bus 1 (Leading)')
    if (arrivals[1] && arrivals[1] !== 'Unavailable' && arrivals[1] !== 'No prediction') slots.push('Bus 2 (Following)')
    if (slots.length) return slots
  }
  return []
}

function DiagnosisRequestDetail({ request, corridor, detectedBusReferences, generating, suggesting, token, onSuggestionPending, onSaved, onGenerate }: {
  request: PlanningDiagnosisRequest; corridor?: CorridorDiagnosisGroup | null; detectedBusReferences: string[]; generating: boolean; suggesting: boolean; token: string
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
            <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
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

    <div><h3 className="text-sm font-medium">Bunching detection</h3>
      <p className="mt-1 text-sm text-muted-foreground">{completed
        ? advisoryOnly
          ? 'Diagnosis generated from live observations. AI mitigation is unavailable because validated operational context is not available; you can still record controller actions below.'
          : 'Diagnosis generated. This incident remains open here while you review it or wait for the AI mitigation recommendation.'
        : processing ? 'Diagnosis is being generated.' : retry ? 'The previous diagnosis attempt needs to be retried.' : 'Generate a diagnosis to receive an AI mitigation recommendation for this detected bunching event.'}</p>
      {request.failureCode && <p className="mt-2 text-sm text-destructive">Last attempt: {request.failureCode}</p>}</div>
    {completed && <div className="rounded-lg border p-4">
      <h3 className="text-sm font-medium">AI diagnosis</h3>
      {request.likelyCause
        ? <p className="mt-2 text-sm">{request.likelyCause}</p>
        : <p className="mt-2 text-sm text-muted-foreground">No supported contributing cause was identified from the available evidence.</p>}
      {request.confidence !== null && request.confidence > 0 && <p className="mt-2 text-xs text-muted-foreground">Confidence: {Math.round(request.confidence * 100)}%</p>}
      {request.limitations.length > 0 && <div className="mt-3">
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

function RecommendationDetail({ selected, identity, scopeIdentity, token, isCurrent, validationFailed, retryCurrentCheck, onSaved, onMessage, onEditorChange }: {
  selected: PendingRecommendation; identity: string; scopeIdentity: string; token: string
  isCurrent: boolean; validationFailed: boolean; retryCurrentCheck: () => void
  onSaved: () => void; onMessage: (message: string) => void; onEditorChange: Dispatch<SetStateAction<EditingRecommendation | null>>
}) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)
  const [instructions, setInstructions] = useState<ControllerBusInstruction[]>(() => initialInstructions(selected))
  const [rationale, setRationale] = useState('')
  const [openingDraft, setOpeningDraft] = useState<{ instructions: ControllerBusInstruction[]; rationale: string } | null>(null)
  const submitting = useRef(false)
  const mutationRef = useRef<AbortController | null>(null)
  const firstBusReference = useRef<HTMLInputElement>(null)
  const dirty = openingDraft !== null && (rationale !== openingDraft.rationale || JSON.stringify(instructions) !== JSON.stringify(openingDraft.instructions))

  useEffect(() => () => mutationRef.current?.abort(), [])

  function openEditor() {
    const nextInstructions = initialInstructions(selected)
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
    setInstructions(initialInstructions(selected))
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

  function updateInstruction(index: number, field: keyof ControllerBusInstruction, value: string) {
    setMessage('')
    setInstructions(items => items.map((item, itemIndex) => itemIndex === index ? { ...item, [field]: value } : item))
  }

  async function decide(action: Decision['action']) {
    if (submitting.current || !isCurrent) return
    const overrideInstructions = action === 'REJECTED' ? instructions.map(item => ({
      busReference: item.busReference.trim(), instruction: item.instruction.trim(),
    })) : []
    if (action === 'REJECTED' && (!rationale.trim() || overrideInstructions.some(item => !item.busReference || !item.instruction))) {
      setMessage('Enter a bus reference and instruction for every affected bus, plus a controller rationale.')
      return
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
          overrideInstructions,
          controllerRationale: action === 'REJECTED' ? rationale.trim() : null,
        },
      })
      if (!controller.signal.aborted) {
        onMessage(action === 'APPROVED' ? 'Recommendation approved.' : 'AI recommendation replaced with controller instructions.')
        closeEditor(true)
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        onMessage('This recommendation has changed or already has a decision. The latest state has been requested.')
        closeEditor(true)
      } else setMessage(error instanceof ApiError && error.status === 403 ? 'You do not have permission to record this decision.' : 'Unable to save the decision. Check the instructions and try again.')
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
          <Button disabled={pending || !isCurrent} onClick={() => { void decide('APPROVED') }}>Approve recommendation</Button>
          <Dialog open={editorOpen} onOpenChange={changeEditorOpen}>
          <DialogTrigger asChild><Button variant="outline" disabled={pending}>Enter controller recommendation</Button></DialogTrigger>
            <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-3xl"
              onOpenAutoFocus={event => { event.preventDefault(); firstBusReference.current?.focus() }}
              onEscapeKeyDown={event => { if (pending) event.preventDefault() }}
              onPointerDownOutside={event => { event.preventDefault(); requestClose() }}
              onInteractOutside={event => { event.preventDefault(); requestClose() }}>
              <DialogHeader>
                <DialogTitle>Enter controller recommendation</DialogTitle>
                <DialogDescription>Replace the AI recommendation with instructions for the affected buses and a controller rationale.</DialogDescription>
              </DialogHeader>
              {!isCurrent && <div role="alert" className="rounded-lg border p-3 text-sm">
                <p>{validationFailed ? 'Unable to confirm this recommendation is current. Your draft is preserved; decisions are paused.'
                  : 'Checking that this recommendation is still pending. Decisions are paused.'}</p>
                {validationFailed && <Button type="button" variant="outline" className="mt-3" onClick={retryCurrentCheck}>Retry recommendation check</Button>}
              </div>}
              {message && <p role="alert" className="rounded-lg border p-3 text-sm">{message}</p>}
              <form className="space-y-4" noValidate onSubmit={event => { event.preventDefault(); void decide('REJECTED') }}>
                {instructions.map((item, index) => <div key={index} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr_auto]">
                  <div className="space-y-2"><Label htmlFor={`bus-reference-${index}`}>Bus reference</Label><Input ref={index === 0 ? firstBusReference : undefined} id={`bus-reference-${index}`} value={item.busReference} onChange={event => updateInstruction(index, 'busReference', event.target.value)} required /></div>
                  <div className="space-y-2"><Label htmlFor={`bus-instruction-${index}`}>Instruction</Label><textarea id={`bus-instruction-${index}`} maxLength={500} rows={2} value={item.instruction} onChange={event => updateInstruction(index, 'instruction', event.target.value)} required className="min-h-16 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring" /></div>
                </div>)}
                <div className="space-y-2"><Label htmlFor="controller-rationale">Controller rationale</Label><textarea id="controller-rationale" maxLength={1000} rows={3} value={rationale} onChange={event => { setRationale(event.target.value); setMessage('') }} required className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring" /></div>
                <DialogFooter>
                  <DialogClose asChild><Button type="button" variant="outline" disabled={pending}>Close</Button></DialogClose>
                  <Button type="button" variant="outline" disabled={pending} onClick={requestClose}>Cancel</Button>
                  <Button type="submit" disabled={pending || !isCurrent}>{pending ? 'Saving…' : 'Reject AI suggestion & save instructions'}</Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
          <AlertDialog open={discardOpen} onOpenChange={open => { if (!open) setDiscardOpen(false) }}>
            <AlertDialogContent onCloseAutoFocus={event => {
              if (editorOpen) { event.preventDefault(); firstBusReference.current?.focus() }
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
          <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-1"><div><dt className="text-xs text-muted-foreground">Observed gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.observedHeadwaySeconds)}</dd></div><div><dt className="text-xs text-muted-foreground">Target gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.expectedHeadwaySeconds)}</dd></div></dl>
        </div>

        <section aria-labelledby="affected-buses" className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><h3 id="affected-buses" className="text-sm font-medium">Buses bunching</h3><span className="text-xs text-muted-foreground">Expected gap {seconds(selected.expectedImpact.headwayAfterSeconds)}</span></div>
          <div className="divide-y rounded-lg border">{affectedBuses(selected).map(busReference => {
            const isTarget = busReference === selected.targetBusReference
            return <div key={busReference} className="grid gap-1 p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr] sm:gap-4"><p className="font-medium">{busReference}</p><p className="text-sm text-muted-foreground">{isTarget ? suggestedInstruction(selected) : 'No individual instruction proposed.'}</p></div>
          })}</div>
        </section>

        <div className="grid gap-5 border-t pt-4 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Why this action</h3><p className="mt-2 text-sm text-muted-foreground">{selected.explanation.rationale}</p></div><dl className="space-y-2 text-sm"><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Passenger minutes</dt><dd>{selected.expectedImpact.passengerMinutesBefore} → {selected.expectedImpact.passengerMinutesAfter}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Queue passengers</dt><dd>{selected.expectedImpact.queueBeforePassengers} → {selected.expectedImpact.queueAfterPassengers}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Adjustment</dt><dd>{selected.adjustmentSeconds} seconds</dd></div></dl></div>

        <details className="border-t pt-4"><summary className="cursor-pointer text-sm font-medium">Evidence and limitations</summary>
          <div className="mt-4 space-y-4">
            {!!selected.explanation.evidenceDetails?.length && <div><h3 className="text-sm font-medium">Supporting incident evidence</h3><RecommendationEvidenceList items={selected.explanation.evidenceDetails} /></div>}
            <div className="grid gap-5 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Trade-offs</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.tradeOffs.map((item, index) => <li key={index}>{item}</li>)}</ul></div><div><h3 className="text-sm font-medium">Limitations</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></div></div>
          </div>
        </details>

      </CardContent></Card>
}

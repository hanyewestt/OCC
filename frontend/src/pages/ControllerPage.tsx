import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError, apiRequest, enrichBunchingStops, routeDirections, type AiAuditRecord, type Arrival, type BunchingAlert, type BusRoute, type Decision, type DemoRun, type DirectoryPage, type DirectoryUser, type EarlyControllerSuggestion, type PageResult, type PendingRecommendation, type PlanningDiagnosisRequest, type PlanningDiagnosisRequestPage, type RouteDirection, type RoutePositions } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { ControllerSidebar, controllerPages } from '@/components/ControllerSidebar'
import { ControllerOverview, ControllerDisruptions, ControllerAudit } from '@/pages/ControllerViews'
import { ControllerRouteStrip } from '@/pages/ControllerRouteStrip'
import { ControllerPlanning } from '@/pages/ControllerPlanning'
import { ControllerAdvisories } from '@/pages/ControllerAdvisories'
import { usePagedCollection } from '@/lib/paged-collection'
import { usePageRefresh } from '@/lib/page-refresh'
import { PageRefreshStatus } from '@/components/PageRefreshStatus'
import { ControllerCaptainsPage } from '@/pages/ControllerCaptainsPage'
import { captainDirectoryPath, captainSelectionIsValid, selectedCaptainIds } from '@/pages/controller-captains'

export type Resource<T> = { data: T | null; error: string }
export type Planning = { recommendations: PageResult<PendingRecommendation> & { mode: string; demoRunId: string | null }; decisions: PageResult<Decision> }
const empty = { data: null, error: '' }

async function readResource<T>(operation: Promise<T | null>, signal: AbortSignal): Promise<Resource<T>> {
  try { return { data: await operation, error: '' } }
  catch (error) {
    if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
    return { data: null, error: error instanceof ApiError && error.status === 403 ? 'Access denied for this data.' : 'Unable to load this data.' }
  }
}

async function loadRoutePositions(token: string, signal: AbortSignal): Promise<Resource<RoutePositions[]>> {
  const results = await Promise.all(['190', '972'].map(async serviceId => {
    try {
      return { data: await apiRequest<RoutePositions>(`/api/v1/route-positions?service=${serviceId}`, token, signal), error: '' }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      const reason = error instanceof ApiError ? `HTTP ${error.status}` : 'request failed'
      return { data: null, error: `Service ${serviceId}: ${reason}` }
    }
  }))
  const snapshots = results.flatMap(result => result.data ? [result.data] : [])
  return {
    data: snapshots.length ? snapshots : null,
    error: results.some(result => result.error) ? `Some estimated bus positions could not be loaded (${results.filter(result => result.error).map(result => result.error).join('; ')}).` : '',
  }
}

async function loadSimulationRoutePositions(token: string, runId: string, signal: AbortSignal): Promise<Resource<RoutePositions[]>> {
  try {
    const position = await apiRequest<RoutePositions>(`/api/v1/demo-runs/${encodeURIComponent(runId)}/route-positions`, token, signal)
    return { data: position ? [position] : null, error: '' }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
    return { data: null, error: 'Simulation route positions are unavailable.' }
  }
}

async function loadRouteDirections(token: string, serviceIds: string[], signal: AbortSignal): Promise<Resource<RouteDirection[]>> {
  const results = await Promise.all(serviceIds.map(serviceId =>
    readResource(apiRequest<BusRoute>(`/api/v1/bus-routes/${encodeURIComponent(serviceId)}`, token, signal), signal)))
  return {
    data: routeDirections(results.flatMap(result => result.data ? [result.data] : [])),
    error: results.some(result => result.error) ? 'Unable to load all route references.' : '',
  }
}

async function loadRouteArrivalsForService(token: string, serviceId: string, signal: AbortSignal): Promise<Resource<Arrival[]>> {
  try {
    const params = new URLSearchParams({ serviceId, page: '0', size: '200' })
    const firstPage = await apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?${params}`, token, signal)
    if (!firstPage) return { data: null, error: '' }
    const items = [...firstPage.items]
    // Read only this service's finite current-snapshot pages; each response is capped at 200 rows by the API.
    for (let page = 1; page < firstPage.totalPages; page++) {
      params.set('page', String(page))
      const result = await apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?${params}`, token, signal)
      if (!result) throw new Error('Route arrival page was empty.')
      items.push(...result.items)
    }
    return { data: items, error: '' }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
    return { data: null, error: `Service ${serviceId}: route arrivals are unavailable.` }
  }
}

async function loadRouteArrivals(token: string, signal: AbortSignal): Promise<Resource<Arrival[]>> {
  const results = await Promise.all(['190', '972'].map(serviceId => loadRouteArrivalsForService(token, serviceId, signal)))
  const errors = results.flatMap(result => result.error ? [result.error] : [])
  return { data: errors.length ? null : results.flatMap(result => result.data ?? []), error: errors.join(' ') }
}

type LiveData = {
  routeArrivals: Resource<Arrival[]>
  positions: Resource<RoutePositions[]>
}

async function loadLiveData(token: string, signal: AbortSignal): Promise<LiveData> {
  const [routeArrivals, positions] = await Promise.all([
    loadRouteArrivals(token, signal),
    loadRoutePositions(token, signal),
  ])
  return { routeArrivals, positions }
}

export function ControllerPage({ email, token, controllerRefreshIntervalSeconds, ingestionPollIntervalSeconds,
  routeIncidentGapToleranceSeconds }: {
  email: string; token: string; controllerRefreshIntervalSeconds: number; ingestionPollIntervalSeconds: number
  routeIncidentGapToleranceSeconds: number
}) {
  const { pathname, hash } = useLocation()
  const current = controllerPages.find(page => page.path === pathname)!
  const [advisoryPending, setAdvisoryPending] = useState(false)
  const [planningPending, setPlanningPending] = useState(false)
  const [shellRefreshTime, setShellRefreshTime] = useState({ key: '', at: null as string | null })
  const [advisoryRefreshTime, setAdvisoryRefreshTime] = useState({ key: '', at: null as string | null })
  const refreshAdvisories = useRef<(signal: AbortSignal) => Promise<void>>(() => Promise.resolve())
  const registerAdvisoryRefresh = useCallback((callback: (signal: AbortSignal) => Promise<void>) => { refreshAdvisories.current = callback }, [])
  const [arrivalStopName, setArrivalStopName] = useState('')
  const [arrivalSortBy, setArrivalSortBy] = useState<'arrival' | 'stopName'>('arrival')
  const [arrivalSortDirection, setArrivalSortDirection] = useState<'asc' | 'desc'>('asc')
  const [routeArrivals, setRouteArrivals] = useState<Resource<Arrival[]>>(empty)
  const [routeStops, setRouteStops] = useState<Resource<RouteDirection[]>>(empty)
  const [routePositions, setRoutePositions] = useState<Resource<RoutePositions[]>>(empty)
  const [run, setRun] = useState<Resource<DemoRun>>(empty)
  const runState = useRef<Resource<DemoRun>>(empty)
  const routeReference = useRef<Resource<RouteDirection[]>>(empty)
  const [loaded, setLoaded] = useState('')
  const [switching, setSwitching] = useState(false)
  const [modeError, setModeError] = useState('')
  const [simulationDialogOpen, setSimulationDialogOpen] = useState(false)
  const [simulationConfig, setSimulationConfig] = useState({ serviceId: '190', directionId: 1, busCount: 3, dispatchGapSeconds: 90, busGapsSeconds: [90, 90], monitoredStopCode: '44251', scenario: 'BUNCHING' as 'NORMAL' | 'BUNCHING', captainUserIds: ['', '', ''] })
  const [captainSearch, setCaptainSearch] = useState('')
  const [availableCaptains, setAvailableCaptains] = useState<DirectoryUser[]>([])
  const [captainSearchError, setCaptainSearchError] = useState('')
  const [captainsLoading, setCaptainsLoading] = useState(false)
  const modeRequestRef = useRef<AbortController | null>(null)
  const liveRequestGenerationRef = useRef(0)
  const switchingRef = useRef(false)
  const requestKey = `${token}:${pathname}`
  const loading = loaded !== requestKey || switching
  const scopeVerified = loaded === requestKey && !run.error
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const captainAssignmentInvalid = !captainSelectionIsValid(simulationConfig.captainUserIds, simulationConfig.busCount)
  const simulationDirections = routeStops.data?.filter(route => route.serviceId === simulationConfig.serviceId) ?? []
  const simulationStops = simulationDirections.find(route => route.directionId === simulationConfig.directionId)?.stops ?? []

  useEffect(() => {
    if (!simulationDialogOpen) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      setCaptainsLoading(true)
      setCaptainSearchError('')
      void apiRequest<DirectoryPage>(captainDirectoryPath(captainSearch, 0, true), token, controller.signal)
        .then(result => {
          if (!result) throw new Error('Captain search response was empty.')
          if (!controller.signal.aborted) setAvailableCaptains(result.items)
        })
        .catch(error => {
          if (controller.signal.aborted) return
          if (error instanceof ApiError && error.status === 401) void signOut(error.message)
          setCaptainSearchError(error instanceof ApiError && error.status === 403 ? 'You do not have permission to search captains.' : 'Unable to load available captains.')
        })
        .finally(() => { if (!controller.signal.aborted) setCaptainsLoading(false) })
    }, captainSearch ? 200 : 0)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [captainSearch, simulationDialogOpen, token])
  const refreshScope = `${requestKey}:${simulation?.id ?? 'LIVE'}`
  const reportAdvisoryRefresh = useCallback((at: string | null) => {
    setAdvisoryRefreshTime(previous => previous.key === refreshScope && previous.at === at ? previous : { key: refreshScope, at })
  }, [refreshScope])
  const arrivalCollectionKey = `${token}:${arrivalStopName}:${arrivalSortBy}:${arrivalSortDirection}`
  const arrivalCollection = usePagedCollection<Arrival, PageResult<Arrival>>(arrivalCollectionKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '50', sortBy: arrivalSortBy, sortDirection: arrivalSortDirection })
    if (arrivalStopName) params.set('stopName', arrivalStopName)
    try {
      const result = await apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?${params}`, token, signal)
      if (!result) throw new Error('The arrivals response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, arrival => arrival.observationId, pathname === '/controller')
  const scopeKey = `${token}:${simulation?.id ?? 'LIVE'}`
  const alertCollection = usePagedCollection<BunchingAlert, PageResult<BunchingAlert>>(scopeKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '200' })
    try {
      const result = await apiRequest<PageResult<BunchingAlert>>(`/api/v1/live-bunching-alerts?${params}`, token, signal)
      if (!result) throw new Error('Bunching response was empty.')
      return { ...result, items: await enrichBunchingStops(result.items, token, signal) }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, alert => alert.eventId, scopeVerified && !simulation && (pathname === '/controller' || pathname === '/controller/disruptions' || pathname === '/controller/planning'))
  const recommendationCollection = usePagedCollection<PendingRecommendation, Planning['recommendations']>(scopeKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<Planning['recommendations']>(`/api/v1/planning/recommendations/pending?${params}`, token, signal)
      if (!result) throw new Error('Recommendation response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, recommendation => recommendation.recommendationId, scopeVerified && (pathname === '/controller' || pathname === '/controller/planning'))
  const diagnosisCollection = usePagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(scopeKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PlanningDiagnosisRequestPage>(`/api/v1/planning/diagnoses?${params}`, token, signal)
      if (!result) throw new Error('Bunching request response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, request => request.requestId, scopeVerified && pathname === '/controller/planning')
  const decisionCollection = usePagedCollection<Decision, PageResult<Decision>>(token, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<Decision>>(`/api/v1/planning/recommendations/decisions?${params}`, token, signal)
      if (!result) throw new Error('Decision response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, decision => decision.recommendationId, pathname === '/controller/audit')
  const suggestionCollection = usePagedCollection<EarlyControllerSuggestion, PageResult<EarlyControllerSuggestion>>(scopeKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<EarlyControllerSuggestion>>(`/api/v1/planning/diagnoses/controller-suggestions?${params}`, token, signal)
      if (!result) throw new Error('The controller suggestion response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, suggestion => suggestion.requestId, scopeVerified && pathname === '/controller/audit')
  const aiAuditCollection = usePagedCollection<AiAuditRecord, PageResult<AiAuditRecord>>(token, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<AiAuditRecord>>(`/api/v1/planning/ai-audit-records?${params}`, token, signal)
      if (!result) throw new Error('AI audit response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, record => record.invocationId, pathname === '/controller/audit')
  const requestLiveData = useCallback(async (signal: AbortSignal) => {
    const generation = ++liveRequestGenerationRef.current
    return { generation, data: await loadLiveData(token, signal) }
  }, [token])
  const fetchStopArrivals = useCallback(async (route: RouteDirection, stopCode: string, signal: AbortSignal): Promise<Arrival[]> => {
    const params = new URLSearchParams({ stopCode, serviceId: route.serviceId, page: '0', size: '50' })
    try {
      const result = await apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?${params}`, token, signal)
      return result?.items ?? []
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, [token])
  const fetchStopName = useCallback(async (stopCode: string): Promise<string | null> => {
    try {
      const result = await apiRequest<{ description: string | null }>(
        `/api/v1/bus-stops/${encodeURIComponent(stopCode)}`, token, new AbortController().signal,
      )
      return result?.description ?? null
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      throw error
    }
  }, [token])
  useEffect(() => () => modeRequestRef.current?.abort(), [])
  useEffect(() => {
    if (pathname !== '/controller' || hash !== '#arrivals') return
    document.getElementById('arrivals-title')?.focus({ preventScroll: true })
    document.getElementById('arrivals')?.scrollIntoView({ block: 'start' })
  }, [pathname, hash])
  const loadShell = useCallback(async (signal: AbortSignal) => {
    const runPromise = readResource(apiRequest<DemoRun>('/api/v1/demo-runs/active', token, signal).then(async value =>
      value ? { ...value, alerts: await enrichBunchingStops(value.alerts, token, signal) } : null), signal)
    const [liveRequest, nextRun] = await Promise.all([
      (pathname === '/controller' || pathname.endsWith('/disruptions') || pathname.endsWith('/planning')) ? requestLiveData(signal) : Promise.resolve(null), runPromise,
    ])
    if (signal.aborted) return false
    const liveIsCurrent = !liveRequest || liveRequest.generation === liveRequestGenerationRef.current
    const live = liveIsCurrent ? liveRequest?.data ?? null : null
    const previousMode = runState.current.data?.id ?? 'LIVE'
    const resolvedRun = nextRun.error && runState.current.data ? { ...runState.current, error: nextRun.error } : nextRun
    const activeSimulation = resolvedRun.data && resolvedRun.data.status !== 'CLOSED' ? resolvedRun.data : null
    const nextRouteStops = await loadRouteDirections(token,
      activeSimulation ? [activeSimulation.serviceId || '190'] : ['190', '972'], signal)
    if (live && !activeSimulation) {
      setRouteArrivals(previous => live.routeArrivals.error && previous.data ? { ...previous, error: live.routeArrivals.error } : live.routeArrivals)
      setRoutePositions(previous => {
        if (!live.positions.error) return live.positions
        const snapshots = new Map((previous.data ?? []).map(snapshot => [snapshot.serviceId, snapshot]))
        for (const snapshot of live.positions.data ?? []) snapshots.set(snapshot.serviceId, snapshot)
        return { data: snapshots.size ? [...snapshots.values()] : null, error: live.positions.error }
      })
    }
    runState.current = resolvedRun
    setRun(resolvedRun)
    if ((pathname === '/controller' || pathname.endsWith('/disruptions') || pathname.endsWith('/planning')) && activeSimulation) {
      const simulationPositions = await loadSimulationRoutePositions(token, activeSimulation.id, signal)
      if (!signal.aborted) {
        setRoutePositions(simulationPositions)
        setRouteArrivals({ data: [], error: '' })
      }
    }
    routeReference.current = nextRouteStops
    setRouteStops(nextRouteStops)
    setLoaded(requestKey)
    if (liveIsCurrent && !nextRun.error && !live?.routeArrivals.error && !live?.positions.error && !nextRouteStops?.error) {
      setShellRefreshTime({ key: requestKey, at: new Date().toISOString() })
    }
    return previousMode !== (resolvedRun.data?.id ?? 'LIVE')
  }, [token, pathname, requestKey, requestLiveData])

  useEffect(() => {
    const controller = new AbortController()
    void Promise.resolve().then(() => {
      if (!controller.signal.aborted) return loadShell(controller.signal)
    })
    return () => controller.abort()
  }, [loadShell])

  const pollIntervalSeconds = routePositions.data?.length
    ? Math.min(...routePositions.data.map(value => value.refreshIntervalSeconds)) : ingestionPollIntervalSeconds
  const collectionsLoading = pathname === '/controller'
    ? arrivalCollection.isLoading || !simulation && alertCollection.isLoading || recommendationCollection.isLoading
    : pathname.endsWith('/disruptions') ? !simulation && alertCollection.isLoading
      : pathname.endsWith('/planning') ? recommendationCollection.isLoading || diagnosisCollection.isLoading || (!simulation && alertCollection.isLoading)
      : pathname.endsWith('/audit') && (decisionCollection.isLoading || suggestionCollection.isLoading || aiAuditCollection.isLoading)
  const { requestRefresh, isRefreshing } = usePageRefresh(controllerRefreshIntervalSeconds, async signal => {
    const modeChanged = await loadShell(signal)
    if (signal.aborted || modeChanged) return
    const requests: Promise<void>[] = []
    if (pathname === '/controller') requests.push(arrivalCollection.refresh(signal), recommendationCollection.refresh(signal))
    if (!simulation && (pathname === '/controller' || pathname.endsWith('/disruptions') || pathname.endsWith('/planning'))) requests.push(alertCollection.refresh(signal))
    if (pathname === '/controller/planning') requests.push(recommendationCollection.refresh(signal), diagnosisCollection.refresh(signal))
    if (pathname === '/controller/audit') requests.push(decisionCollection.refresh(signal), suggestionCollection.refresh(signal), aiAuditCollection.refresh(signal))
    if (pathname !== '/controller' && !pathname.endsWith('/planning') && !pathname.endsWith('/captains')) requests.push(refreshAdvisories.current(signal))
    await Promise.allSettled(requests)
  }, loading || collectionsLoading || advisoryPending || planningPending || switching, requestKey)

  const refreshTimes = [shellRefreshTime.key === requestKey ? shellRefreshTime.at : null,
    ...(pathname === '/controller' ? [arrivalCollection.lastSuccessAt, recommendationCollection.lastSuccessAt]
      : pathname.endsWith('/planning') ? [recommendationCollection.lastSuccessAt, diagnosisCollection.lastSuccessAt]
      : pathname.endsWith('/audit') ? [decisionCollection.lastSuccessAt, suggestionCollection.lastSuccessAt, aiAuditCollection.lastSuccessAt] : []),
    ...(!simulation && (pathname === '/controller' || pathname.endsWith('/disruptions') || pathname.endsWith('/planning')) ? [alertCollection.lastSuccessAt] : []),
    ...(pathname !== '/controller' && !pathname.endsWith('/planning') && !pathname.endsWith('/captains') ? [advisoryRefreshTime.key === refreshScope ? advisoryRefreshTime.at : null] : [])]
  const lastPageRefresh = refreshTimes.includes(null) ? null : [...refreshTimes].sort()[0] ?? null

  const modeValue = simulation ? 'DEMO' : 'LIVE'
  const recommendationPage = recommendationCollection.latestPage
  const recommendationMatches = !run.error && recommendationPage?.mode === modeValue &&
    (recommendationPage.demoRunId ?? null) === (simulation?.id ?? null)
  const pending = recommendationMatches ? recommendationPage.totalItems : undefined
  const alertResource: Resource<PageResult<BunchingAlert>> = {
    data: alertCollection.latestPage ? { ...alertCollection.latestPage, items: alertCollection.items } : null,
    error: alertCollection.error !== null && !alertCollection.items.length ? 'Unable to load bunching detections.' : '',
  }
  const mode = loading ? 'Checking mode…' : run.error ? 'Mode unavailable' : simulation ? 'Simulation' : 'Live'

  async function switchMode(configuration?: typeof simulationConfig) {
    if (loading || run.error || switchingRef.current) return
    switchingRef.current = true
    setSwitching(true); setModeError('')
    const controller = new AbortController()
    modeRequestRef.current = controller
    const path = simulation ? `/api/v1/demo-runs/${encodeURIComponent(simulation.id)}/close` : '/api/v1/demo-runs'
    try {
      await apiRequest<DemoRun>(path, token, controller.signal, { method: 'POST', ...(configuration ? { body: {
        ...configuration,
        ...(selectedCaptainIds(configuration.captainUserIds, configuration.busCount).every(Boolean)
          ? { captainUserIds: selectedCaptainIds(configuration.captainUserIds, configuration.busCount) } : { captainUserIds: undefined }),
      } } : {}) })
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else setModeError(error instanceof ApiError && error.status === 403 ? 'You do not have permission to switch modes.' :
        error instanceof ApiError && error.status === 409 ? 'A simulation is already active. Checking the current mode.' :
          'Unable to switch modes. Check the current mode before trying again.')
    } finally {
      switchingRef.current = false
      if (!controller.signal.aborted) {
        setSwitching(false)
        void requestRefresh('mutation')
      }
    }
  }

  function startSimulation() {
    setSimulationDialogOpen(false)
    void switchMode(simulationConfig)
  }

  return <div className="flex min-h-svh w-full bg-background">
    <ControllerSidebar email={email} />
    <div className="min-w-0 flex-1">
      <header className="sticky top-0 z-10 flex min-h-16 flex-wrap items-center justify-between gap-3 border-b bg-background px-5 py-3 md:px-8">
        <h1 className="text-xl font-bold tracking-tight">{current.title}</h1>
        <div className="flex flex-wrap items-center gap-4">
          <Button variant="outline" size="icon" aria-label="Refresh" title="Refresh page" aria-busy={loading || isRefreshing}
            disabled={loading || collectionsLoading || advisoryPending || planningPending || isRefreshing} onClick={() => { void requestRefresh() }}>
            <RefreshCw aria-hidden="true" className={loading || isRefreshing ? 'motion-safe:animate-spin' : undefined} />
          </Button>
          <span role="status" className={`flex items-center gap-2 text-xs font-medium ${simulation ? 'text-amber-700 dark:text-amber-400' : !loading && !run.error ? 'text-emerald-700 dark:text-emerald-400' : 'text-muted-foreground'}`}><span className="size-1.5 rounded-full bg-current" />{mode}</span>
          {simulation && <span className="text-xs text-muted-foreground">Injection stop {simulation.monitoredStopCode}</span>}
          <Button variant="outline" disabled={loading || isRefreshing || advisoryPending || planningPending || !!run.error}
            onClick={() => simulation ? void switchMode() : setSimulationDialogOpen(true)}>{switching ? 'Switching…' : simulation ? 'Return to live' : 'Start simulation'}</Button>
        </div>
      </header>
      <Dialog open={simulationDialogOpen} onOpenChange={open => { if (!switching) setSimulationDialogOpen(open) }}>
        <DialogContent className="max-h-[90vh] overflow-y-auto"><DialogHeader><DialogTitle>Configure simulation</DialogTitle><DialogDescription>Choose one fictional route and fleet. The same buses send updated positions throughout the run.</DialogDescription></DialogHeader>
          <div className="grid gap-4">
            <label className="grid gap-1.5 text-sm font-medium">Route
              <select value={simulationConfig.serviceId} onChange={event => setSimulationConfig(value => {
                const directions = routeStops.data?.filter(route => route.serviceId === event.target.value) ?? []
                const direction = directions.find(route => route.directionId === value.directionId) ?? directions[0]
                return { ...value, serviceId: event.target.value, directionId: direction?.directionId ?? value.directionId,
                  monitoredStopCode: direction?.stops[0]?.stopCode ?? value.monitoredStopCode }
              })} className="h-10 rounded-md border bg-background px-3">
                <option value="190">Service 190</option><option value="972">Service 972</option>
              </select>
            </label>
            <label className="grid gap-1.5 text-sm font-medium">Direction
              <Input type="number" min="1" max="99" value={simulationConfig.directionId} onChange={event => setSimulationConfig(value => {
                const directionId = Number(event.target.value)
                const stop = (routeStops.data?.find(route => route.serviceId === value.serviceId && route.directionId === directionId)?.stops ?? [])[0]
                return { ...value, directionId, monitoredStopCode: stop?.stopCode ?? value.monitoredStopCode }
              })} />
            </label>
            <label className="grid gap-1.5 text-sm font-medium">Buses in this injection
              <Input type="number" min="2" max="20" value={simulationConfig.busCount} onChange={event => setSimulationConfig(value => {
                const busCount = Math.max(2, Math.min(20, Number(event.target.value) || 2))
                return { ...value, busCount, busGapsSeconds: Array.from({ length: busCount - 1 }, (_, index) => value.busGapsSeconds[index] ?? value.dispatchGapSeconds), captainUserIds: Array.from({ length: busCount }, (_, index) => value.captainUserIds[index] ?? '') }
              })} />
            </label>
            <fieldset className="grid gap-2"><legend className="text-sm font-medium">Captain account for each bus</legend>
              <p className="text-xs text-muted-foreground">Search available captain accounts. Assign one to every bus or leave all buses unassigned.</p>
              <label className="grid gap-1.5 text-sm" htmlFor="simulation-captain-search">Search available captains</label>
              <Input id="simulation-captain-search" placeholder="Name or email" value={captainSearch} onChange={event => setCaptainSearch(event.target.value)} />
              {captainsLoading && <p role="status" className="text-xs text-muted-foreground">Searching captains…</p>}
              {captainSearchError && <p role="alert" className="text-xs text-destructive">{captainSearchError}</p>}
              {!captainsLoading && !captainSearchError && !availableCaptains.length && <p className="text-xs text-muted-foreground">No available captains match this search.</p>}
              {simulationConfig.captainUserIds.slice(0, simulationConfig.busCount).map((userId, index) => {
                const usedByAnotherBus = new Set(simulationConfig.captainUserIds.filter((id, otherIndex) => otherIndex !== index && id))
                const options = availableCaptains.filter(captain => captain.id === userId || !usedByAnotherBus.has(captain.id))
                const selectedMissing = userId && !options.some(captain => captain.id === userId)
                return <label key={index} className="grid gap-1.5 text-sm">Bus {index + 1} captain
                  <select aria-label={`Bus ${index + 1} captain`} value={userId} onChange={event => setSimulationConfig(value => ({ ...value, captainUserIds: value.captainUserIds.map((id, itemIndex) => itemIndex === index ? event.target.value : id) }))} className="h-10 rounded-md border bg-background px-3">
                    <option value="">Unassigned</option>
                    {selectedMissing && <option value={userId}>Selected captain ({userId})</option>}
                    {options.map(captain => <option key={captain.id} value={captain.id}>{captain.displayName || captain.name || captain.email} · {captain.email}</option>)}
                  </select>
                </label>
              })}
              {captainAssignmentInvalid && <p role="alert" className="text-xs text-destructive">Choose a different captain for every bus, or leave all buses unassigned.</p>}
            </fieldset>
            <label className="grid gap-1.5 text-sm font-medium">Injection stop
              {simulationStops.length ? <select value={simulationConfig.monitoredStopCode} onChange={event => setSimulationConfig(value => ({ ...value, monitoredStopCode: event.target.value }))} className="h-10 rounded-md border bg-background px-3">
                {simulationStops.map(stop => <option key={stop.stopCode} value={stop.stopCode}>{stop.description || stop.stopCode} ({stop.stopCode})</option>)}
              </select> : <Input value={simulationConfig.monitoredStopCode} onChange={event => setSimulationConfig(value => ({ ...value, monitoredStopCode: event.target.value }))} />}
            </label>
            <label className="grid gap-1.5 text-sm font-medium">Default gap for new buses (seconds)
              <Input type="number" min="15" max="900" value={simulationConfig.dispatchGapSeconds} onChange={event => setSimulationConfig(value => ({ ...value, dispatchGapSeconds: Number(event.target.value) }))} />
            </label>
            <fieldset className="grid gap-2"><legend className="text-sm font-medium">Gaps between individual buses (seconds)</legend>
              <p className="text-xs text-muted-foreground">Each gap is kept at this spacing during normal operation.</p>
              {simulationConfig.busGapsSeconds.map((gap, index) => <label key={index} className="grid gap-1.5 text-sm">Bus {index + 1} → Bus {index + 2}
                <Input aria-label={`Gap from bus ${index + 1} to bus ${index + 2} in seconds`} type="number" min="15" max="900" value={gap} onChange={event => setSimulationConfig(value => ({ ...value,
                  busGapsSeconds: value.busGapsSeconds.map((current, gapIndex) => gapIndex === index ? Number(event.target.value) : current),
                }))} />
              </label>)}
            </fieldset>
            <fieldset className="grid gap-2"><legend className="text-sm font-medium">Scenario</legend>
              {([['NORMAL', 'Normal operation'], ['BUNCHING', 'Bunching']] as const).map(([value, label]) => <label key={value} className="flex items-center gap-2 text-sm"><input type="radio" name="simulation-scenario" checked={simulationConfig.scenario === value} onChange={() => setSimulationConfig(config => {
                if (value !== 'NORMAL') return { ...config, scenario: value }
                return { ...config, scenario: value, dispatchGapSeconds: config.dispatchGapSeconds <= 90 ? 120 : config.dispatchGapSeconds,
                  busGapsSeconds: config.busGapsSeconds.map(gap => gap <= 90 ? 120 : gap) }
              })} />{label}</label>)}
            </fieldset>
          </div>
          <DialogFooter><Button variant="outline" onClick={() => setSimulationDialogOpen(false)}>Cancel</Button><Button disabled={captainAssignmentInvalid} onClick={startSimulation}>Start simulation</Button></DialogFooter>
        </DialogContent>
      </Dialog>
      <div className="mx-auto max-w-7xl space-y-7 p-5 md:p-8">
        <PageRefreshStatus lastSuccessAt={lastPageRefresh} intervalSeconds={controllerRefreshIntervalSeconds} />
        {run.error && !loading && <p role="alert" className="text-sm text-destructive">{run.error} Simulation state could not be verified.</p>}
        {modeError && <p role="alert" className="text-sm text-destructive">{modeError}</p>}
        {pathname.endsWith('/captains') && <ControllerCaptainsPage token={token} />}
        {pathname === '/controller' && <>
          <div className="grid gap-4 sm:grid-cols-3">
            {[
              { title: 'Current arrivals', value: arrivalCollection.latestPage?.totalItems,
                error: arrivalCollection.error && !arrivalCollection.items.length ? 'Unable to load this data.' : '', note: 'Live arrival records', to: '#arrivals' },
              { title: simulation ? 'Simulation detections' : 'Bunching detections', value: simulation ? simulation.alerts.length : alertCollection.latestPage?.totalItems,
                error: run.error || (!simulation && alertCollection.error !== null && !alertCollection.items.length), note: 'View disruptions →', to: '/controller/disruptions' },
              { title: 'Awaiting decision', value: pending,
                error: run.error || recommendationCollection.error !== null || !!recommendationPage && !recommendationMatches,
                note: 'Open decision queue →', to: '/controller/planning' },
            ].map(item => <Link key={item.title} to={item.to} className="rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-ring"><Card className="h-full hover:bg-muted/30"><CardContent className="space-y-3 p-1 px-5"><p className="text-sm text-muted-foreground">{item.title}</p><p className="text-3xl font-semibold tabular-nums">{loading || item.error ? '—' : item.value ?? '—'}</p><p className="text-xs text-muted-foreground">{item.error ? 'Unavailable · refresh to retry' : item.note}</p></CardContent></Card></Link>)}
          </div>
          <ControllerRouteStrip alerts={alertResource} run={run} stops={routeStops} positions={routePositions}
            arrivals={routeArrivals} pollIntervalSeconds={pollIntervalSeconds}
            incidentGapToleranceSeconds={Math.max(30, routeIncidentGapToleranceSeconds)} lastUpdated={arrivalCollection.lastSuccessAt ?? ''} loading={loading || alertCollection.isLoading}
            fetchStopArrivals={fetchStopArrivals} fetchStopName={fetchStopName} />
          <ControllerOverview collection={arrivalCollection} search={arrivalStopName} onSearch={setArrivalStopName}
            sortBy={arrivalSortBy} sortDirection={arrivalSortDirection} onSort={column => {
              setArrivalSortDirection(arrivalSortBy === column ? arrivalSortDirection === 'asc' ? 'desc' : 'asc' : 'asc')
              setArrivalSortBy(column)
            }} />
        </>}
        {pathname.endsWith('/disruptions') && <ControllerDisruptions liveAlerts={alertCollection} run={run} loading={loading}
          routes={routeStops.data ?? []} positions={routePositions.data ?? []} pollIntervalSeconds={pollIntervalSeconds}
          gapToleranceSeconds={Math.max(30, routeIncidentGapToleranceSeconds)} />}
        {pathname.endsWith('/planning') && <ControllerPlanning recommendations={recommendationCollection} diagnosisRequests={diagnosisCollection}
          loading={loading} modeError={run.error} mode={modeValue} demoRunId={simulation?.id ?? null} token={token}
          detectedAlerts={simulation ? simulation.alerts : alertCollection.items}
          positions={routePositions.data ?? []}
          routes={routeStops.data ?? []} pollIntervalSeconds={pollIntervalSeconds}
          gapToleranceSeconds={Math.max(30, routeIncidentGapToleranceSeconds)}
          nowMs={Date.parse(alertCollection.lastSuccessAt || (simulation ? shellRefreshTime.at : '') || '') || 0}
          onPendingChange={setPlanningPending} onRefresh={() => { void requestRefresh() }} onSaved={() => { void requestRefresh('mutation') }} />}
        {pathname.endsWith('/audit') && <ControllerAudit decisions={decisionCollection} suggestions={suggestionCollection} aiAudits={aiAuditCollection} loading={loading} />}
        {pathname !== '/controller' && !pathname.endsWith('/captains') && loaded === requestKey && <ControllerAdvisories key={`${token}:${pathname}:${simulation?.id ?? 'LIVE'}`} token={token}
          registerRefresh={registerAdvisoryRefresh} onRefresh={() => { void requestRefresh('mutation') }} onPendingChange={setAdvisoryPending} onRefreshTimeChange={reportAdvisoryRefresh}
          view={pathname.endsWith('/disruptions') ? 'hazards' : pathname.endsWith('/planning') ? 'planning' : pathname.endsWith('/notices') ? 'notices' : 'audit'} />}
      </div>
    </div>
  </div>
}

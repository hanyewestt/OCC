import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ApiError, apiRequest, enrichBunchingStops, routeDirections, type Arrival, type BunchingAlert, type BusRoute, type Decision, type DemoRun, type PageResult, type PendingRecommendation, type RouteDirection, type RoutePositions, type PlanningDiagnosisPage } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { ControllerSidebar, controllerPages } from '@/components/ControllerSidebar'
import { ControllerOverview, ControllerDisruptions, ControllerAudit } from '@/pages/ControllerViews'
import { ControllerRouteStrip } from '@/pages/ControllerRouteStrip'
import { ControllerPlanning } from '@/pages/ControllerPlanning'
import { ControllerAdvisories } from '@/pages/ControllerAdvisories'
import { formatSgtTime } from '@/lib/time'
import { usePagedCollection } from '@/lib/paged-collection'

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
  const [refresh, setRefresh] = useState(0)
  const [arrivalStopName, setArrivalStopName] = useState('')
  const [arrivalSortBy, setArrivalSortBy] = useState<'arrival' | 'stopName'>('arrival')
  const [arrivalSortDirection, setArrivalSortDirection] = useState<'asc' | 'desc'>('asc')
  const [routeArrivals, setRouteArrivals] = useState<Resource<Arrival[]>>(empty)
  const [routeStops, setRouteStops] = useState<Resource<RouteDirection[]>>(empty)
  const [routePositions, setRoutePositions] = useState<Resource<RoutePositions[]>>(empty)
  const [run, setRun] = useState<Resource<DemoRun>>(empty)
  const [loaded, setLoaded] = useState('')
  const [lastUpdated, setLastUpdated] = useState('')
  const [switching, setSwitching] = useState(false)
  const [modeError, setModeError] = useState('')
  const modeRequestRef = useRef<AbortController | null>(null)
  const switchingRef = useRef(false)
  const requestKey = `${token}:${refresh}`
  const loading = loaded !== requestKey || switching
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
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
  }, arrival => arrival.observationId)
  const reloadArrivals = arrivalCollection.reload
  const scopeKey = `${token}:${refresh}:${simulation?.id ?? 'LIVE'}`
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
  }, alert => alert.eventId)
  const reloadAlerts = alertCollection.reload
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
  }, recommendation => recommendation.recommendationId)
  const decisionCollection = usePagedCollection<Decision, PageResult<Decision>>(`${token}:${refresh}`, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<Decision>>(`/api/v1/planning/recommendations/decisions?${params}`, token, signal)
      if (!result) throw new Error('Decision response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, decision => decision.recommendationId)
  const diagnosisCollection = usePagedCollection<PlanningDiagnosisPage['items'][number], PlanningDiagnosisPage>(scopeKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PlanningDiagnosisPage>(`/api/v1/planning/diagnoses?${params}`, token, signal)
      if (!result) throw new Error('Diagnosis response was empty.')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
      throw error
    }
  }, diagnosis => diagnosis.requestId)
  const requestLiveData = useCallback(async (signal: AbortSignal): Promise<LiveData | null> => {
    return loadLiveData(token, signal)
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
  useEffect(() => {
    const controller = new AbortController()
    const signal = controller.signal
    const runPromise = readResource(apiRequest<DemoRun>('/api/v1/demo-runs/active', token, signal).then(async value =>
      value ? { ...value, alerts: await enrichBunchingStops(value.alerts, token, signal) } : null), signal)
    const routePromise = Promise.all(['190', '972'].map(serviceId =>
      readResource(apiRequest<BusRoute>(`/api/v1/bus-routes/${serviceId}`, token, signal), signal)))
      .then(results => ({
        data: routeDirections(results.flatMap(result => result.data ? [result.data] : [])),
        error: results.some(result => result.error) ? 'Unable to load all route references.' : '',
      }))
    void Promise.all([requestLiveData(signal), runPromise, routePromise])
      .then(([live, nextRun, nextRouteStops]) => {
      if (signal.aborted) return
      if (live) { setRouteArrivals(live.routeArrivals); setRoutePositions(live.positions) }
      setRun(previous => nextRun.error && previous.data ? { ...previous, error: nextRun.error } : nextRun)
      setRouteStops(nextRouteStops); setLastUpdated(new Date().toISOString())
      setLoaded(requestKey)
    })
    return () => controller.abort()
  }, [token, refresh, requestKey, requestLiveData])

  const pollIntervalSeconds = routePositions.data?.length
    ? Math.min(...routePositions.data.map(value => value.refreshIntervalSeconds)) : ingestionPollIntervalSeconds
  useEffect(() => {
    if (pathname !== '/controller') return
    const controller = new AbortController()
    const refreshAll = async () => {
      const live = await requestLiveData(controller.signal)
      if (!live || controller.signal.aborted) return
      reloadArrivals()
      reloadAlerts(); setRouteArrivals(live.routeArrivals)
      setRoutePositions(previous => {
        const byService = new Map((previous.data ?? []).map(snapshot => [snapshot.serviceId, snapshot]))
        for (const snapshot of live.positions.data ?? []) byService.set(snapshot.serviceId, snapshot)
        return { data: byService.size ? [...byService.values()] : null, error: live.positions.error }
      })
      setLastUpdated(new Date().toISOString())
    }
    const timer = window.setInterval(() => {
      void refreshAll()
    }, controllerRefreshIntervalSeconds * 1000)
    return () => { window.clearInterval(timer); controller.abort() }
  }, [pathname, requestLiveData, controllerRefreshIntervalSeconds, reloadArrivals, reloadAlerts])

  useEffect(() => {
    if (pathname !== '/controller/planning') return
    const timer = window.setInterval(() => {
      if (!switchingRef.current) setRefresh(value => value + 1)
    }, controllerRefreshIntervalSeconds * 1000)
    return () => window.clearInterval(timer)
  }, [pathname, controllerRefreshIntervalSeconds])

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

  async function switchMode() {
    if (loading || run.error || switchingRef.current) return
    switchingRef.current = true
    setSwitching(true); setModeError('')
    const controller = new AbortController()
    modeRequestRef.current = controller
    const path = simulation ? `/api/v1/demo-runs/${encodeURIComponent(simulation.id)}/close` : '/api/v1/demo-runs'
    try {
      await apiRequest<DemoRun>(path, token, controller.signal, { method: 'POST' })
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
        setRefresh(value => value + 1)
      }
    }
  }

  return <div className="flex min-h-svh w-full bg-background">
    <ControllerSidebar email={email} />
    <div className="min-w-0 flex-1">
      <header className="sticky top-0 z-10 flex min-h-16 flex-wrap items-center justify-between gap-3 border-b bg-background px-5 py-3 md:px-8">
        <p className="text-sm text-muted-foreground">Operations control</p>
        <div className="flex flex-wrap items-center gap-4"><span role="status" className={`flex items-center gap-2 text-xs font-medium ${simulation ? 'text-amber-700 dark:text-amber-400' : !loading && !run.error ? 'text-emerald-700 dark:text-emerald-400' : 'text-muted-foreground'}`}><span className="size-1.5 rounded-full bg-current" />{mode}</span>
          <Button variant="outline" disabled={loading || !!run.error} onClick={() => void switchMode()}>{switching ? 'Switching…' : simulation ? 'Return to live' : 'Start simulation'}</Button>
        </div>
      </header>
      <div className="mx-auto max-w-7xl space-y-7 p-5 md:p-8">
        <div className="flex flex-wrap items-start justify-between gap-4"><div><h1 className="text-2xl font-semibold tracking-tight">{current.title}</h1></div>
          <Button variant="outline" disabled={loading} onClick={() => {
            reloadArrivals()
            setRefresh(value => value + 1)
          }}>{loading ? 'Refreshing…' : 'Refresh'}</Button></div>
        <p className="text-xs text-muted-foreground">Last updated {formatSgtTime(lastUpdated)} · Refreshes every {controllerRefreshIntervalSeconds} seconds</p>
        {run.error && !loading && <p role="alert" className="text-sm text-destructive">{run.error} Simulation state could not be verified.</p>}
        {modeError && <p role="alert" className="text-sm text-destructive">{modeError}</p>}
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
            incidentGapToleranceSeconds={routeIncidentGapToleranceSeconds} lastUpdated={lastUpdated} loading={loading || alertCollection.isLoading}
            fetchStopArrivals={fetchStopArrivals} fetchStopName={fetchStopName} />
          <ControllerOverview collection={arrivalCollection} search={arrivalStopName} onSearch={setArrivalStopName}
            sortBy={arrivalSortBy} sortDirection={arrivalSortDirection} onSort={column => {
              setArrivalSortDirection(arrivalSortBy === column ? arrivalSortDirection === 'asc' ? 'desc' : 'asc' : 'asc')
              setArrivalSortBy(column)
            }} />
        </>}
        {pathname.endsWith('/disruptions') && <ControllerDisruptions liveAlerts={alertCollection} run={run} loading={loading} />}
        {pathname.endsWith('/planning') && <ControllerPlanning recommendations={recommendationCollection} diagnoses={diagnosisCollection}
          loading={loading} modeError={run.error} mode={modeValue} demoRunId={simulation?.id ?? null} token={token}
          onRefresh={() => setRefresh(value => value + 1)} onSaved={() => setRefresh(value => value + 1)} />}
        {pathname.endsWith('/audit') && <ControllerAudit decisions={decisionCollection} loading={loading} />}
        {pathname !== '/controller' && <ControllerAdvisories key={`${token}:${pathname}:${simulation?.id ?? 'LIVE'}`} token={token}
          refreshIntervalSeconds={controllerRefreshIntervalSeconds}
          view={pathname.endsWith('/disruptions') ? 'hazards' : pathname.endsWith('/planning') ? 'planning' : 'audit'} />}
      </div>
    </div>
  </div>
}

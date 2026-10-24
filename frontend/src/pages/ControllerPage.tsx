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

async function loadRouteArrivals(token: string, signal: AbortSignal): Promise<Resource<Arrival[]>> {
  const results = await Promise.all(['190', '972'].map(serviceId => readResource(
    apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?serviceId=${serviceId}&page=0&size=200`, token, signal), signal)))
  const items = results.flatMap(result => result.data?.items ?? [])
  const errors = results.flatMap((result, index) => result.error ? [`Service ${['190', '972'][index]}: ${result.error}`] : [])
  return { data: items.length ? items : null, error: errors.join('; ') }
}

type LiveData = {
  arrivals: Resource<PageResult<Arrival>>
  alerts: Resource<PageResult<BunchingAlert>>
  routeArrivals: Resource<Arrival[]>
  positions: Resource<RoutePositions[]>
}

async function loadLiveData(token: string, arrivalPage: number, signal: AbortSignal): Promise<LiveData> {
  const [arrivals, alerts, routeArrivals, positions] = await Promise.all([
    readResource(apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?page=${arrivalPage}&size=50`, token, signal), signal),
    readResource(apiRequest<PageResult<BunchingAlert>>('/api/v1/live-bunching-alerts?size=200', token, signal)
      .then(async value => value ? { ...value, items: await enrichBunchingStops(value.items, token, signal) } : null), signal),
    loadRouteArrivals(token, signal),
    loadRoutePositions(token, signal),
  ])
  return { arrivals, alerts, routeArrivals, positions }
}

export function ControllerPage({ email, token, controllerRefreshIntervalSeconds, ingestionPollIntervalSeconds,
  routeIncidentGapToleranceSeconds }: {
  email: string; token: string; controllerRefreshIntervalSeconds: number; ingestionPollIntervalSeconds: number
  routeIncidentGapToleranceSeconds: number
}) {
  const { pathname } = useLocation()
  const current = controllerPages.find(page => page.path === pathname)!
  const [refresh, setRefresh] = useState(0)
  const [arrivalPage, setArrivalPage] = useState(0)
  const [arrivals, setArrivals] = useState<Resource<PageResult<Arrival>>>(empty)
  const [alerts, setAlerts] = useState<Resource<PageResult<BunchingAlert>>>(empty)
  const [routeArrivals, setRouteArrivals] = useState<Resource<Arrival[]>>(empty)
  const [routeStops, setRouteStops] = useState<Resource<RouteDirection[]>>(empty)
  const [routePositions, setRoutePositions] = useState<Resource<RoutePositions[]>>(empty)
  const [run, setRun] = useState<Resource<DemoRun>>(empty)
  const [planning, setPlanning] = useState<Resource<Planning>>(empty)
  const [diagnoses, setDiagnoses] = useState<Resource<PlanningDiagnosisPage>>(empty)
  const [loaded, setLoaded] = useState('')
  const [lastUpdated, setLastUpdated] = useState('')
  const [switching, setSwitching] = useState(false)
  const [modeError, setModeError] = useState('')
  const modeRequestRef = useRef<AbortController | null>(null)
  const liveRefreshInFlightRef = useRef(false)
  const switchingRef = useRef(false)
  const requestKey = `${token}:${refresh}:${arrivalPage}`
  const loading = loaded !== requestKey || switching
  const requestLiveData = useCallback(async (signal: AbortSignal): Promise<LiveData | null> => {
    if (liveRefreshInFlightRef.current) return null
    liveRefreshInFlightRef.current = true
    try { return await loadLiveData(token, arrivalPage, signal) }
    finally { liveRefreshInFlightRef.current = false }
  }, [token, arrivalPage])
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
  useEffect(() => () => modeRequestRef.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    const signal = controller.signal
    const runPromise = readResource(apiRequest<DemoRun>('/api/v1/demo-runs/active', token, signal).then(async value =>
      value ? { ...value, alerts: await enrichBunchingStops(value.alerts, token, signal) } : null), signal)
    const planningPromise = readResource((async () => {
      const [recommendations, decisions] = await Promise.all([
        apiRequest<Planning['recommendations']>('/api/v1/planning/recommendations/pending?size=100', token, signal),
        apiRequest<PageResult<Decision>>('/api/v1/planning/recommendations/decisions?size=100', token, signal),
      ])
      return {
        recommendations: recommendations ?? { mode: 'UNKNOWN', demoRunId: null, items: [], page: 0, totalItems: 0, totalPages: 0 },
        decisions: decisions ?? { items: [], page: 0, totalItems: 0, totalPages: 0 },
      }
    })(), signal)
    const routePromise = Promise.all(['190', '972'].map(serviceId =>
      readResource(apiRequest<BusRoute>(`/api/v1/bus-routes/${serviceId}`, token, signal), signal)))
      .then(results => ({
        data: routeDirections(results.flatMap(result => result.data ? [result.data] : [])),
        error: results.some(result => result.error) ? 'Unable to load all route references.' : '',
      }))
    void Promise.all([requestLiveData(signal), runPromise, planningPromise, routePromise,
      readResource(apiRequest<PlanningDiagnosisPage>('/api/v1/planning/diagnoses?size=100', token, signal), signal)])
      .then(([live, nextRun, nextPlanning, nextRouteStops, nextDiagnoses]) => {
      if (signal.aborted) return
      if (live) {
        setArrivals(live.arrivals); setAlerts(live.alerts); setRouteArrivals(live.routeArrivals); setRoutePositions(live.positions)
      }
      const activeRunId = nextRun.data && nextRun.data.status !== 'CLOSED' ? nextRun.data.id : null
      const scopeMatches = !nextRun.error && nextDiagnoses.data?.mode === (activeRunId ? 'DEMO' : 'LIVE')
        && (nextDiagnoses.data?.demoRunId ?? null) === activeRunId
      setDiagnoses(scopeMatches ? nextDiagnoses : { data: null, error: nextDiagnoses.error || 'Data selection changed. Refresh to load the active mode.' })
      const pendingScope = nextPlanning.data?.recommendations
      const pendingMatches = !nextRun.error && pendingScope?.mode === (activeRunId ? 'DEMO' : 'LIVE')
        && (pendingScope.demoRunId ?? null) === activeRunId
      setRun(nextRun); setPlanning(pendingMatches ? nextPlanning : { data: null, error: 'Data selection changed. Refresh before making a decision.' }); setRouteStops(nextRouteStops); setLastUpdated(new Date().toISOString())
      setLoaded(requestKey)
    })
    return () => controller.abort()
  }, [token, refresh, arrivalPage, requestKey, requestLiveData])

  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const pollIntervalSeconds = routePositions.data?.length
    ? Math.min(...routePositions.data.map(value => value.refreshIntervalSeconds)) : ingestionPollIntervalSeconds
  useEffect(() => {
    if (pathname !== '/controller') return
    const controller = new AbortController()
    const refreshAll = async () => {
      const live = await requestLiveData(controller.signal)
      if (!live || controller.signal.aborted) return
      setArrivals(live.arrivals); setAlerts(live.alerts); setRouteArrivals(live.routeArrivals)
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
  }, [pathname, requestLiveData, controllerRefreshIntervalSeconds])

  useEffect(() => {
    if (pathname !== '/controller/planning') return
    const timer = window.setInterval(() => {
      if (!switchingRef.current) setRefresh(value => value + 1)
    }, controllerRefreshIntervalSeconds * 1000)
    return () => window.clearInterval(timer)
  }, [pathname, controllerRefreshIntervalSeconds])

  const pending = planning.data?.recommendations.totalItems
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
      <header className="flex min-h-16 flex-wrap items-center justify-between gap-3 border-b px-5 py-3 md:px-8">
        <p className="text-sm text-muted-foreground">Operations control</p>
        <div className="flex flex-wrap items-center gap-4"><span role="status" className="flex items-center gap-2 text-xs font-medium"><span className="size-1.5 rounded-full bg-current" />{mode}</span>
          <Button variant="outline" disabled={loading || !!run.error} onClick={() => void switchMode()}>{switching ? 'Switching…' : simulation ? 'Return to live' : 'Start simulation'}</Button>
        </div>
      </header>
      <div className="mx-auto max-w-7xl space-y-7 p-5 md:p-8">
        <div className="flex flex-wrap items-start justify-between gap-4"><div className="space-y-1"><h1 className="text-2xl font-semibold tracking-tight">{current.title}</h1><p className="text-sm text-muted-foreground">{pathname === '/controller' ? 'Arrivals, disruptions and decisions at a glance.' : pathname.endsWith('/disruptions') ? 'Bunching detections and traffic disruptions.' : pathname.endsWith('/planning') ? 'Review recommendations before recording a decision.' : 'Saved controller decisions and AI audit availability.'}</p></div>
          <Button variant="outline" disabled={loading} onClick={() => setRefresh(value => value + 1)}>{loading ? 'Refreshing…' : 'Refresh'}</Button></div>
        <p className="text-xs text-muted-foreground">Updates every {controllerRefreshIntervalSeconds} seconds · Last updated {lastUpdated ? new Date(lastUpdated).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'}. {simulation && 'Simulation detections are separate from live arrivals.'}</p>
        {run.error && !loading && <p role="alert" className="text-sm text-destructive">{run.error} Simulation state could not be verified.</p>}
        {modeError && <p role="alert" className="text-sm text-destructive">{modeError}</p>}
        {pathname === '/controller' && <>
          <div className="grid gap-4 sm:grid-cols-3">
            {[
              { title: 'Current arrivals', value: arrivals.data?.totalItems, error: arrivals.error, note: 'Live arrival records', to: '#arrivals' },
              { title: simulation ? 'Simulation detections' : 'Bunching detections', value: simulation ? simulation.alerts.length : alerts.data?.totalItems, error: run.error || (!simulation && alerts.error), note: 'View disruptions →', to: '/controller/disruptions' },
              { title: 'Awaiting decision', value: planning.data ? pending : undefined, error: planning.error, note: 'Open decision queue →', to: '/controller/planning' },
            ].map(item => <Link key={item.title} to={item.to} className="rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-ring"><Card className="h-full hover:bg-muted/30"><CardContent className="space-y-3 p-1 px-5"><p className="text-sm text-muted-foreground">{item.title}</p><p className="text-3xl font-semibold tabular-nums">{loading || item.error ? '—' : item.value ?? '—'}</p><p className="text-xs text-muted-foreground">{item.error ? 'Unavailable · refresh to retry' : item.note}</p></CardContent></Card></Link>)}
          </div>
          <ControllerRouteStrip alerts={alerts} run={run} stops={routeStops} positions={routePositions}
            arrivals={routeArrivals} pollIntervalSeconds={pollIntervalSeconds}
            incidentGapToleranceSeconds={routeIncidentGapToleranceSeconds} lastUpdated={lastUpdated}
            controllerRefreshIntervalSeconds={controllerRefreshIntervalSeconds} loading={loading}
            fetchStopArrivals={fetchStopArrivals} />
          <ControllerOverview arrivals={arrivals} loading={loading} page={arrivalPage} onPage={setArrivalPage} />
        </>}
        {pathname.endsWith('/disruptions') && <ControllerDisruptions alerts={alerts} run={run} loading={loading} />}
        {pathname.endsWith('/planning') && <ControllerPlanning planning={planning} diagnoses={diagnoses} loading={loading} token={token} onSaved={() => setRefresh(value => value + 1)} />}
        {pathname.endsWith('/audit') && <ControllerAudit planning={planning} loading={loading} />}
      </div>
    </div>
  </div>
}

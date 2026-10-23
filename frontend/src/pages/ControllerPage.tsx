import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ApiError, apiRequest, enrichBunchingStops, type Arrival, type BunchingAlert, type Decision, type DemoRun, type PageResult, type PendingRecommendation } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { ControllerSidebar, controllerPages } from '@/components/ControllerSidebar'
import { ControllerOverview, ControllerDisruptions, ControllerAudit } from '@/pages/ControllerViews'
import { ControllerPlanning } from '@/pages/ControllerPlanning'

export type Resource<T> = { data: T | null; error: string }
export type Planning = { recommendations: PageResult<PendingRecommendation>; decisions: PageResult<Decision> }
const empty = { data: null, error: '' }

export function ControllerPage({ email, token }: { email: string; token: string }) {
  const { pathname } = useLocation()
  const current = controllerPages.find(page => page.path === pathname)!
  const [refresh, setRefresh] = useState(0)
  const [arrivalPage, setArrivalPage] = useState(0)
  const [arrivals, setArrivals] = useState<Resource<PageResult<Arrival>>>(empty)
  const [alerts, setAlerts] = useState<Resource<PageResult<BunchingAlert>>>(empty)
  const [run, setRun] = useState<Resource<DemoRun>>(empty)
  const [planning, setPlanning] = useState<Resource<Planning>>(empty)
  const [loaded, setLoaded] = useState('')
  const [switching, setSwitching] = useState(false)
  const [modeError, setModeError] = useState('')
  const modeRequestRef = useRef<AbortController | null>(null)
  const switchingRef = useRef(false)
  const requestKey = `${token}:${refresh}:${arrivalPage}`
  const loading = loaded !== requestKey || switching
  useEffect(() => () => modeRequestRef.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    const signal = controller.signal
    async function read<T>(operation: Promise<T | null>): Promise<Resource<T>> {
      try { return { data: await operation, error: '' } }
      catch (error) {
        if (error instanceof ApiError && error.status === 401 && !signal.aborted) void signOut(error.message)
        return { data: null, error: error instanceof ApiError && error.status === 403 ? 'Access denied for this data.' : 'Unable to load this data.' }
      }
    }
    const runPromise = read(apiRequest<DemoRun>('/api/v1/demo-runs/active', token, signal).then(async value =>
      value ? { ...value, alerts: await enrichBunchingStops(value.alerts, token, signal) } : null))
    const planningPromise = read((async () => {
      const [recommendations, decisions] = await Promise.all([
        apiRequest<PageResult<PendingRecommendation>>('/api/v1/planning/recommendations/pending?size=100', token, signal),
        apiRequest<PageResult<Decision>>('/api/v1/planning/recommendations/decisions?size=100', token, signal),
      ])
      return {
        recommendations: recommendations ?? { items: [], page: 0, totalItems: 0, totalPages: 0 },
        decisions: decisions ?? { items: [], page: 0, totalItems: 0, totalPages: 0 },
      }
    })())
    void Promise.all([
      read(apiRequest<PageResult<Arrival>>(`/api/v1/arrivals?page=${arrivalPage}&size=50`, token, signal)),
      read(apiRequest<PageResult<BunchingAlert>>('/api/v1/live-bunching-alerts?size=200', token, signal).then(async value =>
        value ? { ...value, items: await enrichBunchingStops(value.items, token, signal) } : null)),
      runPromise, planningPromise,
    ]).then(([nextArrivals, nextAlerts, nextRun, nextPlanning]) => {
      if (signal.aborted) return
      setArrivals(nextArrivals); setAlerts(nextAlerts); setRun(nextRun); setPlanning(nextPlanning)
      setLoaded(requestKey)
    })
    return () => controller.abort()
  }, [token, refresh, arrivalPage, requestKey])

  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
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
        <p className="text-xs text-muted-foreground">Updates on refresh. {simulation && 'Simulation detections are separate from live arrivals.'}</p>
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
          <ControllerOverview arrivals={arrivals} loading={loading} page={arrivalPage} onPage={setArrivalPage} />
        </>}
        {pathname.endsWith('/disruptions') && <ControllerDisruptions alerts={alerts} run={run} loading={loading} />}
        {pathname.endsWith('/planning') && <ControllerPlanning planning={planning} loading={loading} token={token} onSaved={() => setRefresh(value => value + 1)} />}
        {pathname.endsWith('/audit') && <ControllerAudit planning={planning} loading={loading} />}
      </div>
    </div>
  </div>
}

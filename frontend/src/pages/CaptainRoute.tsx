import { useCallback, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { ApiError, apiRequest, type CaptainRoute, type CaptainRouteStop } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { compactStops, distanceText, routeProgress, stopName } from '@/lib/captain-route'
import { formatSgtClock, formatSgtTime } from '@/lib/time'
import { CaptainPacing } from './CaptainPacing'

const ROUTE_PATH = '/api/v1/captain-routes/current'
// Like an on-board unit, show the next few stops and the terminal; the rest is one tap away.
const COMPACT_STOPS_AHEAD = 3

type RouteState = { status: 'loading' } | { status: 'unassigned' } | { status: 'error' } | { status: 'ready'; route: CaptainRoute }

function arrivalText(stop: CaptainRouteStop): string | null {
  if (!stop.arrivalAt) return null
  return stop.arrivalKind === 'ESTIMATED' ? `${formatSgtClock(stop.arrivalAt)} est.` : formatSgtClock(stop.arrivalAt)
}

function AheadStop({ stop, terminal = false }: { stop: CaptainRouteStop; terminal?: boolean }) {
  const arrival = arrivalText(stop)
  return <li data-state={terminal ? 'terminal' : 'ahead'}
    className={terminal ? 'flex items-center gap-3 border-t-2 px-4 pt-3 pb-2 text-lg font-bold' : 'flex items-center gap-3 px-4 py-2 text-lg'}>
    <span aria-hidden="true" className={terminal ? 'size-3 shrink-0 bg-foreground' : 'size-3 shrink-0 rounded-full bg-foreground'} />
    <span className="w-14 shrink-0 text-sm font-normal text-muted-foreground tabular-nums">{stop.stopCode}</span>
    <span className="min-w-0 flex-1 font-semibold">{stopName(stop)}{terminal && <span className="ml-2 text-sm text-muted-foreground">(terminal)</span>}</span>
    {arrival && <span className="text-sm tabular-nums">{arrival}</span>}
  </li>
}

/** The captain's assigned bus route; refreshed by the page's shared refresh cycle. */
export function CaptainRouteView({ token, registerRefresh }: {
  token: string; registerRefresh: (callback: (signal: AbortSignal) => Promise<void>) => void
}) {
  const [state, setState] = useState<RouteState>({ status: 'loading' })
  const [showFullRoute, setShowFullRoute] = useState(false)

  const load = useCallback((signal: AbortSignal) => apiRequest<CaptainRoute>(ROUTE_PATH, token, signal).then(
    route => { if (!signal.aborted) setState(route ? { status: 'ready', route } : { status: 'unassigned' }) },
    failure => {
      if (signal.aborted) return
      if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      // Keep the last route on screen during a failed refresh; it is labelled with its observation time.
      else setState(current => current.status === 'ready' ? current : { status: 'error' })
    }), [token])

  useEffect(() => { registerRefresh(load) }, [load, registerRefresh])
  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  if (state.status === 'loading') return <p role="status" className="text-muted-foreground">Loading your route…</p>
  if (state.status === 'error') return <p role="alert" className="rounded-lg border-2 border-dashed p-4 font-medium">
    Unable to load your route. Use Refresh to try again.</p>
  if (state.status === 'unassigned') return <section aria-labelledby="route-heading" className="space-y-2 rounded-xl border bg-card p-4 sm:p-6">
    <h2 id="route-heading" className="text-xl font-bold">Your route</h2>
    <p>No bus is assigned to you right now. Your bus and route appear here when a controller starts a simulation
      run that assigns you a bus.</p>
  </section>

  const { route } = state
  const progress = routeProgress(route)
  const known = route.position.source === 'SIMULATION'
  const terminal = route.stops[route.stops.length - 1]
  const compact = compactStops(progress.ahead, COMPACT_STOPS_AHEAD)
  const listed = showFullRoute ? progress.ahead : compact.shown
  const nextArrival = progress.next ? arrivalText(progress.next) : null
  const nextDistance = progress.last && progress.next ? distanceText(progress.last, progress.next) : null
  return <><section aria-labelledby="route-heading" className="space-y-4 rounded-xl border bg-card p-4 sm:p-6">
    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
      <h2 id="route-heading" className="text-[clamp(1.25rem,3.5vw,2rem)] font-black">
        Service {route.bus.serviceId} <span aria-hidden="true">→</span><span className="sr-only">towards</span> {terminal ? stopName(terminal) : 'terminal'}</h2>
      <p className="text-muted-foreground">Bus {route.bus.registrationNumber} · Direction {route.bus.directionId} · {route.stops.length} stops</p>
      <span className="border-2 border-amber-700 px-2 py-0.5 text-xs font-bold sm:ml-auto dark:border-amber-400">SIMULATION</span>
    </div>

    {known ? <div aria-live="polite" className="grid gap-3 md:grid-cols-[1fr_2fr]">
      <div className="rounded-lg border-2 p-4">
        <p className="text-sm font-bold tracking-wide text-muted-foreground uppercase">Now leaving</p>
        <p className="text-xl font-bold">{progress.last ? stopName(progress.last) : 'Start of route'}</p>
        {progress.last && <p className="text-sm text-muted-foreground tabular-nums">{progress.last.stopCode}</p>}
      </div>
      <div className="rounded-lg border-4 border-blue-600 p-4 dark:border-blue-400">
        <p className="text-sm font-bold tracking-wide text-blue-700 uppercase dark:text-blue-300">Next stop</p>
        <p className="text-[clamp(1.75rem,5vw,3rem)] leading-tight font-black">{progress.next ? stopName(progress.next) : 'End of route'}</p>
        {progress.next && <p className="text-lg text-muted-foreground tabular-nums">{progress.next.stopCode}
          {nextDistance && ` · ${nextDistance}`}{nextArrival && ` · due ${nextArrival}`}</p>}
      </div>
    </div> : <p role="note" className="rounded-lg border-2 border-dashed p-4 font-medium">
      Position unavailable: no recent simulation progress for this bus. The full route is shown.</p>}

    <ol aria-label="Route stops" className="space-y-1">
      {progress.passedCount > 0 && <li className="px-4 py-1 text-sm text-muted-foreground">
        <span aria-hidden="true">✓ </span>{progress.passedCount} earlier {progress.passedCount === 1 ? 'stop' : 'stops'} passed</li>}
      {progress.last && <li data-state="passed" className="flex items-center gap-3 px-4 py-2 text-muted-foreground">
        <span aria-hidden="true">✓</span><span className="w-14 shrink-0 text-sm tabular-nums">{progress.last.stopCode}</span>
        <span className="min-w-0 flex-1">{stopName(progress.last)}</span><span className="text-sm">LEFT</span></li>}
      {progress.next && <li data-state="next" aria-current="location"
        className="flex items-center gap-3 rounded-lg border-4 border-blue-600 px-3 py-2 text-lg font-bold dark:border-blue-400">
        <span aria-hidden="true">➜</span><span className="w-14 shrink-0 text-sm tabular-nums">{progress.next.stopCode}</span>
        <span className="min-w-0 flex-1">{stopName(progress.next)}</span>
        <span className="text-sm tabular-nums">{nextArrival ?? 'NEXT'}</span></li>}
      {listed.map(stop => <AheadStop key={`${stop.sequence}-${stop.stopCode}`} stop={stop} />)}
      {!showFullRoute && compact.hiddenCount > 0 && <li className="px-4 py-1 text-sm text-muted-foreground">
        <span aria-hidden="true">⋮ </span>{compact.hiddenCount} more {compact.hiddenCount === 1 ? 'stop' : 'stops'}</li>}
      {!showFullRoute && compact.terminal && <AheadStop stop={compact.terminal} terminal />}
    </ol>
    {compact.terminal && <Button variant="outline" aria-expanded={showFullRoute}
      onClick={() => setShowFullRoute(value => !value)}>{showFullRoute ? 'Show fewer stops' : 'Show full route'}</Button>}
    <p className="text-sm text-muted-foreground">
      {route.position.observedAt && `Position from ${formatSgtTime(route.position.observedAt)}. `}
      Times marked est. are estimated from route distance.</p>
  </section>
  <CaptainPacing pacing={route.pacing} /></>
}

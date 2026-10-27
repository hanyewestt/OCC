import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import { signOut } from '@/lib/auth'
import { cn } from '@/lib/utils'
import { classify, clockText, deltaText, gapFlex, initialScenario, sampleGaps,
  scenarios, SAMPLE_REFRESH_MS, SAMPLE_TARGET_SECONDS, type GapState, type Scenario } from '@/lib/obu-pacing'
import { groupStops, sampleUpcomingStops, skipLabel } from '@/lib/skip-stops'
import { CaptainInstructions } from './CaptainInstructions'
import { usePageRefresh } from '@/lib/page-refresh'
import { PageRefreshStatus } from '@/components/PageRefreshStatus'

const states: Record<GapState, { symbol: string; label: string; color: string }> = {
  close: { symbol: '▲', label: 'TOO CLOSE', color: 'text-red-700 dark:text-red-400' },
  ok: { symbol: '●', label: 'ON TARGET', color: 'text-green-700 dark:text-green-400' },
  far: { symbol: '▼', label: 'TOO FAR', color: 'text-amber-800 dark:text-amber-400' },
  unknown: { symbol: '?', label: 'NO DATA', color: 'text-muted-foreground' },
}

function initialTheme(): 'day' | 'night' {
  try { return localStorage.getItem('occ-obu-theme') === 'day' ? 'day' : 'night' }
  catch { return 'night' }
}

function GapTile({ side, seconds }: { side: 'ahead' | 'behind'; seconds: number | null }) {
  const state = classify(seconds, SAMPLE_TARGET_SECONDS)
  const info = states[state]
  return <Card role="region" aria-label={`Gap to bus ${side}`} data-state={state}
    className={cn('gap-3 border-4 border-current p-5 shadow-none', info.color,
      (state === 'far' || state === 'unknown') && 'border-dashed', side === 'ahead' && 'max-md:order-first')}>
    <h2 className="text-lg font-bold tracking-wide text-muted-foreground uppercase">Gap to bus {side}</h2>
    <p className="text-[clamp(4rem,10vw,9rem)] leading-none font-black text-foreground tabular-nums">{clockText(seconds)}</p>
    <p className="text-2xl font-black sm:text-3xl"><span aria-hidden="true">{info.symbol} </span>{info.label}</p>
    <p className="text-lg font-semibold text-foreground">{deltaText(seconds, SAMPLE_TARGET_SECONDS)}</p>
  </Card>
}

function RoadGap({ seconds }: { seconds: number | null }) {
  const state = classify(seconds, SAMPLE_TARGET_SECONDS)
  return <div className={cn('min-w-6 basis-0 text-center', states[state].color)}
    style={{ flexGrow: gapFlex(seconds, SAMPLE_TARGET_SECONDS) }}>
    <span className="hidden text-sm font-bold tabular-nums sm:block">{clockText(seconds)}</span>
    <div className={cn('mt-1 border-t-8 border-current', (state === 'far' || state === 'unknown') && 'border-dashed')} />
  </div>
}

function UpcomingStops() {
  const segments = groupStops(sampleUpcomingStops)
  const skipped = sampleUpcomingStops.filter(stop => stop.skipped).length
  return <section aria-labelledby="upcoming-stops-heading" className="space-y-4 rounded-xl border bg-card p-4 sm:p-6">
    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
      <h2 id="upcoming-stops-heading" className="text-xl font-bold">Upcoming stops</h2>
      <p className="text-sm text-muted-foreground">Sample stops · no live instructions.</p>
      <p className="text-lg font-semibold sm:ml-auto">{skipped === 0 ? 'No stops to skip' : `${skipLabel(skipped)} on this trip`}</p>
    </div>
    <ScrollArea role="region" aria-label="Upcoming stops in route order" tabIndex={0} className="max-h-[26rem]">
    <ol aria-label="Upcoming stops in route order" className="space-y-2 p-1 pr-3.5">
      {segments.map(segment => segment.kind === 'stop'
        ? <li key={segment.stop.stopCode} className="flex items-center gap-3 px-4 py-2 text-lg">
          <span aria-hidden="true" className="size-3 shrink-0 rounded-full bg-foreground" />
          <span className="w-14 shrink-0 text-sm text-muted-foreground tabular-nums">{segment.stop.stopCode}</span>
          <span className="min-w-0 flex-1 font-semibold">{segment.stop.name}</span>
          <span className="text-sm font-bold">STOP</span>
        </li>
        : <li key={segment.stops[0].stopCode}
          className="rounded-lg border-4 border-amber-700 px-4 py-3 text-amber-800 dark:border-amber-400 dark:text-amber-400">
          <p className="text-xl font-black uppercase sm:text-2xl"><span aria-hidden="true">✕ </span>{skipLabel(segment.stops.length)}</p>
          <ol aria-label={skipLabel(segment.stops.length)} className="mt-2 space-y-1">
            {segment.stops.map(stop => <li key={stop.stopCode} className="flex items-center gap-3 text-lg text-foreground">
              <span className="w-14 shrink-0 text-sm text-muted-foreground tabular-nums">{stop.stopCode}</span>
              <s className="min-w-0 flex-1 decoration-2">{stop.name}</s>
              <span className="sr-only">(skipped)</span>
            </li>)}
          </ol>
        </li>)}
    </ol></ScrollArea>
  </section>
}

export function CaptainPage({ email, token, refreshIntervalSeconds }: { email: string; token: string; refreshIntervalSeconds: number }) {
  const { search } = useLocation()
  const [sample, setSample] = useState(() => ({ scenario: initialScenario(search), tick: 0 }))
  const [theme, setTheme] = useState(initialTheme)
  const [instructionPending, setInstructionPending] = useState(false)
  const [lastInstructionRefresh, setLastInstructionRefresh] = useState<string | null>(null)
  const instructionRefresh = useRef<(signal: AbortSignal) => Promise<void>>(() => Promise.resolve())
  const { requestRefresh, isRefreshing } = usePageRefresh(refreshIntervalSeconds, signal => instructionRefresh.current(signal), instructionPending, token)
  const gaps = sampleGaps(sample.scenario, sample.tick)

  useEffect(() => {
    const timer = window.setInterval(() => setSample(current => ({ ...current, tick: current.tick + 1 })), SAMPLE_REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    try { localStorage.setItem('occ-obu-theme', theme) }
    catch { /* Theme remains usable when browser storage is unavailable. */ }
  }, [theme])

  return <section aria-label="Captain pacing display" data-theme={theme}
    className={cn('w-full max-w-7xl space-y-6 rounded-xl border bg-background p-4 text-foreground sm:p-6', theme === 'night' && 'dark')}>
    <header className="flex flex-wrap items-center justify-between gap-4 border-b pb-4">
      <div className="space-y-3"><h1 className="text-2xl font-semibold">Captain</h1>
        <PageRefreshStatus lastSuccessAt={lastInstructionRefresh} intervalSeconds={refreshIntervalSeconds} />
        <p role="status" className="text-sm text-muted-foreground">Access verified</p></div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={instructionPending || isRefreshing} onClick={() => { void requestRefresh() }}>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}</Button>
        <Button variant="outline" onClick={() => setTheme(current => current === 'night' ? 'day' : 'night')}>
          {theme === 'night' ? 'Day mode' : 'Night mode'}</Button>
        <Button variant="outline" onClick={() => void signOut()}>Sign out</Button>
      </div>
    </header>

    <CaptainInstructions key={token} token={token} registerRefresh={callback => { instructionRefresh.current = callback }}
      onPendingChange={setInstructionPending} onSaved={() => { void requestRefresh('mutation') }} onRefreshTimeChange={setLastInstructionRefresh} />
    <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
      <div><p className="text-xl font-bold">Service 190 · Direction 1</p>
        <p className="text-sm text-muted-foreground">DEMO-190-B</p></div>
      <span className="border-2 border-amber-700 px-3 py-1 text-sm font-bold dark:border-amber-400">SAMPLE DATA</span>
      <p className="text-lg sm:ml-auto">Target gap <strong className="tabular-nums">{clockText(SAMPLE_TARGET_SECONDS)}</strong></p>
    </div>

    <div aria-hidden="true" className="space-y-2 rounded-xl border bg-card p-4">
      <div className="flex justify-between text-xs font-semibold text-muted-foreground sm:text-sm">
        <span>Bus behind</span><span>Direction of travel →</span><span>Bus ahead</span>
      </div>
      <div className="flex items-center gap-2 font-bold">
        <span className={cn('rounded-md border-2 px-2 py-2 text-xs sm:px-4', gaps.behind === null && 'border-dashed opacity-40')}>BUS</span>
        <RoadGap seconds={gaps.behind} />
        <span className="rounded-md border-2 border-blue-600 bg-blue-600 px-2 py-2 text-xs text-white sm:px-4">YOU</span>
        <RoadGap seconds={gaps.ahead} />
        <span className="rounded-md border-2 px-2 py-2 text-xs sm:px-4">BUS</span>
      </div>
    </div>

    <div className="grid gap-4 md:grid-cols-2">
      <GapTile side="behind" seconds={gaps.behind} />
      <GapTile side="ahead" seconds={gaps.ahead} />
    </div>
    <p className="sr-only" aria-live="assertive" aria-atomic="true">
      Bus ahead: {states[classify(gaps.ahead, SAMPLE_TARGET_SECONDS)].label.toLowerCase()}.
      Bus behind: {states[classify(gaps.behind, SAMPLE_TARGET_SECONDS)].label.toLowerCase()}.
    </p>

    <div role="group" aria-label="Sample scenario" className="flex flex-wrap items-center gap-2">
      <span className="mr-2 text-sm font-medium">Sample scenario:</span>
      {(Object.keys(scenarios) as Scenario[]).map(name => <Button key={name} size="lg"
        variant={sample.scenario === name ? 'default' : 'outline'} aria-pressed={sample.scenario === name}
        onClick={() => setSample({ scenario: name, tick: 0 })}>{scenarios[name].label}</Button>)}
    </div>
    <UpcomingStops />
    <footer className="space-y-1 border-t pt-4 text-sm text-muted-foreground">
      <p>Sample data · no live bus feed.</p>
      <p className="break-all">Signed in as {email} · Role: Captain</p>
    </footer>
  </section>
}

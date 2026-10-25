import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { signOut } from '@/lib/auth'
import { acknowledgeBusInstruction, ApiError, getBusInstructions, type BusInstruction } from '@/lib/api'
import { cn } from '@/lib/utils'
import { classify, clockText, deltaText, gapFlex, initialScenario, sampleGaps,
  scenarios, SAMPLE_REFRESH_MS, SAMPLE_TARGET_SECONDS, type GapState, type Scenario } from '@/lib/obu-pacing'
import { groupStops, sampleUpcomingStops, skipLabel } from '@/lib/skip-stops'

const DEMO_BUS_REFERENCE = 'SBS5898U'

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
      <p className="text-sm text-muted-foreground">Sample skip-stop instructions. No live instructions.</p>
      <p className="text-lg font-semibold sm:ml-auto">{skipped === 0 ? 'No stops to skip' : `${skipLabel(skipped)} on this trip`}</p>
    </div>
    <ol aria-label="Upcoming stops in route order" className="space-y-2">
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
    </ol>
  </section>
}

function instructionTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Unknown time' : new Intl.DateTimeFormat(undefined, {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(date)
}

function InstructionPanel({ token, refreshIntervalSeconds }: { token: string; refreshIntervalSeconds: number }) {
  const [instruction, setInstruction] = useState<BusInstruction | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [confirmationError, setConfirmationError] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [refreshRevision, setRefreshRevision] = useState(0)
  const acknowledgementRequest = useRef<AbortController | null>(null)

  const handleLoadError = useCallback((error: unknown, signal: AbortSignal) => {
    if (signal.aborted) return
    if (error instanceof ApiError && error.status === 401) void signOut(error.message)
    else setLoadError(error instanceof ApiError && error.status === 403
      ? 'This account cannot read bus instructions.'
      : 'Unable to load controller instructions. Try again.')
  }, [])

  useEffect(() => {
    let active = true
    let request: AbortController | null = null
    let inFlight = false
    const refresh = async () => {
      if (inFlight) return
      inFlight = true
      request = new AbortController()
      try {
        const page = await getBusInstructions(DEMO_BUS_REFERENCE, token, request.signal)
        if (!active || request.signal.aborted) return
        const latest = page?.items[0] ?? null
        setInstruction(current => current && latest && current.instructionId === latest.instructionId &&
          current.status === 'ACKNOWLEDGED' && latest.status === 'SENT' ? current : latest)
        setLoadError('')
        setConfirmationError('')
      } catch (error) {
        handleLoadError(error, request.signal)
      } finally {
        if (active) setLoading(false)
        inFlight = false
      }
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), refreshIntervalSeconds * 1000)
    return () => {
      active = false
      request?.abort()
      window.clearInterval(timer)
    }
  }, [handleLoadError, refreshIntervalSeconds, refreshRevision, token])

  useEffect(() => () => acknowledgementRequest.current?.abort(), [])

  const confirm = async () => {
    if (!instruction || instruction.status !== 'SENT' || confirming) return
    acknowledgementRequest.current?.abort()
    const controller = new AbortController()
    acknowledgementRequest.current = controller
    setConfirming(true)
    setConfirmationError('')
    try {
      const acknowledged = await acknowledgeBusInstruction(instruction.instructionId, token, controller.signal)
      if (acknowledged) setInstruction(acknowledged)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        setConfirmationError('Another user already confirmed this instruction. Refresh to see its status.')
      } else if (error instanceof ApiError && error.status === 403) {
        setConfirmationError('This account cannot confirm bus instructions.')
      } else setConfirmationError('Unable to confirm the instruction. Try again.')
    } finally {
      if (!controller.signal.aborted) setConfirming(false)
    }
  }

  return <section aria-labelledby="controller-instruction-heading"
    className="space-y-4 rounded-xl border-4 border-blue-700 bg-card p-4 dark:border-blue-400 sm:p-6">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 id="controller-instruction-heading" className="text-xl font-black uppercase sm:text-2xl">
        Controller instruction
      </h2>
      <span className="text-sm font-bold text-muted-foreground">Bus {DEMO_BUS_REFERENCE}</span>
    </div>
    {loading && !instruction && <p role="status">Loading controller instructions…</p>}
    {loadError && <div className="space-y-3"><p role="alert">{loadError}</p>
      <Button variant="outline" onClick={() => { setLoading(true); setRefreshRevision(value => value + 1) }}>
        Try again
      </Button></div>}
    {!loading && !loadError && !instruction &&
      <p role="status" className="text-lg font-semibold">No controller instruction for this bus.</p>}
    {instruction && <div className="space-y-4">
      <p className="text-2xl font-black leading-snug sm:text-3xl">{instruction.instruction}</p>
      <p className="text-sm text-muted-foreground">Sent at {instructionTime(instruction.sentAt)}</p>
      {instruction.status === 'SENT' ? <Button size="lg" className="min-h-14 w-full text-lg font-black sm:w-auto"
        disabled={confirming} onClick={() => void confirm()}>
        {confirming ? 'Confirming…' : 'Confirm instruction'}
      </Button> : <p role="status" className="text-lg font-bold text-green-700 dark:text-green-400">
        Instruction confirmed at {instructionTime(instruction.acknowledgedAt!)}
      </p>}
      {confirmationError && <div className="space-y-3"><p role="alert">{confirmationError}</p>
        <Button variant="outline" onClick={() => void confirm()} disabled={confirming}>Try confirmation again</Button>
      </div>}
    </div>}
    <p className="sr-only" aria-live="polite" aria-atomic="true">
      {instruction?.status === 'SENT' ? `New controller instruction: ${instruction.instruction}` :
        instruction?.status === 'ACKNOWLEDGED' ? 'Controller instruction confirmed.' : ''}
    </p>
  </section>
}

export function CaptainPage({ email, token, refreshIntervalSeconds }: {
  email: string; token: string; refreshIntervalSeconds: number
}) {
  const { search } = useLocation()
  const [sample, setSample] = useState(() => ({ scenario: initialScenario(search), tick: 0 }))
  const [theme, setTheme] = useState(initialTheme)
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
      <div><h1 className="text-2xl font-semibold">Captain</h1>
        <p role="status" className="text-sm text-muted-foreground">Access verified</p></div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => setTheme(current => current === 'night' ? 'day' : 'night')}>
          {theme === 'night' ? 'Day mode' : 'Night mode'}</Button>
        <Button variant="outline" onClick={() => void signOut()}>Sign out</Button>
      </div>
    </header>

    <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
      <div><p className="text-xl font-bold">Service 190 · Direction 1</p>
        <p className="text-sm text-muted-foreground">{DEMO_BUS_REFERENCE}</p></div>
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
    <InstructionPanel token={token} refreshIntervalSeconds={refreshIntervalSeconds} />
    <UpcomingStops />
    <footer className="space-y-1 border-t pt-4 text-sm text-muted-foreground">
      <p>Built-in samples update every 10 seconds. No live bus data.</p>
      <p className="break-all">Signed in as {email} · Role: Captain</p>
    </footer>
  </section>
}

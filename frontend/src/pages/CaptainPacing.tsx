import { Card } from '@/components/ui/card'
import type { CaptainRoute, PacingGap } from '@/lib/api'
import { clockText, gapFlex, gapSummary } from '@/lib/obu-pacing'
import { formatSgtTime } from '@/lib/time'
import { cn } from '@/lib/utils'

type GapState = PacingGap['state'] | 'NO_DATA'
const states: Record<GapState, { symbol: string; label: string; color: string }> = {
  TOO_CLOSE: { symbol: '▲', label: 'TOO CLOSE', color: 'text-red-700 dark:text-red-400' },
  ON_TARGET: { symbol: '●', label: 'ON TARGET', color: 'text-green-700 dark:text-green-400' },
  TOO_FAR: { symbol: '▼', label: 'TOO FAR', color: 'text-amber-800 dark:text-amber-400' },
  NO_DATA: { symbol: '?', label: 'NO DATA', color: 'text-muted-foreground' },
}

function GapTile({ side, gap, target }: { side: 'ahead' | 'behind'; gap: PacingGap | null; target: number }) {
  const state: GapState = gap?.state ?? 'NO_DATA'
  const info = states[state]
  const seconds = gap?.headwaySeconds ?? null
  return <Card role="region" aria-label={`Gap to bus ${side}`} data-state={state}
    className={cn('gap-3 border-4 border-current p-5 shadow-none', info.color,
      (state === 'TOO_FAR' || state === 'NO_DATA') && 'border-dashed', side === 'ahead' && 'max-md:order-first')}>
    <h3 className="text-base font-bold tracking-wide text-muted-foreground uppercase">Gap to bus {side}</h3>
    <p className="text-[clamp(1.75rem,5vw,2.75rem)] leading-tight font-black"><span aria-hidden="true">{info.symbol} </span>{info.label}</p>
    <p className="text-[clamp(3.5rem,9vw,8rem)] leading-none font-black text-foreground tabular-nums">{clockText(seconds)}</p>
    <p className="text-lg font-semibold text-foreground">{gapSummary(side, seconds, target)}</p>
  </Card>
}

function RoadGap({ gap, target }: { gap: PacingGap | null; target: number }) {
  const state: GapState = gap?.state ?? 'NO_DATA'
  const seconds = gap?.headwaySeconds ?? null
  return <div className={cn('min-w-6 basis-0 text-center', states[state].color)} style={{ flexGrow: gapFlex(seconds, target) }}>
    <span className="hidden text-sm font-bold tabular-nums sm:block">{clockText(seconds)}</span>
    <div className={cn('mt-1 border-t-8 border-current', (state === 'TOO_FAR' || state === 'NO_DATA') && 'border-dashed')} />
  </div>
}

/** Live pacing for the captain's bus from the backend pacing service (WQAP-68). */
export function CaptainPacing({ pacing }: { pacing: CaptainRoute['pacing'] }) {
  return <section aria-labelledby="pacing-heading" className="space-y-4">
    <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1">
      <h2 id="pacing-heading" className="text-xl font-bold">Pacing</h2>
      {pacing && <p className="text-lg sm:ml-auto">Target gap <strong className="tabular-nums">{clockText(pacing.targetHeadwaySeconds)}</strong></p>}
    </div>
    {!pacing ? <p role="note" className="rounded-lg border-2 border-dashed p-4 font-medium">
      No pacing data for your bus yet. Gaps appear once the pacing service has fresh predictions for it.</p> : <>
      <div aria-hidden="true" className="space-y-2 rounded-xl border bg-card p-4">
        <div className="flex justify-between text-xs font-semibold text-muted-foreground sm:text-sm">
          <span>Bus behind</span><span>Direction of travel →</span><span>Bus ahead</span>
        </div>
        <div className="flex items-center gap-2 font-bold">
          <span className={cn('rounded-md border-2 px-2 py-2 text-xs sm:px-4', !pacing.behind && 'border-dashed opacity-40')}>BUS</span>
          <RoadGap gap={pacing.behind} target={pacing.targetHeadwaySeconds} />
          <span className="rounded-md border-2 border-blue-600 bg-blue-600 px-2 py-2 text-xs text-white sm:px-4">YOU</span>
          <RoadGap gap={pacing.ahead} target={pacing.targetHeadwaySeconds} />
          <span className={cn('rounded-md border-2 px-2 py-2 text-xs sm:px-4', !pacing.ahead && 'border-dashed opacity-40')}>BUS</span>
        </div>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <GapTile side="behind" gap={pacing.behind} target={pacing.targetHeadwaySeconds} />
        <GapTile side="ahead" gap={pacing.ahead} target={pacing.targetHeadwaySeconds} />
      </div>
      <p className="sr-only" aria-live="polite" aria-atomic="true">
        Bus ahead: {states[pacing.ahead?.state ?? 'NO_DATA'].label.toLowerCase()}.
        Bus behind: {states[pacing.behind?.state ?? 'NO_DATA'].label.toLowerCase()}.
      </p>
      <p className="text-sm text-muted-foreground">Pacing calculated {formatSgtTime(pacing.calculatedAt)} from predictions fetched {formatSgtTime(pacing.observedAt)}.</p>
    </>}
  </section>
}

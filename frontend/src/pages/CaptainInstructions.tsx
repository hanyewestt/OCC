import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { ApiError, apiRequest, type CaptainInstruction, type InstructionType, type PageResult } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime } from '@/lib/time'
import { acknowledgementTimeLeft, instructionHeadline, instructionState } from '@/lib/captain-instructions'
import { cn } from '@/lib/utils'
import { Ban, CircleCheck, Clock, Gauge, Hand, TrendingDown, TriangleAlert, type LucideIcon } from 'lucide-react'
import { usePagedCollection } from '@/lib/paged-collection'

const instructionIcons: Record<InstructionType, LucideIcon> = {
  HOLD: Hand, DWELL_ADJUSTMENT: Clock, REDUCE_SPEED: Gauge, SLOW_DOWN_NEAR_STOPS: TrendingDown, BE_CAUTIOUS: TriangleAlert,
}

type CaptainInstructionPage = PageResult<CaptainInstruction> & { receivedAt: number }

export function CaptainInstructions({ token, registerRefresh, onPendingChange, onSaved, onRefreshTimeChange }: {
  token: string; registerRefresh: (callback: (signal: AbortSignal) => Promise<void>) => void
  onPendingChange: (pending: boolean) => void; onSaved: () => void
  onRefreshTimeChange: (at: string | null) => void
}) {
  const [actionError, setActionError] = useState('')
  const [pending, setPending] = useState<string | null>(null)
  const [monotonicNow, setMonotonicNow] = useState(() => performance.now())
  const action = useRef<AbortController | null>(null)
  const collection = usePagedCollection<CaptainInstruction, CaptainInstructionPage>(token, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<CaptainInstruction>>(`/api/v1/planning/captain/instructions?${params}`, token, signal)
      if (!result) throw new Error('Instruction response was empty.')
      return { ...result, receivedAt: performance.now() }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, item => item.id)

  const serverNow = collection.latestPage?.serverTime
    ? Date.parse(collection.latestPage.serverTime) + Math.max(0, monotonicNow - collection.latestPage.receivedAt) : null
  useEffect(() => { registerRefresh(collection.refresh) }, [collection.refresh, registerRefresh])
  useEffect(() => { onRefreshTimeChange(collection.lastSuccessAt) }, [collection.lastSuccessAt, onRefreshTimeChange])
  useEffect(() => { onPendingChange(pending !== null || collection.isLoading) }, [pending, collection.isLoading, onPendingChange])
  useEffect(() => () => onPendingChange(false), [onPendingChange])
  useEffect(() => {
    const timer = window.setInterval(() => setMonotonicNow(performance.now()), 1000)
    return () => { window.clearInterval(timer); action.current?.abort() }
  }, [])
  async function acknowledge(id: string) {
    action.current?.abort()
    const controller = new AbortController()
    action.current = controller
    setPending(id)
    try {
      const saved = await apiRequest<CaptainInstruction>(`/api/v1/planning/captain/instructions/${encodeURIComponent(id)}/acknowledgements`, token, controller.signal, { method: 'POST' })
      if (!controller.signal.aborted && saved) {
        collection.updateItem(saved); setActionError('')
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setActionError(failure instanceof Error ? failure.message : 'Acknowledgement was not saved. Try again.')
        if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      }
    } finally {
      if (!controller.signal.aborted) { setPending(null); onSaved() }
    }
  }
  const actionCount = collection.items.filter(item => instructionState(item, serverNow) === 'action').length
  return <section aria-labelledby="captain-instructions-heading" className="space-y-4">
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <h2 id="captain-instructions-heading" className="text-xl font-semibold">Controller instructions</h2>
      {actionCount > 0 && <p role="status" className="font-bold text-amber-800 dark:text-amber-300">
        {actionCount === 1 ? '1 instruction needs acknowledgement' : `${actionCount} instructions need acknowledgement`}</p>}
    </div>
    {actionError && <p role="alert" className="rounded-lg border-2 border-red-700 p-3 font-medium text-red-800 dark:border-red-400 dark:text-red-300">{actionError}</p>}
    {collection.isLoading && !collection.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading instructions…</p>}
    {collection.error !== null && !collection.items.length && <p role="alert" className="rounded-lg border p-3 text-sm">Instructions are unavailable.</p>}
    {!collection.isLoading && collection.error === null && collection.latestPage && !collection.items.length && <p className="text-sm text-muted-foreground">No current instructions for your assigned bus.</p>}
    {!!collection.items.length && <ScrollArea role="region" aria-label="Captain instructions" tabIndex={0} className="max-h-[32rem]"
      viewportProps={{ onScroll: collection.onScroll }}><div className="space-y-3 p-1 pr-3.5">{collection.items.map(item => {
        const state = instructionState(item, serverNow)
        const Icon = instructionIcons[item.type]
        const timeLeft = state === 'action' ? acknowledgementTimeLeft(item.acknowledgementDeadline, serverNow) : null
        return <Card key={item.id} data-state={state} className={cn('min-h-28 shadow-none',
          state === 'action' ? 'border-4 border-amber-700 bg-amber-50 dark:border-amber-400 dark:bg-amber-950/40'
            : 'border-2 opacity-90')}><CardContent className="space-y-4">
          <p className="text-xs font-medium text-muted-foreground">Simulation · {item.busId} · Hazard: {dataClassificationLabel(item.hazardClassification)}</p>
          <div className="flex items-start gap-3">
            <Icon aria-hidden="true" className={cn('mt-1 shrink-0', state === 'action' ? 'size-9 text-amber-800 dark:text-amber-300' : 'size-6 text-muted-foreground')} />
            <div className="min-w-0 space-y-1">
              <p className={cn('font-black uppercase leading-tight', state === 'action' ? 'text-[clamp(1.75rem,5vw,2.75rem)]' : 'text-xl')}>
                {instructionHeadline(item.type, item.parameters)}</p>
              <p className={cn('leading-snug', state === 'action' ? 'text-xl font-semibold' : 'text-base text-muted-foreground')}>{item.message}</p>
            </div>
          </div>
          {state === 'acknowledged' && <div role="status" className="space-y-1">
            <p className="flex items-center gap-2 text-lg font-bold text-green-800 dark:text-green-300">
              <CircleCheck aria-hidden="true" className="size-6 shrink-0" /><span>Acknowledged {formatSgtDateTime(item.acknowledgedAt)}</span></p>
            {item.applicableUntil && <p className="text-sm">{serverNow === null ? 'OCC checks the advisory window.'
              : `${Math.max(0, Math.floor((Date.parse(item.applicableUntil) - serverNow) / 1000))} sec remaining · advisory window`}</p>}
          </div>}
          {state === 'action' && <div className="space-y-2">
            <Button size="lg" className="h-16 w-full text-xl font-bold" disabled={pending !== null}
              onClick={() => void acknowledge(item.id)}>{pending === item.id ? 'Saving…' : 'Acknowledge'}</Button>
            {timeLeft && <p className="text-center text-sm font-medium tabular-nums">Acknowledge within {timeLeft}</p>}
          </div>}
          {state === 'closed' && <p className="flex items-center gap-2 font-semibold text-muted-foreground">
            <Clock aria-hidden="true" className="size-5 shrink-0" />Acknowledgement window closed. Contact the controller if you still need this instruction.</p>}
          {state === 'cancelled' && <p className="flex items-center gap-2 font-semibold text-muted-foreground">
            <Ban aria-hidden="true" className="size-5 shrink-0" />Cancelled by the controller{item.inactiveReason ? ` · ${item.inactiveReason.replaceAll('_', ' ').toLowerCase()}` : ''}.</p>}
        </CardContent></Card>
      })}</div></ScrollArea>}
    {collection.latestPage && collection.latestPage.totalItems > collection.items.length && <p className="text-xs text-muted-foreground">Showing {collection.items.length} of {collection.latestPage.totalItems} instructions.</p>}
    <PagedCollectionControls label="captain instructions" hasItems={!!collection.items.length} hasMore={collection.hasMore}
      isLoadingMore={collection.isLoadingMore} error={collection.error} refreshError={collection.refreshError} reload={collection.reload} loadNext={collection.loadNext} />
  </section>
}
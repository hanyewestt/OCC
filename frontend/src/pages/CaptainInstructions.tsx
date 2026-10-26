import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { ApiError, apiRequest, type CaptainInstruction, type PageResult } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime } from '@/lib/time'
import { usePagedCollection } from '@/lib/paged-collection'

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
  return <section aria-labelledby="captain-instructions-heading" className="space-y-4">
    <h2 id="captain-instructions-heading" className="text-xl font-semibold">Controller instructions</h2>
    {actionError && <p role="alert" className="rounded-lg border p-3 text-sm">{actionError}</p>}
    {collection.isLoading && !collection.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading instructions…</p>}
    {collection.error !== null && !collection.items.length && <p role="alert" className="rounded-lg border p-3 text-sm">Instructions are unavailable.</p>}
    {!collection.isLoading && collection.error === null && collection.latestPage && !collection.items.length && <p className="text-sm text-muted-foreground">No current instructions for your assigned bus.</p>}
    {!!collection.items.length && <ScrollArea role="region" aria-label="Captain instructions" tabIndex={0} className="max-h-[28rem]"
      viewportProps={{ onScroll: collection.onScroll }}><div className="space-y-3">{collection.items.map(item => <Card key={item.id} className="min-h-28"><CardContent className="space-y-4">
        <p className="text-xs font-medium text-muted-foreground">Simulation · {item.busId} · Hazard: {dataClassificationLabel(item.hazardClassification)}</p>
        <p className="text-2xl font-semibold leading-snug">{item.message}</p>
        {item.acknowledgedAt ? <div role="status" className="space-y-1 text-sm">
          <p>Acknowledged {formatSgtDateTime(item.acknowledgedAt)}</p>
          {item.applicableUntil && <p>{serverNow === null ? 'OCC checks the advisory window.'
            : `${Math.max(0, Math.floor((Date.parse(item.applicableUntil) - serverNow) / 1000))} sec remaining · advisory window`}</p>}
        </div> : <Button size="lg" disabled={pending !== null || serverNow !== null && serverNow >= Date.parse(item.acknowledgementDeadline)}
          onClick={() => void acknowledge(item.id)}>{pending === item.id ? 'Saving…' : 'Acknowledge'}</Button>}
      </CardContent></Card>)}</div></ScrollArea>}
    {collection.latestPage && collection.latestPage.totalItems > collection.items.length && <p className="text-xs text-muted-foreground">Showing {collection.items.length} of {collection.latestPage.totalItems} instructions.</p>}
    <PagedCollectionControls label="captain instructions" hasItems={!!collection.items.length} hasMore={collection.hasMore}
      isLoadingMore={collection.isLoadingMore} error={collection.error} refreshError={collection.refreshError} reload={collection.reload} loadNext={collection.loadNext} />
  </section>
}

import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ApiError, apiRequest, type CaptainInstruction, type PageResult } from '@/lib/api'
import { signOut } from '@/lib/auth'

export function CaptainInstructions({ token, refreshIntervalSeconds }: { token: string; refreshIntervalSeconds: number }) {
  const [items, setItems] = useState<CaptainInstruction[] | null>(null)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')
  const [pending, setPending] = useState<string | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [now, setNow] = useState(Date.now)
  const polling = useRef<AbortController | null>(null)
  const action = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    polling.current = controller
    let timer: number | undefined
    async function load() {
      try {
        const result = await apiRequest<PageResult<CaptainInstruction>>('/api/v1/planning/captain/instructions?size=100', token, controller.signal)
        if (!controller.signal.aborted) { setItems(result?.items ?? []); setError('') }
      } catch (failure) {
        if (!controller.signal.aborted) {
          setItems(null); setError(failure instanceof Error ? failure.message : 'Instructions are unavailable.')
          if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
        }
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(() => void load(), refreshIntervalSeconds * 1000)
      }
    }
    void load()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [token, refreshIntervalSeconds, refresh])
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => { window.clearInterval(timer); action.current?.abort() }
  }, [])
  async function acknowledge(id: string) {
    polling.current?.abort()
    action.current?.abort()
    const controller = new AbortController()
    action.current = controller
    setPending(id)
    try {
      const saved = await apiRequest<CaptainInstruction>(`/api/v1/planning/captain/instructions/${encodeURIComponent(id)}/acknowledgements`, token, controller.signal, { method: 'POST' })
      if (!controller.signal.aborted && saved) {
        setItems(previous => previous?.map(item => item.id === id ? saved : item) ?? [saved]); setActionError('')
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setActionError(failure instanceof Error ? failure.message : 'Acknowledgement was not saved. Try again.')
        if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      }
    } finally {
      if (!controller.signal.aborted) { setPending(null); setRefresh(value => value + 1) }
    }
  }
  return <section aria-labelledby="captain-instructions-heading" className="space-y-4">
    <h2 id="captain-instructions-heading" className="text-xl font-semibold">Controller instructions</h2>
    {error && <p role="alert" className="rounded-lg border p-3 text-sm">{error}</p>}
    {actionError && <p role="alert" className="rounded-lg border p-3 text-sm">{actionError}</p>}
    {items === null && !error && <p role="status" className="text-sm text-muted-foreground">Loading instructions…</p>}
    {items?.length === 0 && <p className="text-sm text-muted-foreground">No current instructions for your assigned bus.</p>}
    {items?.map(item => <Card key={item.id}><CardContent className="space-y-4">
      <p className="text-xs font-medium text-muted-foreground">SIMULATION · {item.busId} · Hazard: {item.hazardClassification === 'SIMULATION' ? 'synthetic' : 'live'}</p>
      <p className="text-2xl font-semibold leading-snug">{item.message}</p>
      {item.acknowledgedAt ? <div role="status" className="space-y-1 text-sm">
        <p>Acknowledged {new Date(item.acknowledgedAt).toLocaleTimeString()}</p>
        {item.applicableUntil && <p>{Math.max(0, Math.ceil((Date.parse(item.applicableUntil) - now) / 1000))} sec remaining · advisory window</p>}
      </div> : <Button size="lg" disabled={pending !== null || now >= Date.parse(item.acknowledgementDeadline)}
        onClick={() => void acknowledge(item.id)}>{pending === item.id ? 'Saving…' : 'Acknowledge'}</Button>}
    </CardContent></Card>)}
  </section>
}

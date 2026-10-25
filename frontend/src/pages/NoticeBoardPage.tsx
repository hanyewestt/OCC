import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { apiRequest, type PageResult, type PublicNotice } from '@/lib/api'

export function NoticeBoardPage() {
  const [params] = useSearchParams()
  const classification = params.get('classification') ?? 'OBSERVATION_ONLY'
  const runId = params.get('demoRunId')
  const [page, setPage] = useState(0)
  const [refresh, setRefresh] = useState(0)
  const [notices, setNotices] = useState<PageResult<PublicNotice> | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    let timer: number | undefined
    async function load() {
      let pageChanged = false
      try {
        // Public timing is independent of sign-in configuration and Supabase availability.
        const configResponse = await fetch('/frontend-config', { signal: controller.signal, cache: 'no-store', redirect: 'error' })
        if (!configResponse.ok) throw new Error('Notice board settings are unavailable. Try again.')
        const config = await configResponse.json() as { publicNoticeRefreshIntervalSeconds?: number }
        const interval = config.publicNoticeRefreshIntervalSeconds
        if (!Number.isInteger(interval) || interval! < 1) throw new Error('Notice board refresh settings are unavailable.')
        if (classification !== 'OBSERVATION_ONLY' && classification !== 'SIMULATION') throw new Error('Invalid notice view.')
        if (classification === 'SIMULATION' && !runId) throw new Error('The simulation view needs a demo run ID.')
        const query = new URLSearchParams({ classification, page: String(page), size: '20' })
        if (runId) query.set('demoRunId', runId)
        async function poll() {
          try {
            const data = await apiRequest<PageResult<PublicNotice>>(`/api/v1/planning/public/notices?${query}`, undefined, controller.signal)
            if (!controller.signal.aborted && data && page > Math.max(0, data.totalPages - 1)) {
              pageChanged = true
              setLoading(true)
              setPage(Math.max(0, data.totalPages - 1))
              return
            }
            if (!controller.signal.aborted) { setNotices(data); setError(''); setLastUpdated(new Date().toISOString()) }
          } catch (failure) {
            if (!controller.signal.aborted) { setNotices(null); setError(failure instanceof Error ? failure.message : 'Notices are unavailable.') }
          } finally {
            if (!controller.signal.aborted && !pageChanged) { setLoading(false); timer = window.setTimeout(() => void poll(), interval! * 1000) }
          }
        }
        await poll()
      } catch (failure) {
        if (!controller.signal.aborted) { setLoading(false); setNotices(null); setError(failure instanceof Error ? failure.message : 'Notices are unavailable.') }
      }
    }
    void load()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [classification, runId, page, refresh])

  return <main className="min-h-svh bg-background text-foreground">
    <header className="border-b"><div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-5 md:px-8">
      <Link to="/notices" className="text-lg font-semibold tracking-tight">OCC</Link>
      <Button variant="outline" asChild><Link to="/login">Staff sign in</Link></Button>
    </div></header>
    <div className="mx-auto max-w-7xl space-y-6 p-5 md:p-8">
      <div className="flex flex-wrap items-start justify-between gap-4"><div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Service advisories</h1>
        <p className="text-sm text-muted-foreground">Approved notices about affected services and stops.</p>
      </div><Button variant="outline" disabled={loading} onClick={() => { setLoading(true); setRefresh(value => value + 1) }}>Refresh</Button></div>
      {classification === 'SIMULATION' && <p role="status" className="rounded-lg border bg-muted p-3 text-sm font-medium">SIMULATION · Demonstration notices</p>}
      {lastUpdated && <p className="text-xs text-muted-foreground">Last updated {new Date(lastUpdated).toLocaleTimeString()}</p>}
      {loading && <p role="status" className="text-sm text-muted-foreground">Loading notices…</p>}
      {error && <div role="alert" className="space-y-3 rounded-xl border p-5"><p>{error}</p><Button variant="outline" onClick={() => { setLoading(true); setRefresh(value => value + 1) }}>Try again</Button></div>}
      {!loading && !error && notices?.items.length === 0 && <Card><CardContent className="text-sm text-muted-foreground">No current service advisories.</CardContent></Card>}
      <div className="grid gap-4 md:grid-cols-2">{!loading && !error && notices?.items.map(notice => <Card key={notice.id}>
        <CardContent className="space-y-4">
          <div className="space-y-1">{notice.classification === 'SIMULATION' && <p className="text-xs font-medium text-muted-foreground">SIMULATION</p>}
            <h2 className="text-lg font-semibold">{notice.title}</h2></div>
          <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{notice.message}</p>
          {notice.services.length > 0 && <p className="text-sm"><span className="text-muted-foreground">Services: </span>{notice.services.join(', ')}</p>}
          {notice.locations.length > 0 && <p className="text-sm"><span className="text-muted-foreground">Locations: </span>{notice.locations.join(', ')}</p>}
          <p className="text-xs text-muted-foreground">Until <time dateTime={notice.effectiveUntil}>{new Date(notice.effectiveUntil).toLocaleString()}</time></p>
        </CardContent>
      </Card>)}</div>
      {!loading && !error && notices && notices.totalPages > 1 && <nav aria-label="Notice pages" className="flex items-center gap-3">
        <Button variant="outline" disabled={page === 0} onClick={() => { setLoading(true); setPage(value => value - 1) }}>Previous</Button>
        <p className="text-sm text-muted-foreground">Page {page + 1} of {notices.totalPages}</p>
        <Button variant="outline" disabled={page + 1 >= notices.totalPages} onClick={() => { setLoading(true); setPage(value => value + 1) }}>Next</Button>
      </nav>}
    </div>
  </main>
}

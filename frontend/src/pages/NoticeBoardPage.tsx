import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { apiRequest, type PageResult, type PublicNotice } from '@/lib/api'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime } from '@/lib/time'
import { PageRefreshStatus } from '@/components/PageRefreshStatus'
import { usePagedCollection } from '@/lib/paged-collection'
import { usePageRefresh } from '@/lib/page-refresh'

export function NoticeBoardPage() {
  const [params] = useSearchParams()
  const classification = params.get('classification') ?? 'OBSERVATION_ONLY'
  const runId = params.get('demoRunId')
  const [configRetry, setConfigRetry] = useState(0)
  const [configReady, setConfigReady] = useState(false)
  const [configError, setConfigError] = useState('')
  const [refreshIntervalSeconds, setRefreshIntervalSeconds] = useState<number | null>(null)
  const collectionKey = `${classification}:${runId ?? ''}:${configReady}`
  const collection = usePagedCollection<PublicNotice, PageResult<PublicNotice>>(collectionKey, async (page, signal) => {
    if (!configReady || classification !== 'OBSERVATION_ONLY' && classification !== 'SIMULATION' || classification === 'SIMULATION' && !runId) {
      return { items: [], page, totalItems: 0, totalPages: 0 }
    }
    const query = new URLSearchParams({ classification, page: String(page), size: '20' })
    if (runId) query.set('demoRunId', runId)
    const result = await apiRequest<PageResult<PublicNotice>>(`/api/v1/planning/public/notices?${query}`, undefined, signal)
    if (!result) throw new Error('Service advisories response was empty.')
    return result
  }, notice => notice.id, configReady)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        // Public timing is independent of sign-in configuration and Supabase availability.
        const configResponse = await fetch('/frontend-config', { signal: controller.signal, cache: 'no-store', redirect: 'error' })
        if (!configResponse.ok) throw new Error('Notice board settings are unavailable. Try again.')
        const config = await configResponse.json() as { publicNoticeRefreshIntervalSeconds?: number }
        const interval = config.publicNoticeRefreshIntervalSeconds
        if (!Number.isInteger(interval) || interval! < 1) throw new Error('Notice board refresh settings are unavailable.')
        if (classification !== 'OBSERVATION_ONLY' && classification !== 'SIMULATION') throw new Error('Invalid notice view.')
        if (classification === 'SIMULATION' && !runId) throw new Error('The simulation view needs a demo run ID.')
        if (!controller.signal.aborted) { setRefreshIntervalSeconds(interval!); setConfigReady(true); setConfigError('') }
      } catch (failure) {
        if (!controller.signal.aborted) { setConfigReady(false); setConfigError(failure instanceof Error ? failure.message : 'Notices are unavailable.') }
      }
    }
    void load()
    return () => controller.abort()
  }, [classification, runId, configRetry])

  const { requestRefresh } = usePageRefresh(refreshIntervalSeconds, collection.refresh, !configReady || collection.isLoading, collectionKey)
  const lastUpdated = collection.lastSuccessAt
  const loading = !configReady && !configError || collection.isLoading
  const error = configError || (!collection.items.length
    ? collection.error instanceof Error ? collection.error.message : collection.error !== null ? 'Service advisories are unavailable.' : ''
    : '')
  const retry = () => configError ? setConfigRetry(value => value + 1) : void requestRefresh()

  return <main className="min-h-svh bg-background text-foreground">
    <header className="border-b"><div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-5 md:px-8">
      <Link to="/notices" className="text-lg font-semibold tracking-tight">OCC</Link>
      <Button variant="outline" asChild><Link to="/login">Staff sign in</Link></Button>
    </div></header>
    <div className="mx-auto max-w-7xl space-y-6 p-5 md:p-8">
      <div className="flex flex-wrap items-start justify-between gap-4"><div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Service advisories</h1>
        <PageRefreshStatus lastSuccessAt={lastUpdated} intervalSeconds={refreshIntervalSeconds} />
        <p className="text-sm text-muted-foreground">Approved notices about affected services and stops.</p>
      </div><Button variant="outline" disabled={loading} onClick={retry}>Refresh</Button></div>
      {classification === 'SIMULATION' && <p role="status" className="rounded-lg border bg-muted p-3 text-sm font-medium">Simulation · Demonstration notices</p>}
      <p role="status" aria-atomic="true" className={loading ? 'text-sm text-muted-foreground' : 'sr-only'}>
        {loading ? collection.items.length ? 'Refreshing notices…' : 'Loading notices…'
          : !configError && collection.error === null && collection.latestPage ? `Service advisories loaded: ${collection.items.length}.` : ''}
      </p>
      {error && <div role="alert" className="space-y-3 rounded-xl border p-5"><p>{error}</p><Button variant="outline" onClick={retry}>Try again</Button></div>}
      {!loading && !error && collection.latestPage && !collection.items.length && <Card><CardContent className="text-sm text-muted-foreground">No current service advisories.</CardContent></Card>}
      {!!collection.items.length && <ScrollArea className="max-h-[34rem]"
        viewportProps={{ role: 'region', 'aria-label': 'Public service advisories', tabIndex: 0,
          'aria-busy': collection.isLoading || collection.isLoadingMore, onScroll: collection.onScroll }}>
        <div className="grid gap-4 p-1 pr-3.5 md:grid-cols-2">{collection.items.map(notice => <Card key={notice.id} className="min-h-56 min-w-0">
        <CardContent className="space-y-4 [overflow-wrap:anywhere]">
          <div className="space-y-1"><p className="text-xs font-medium text-muted-foreground">{dataClassificationLabel(notice.classification)}</p>
            <h2 className="text-lg font-semibold">{notice.title}</h2></div>
          <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{notice.message}</p>
          {notice.services.length > 0 && <p className="text-sm"><span className="text-muted-foreground">Services: </span>{notice.services.join(', ')}</p>}
          {notice.locations.length > 0 && <p className="text-sm"><span className="text-muted-foreground">Locations: </span>{notice.locations.join(', ')}</p>}
          <p className="text-xs text-muted-foreground">Until <time dateTime={notice.effectiveUntil}>{formatSgtDateTime(notice.effectiveUntil)}</time></p>
        </CardContent>
      </Card>)}</div></ScrollArea>}
      {collection.latestPage && collection.latestPage.totalItems > collection.items.length && <p className="text-xs text-muted-foreground">Showing {collection.items.length} of {collection.latestPage.totalItems} notices.</p>}
      {!!collection.items.length && <PagedCollectionControls label="service advisories" hasItems hasMore={collection.hasMore}
        isLoadingMore={collection.isLoadingMore} error={collection.error} refreshError={collection.refreshError} reload={retry} loadNext={collection.loadNext} />}
    </div>
  </main>
}

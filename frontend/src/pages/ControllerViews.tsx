import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import type { Arrival, BunchingAlert, DemoRun, PageResult } from '@/lib/api'
import { bunchingArrivals } from '@/lib/bunching'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime } from '@/lib/time'
import type { Decision } from '@/lib/api'
import type { PagedCollection } from '@/lib/paged-collection'
import type { Resource } from './ControllerPage'

export function timestamp(value: string | null | undefined) {
  return formatSgtDateTime(value)
}

export function DataMessage({ error, loading, empty, children }: {
  error: string; loading: boolean; empty: boolean; children: React.ReactNode
}) {
  return loading ? <p role="status" className="py-6 text-sm text-muted-foreground">Loading…</p> :
    error ? <p role="alert" className="py-6 text-sm text-destructive">{error} Use Refresh to try again.</p> :
      empty ? <p className="py-6 text-sm text-muted-foreground">{children}</p> : null
}

type ArrivalCollection = {
  items: Arrival[]; latestPage: PageResult<Arrival> | null; isLoading: boolean; isLoadingMore: boolean
  hasMore: boolean; error: unknown; reload: () => void; loadNext: () => void
  onScroll: React.UIEventHandler<HTMLDivElement>
}

export function ControllerOverview({ collection, search, onSearch, sortBy, sortDirection, onSort }: {
  collection: ArrivalCollection; search: string; onSearch: (value: string) => void
  sortBy: 'arrival' | 'stopName'; sortDirection: 'asc' | 'desc'; onSort: (value: 'arrival' | 'stopName') => void
}) {
  const [searchInput, setSearchInput] = useState(search)
  const data = collection.latestPage ? { ...collection.latestPage, items: collection.items } : null
  const initialError = collection.error !== null && !collection.items.length ? 'Unable to load arrivals.' : ''
  const loadMoreError = collection.error !== null && collection.items.length > 0
  const loadCategory = (value: string | null) => value === null ? '—' : ({
    SEA: 'Seats Available', SDA: 'Standing Available', LSD: 'Limited Standing',
  }[value] ?? value)
  return <section id="arrivals" aria-labelledby="arrivals-title" className="scroll-mt-20 space-y-4">
    <div><h2 id="arrivals-title" tabIndex={-1} className="w-fit font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring">Current bus arrivals</h2></div>
    <form role="search" onSubmit={event => { event.preventDefault(); onSearch(searchInput.trim()) }} className="flex flex-wrap items-end gap-2">
      <div className="w-full max-w-sm space-y-1.5">
        <label htmlFor="arrival-stop-search" className="text-sm font-medium">Search bus stop name</label>
        <Input id="arrival-stop-search" type="search" value={searchInput} onChange={event => setSearchInput(event.target.value)} />
      </div>
      <Button type="submit" variant="outline">Search</Button>
      {(searchInput || search) && <Button type="button" variant="ghost" onClick={() => { setSearchInput(''); onSearch('') }}>Clear</Button>}
    </form>
    <DataMessage error={initialError} loading={collection.isLoading} empty={!data?.items.length}>
      {search ? 'No arrivals match this stop name' : 'No current arrivals.'}
    </DataMessage>
    {!!data?.items.length && <Table containerProps={{ role: 'region', 'aria-label': 'Current bus arrivals', tabIndex: 0,
      onScroll: collection.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[42rem]">
      <TableCaption className="sr-only">Current bus arrivals</TableCaption>
      <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-background">
        <TableRow><TableHead>Service</TableHead>
          <TableHead aria-sort={sortBy === 'stopName' ? sortDirection === 'asc' ? 'ascending' : 'descending' : undefined}>
            <Button type="button" variant="ghost" size="sm"
              aria-label={`Sort stop names ${sortBy === 'stopName' && sortDirection === 'asc' ? 'descending' : 'ascending'}`}
              onClick={() => onSort('stopName')}>Stop <span aria-hidden="true">{sortBy === 'stopName' ? sortDirection === 'asc' ? '↑' : '↓' : '↕'}</span></Button>
          </TableHead>
          <TableHead aria-sort={sortBy === 'arrival' ? sortDirection === 'asc' ? 'ascending' : 'descending' : undefined}>
            <Button type="button" variant="ghost" size="sm"
              aria-label={`Sort arrival times ${sortBy === 'arrival' && sortDirection === 'asc' ? 'descending' : 'ascending'}`}
              onClick={() => onSort('arrival')}>Arrival <span aria-hidden="true">{sortBy === 'arrival' ? sortDirection === 'asc' ? '↑' : '↓' : '↕'}</span></Button>
          </TableHead>
          <TableHead>Load</TableHead><TableHead>Fetched</TableHead></TableRow>
      </TableHeader>
      <TableBody>{data.items.map(arrival => <TableRow key={arrival.observationId}>
        <TableCell className="font-medium">{arrival.serviceId}</TableCell>
        <TableCell>{`${arrival.stopName?.trim() || `Stop ${arrival.stopCode}`} (${arrival.stopCode})`}</TableCell>
        <TableCell>{timestamp(arrival.estimatedArrivalAt)}</TableCell>
        <TableCell>{loadCategory(arrival.loadCategory)}</TableCell>
        <TableCell className="text-muted-foreground">{timestamp(arrival.fetchedAt)}</TableCell>
      </TableRow>)}</TableBody>
    </Table>}
    {loadMoreError && <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-destructive">
      <span>Unable to load more arrivals.</span>
      <Button variant="outline" size="sm" disabled={collection.isLoadingMore} onClick={collection.loadNext}>Retry loading more arrivals</Button>
    </div>}
    {collection.hasMore && !loadMoreError && <Button variant="outline" disabled={collection.isLoadingMore} onClick={collection.loadNext}>
      {collection.isLoadingMore ? 'Loading more arrivals…' : 'Load more arrivals'}
    </Button>}
    {collection.error !== null && !collection.items.length && <Button variant="outline" onClick={collection.reload}>Retry arrivals</Button>}
  </section>
}

export function ControllerDisruptions({ liveAlerts, run, loading }: {
  liveAlerts: PagedCollection<BunchingAlert, PageResult<BunchingAlert>>; run: Resource<DemoRun>; loading: boolean
}) {
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const error = run.error || (!simulation && liveAlerts.error !== null && !liveAlerts.items.length ? 'Unable to load this data.' : '')
  const items = loading || error || !simulation && liveAlerts.isLoading && !liveAlerts.latestPage
    ? [] : simulation ? simulation.alerts : liveAlerts.items
  return <div className="space-y-10">
    <section aria-labelledby="bunching-title" className="space-y-4"><div><h2 id="bunching-title" className="font-medium">Bunching detections</h2></div>
      <DataMessage error={error} loading={loading || !simulation && liveAlerts.isLoading && !liveAlerts.latestPage} empty={!items.length}>No bunching detections.</DataMessage>
      {!!items.length && <Table containerProps={{ role: 'region', 'aria-label': 'Bunching detections', tabIndex: 0,
        onScroll: simulation ? undefined : liveAlerts.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[60rem]">
        <TableCaption className="sr-only">Bunching detections</TableCaption>
        <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-background"><TableRow><TableHead>Service</TableHead><TableHead>Bus stop</TableHead><TableHead>Direction</TableHead><TableHead>Bus 1 arrival</TableHead><TableHead>Bus 2 arrival</TableHead><TableHead>Bus 3 arrival</TableHead><TableHead>Event</TableHead><TableHead>Detected</TableHead><TableHead>Source</TableHead></TableRow></TableHeader>
        <TableBody>{items.map(alert => <TableRow key={alert.eventId}><TableCell className="font-medium">{alert.serviceId}</TableCell><TableCell><p className="font-medium">{alert.stopName || 'Stop name unavailable'}</p><p className="mt-1 text-xs text-muted-foreground">{alert.stopRoadName ? `${alert.stopRoadName} · ` : ''}{alert.stopCode}</p></TableCell><TableCell>{alert.directionId ? `Direction ${alert.directionId}` : 'Unavailable'}</TableCell>{bunchingArrivals(alert).map((value, index) => <TableCell key={index}>{value === 'No prediction' || value === 'Unavailable' ? <span className="text-muted-foreground">{value}</span> : timestamp(value)}</TableCell>)}<TableCell>{alert.action}</TableCell><TableCell>{timestamp(alert.detectedAt)}</TableCell><TableCell>{dataClassificationLabel(alert.classification)}</TableCell></TableRow>)}</TableBody>
      </Table>}
      {!simulation && !loading && liveAlerts.latestPage && liveAlerts.latestPage.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {liveAlerts.latestPage.totalItems} detections.</p>}
      {!simulation && <PagedCollectionControls label="bunching detections" hasItems={!!liveAlerts.items.length} hasMore={liveAlerts.hasMore}
        isLoadingMore={liveAlerts.isLoadingMore} error={liveAlerts.error} reload={liveAlerts.reload} loadNext={liveAlerts.loadNext} />}
    </section>
  </div>
}

export function ControllerAudit({ decisions, loading }: {
  decisions: PagedCollection<Decision, PageResult<Decision>>; loading: boolean
}) {
  const items = !loading ? decisions.items : []
  const error = decisions.error !== null && !decisions.items.length ? 'Unable to load this data.' : ''
  return <div className="space-y-10">
    <section aria-labelledby="decisions-title" className="space-y-4"><h2 id="decisions-title" className="font-medium">Controller decisions</h2>
      <DataMessage error={error} loading={loading || decisions.isLoading && !decisions.latestPage} empty={!items.length}>No controller decisions have been saved.</DataMessage>
      {!!items.length && <Table containerProps={{ role: 'region', 'aria-label': 'Controller decisions', tabIndex: 0,
        onScroll: decisions.onScroll, className: 'max-h-[26rem] overflow-auto rounded-lg border' }} className="min-w-[48rem]">
        <TableCaption className="sr-only">Controller decisions</TableCaption>
        <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-background"><TableRow><TableHead>Decision</TableHead><TableHead>Recorded</TableHead><TableHead>Controller</TableHead><TableHead>Replacement instructions</TableHead><TableHead>Rationale</TableHead><TableHead>Recommendation ID</TableHead></TableRow></TableHeader>
        <TableBody>{items.map(decision => <TableRow key={decision.recommendationId}><TableCell className="font-medium">{decision.action}</TableCell><TableCell>{timestamp(decision.decidedAt)}</TableCell><TableCell className="text-muted-foreground">{decision.actorSubject}</TableCell><TableCell>{decision.overrideInstructions.length ? <ul className="space-y-2">{decision.overrideInstructions.map((instruction, index) => <li key={`${instruction.busReference}-${index}`}><span className="font-medium">{instruction.busReference}</span><span className="block text-xs text-muted-foreground">{instruction.instruction}</span></li>)}</ul> : '—'}</TableCell><TableCell className="max-w-72 text-muted-foreground">{decision.controllerRationale ?? '—'}</TableCell><TableCell className="text-xs text-muted-foreground">{decision.recommendationId}</TableCell></TableRow>)}</TableBody>
      </Table>}
      {decisions.latestPage && decisions.latestPage.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {decisions.latestPage.totalItems} decisions.</p>}
      <PagedCollectionControls label="decisions" hasItems={!!decisions.items.length} hasMore={decisions.hasMore}
        isLoadingMore={decisions.isLoadingMore} error={decisions.error} reload={decisions.reload} loadNext={decisions.loadNext} />
    </section>
    <section aria-labelledby="ai-audit-title" className="space-y-2 border-t pt-6"><h2 id="ai-audit-title" className="font-medium">AI audit logs</h2><p className="text-sm text-muted-foreground">Not connected yet. AI audit records will appear here when available.</p></section>
  </div>
}

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import type { Arrival, BunchingAlert, DemoRun, PageResult } from '@/lib/api'
import { bunchingArrivals } from '@/lib/bunching'
import { formatSgtDateTime } from '@/lib/time'
import type { Planning, Resource } from './ControllerPage'

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

export function ControllerDisruptions({ alerts, run, loading }: {
  alerts: Resource<PageResult<BunchingAlert>>; run: Resource<DemoRun>; loading: boolean
}) {
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const error = run.error || (!simulation ? alerts.error : '')
  const items = loading || error ? [] : simulation ? simulation.alerts : alerts.data?.items ?? []
  return <div className="space-y-10">
    <section aria-labelledby="bunching-title" className="space-y-4"><div><h2 id="bunching-title" className="font-medium">Bunching detections</h2><p className="mt-1 text-xs text-muted-foreground">{simulation ? 'Simulation events from the current run.' : 'Live detections.'}</p></div>
      <DataMessage error={error} loading={loading} empty={!items.length}>No bunching detections.</DataMessage>
      {!!items.length && <div className="rounded-lg border"><Table><TableCaption className="mb-3">Bus 1, 2 and 3 are arrival predictions recorded at detection time, shown in your local time zone.</TableCaption><TableHeader><TableRow><TableHead>Service</TableHead><TableHead>Bus stop</TableHead><TableHead>Direction</TableHead><TableHead>Bus 1 arrival</TableHead><TableHead>Bus 2 arrival</TableHead><TableHead>Bus 3 arrival</TableHead><TableHead>Event</TableHead><TableHead>Detected</TableHead><TableHead>Source</TableHead></TableRow></TableHeader><TableBody>{items.map(alert => <TableRow key={alert.eventId}><TableCell className="font-medium">{alert.serviceId}</TableCell><TableCell><p className="font-medium">{alert.stopName || 'Stop name unavailable'}</p><p className="mt-1 text-xs text-muted-foreground">{alert.stopRoadName ? `${alert.stopRoadName} · ` : ''}{alert.stopCode}</p></TableCell><TableCell>{alert.directionId ? `Direction ${alert.directionId}` : 'Unavailable'}</TableCell>{bunchingArrivals(alert).map((value, index) => <TableCell key={index}>{value === 'No prediction' || value === 'Unavailable' ? <span className="text-muted-foreground">{value}</span> : timestamp(value)}</TableCell>)}<TableCell>{alert.action}</TableCell><TableCell>{timestamp(alert.detectedAt)}</TableCell><TableCell>{alert.classification}</TableCell></TableRow>)}</TableBody></Table></div>}
      {!simulation && !loading && alerts.data && alerts.data.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {alerts.data.totalItems} detections.</p>}
    </section>
  </div>
}

export function ControllerAudit({ planning, loading }: { planning: Resource<Planning>; loading: boolean }) {
  const decisions = !loading && !planning.error ? planning.data?.decisions.items ?? [] : []
  return <div className="space-y-10">
    <section aria-labelledby="decisions-title" className="space-y-4"><div><h2 id="decisions-title" className="font-medium">Controller decisions</h2><p className="mt-1 text-xs text-muted-foreground">Persisted approvals and controller-authored replacement instructions, newest first.</p></div>
      <DataMessage error={planning.error} loading={loading} empty={!decisions.length}>No controller decisions have been saved.</DataMessage>
      {!!decisions.length && <div className="rounded-lg border"><Table><TableHeader><TableRow><TableHead>Decision</TableHead><TableHead>Recorded</TableHead><TableHead>Controller</TableHead><TableHead>Replacement instructions</TableHead><TableHead>Rationale</TableHead><TableHead>Recommendation ID</TableHead></TableRow></TableHeader><TableBody>{decisions.map(decision => <TableRow key={decision.recommendationId}><TableCell className="font-medium">{decision.action}</TableCell><TableCell>{timestamp(decision.decidedAt)}</TableCell><TableCell className="text-muted-foreground">{decision.actorSubject}</TableCell><TableCell>{decision.overrideInstructions.length ? <ul className="space-y-2">{decision.overrideInstructions.map((instruction, index) => <li key={`${instruction.busReference}-${index}`}><span className="font-medium">{instruction.busReference}</span><span className="block text-xs text-muted-foreground">{instruction.instruction}</span></li>)}</ul> : '—'}</TableCell><TableCell className="max-w-72 text-muted-foreground">{decision.controllerRationale ?? '—'}</TableCell><TableCell className="text-xs text-muted-foreground">{decision.recommendationId}</TableCell></TableRow>)}</TableBody></Table></div>}
    </section>
    <section aria-labelledby="ai-audit-title" className="space-y-2 border-t pt-6"><h2 id="ai-audit-title" className="font-medium">AI audit logs</h2><p className="text-sm text-muted-foreground">Not connected yet. AI audit records will appear here when available.</p></section>
  </div>
}

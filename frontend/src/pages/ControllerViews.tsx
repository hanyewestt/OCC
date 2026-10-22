import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import type { Arrival, BunchingAlert, DemoRun, PageResult } from '@/lib/api'
import { bunchingArrivals } from '@/lib/bunching'
import type { Planning, Resource } from './ControllerPage'

export function timestamp(value: string | null | undefined) {
  if (!value || Number.isNaN(Date.parse(value))) return '—'
  return new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function DataMessage({ error, loading, empty, children }: {
  error: string; loading: boolean; empty: boolean; children: React.ReactNode
}) {
  return loading ? <p role="status" className="py-6 text-sm text-muted-foreground">Loading…</p> :
    error ? <p role="alert" className="py-6 text-sm text-destructive">{error} Use Refresh to try again.</p> :
      empty ? <p className="py-6 text-sm text-muted-foreground">{children}</p> : null
}

export function ControllerOverview({ arrivals, loading, page, onPage }: {
  arrivals: Resource<PageResult<Arrival>>; loading: boolean; page: number; onPage: (page: number) => void
}) {
  const data = !loading && !arrivals.error ? arrivals.data : null
  return <section id="arrivals" aria-labelledby="arrivals-title" className="space-y-4">
    <div><h2 id="arrivals-title" className="font-medium">Current bus arrivals</h2><p className="mt-1 text-xs text-muted-foreground">Live feed · arrival times shown in your local time zone.</p></div>
    <DataMessage error={arrivals.error} loading={loading} empty={!data?.items.length}>No current arrivals.</DataMessage>
    {!!data?.items.length && <div className="rounded-lg border"><Table><TableCaption className="mb-3">Latest fetched records; this view does not update automatically.</TableCaption>
      <TableHeader><TableRow><TableHead>Service</TableHead><TableHead>Stop</TableHead><TableHead>Arrival</TableHead><TableHead>Load</TableHead><TableHead>Fetched</TableHead></TableRow></TableHeader>
      <TableBody>{data.items.map(arrival => <TableRow key={arrival.observationId}><TableCell className="font-medium">{arrival.serviceId}</TableCell><TableCell>{arrival.stopCode}</TableCell><TableCell>{timestamp(arrival.estimatedArrivalAt)}</TableCell><TableCell>{arrival.loadCategory ?? '—'}</TableCell><TableCell className="text-muted-foreground">{timestamp(arrival.fetchedAt)}</TableCell></TableRow>)}</TableBody>
    </Table></div>}
    {data && data.totalPages > 1 && <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted-foreground"><span>Page {page + 1} of {data.totalPages} · {data.totalItems} arrivals</span><div className="flex gap-2"><Button variant="outline" disabled={loading || page === 0} onClick={() => onPage(page - 1)}>Previous</Button><Button variant="outline" disabled={loading || page + 1 >= data.totalPages} onClick={() => onPage(page + 1)}>Next</Button></div></div>}
  </section>
}

export function ControllerDisruptions({ alerts, run, loading }: {
  alerts: Resource<PageResult<BunchingAlert>>; run: Resource<DemoRun>; loading: boolean
}) {
  const simulation = run.data && run.data.status !== 'CLOSED' ? run.data : null
  const error = run.error || (!simulation ? alerts.error : '')
  const items = loading || error ? [] : simulation ? simulation.alerts : alerts.data?.items ?? []
  return <div className="space-y-10">
    <section aria-labelledby="bunching-title" className="space-y-4"><div><h2 id="bunching-title" className="font-medium">Bunching detections</h2><p className="mt-1 text-xs text-muted-foreground">{simulation ? 'Simulation events from the current run; these are not live incidents.' : 'Currently open live detections · observation only.'}</p></div>
      <DataMessage error={error} loading={loading} empty={!items.length}>No bunching detections.</DataMessage>
      {!!items.length && <div className="rounded-lg border"><Table><TableCaption className="mb-3">Bus 1, 2 and 3 are arrival predictions recorded at detection time, shown in your local time zone.</TableCaption><TableHeader><TableRow><TableHead>Service</TableHead><TableHead>Bus stop</TableHead><TableHead>Direction</TableHead><TableHead>Bus 1 arrival</TableHead><TableHead>Bus 2 arrival</TableHead><TableHead>Bus 3 arrival</TableHead><TableHead>Event</TableHead><TableHead>Detected</TableHead><TableHead>Source</TableHead></TableRow></TableHeader><TableBody>{items.map(alert => <TableRow key={alert.eventId}><TableCell className="font-medium">{alert.serviceId}</TableCell><TableCell><p className="font-medium">{alert.stopName || 'Stop name unavailable'}</p><p className="mt-1 text-xs text-muted-foreground">{alert.stopRoadName ? `${alert.stopRoadName} · ` : ''}{alert.stopCode}</p></TableCell><TableCell>{alert.directionId ? `Direction ${alert.directionId}` : 'Unavailable'}</TableCell>{bunchingArrivals(alert).map((value, index) => <TableCell key={index}>{value === 'No prediction' || value === 'Unavailable' ? <span className="text-muted-foreground">{value}</span> : timestamp(value)}</TableCell>)}<TableCell>{alert.action}</TableCell><TableCell>{timestamp(alert.detectedAt)}</TableCell><TableCell>{alert.classification}</TableCell></TableRow>)}</TableBody></Table></div>}
      {!simulation && !loading && alerts.data && alerts.data.totalItems > items.length && <p className="text-xs text-muted-foreground">Showing {items.length} of {alerts.data.totalItems} detections.</p>}
    </section>
    <section aria-labelledby="traffic-title" className="space-y-2 border-t pt-6"><h2 id="traffic-title" className="font-medium">Traffic disruptions</h2><p className="text-sm text-muted-foreground">Not connected yet. Traffic disruptions will appear here.</p></section>
  </div>
}

export function ControllerAudit({ planning, loading }: { planning: Resource<Planning>; loading: boolean }) {
  const decision = !loading && !planning.error ? planning.data?.decision : null
  return <div className="space-y-10">
    <section aria-labelledby="decisions-title" className="space-y-4"><div><h2 id="decisions-title" className="font-medium">Controller decisions</h2><p className="mt-1 text-xs text-muted-foreground">Saved decision for the current simulation. Earlier runs are not included; full history is not connected yet.</p></div>
      <DataMessage error={planning.error} loading={loading} empty={!decision}>No saved decision for the current simulation.</DataMessage>
      {decision && <div className="rounded-lg border"><Table><TableHeader><TableRow><TableHead>Decision</TableHead><TableHead>Recorded</TableHead><TableHead>Recommendation ID</TableHead></TableRow></TableHeader><TableBody><TableRow><TableCell className="font-medium">{decision.action}</TableCell><TableCell>{timestamp(decision.decidedAt)}</TableCell><TableCell>{decision.recommendationId}</TableCell></TableRow></TableBody></Table></div>}
    </section>
    <section aria-labelledby="ai-audit-title" className="space-y-2 border-t pt-6"><h2 id="ai-audit-title" className="font-medium">AI audit logs</h2><p className="text-sm text-muted-foreground">Not connected yet. AI audit records will appear here when available.</p></section>
  </div>
}

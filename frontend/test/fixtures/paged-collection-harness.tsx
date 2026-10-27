import { useState } from 'react'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { usePagedCollection } from '@/lib/paged-collection'
import { createRoot } from 'react-dom/client'
import '/src/index.css'

type Row = { id: string; label: string }
type RowPage = { items: Row[]; page: number; totalItems: number; totalPages: number }

function PagedCollectionHarness() {
  const [scope, setScope] = useState('alpha')
  const collection = usePagedCollection<Row, RowPage>(scope, async (page, signal) => {
    const query = new URLSearchParams({ scope, page: String(page) })
    const response = await fetch(`/__test/pages?${query}`, { signal })
    if (!response.ok) throw new Error('Could not load more rows.')
    return response.json() as Promise<RowPage>
  }, row => row.id)

  return <main className="space-y-5 p-4">
    <label>Scope
      <select aria-label="Collection scope" value={scope} onChange={event => setScope(event.target.value)}>
        <option value="alpha">Alpha</option><option value="beta">Beta</option>
      </select>
    </label>
    <Button onClick={collection.reload}>Refresh rows</Button>
    <p role="status">Loaded {collection.items.length} rows</p>
    <p aria-label="Last successful refresh">{collection.lastSuccessAt ?? 'Never'}</p>
    {collection.error != null && <p role="alert">{collection.error instanceof Error ? collection.error.message : 'Rows are unavailable.'}</p>}
    <Table containerProps={{
      role: 'region', 'aria-label': 'Arrival rows', tabIndex: 0,
      className: 'h-96 overflow-auto rounded border',
      onScroll: collection.onScroll,
    }} className="min-w-[48rem]">
      <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-background">
        <TableRow><TableHead>Arrival</TableHead><TableHead>Stop</TableHead></TableRow>
      </TableHeader>
      <TableBody>{collection.items.map(row => <TableRow key={row.id} data-testid="arrival-row" className="h-9">
        <TableCell>{row.label}</TableCell><TableCell>{row.id}</TableCell>
      </TableRow>)}</TableBody>
    </Table>
    {collection.hasMore && <Button disabled={collection.isLoadingMore} onClick={collection.loadNext}>
      {collection.error ? 'Retry loading more' : collection.isLoadingMore ? 'Loading more…' : 'Load more rows'}
    </Button>}
    <h2>Incident cards</h2>
    <ScrollArea aria-label="Incident cards" role="region" tabIndex={0} className="h-72 rounded border p-2">
      <div className="space-y-2">{Array.from({ length: 9 }, (_, index) => <article
        key={index} data-testid="incident-card" className="min-h-16 rounded border p-3">
        Incident {index + 1}
      </article>)}</div>
    </ScrollArea>
    <ScrollArea aria-label="Short collection" role="region" className="max-h-72 rounded border p-2">
      <div className="space-y-2">{['One', 'Two', 'Three'].map(item => <article key={item} className="rounded border p-3">{item}</article>)}</div>
    </ScrollArea>
  </main>
}

createRoot(document.getElementById('root')!).render(<PagedCollectionHarness />)

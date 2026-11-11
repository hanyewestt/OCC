import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiRequest, type DirectoryPage, type DirectoryUser } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { signOut } from '@/lib/auth'
import { captainDirectoryPath } from '@/pages/controller-captains'

const emptyPage = (): DirectoryPage => ({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, directorySynchronizedAt: null })
const displayName = (captain: DirectoryUser) => captain.displayName || captain.name || 'Unnamed captain'

export function ControllerCaptainsPage({ token }: { token: string }) {
  const [search, setSearch] = useState('')
  const [pageNo, setPageNo] = useState(0)
  const [data, setData] = useState<DirectoryPage>(emptyPage)
  const [scope, setScope] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)

  const load = useCallback(async (page: number, signal: AbortSignal) => {
    const requestScope = `${token}:${search.trim()}`
    setBusy(true)
    setError('')
    try {
      const result = await apiRequest<DirectoryPage>(captainDirectoryPath(search, page, false), token, signal)
      if (!result) throw new Error('Captain directory response was empty.')
      if (signal.aborted) return
      setData(result)
      setPageNo(result.page)
      setScope(requestScope)
    } catch (e) {
      if (signal.aborted) return
      if (e instanceof ApiError && e.status === 401) void signOut(e.message)
      setError(e instanceof ApiError && e.status === 403 ? 'You do not have permission to view captains.' : 'Unable to load the captain directory.')
    } finally {
      if (!signal.aborted) setBusy(false)
    }
  }, [search, token])

  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(() => void load(pageNo, controller.signal), search ? 200 : 0)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [load, pageNo, refresh, search])

  const currentScope = `${token}:${search.trim()}`
  const rows = scope === currentScope ? data.items : []
  const page = scope === currentScope ? pageNo : 0
  return <Card>
    <CardHeader><CardTitle>Captains</CardTitle><p className="text-sm text-muted-foreground">Captain accounts and their current bus assignment.</p></CardHeader>
    <CardContent className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid w-full max-w-sm gap-1.5"><Label htmlFor="captain-directory-search">Search captains</Label><Input id="captain-directory-search" placeholder="Name or email" value={search} onChange={event => { setSearch(event.target.value); setPageNo(0) }} /></div>
        <Button variant="outline" disabled={busy} onClick={() => setRefresh(value => value + 1)}>Refresh</Button>
      </div>
      {error && <p role="alert">{error}</p>}
      {busy && !rows.length && <p role="status">Loading captains…</p>}
      {!busy && !error && !rows.length && <p>No captains found.</p>}
      {!!rows.length && <>
        <div className="max-h-[65vh] overflow-auto">
          <Table><TableHeader><TableRow><TableHead>Name</TableHead><TableHead>Email</TableHead><TableHead>Account ID</TableHead><TableHead>Current assignment</TableHead></TableRow></TableHeader>
            <TableBody>{rows.map(captain => <TableRow key={captain.id}>
              <TableCell>{displayName(captain)}</TableCell><TableCell>{captain.email}</TableCell><TableCell className="break-all font-mono text-xs">{captain.id}</TableCell>
              <TableCell>{captain.currentAssignment ? <>{captain.currentAssignment.vehicleRegistrationNumber}<span className="block text-xs text-muted-foreground">Service {captain.currentAssignment.serviceId} · Direction {captain.currentAssignment.directionId}</span></> : 'Unassigned'}</TableCell>
            </TableRow>)}</TableBody>
          </Table>
        </div>
        <div className="flex items-center justify-between gap-3"><span className="text-sm text-muted-foreground">{data.totalItems} captains · Page {page + 1} of {Math.max(data.totalPages, 1)}</span><div className="flex gap-2"><Button variant="outline" disabled={busy || page === 0} onClick={() => setPageNo(value => value - 1)}>Previous</Button><Button variant="outline" disabled={busy || page + 1 >= data.totalPages} onClick={() => setPageNo(value => value + 1)}>Next</Button></div></div>
      </>}
    </CardContent>
  </Card>
}

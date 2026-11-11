import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { ClipboardList, LogOut, PanelLeft, UserPlus, UsersRound, X } from 'lucide-react'
import { ApiError, apiRequest, ingestionAdminPath, newIdempotencyKey, type AuditDetail, type AuditEvent, type DirectoryPage, type DirectoryUser, type ProvisioningOperation } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatSgtDateTime, sgtDateTimeInputFromUtc, sgtDateTimeInputToUtc } from '@/lib/time'
import { signOut } from '@/lib/auth'
import { adminRoutes } from '@/lib/admin-routes'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { cn } from '@/lib/utils'

const links = [
  { path: adminRoutes.audit, title: 'Audit', icon: ClipboardList },
  { path: adminRoutes.accounts, title: 'Accounts', icon: UsersRound },
  { path: adminRoutes.provision, title: 'Provision account', icon: UserPlus },
]
const services = ['ingestion', 'disruption', 'planning'] as const
type AuditPage = { items: AuditEvent[]; page: number; totalPages: number; retentionSeconds: number; collectionStartedAt: string }
const emptyPage = (): DirectoryPage => ({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, directorySynchronizedAt: null })
const errorText = (error: unknown) => error instanceof Error ? error.message : 'Unable to load this data.'
const isPending = (status: string) => status === 'PENDING' || status === 'PROCESSING'

export function AdminPage({ token, email }: { token: string; email: string }) {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [expanded, setExpanded] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const page = links.some(item => item.path === pathname) ? pathname : '/admin/audit'
  const closeMobile = () => { if (!window.matchMedia('(min-width: 768px)').matches) setExpanded(false) }
  useEffect(() => { if (pathname === '/admin') navigate('/admin/audit', { replace: true }) }, [navigate, pathname])
  const handleSignOut = async () => {
    if (signingOut) return
    setSigningOut(true)
    try { await signOut() } finally { setSigningOut(false) }
  }
  return <div className="flex min-h-svh w-full bg-background">
    {expanded && <button aria-label="Close navigation" className="fixed inset-0 z-20 bg-black/20 md:hidden" onClick={() => setExpanded(false)} />}
    <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
      <aside aria-label="Admin sidebar" onKeyDown={event => { if (event.key === 'Escape') setExpanded(false) }}
        className={cn('sticky top-0 flex h-svh w-16 shrink-0 flex-col border-r bg-sidebar p-3', expanded && 'fixed inset-y-0 left-0 z-30 w-60 md:sticky')}>
        <div className={cn('mb-8 flex h-9 items-center gap-2', expanded ? 'justify-between' : 'justify-center')}>
          {expanded && <Link to={adminRoutes.audit} className="px-2 text-lg font-semibold tracking-tight" onClick={closeMobile}>OCC<span className="ml-2 text-xs font-normal text-muted-foreground">Admin</span></Link>}
          <CollapsibleTrigger asChild><Button size="icon" variant="ghost" aria-label={expanded ? 'Collapse sidebar' : 'Expand sidebar'}><PanelLeft aria-hidden="true" /></Button></CollapsibleTrigger>
        </div>
        <nav aria-label="Admin navigation" className="space-y-1">{links.map(item => <Button key={item.path} asChild variant={page === item.path ? 'secondary' : 'ghost'} className={cn('h-10 w-full justify-start gap-3 px-2.5', !expanded && 'justify-center px-0')}>
          <Link to={item.path} aria-label={item.title} aria-current={page === item.path ? 'page' : undefined} title={expanded ? undefined : item.title} onClick={closeMobile}>
            <item.icon aria-hidden="true" /><span className={expanded ? '' : 'sr-only'}>{item.title}</span>
          </Link>
        </Button>)}</nav>
        <div className="mt-auto space-y-3 border-t pt-4"><CollapsibleContent><div className="space-y-1 px-2 text-xs text-muted-foreground"><p className="break-all">{email}</p><p>Access verified</p></div></CollapsibleContent>
          <Button variant="ghost" className="h-10 w-full gap-3" disabled={signingOut} onClick={() => void handleSignOut()} aria-label="Sign out" title="Sign out">
            <LogOut aria-hidden="true" />{expanded && (signingOut ? 'Signing out…' : 'Sign out')}
          </Button>
        </div>
      </aside>
    </Collapsible>
    <main className="min-w-0 flex-1 space-y-5 p-4 sm:p-6"><header className="space-y-1"><h1 className="text-2xl font-semibold">{links.find(item => item.path === page)?.title}</h1><p className="text-sm text-muted-foreground">Administrative account and operational records</p></header>
      {page === '/admin/accounts' ? <Accounts token={token} /> : page === '/admin/accounts/new' ? <Provision token={token} /> : <Audit token={token} />}
    </main>
  </div>
}

function Accounts({ token }: { token: string }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const requestedId = params.get('userId')
  const [search, setSearch] = useState('')
  const [role, setRole] = useState('')
  const [pageNo, setPageNo] = useState(0)
  const [data, setData] = useState<DirectoryPage>(emptyPage())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<DirectoryUser | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [syncMessage, setSyncMessage] = useState('')
  const activeListRequest = useRef<AbortController | null>(null)
  const activeDetailRequest = useRef<AbortController | null>(null)
  const query = useMemo(() => new URLSearchParams({ page: '0', size: '20', ...(search.trim() ? { search: search.trim() } : {}), ...(role ? { role } : {}) }).toString(), [role, search])
  const load = useCallback(async (page: number, signal: AbortSignal) => {
    setBusy(true); setError('')
    try {
      const result = await apiRequest<DirectoryPage>(`${ingestionAdminPath('admin/users')}?${query.replace('page=0', `page=${page}`)}`, token, signal)
      if (!result) throw new Error('Account response was empty.')
      setData(result); setPageNo(page)
    } catch (failure) { if (!signal.aborted) { if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message); setError(errorText(failure)) } }
    finally { if (!signal.aborted) setBusy(false) }
  }, [query, token])
  const requestPage = useCallback((page: number) => { activeListRequest.current?.abort(); const controller = new AbortController(); activeListRequest.current = controller; void load(page, controller.signal) }, [load])
  useEffect(() => { const timer = window.setTimeout(() => requestPage(0), 0); return () => { window.clearTimeout(timer); activeListRequest.current?.abort() } }, [requestPage])
  const openUser = useCallback(async (id: string, signal: AbortSignal) => {
    setSelected(null); setDetailError(''); setDetailBusy(true)
    try { const user = await apiRequest<DirectoryUser>(`${ingestionAdminPath('admin/users')}/${id}`, token, signal); if (!user) throw new Error('Account detail was empty.'); if (!signal.aborted) setSelected(user) }
    catch (failure) { if (!signal.aborted) { if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message); setDetailError(errorText(failure)) } } finally { if (!signal.aborted) setDetailBusy(false) }
  }, [token])
  useEffect(() => {
    if (!requestedId) return
    const controller = new AbortController()
    activeDetailRequest.current?.abort(); activeDetailRequest.current = controller
    const timer = window.setTimeout(() => void openUser(requestedId, controller.signal), 0)
    return () => { clearTimeout(timer); controller.abort() }
  }, [openUser, requestedId])
  const showUser = (id: string) => { activeDetailRequest.current?.abort(); const controller = new AbortController(); activeDetailRequest.current = controller; void openUser(id, controller.signal) }
  const closeDetail = (open: boolean) => { if (!open) { activeDetailRequest.current?.abort(); setSelected(null); setDetailError(''); setDetailBusy(false); if (requestedId) navigate('/admin/accounts', { replace: true }) } }
  const refresh = async () => {
    setBusy(true); setSyncMessage(''); setError('')
    let syncError = ''
    try {
      const result = await apiRequest<{ directorySynchronizedAt: string }>(
        ingestionAdminPath('admin/users/synchronizations'), token, new AbortController().signal,
        { method: 'POST' })
      if (!result?.directorySynchronizedAt) throw new Error('Synchronization response was empty.')
      setSyncMessage(`Directory synchronized ${formatSgtDateTime(result.directorySynchronizedAt)}.`)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      syncError = errorText(failure)
    }
    const controller = new AbortController()
    await load(0, controller.signal)
    if (syncError) setError(syncError)
  }
  return <Card><CardHeader><CardTitle>Account directory</CardTitle><p className="text-sm text-muted-foreground">Provider account snapshot · {data.totalItems} accounts · synced {data.directorySynchronizedAt ? formatSgtDateTime(data.directorySynchronizedAt) : 'not yet'}</p></CardHeader><CardContent className="space-y-4">
    <div className="flex flex-wrap items-center gap-3"><Label className="sr-only" htmlFor="account-search">Search accounts</Label><Input id="account-search" placeholder="Search name or email" value={search} onChange={event => setSearch(event.target.value)} className="h-10 max-w-sm"/><Label htmlFor="account-role" className="sr-only">Filter by role</Label><select id="account-role" className="h-10 rounded-lg border bg-background px-3 text-sm" value={role} onChange={event => setRole(event.target.value)}><option value="">All roles</option>{['ADMIN','CONTROLLER','CAPTAIN','UNASSIGNED'].map(value => <option key={value}>{value}</option>)}</select><Button variant="outline" className="h-10 sm:ml-auto" disabled={busy} onClick={() => void refresh()}>{busy ? 'Refreshing…' : 'Refresh'}</Button></div>
    {syncMessage && <p role="status">{syncMessage}</p>}{error && <p role="alert">{error}</p>}{busy && !data.items.length && <p role="status">Loading accounts…</p>}{!busy && !error && !data.items.length && <p>No accounts found.</p>}
    {!!data.items.length && <><div className="max-h-[65vh] overflow-auto"><Table><TableHeader><TableRow><TableHead>Name</TableHead><TableHead>Email</TableHead><TableHead>Role</TableHead><TableHead>Status</TableHead><TableHead>Last sign-in</TableHead><TableHead className="text-right">Details</TableHead></TableRow></TableHeader><TableBody>{data.items.map(user => <TableRow key={user.id}><TableCell>{user.displayName ?? user.name ?? '—'}</TableCell><TableCell>{user.email}</TableCell><TableCell>{user.role}</TableCell><TableCell>{user.available ? 'Available' : 'Unavailable'}</TableCell><TableCell>{user.lastSignInAt ? formatSgtDateTime(user.lastSignInAt) : 'Never'}</TableCell><TableCell className="text-right"><Button variant="link" onClick={() => showUser(user.id)}>View</Button></TableCell></TableRow>)}</TableBody></Table></div><div className="flex items-center justify-between"><span className="text-sm text-muted-foreground">Page {data.page + 1} of {Math.max(data.totalPages, 1)}</span><div className="flex gap-2"><Button variant="outline" disabled={busy || pageNo === 0} onClick={() => requestPage(pageNo - 1)}>Previous</Button><Button variant="outline" disabled={busy || pageNo + 1 >= data.totalPages} onClick={() => requestPage(pageNo + 1)}>Next</Button></div></div></>}
    <Dialog open={!!selected || detailBusy || !!detailError} onOpenChange={closeDetail}><DialogContent><DialogClose aria-label="Close" className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"><X aria-hidden="true" className="size-4" /></DialogClose><DialogHeader><DialogTitle>Account details</DialogTitle><DialogDescription>Current provider projection and assignment</DialogDescription></DialogHeader>{detailBusy && <p role="status">Loading account…</p>}{detailError && <p role="alert">{detailError}</p>}{selected && <dl className="grid gap-3 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">Name</dt><dd>{selected.displayName ?? selected.name ?? '—'}</dd></div><div><dt className="text-muted-foreground">Email</dt><dd>{selected.email}</dd></div><div><dt className="text-muted-foreground">Role</dt><dd>{selected.role}</dd></div><div><dt className="text-muted-foreground">Status</dt><dd>{selected.available ? 'Available' : 'Unavailable'}</dd></div><div><dt className="text-muted-foreground">Created</dt><dd>{formatSgtDateTime(selected.createdAt)}</dd></div><div><dt className="text-muted-foreground">Last sign-in</dt><dd>{formatSgtDateTime(selected.lastSignInAt)}</dd></div><div><dt className="text-muted-foreground">Directory sync</dt><dd>{formatSgtDateTime(selected.synchronizedAt)}</dd></div><div className="sm:col-span-2"><dt className="text-muted-foreground">Current assignment</dt><dd>{selected.currentAssignment ? `${selected.currentAssignment.vehicleRegistrationNumber} · ${selected.currentAssignment.serviceId} direction ${selected.currentAssignment.directionId} · run ${selected.currentAssignment.demoRunId}` : 'None'}</dd></div></dl>}</DialogContent></Dialog>
  </CardContent></Card>
}

function Provision({ token }: { token: string }) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const operationIdFromUrl = searchParams.get('operationId')
  const [displayName, setName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('CAPTAIN')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [operation, setOperation] = useState<ProvisioningOperation | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null)
  const [requestUncertain, setRequestUncertain] = useState(false)
  const [operationChecking, setOperationChecking] = useState(false)
  const passwordLength = Array.from(password).length
  const check = useCallback(async (id: string, signal: AbortSignal) => {
    setOperationChecking(true)
    try {
      const next = await apiRequest<ProvisioningOperation>(ingestionAdminPath(`admin/provisioning-operations/${id}`), token, signal)
      if (!next) throw new Error('Operation response was empty.')
      setOperation(next)
      setRequestUncertain(false)
      if (next.status === 'SUCCEEDED' && next.result?.id) {
        setPassword(''); setIdempotencyKey(null)
        navigate(`/admin/accounts?userId=${encodeURIComponent(next.result.id)}`)
        return
      }
      const pending = isPending(next.status)
      setMessage(pending ? 'Outcome is still uncertain. Check this operation before starting another.'
        : `Provisioning status: ${next.status}${next.failureCode ? ` (${next.failureCode})` : ''}. Re-enter a password before retrying.`)
      if (!pending) {
        setPassword(''); setIdempotencyKey(null)
        navigate(adminRoutes.provision, { replace: true })
      }
    } finally { setOperationChecking(false) }
  }, [navigate, token])
  useEffect(() => {
    if (!operationIdFromUrl || operation?.id === operationIdFromUrl) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      void check(operationIdFromUrl, controller.signal).catch(failure => {
        if (!controller.signal.aborted) setError(errorText(failure))
      })
    }, 0)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [check, operation?.id, operationIdFromUrl])
  const changed = () => {
    if (requestUncertain || (operation && isPending(operation.status))) return
    setIdempotencyKey(null); setOperation(null); setMessage(''); setError('')
    if (operationIdFromUrl) navigate(adminRoutes.provision, { replace: true })
  }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setError('')
    if (passwordLength < 8) { setError('Password must contain at least 8 characters.'); return }
    setBusy(true); setMessage('')
    const key = requestUncertain && idempotencyKey ? idempotencyKey : newIdempotencyKey()
    setIdempotencyKey(key)
    if (operation && !isPending(operation.status)) setOperation(null)
    const controller = new AbortController()
    try {
      const response = await fetch(ingestionAdminPath('admin/users'), { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify({ displayName, email, role, password }), cache: 'no-store', redirect: 'error', signal: controller.signal })
      const body = await response.json()
      if (!response.ok) throw new ApiError(response.status, typeof body.detail === 'string' ? body.detail : undefined, body)
      if (response.status === 202) {
        const pending = body as ProvisioningOperation
        setOperation(pending); setRequestUncertain(false)
        navigate(`${adminRoutes.provision}?operationId=${encodeURIComponent(pending.id)}`, { replace: true })
        await check(pending.id, controller.signal)
      } else {
        const user = body as DirectoryUser
        setPassword(''); setIdempotencyKey(null); setRequestUncertain(false)
        navigate(`/admin/accounts?userId=${encodeURIComponent(user.id)}`)
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      if (!(failure instanceof ApiError) || failure.status >= 500) {
        setRequestUncertain(true)
        setMessage('The submission result is uncertain. Retry the same request to check its idempotent operation; account fields are locked.')
      }
      setError(errorText(failure))
    }
    finally { setBusy(false) }
  }
  const unresolvedOperationLink = !!operationIdFromUrl && !operation
  const loadingOperation = unresolvedOperationLink && operationChecking
  const locked = requestUncertain || unresolvedOperationLink || (!!operation && isPending(operation.status))
  return <Card className="w-full"><CardHeader><CardTitle>Provision an account</CardTitle><p className="text-sm text-muted-foreground">Creates a confirmed-email Controller or Captain account.</p></CardHeader><CardContent><form className="space-y-4" onSubmit={event => void submit(event)}>
    <div><Label htmlFor="display-name">Display name</Label><Input id="display-name" required maxLength={200} value={displayName} onChange={event => { changed(); setName(event.target.value) }} disabled={locked} className="mt-1 h-10"/></div>
    <div><Label htmlFor="provision-email">Email</Label><Input id="provision-email" type="email" required maxLength={254} value={email} onChange={event => { changed(); setEmail(event.target.value) }} disabled={locked} className="mt-1 h-10"/></div>
    <div><Label htmlFor="provision-role">Role</Label><select id="provision-role" className="mt-1 h-10 w-full rounded-lg border bg-background px-3" value={role} onChange={event => { changed(); setRole(event.target.value) }} disabled={locked}><option>CAPTAIN</option><option>CONTROLLER</option></select></div>
    <div><Label htmlFor="provision-password">Temporary password</Label><div className="mt-1 flex gap-2"><Input id="provision-password" type={reveal ? 'text' : 'password'} required autoComplete="new-password" value={password} onChange={event => { changed(); setPassword(Array.from(event.target.value).slice(0, 128).join('')) }} disabled={locked} className="h-10"/><Button type="button" variant="outline" aria-label={reveal ? 'Hide password' : 'Show password'} aria-pressed={reveal} onClick={() => setReveal(value => !value)} disabled={locked} className="h-10">{reveal ? 'Hide' : 'Show'}</Button></div><p className="mt-1 text-xs text-muted-foreground">8–128 characters. Passwords are never saved in this page.</p></div>
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    {loadingOperation && <div className="text-sm text-muted-foreground" role="status">Checking provisioning operation…</div>}
    {operation && <div className="text-xs text-muted-foreground">Operation {operation.id} · {operation.status}{isPending(operation.status) && <Button className="ml-2" variant="link" disabled={busy} onClick={() => { const controller = new AbortController(); setBusy(true); void check(operation.id, controller.signal).catch(failure => setError(errorText(failure))).finally(() => setBusy(false)) }}>Check status</Button>}</div>}
    {unresolvedOperationLink && !operationChecking && <Button type="button" variant="link" disabled={busy} onClick={() => { const controller = new AbortController(); setBusy(true); void check(operationIdFromUrl, controller.signal).catch(failure => setError(errorText(failure))).finally(() => setBusy(false)) }}>Retry status check</Button>}
    <Button type="submit" className="h-10" disabled={busy || (locked && !requestUncertain) || passwordLength < 8}>{busy ? 'Submitting…' : requestUncertain ? 'Retry same submission' : locked ? 'Waiting for operation' : 'Create account'}</Button>
  </form></CardContent></Card>
}

function Audit({ token }: { token: string }) {
  const [service, setService] = useState<(typeof services)[number]>('ingestion')
  const [from, setFrom] = useState(() => sgtDateTimeInputFromUtc(new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()))
  const [to, setTo] = useState(() => sgtDateTimeInputFromUtc(new Date().toISOString()))
  const [eventType, setEventType] = useState('')
  const [events, setEvents] = useState<AuditEvent[]>([]); const [detail, setDetail] = useState<AuditDetail | null>(null); const [pageNo, setPageNo] = useState(0); const [pages, setPages] = useState(0); const [retentionSeconds, setRetentionSeconds] = useState<number | null>(null); const [collectionStartedAt, setCollectionStartedAt] = useState<string | null>(null); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const activeListRequest = useRef<AbortController | null>(null)
  // Unedited bounds stay with the provider default (last 24h to now) so refresh includes the current minute.
  const rangeEdited = useRef({ from: false, to: false })
  const filters = useMemo(() => ({ service, from, to, eventType }), [service, from, to, eventType])
  const filterKey = JSON.stringify(filters)
  const load = useCallback(async (page: number, signal: AbortSignal) => {
    setBusy(true); setError('')
    try {
      const q = new URLSearchParams({ page: String(page), size: '20' })
      if (eventType.trim()) q.set('eventType', eventType.trim())
      if (rangeEdited.current.from && from) q.set('from', sgtDateTimeInputToUtc(from)); if (rangeEdited.current.to && to) q.set('to', sgtDateTimeInputToUtc(to))
      const base = service === 'ingestion' ? ingestionAdminPath('admin/audit-events') : `/api/v1/${service}/admin/audit-events`
      const result = await apiRequest<AuditPage>(`${base}?${q}`, token, signal)
      if (!result) throw new Error('Audit response was empty.')
      setEvents(result.items); setPageNo(result.page); setPages(result.totalPages); setRetentionSeconds(result.retentionSeconds); setCollectionStartedAt(result.collectionStartedAt)
    } catch (failure) { if (!signal.aborted) { if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message); setError(errorText(failure)) } }
    finally { if (!signal.aborted) setBusy(false) }
  }, [eventType, from, service, to, token])
  const requestPage = useCallback((page: number) => { activeListRequest.current?.abort(); const controller = new AbortController(); activeListRequest.current = controller; void load(page, controller.signal) }, [load])
  useEffect(() => { const timer = window.setTimeout(() => requestPage(0), 0); return () => { clearTimeout(timer); activeListRequest.current?.abort() } }, [filterKey, requestPage])
  const refresh = () => requestPage(pageNo)
  const open = async (event: AuditEvent) => {
    setDetail(null); setError('')
    try {
      const base = service === 'ingestion' ? ingestionAdminPath('admin/audit-events') : `/api/v1/${service}/admin/audit-events`
      const result = await apiRequest<AuditDetail>(`${base}/${event.id}`, token, new AbortController().signal)
      if (!result) throw new Error('Audit detail was empty.')
      setDetail(result)
    } catch (failure) { setError(errorText(failure)) }
  }
  const [dateBounds] = useState(() => ({
    max: sgtDateTimeInputFromUtc(new Date().toISOString()),
    min: sgtDateTimeInputFromUtc(new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString()),
  }))
  const { max: maxFrom, min: minFrom } = dateBounds
  return <div className="space-y-4"><Card><CardContent className="space-y-3 pt-6"><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"><div><Label htmlFor="audit-service">Service</Label><select id="audit-service" value={service} onChange={event => setService(event.target.value as typeof service)} className="mt-1 h-10 w-full rounded-lg border bg-background px-3">{services.map(value => <option key={value} value={value}>{value[0].toUpperCase() + value.slice(1)}</option>)}</select></div><div><Label htmlFor="audit-from">From (SGT)</Label><div className="mt-1 rounded-lg focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2"><Input id="audit-from" type="datetime-local" min={minFrom} max={maxFrom} value={from} onChange={event => { rangeEdited.current.from = true; setFrom(event.target.value) }} className="h-10"/></div></div><div><Label htmlFor="audit-to">To (SGT)</Label><div className="mt-1 rounded-lg focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2"><Input id="audit-to" type="datetime-local" min={minFrom} max={maxFrom} value={to} onChange={event => { rangeEdited.current.to = true; setTo(event.target.value) }} className="h-10"/></div></div><div><Label htmlFor="audit-event-type">Event</Label><Input id="audit-event-type" placeholder="e.g. OPERATIONS or FEED" value={eventType} onChange={event => setEventType(event.target.value)} className="mt-1 h-10"/></div></div><div className="flex flex-wrap items-center justify-between gap-3"><p className="text-xs text-muted-foreground">Default range is the last 24 hours. Event matches category or event type (partial, case-insensitive). Available history is limited to 90 days.</p><Button variant="outline" className="h-10" disabled={busy} onClick={refresh}>Refresh</Button></div></CardContent></Card>
    <p className="text-xs text-muted-foreground">{retentionSeconds !== null ? `Retention: ${Math.round(retentionSeconds / 86400)} days` : ''}{collectionStartedAt ? ` · Collection started ${formatSgtDateTime(collectionStartedAt)}` : ''}</p>
    {error && <p role="alert">{error}</p>}{busy && !events.length && <p role="status">Loading audit events…</p>}{!busy && !error && !events.length && <p>No audit events match these filters.</p>}
    {!!events.length && <Card><CardContent className="pt-6"><div className="max-h-[55vh] overflow-auto"><Table><TableHeader><TableRow><TableHead>Time (SGT)</TableHead><TableHead>Event</TableHead><TableHead>Outcome</TableHead><TableHead>Actor</TableHead><TableHead>Summary</TableHead><TableHead className="text-right">Details</TableHead></TableRow></TableHeader><TableBody>{events.map(event => <TableRow key={event.id}><TableCell className="whitespace-nowrap">{formatSgtDateTime(event.occurredAt)}</TableCell><TableCell>{event.category} · {event.eventType}</TableCell><TableCell>{event.outcome}</TableCell><TableCell>{event.actorSubject ?? '—'}</TableCell><TableCell>{event.summary}</TableCell><TableCell className="text-right"><Button variant="link" onClick={() => void open(event)}>View</Button></TableCell></TableRow>)}</TableBody></Table></div><div className="mt-4 flex justify-between"><span className="text-sm text-muted-foreground">Page {pageNo + 1} of {Math.max(pages, 1)}</span><div className="flex gap-2"><Button variant="outline" disabled={busy || pageNo === 0} onClick={() => requestPage(pageNo - 1)}>Previous</Button><Button variant="outline" disabled={busy || pageNo + 1 >= pages} onClick={() => requestPage(pageNo + 1)}>Next</Button></div></div></CardContent></Card>}
    <Dialog open={!!detail} onOpenChange={open => { if (!open) setDetail(null) }}><DialogContent className="max-h-[85vh] overflow-auto"><DialogClose aria-label="Close" className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"><X aria-hidden="true" className="size-4" /></DialogClose><DialogHeader><DialogTitle>Audit event detail</DialogTitle><DialogDescription>{detail && `${detail.id} · ${formatSgtDateTime(detail.occurredAt)}`}</DialogDescription></DialogHeader>{detail && <><dl className="grid gap-3 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">Source</dt><dd>{detail.sourceService}</dd></div><div><dt className="text-muted-foreground">Entity</dt><dd>{detail.entityType} · {detail.entityId}</dd></div><div><dt className="text-muted-foreground">Summary</dt><dd>{detail.summary}</dd></div></dl><h3 className="mt-4 font-medium">Sanitized evidence</h3><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded bg-muted p-3 text-xs">{JSON.stringify(detail.details, null, 2)}</pre></>}</DialogContent></Dialog>
  </div>
}

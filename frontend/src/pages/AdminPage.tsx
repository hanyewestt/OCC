import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { Bus, ClipboardList, LogOut, PanelLeft, UserPlus, UsersRound } from 'lucide-react'
import { ApiError, apiRequest, ingestionAdminPath, newIdempotencyKey, type ActiveAssignments, type AssignmentOperation, type AuditDetail, type AuditEvent, type DirectoryPage, type DirectoryUser, type ProvisioningOperation } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { formatSgtDateTime, sgtDateTimeInputFromUtc, sgtDateTimeInputToUtc } from '@/lib/time'
import { signOut } from '@/lib/auth'
import { adminRoutes } from '@/lib/admin-routes'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { cn } from '@/lib/utils'
import { canEditAssignments, requestedCaptainId } from '@/pages/admin-assignments'

const links = [
  { path: adminRoutes.audit, title: 'Audit', icon: ClipboardList },
  { path: adminRoutes.accounts, title: 'Accounts', icon: UsersRound },
  { path: adminRoutes.provision, title: 'Provision account', icon: UserPlus },
  { path: adminRoutes.assignments, title: 'Assignments', icon: Bus },
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
        <div className="mb-8 flex h-9 items-center justify-between gap-2">
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
    <main className="min-w-0 flex-1 space-y-5 p-4 sm:p-6"><header><h1 className="text-2xl font-semibold">{links.find(item => item.path === page)?.title}</h1><p className="text-sm text-muted-foreground">Administrative account and operational records</p></header>
      {page === '/admin/accounts' ? <Accounts token={token} /> : page === '/admin/accounts/new' ? <Provision token={token} /> : page === '/admin/assignments' ? <Assignments token={token} /> : <Audit token={token} />}
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
  const [syncBusy, setSyncBusy] = useState(false)
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
  const refresh = () => requestPage(pageNo)
  const synchronize = async () => {
    setSyncBusy(true); setSyncMessage(''); setError('')
    try {
      const result = await apiRequest<{ directorySynchronizedAt: string }>(
        ingestionAdminPath('admin/users/synchronizations'), token, new AbortController().signal,
        { method: 'POST' })
      if (!result?.directorySynchronizedAt) throw new Error('Synchronization response was empty.')
      setSyncMessage(`Directory synchronized ${formatSgtDateTime(result.directorySynchronizedAt)}.`)
      const controller = new AbortController()
      await load(0, controller.signal)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      setError(errorText(failure))
    } finally { setSyncBusy(false) }
  }
  return <Card><CardHeader><CardTitle>Account directory</CardTitle><p className="text-sm text-muted-foreground">Provider account snapshot · {data.totalItems} accounts · synced {data.directorySynchronizedAt ? formatSgtDateTime(data.directorySynchronizedAt) : 'not yet'}</p></CardHeader><CardContent className="space-y-4">
    <div className="flex flex-wrap gap-3"><Label className="sr-only" htmlFor="account-search">Search accounts</Label><Input id="account-search" placeholder="Search name or email" value={search} onChange={event => setSearch(event.target.value)} className="max-w-sm"/><Label htmlFor="account-role" className="sr-only">Filter by role</Label><select id="account-role" className="h-10 rounded-md border bg-background px-3 text-sm" value={role} onChange={event => setRole(event.target.value)}><option value="">All roles</option>{['ADMIN','CONTROLLER','CAPTAIN','UNASSIGNED'].map(value => <option key={value}>{value}</option>)}</select><Button variant="outline" disabled={busy || syncBusy} onClick={refresh}>Refresh</Button><Button variant="outline" disabled={busy || syncBusy} onClick={() => void synchronize()}>{syncBusy ? 'Synchronizing…' : 'Synchronize directory'}</Button></div>
    {syncMessage && <p role="status">{syncMessage}</p>}{error && <p role="alert">{error}</p>}{busy && !data.items.length && <p role="status">Loading accounts…</p>}{!busy && !error && !data.items.length && <p>No accounts found.</p>}
    {!!data.items.length && <><div className="max-h-[65vh] overflow-auto"><Table><TableHeader><TableRow><TableHead>Name</TableHead><TableHead>Email</TableHead><TableHead>Role</TableHead><TableHead>Status</TableHead><TableHead>Last sign-in</TableHead><TableHead>Details</TableHead></TableRow></TableHeader><TableBody>{data.items.map(user => <TableRow key={user.id}><TableCell>{user.displayName ?? user.name ?? '—'}</TableCell><TableCell>{user.email}</TableCell><TableCell>{user.role}</TableCell><TableCell>{user.available ? 'Available' : 'Unavailable'}</TableCell><TableCell>{user.lastSignInAt ? formatSgtDateTime(user.lastSignInAt) : 'Never'}</TableCell><TableCell><Button variant="link" onClick={() => showUser(user.id)}>View</Button></TableCell></TableRow>)}</TableBody></Table></div><div className="flex items-center justify-between"><span className="text-sm text-muted-foreground">Page {data.page + 1} of {Math.max(data.totalPages, 1)}</span><div className="flex gap-2"><Button variant="outline" disabled={busy || pageNo === 0} onClick={() => requestPage(pageNo - 1)}>Previous</Button><Button variant="outline" disabled={busy || pageNo + 1 >= data.totalPages} onClick={() => requestPage(pageNo + 1)}>Next</Button></div></div></>}
    <Dialog open={!!selected || detailBusy || !!detailError} onOpenChange={closeDetail}><DialogContent><DialogHeader><DialogTitle>Account details</DialogTitle><DialogDescription>Current provider projection and assignment</DialogDescription></DialogHeader>{detailBusy && <p role="status">Loading account…</p>}{detailError && <p role="alert">{detailError}</p>}{selected && <dl className="grid gap-3 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">Name</dt><dd>{selected.displayName ?? selected.name ?? '—'}</dd></div><div><dt className="text-muted-foreground">Email</dt><dd>{selected.email}</dd></div><div><dt className="text-muted-foreground">Role</dt><dd>{selected.role}</dd></div><div><dt className="text-muted-foreground">Status</dt><dd>{selected.available ? 'Available' : 'Unavailable'}</dd></div><div><dt className="text-muted-foreground">Created</dt><dd>{formatSgtDateTime(selected.createdAt)}</dd></div><div><dt className="text-muted-foreground">Last sign-in</dt><dd>{formatSgtDateTime(selected.lastSignInAt)}</dd></div><div><dt className="text-muted-foreground">Directory sync</dt><dd>{formatSgtDateTime(selected.synchronizedAt)}</dd></div><div className="sm:col-span-2"><dt className="text-muted-foreground">Current assignment</dt><dd>{selected.currentAssignment ? `${selected.currentAssignment.vehicleRegistrationNumber} · ${selected.currentAssignment.serviceId} direction ${selected.currentAssignment.directionId} · run ${selected.currentAssignment.demoRunId}` : 'None'}</dd></div></dl>}</DialogContent></Dialog>
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
    if (passwordLength < 15) { setError('Password must contain at least 15 characters.'); return }
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
  return <Card className="max-w-2xl"><CardHeader><CardTitle>Provision an account</CardTitle><p className="text-sm text-muted-foreground">Creates a confirmed-email Controller or Captain account.</p></CardHeader><CardContent><form className="space-y-4" onSubmit={event => void submit(event)}>
    <div><Label htmlFor="display-name">Display name</Label><Input id="display-name" required maxLength={200} value={displayName} onChange={event => { changed(); setName(event.target.value) }} disabled={locked}/></div>
    <div><Label htmlFor="provision-email">Email</Label><Input id="provision-email" type="email" required maxLength={254} value={email} onChange={event => { changed(); setEmail(event.target.value) }} disabled={locked}/></div>
    <div><Label htmlFor="provision-role">Role</Label><select id="provision-role" className="mt-1 h-10 w-full rounded-md border bg-background px-3" value={role} onChange={event => { changed(); setRole(event.target.value) }} disabled={locked}><option>CAPTAIN</option><option>CONTROLLER</option></select></div>
    <div><Label htmlFor="provision-password">Temporary password</Label><div className="flex gap-2"><Input id="provision-password" type={reveal ? 'text' : 'password'} required autoComplete="new-password" value={password} onChange={event => { changed(); setPassword(Array.from(event.target.value).slice(0, 128).join('')) }} disabled={locked}/><Button type="button" variant="outline" aria-label={reveal ? 'Hide password' : 'Show password'} aria-pressed={reveal} onClick={() => setReveal(value => !value)} disabled={locked}>{reveal ? 'Hide' : 'Show'}</Button></div><p className="mt-1 text-xs text-muted-foreground">15–128 characters. Passwords are never saved in this page.</p></div>
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    {loadingOperation && <div className="text-sm text-muted-foreground" role="status">Checking provisioning operation…</div>}
    {operation && <div className="text-xs text-muted-foreground">Operation {operation.id} · {operation.status}{isPending(operation.status) && <Button className="ml-2" variant="link" disabled={busy} onClick={() => { const controller = new AbortController(); setBusy(true); void check(operation.id, controller.signal).catch(failure => setError(errorText(failure))).finally(() => setBusy(false)) }}>Check status</Button>}</div>}
    {unresolvedOperationLink && !operationChecking && <Button type="button" variant="link" disabled={busy} onClick={() => { const controller = new AbortController(); setBusy(true); void check(operationIdFromUrl, controller.signal).catch(failure => setError(errorText(failure))).finally(() => setBusy(false)) }}>Retry status check</Button>}
    <Button type="submit" disabled={busy || (locked && !requestUncertain) || passwordLength < 15}>{busy ? 'Submitting…' : requestUncertain ? 'Retry same submission' : locked ? 'Waiting for operation' : 'Create account'}</Button>
  </form></CardContent></Card>
}

type CaptainOption = { id: string; displayName: string; email: string }
function Assignments({ token }: { token: string }) {
  const [data, setData] = useState<ActiveAssignments | null>(null); const [captains, setCaptains] = useState<CaptainOption[]>([]); const [choice, setChoice] = useState<Record<string, string>>({}); const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [message, setMessage] = useState(''); const [confirmBus, setConfirmBus] = useState<ActiveAssignments['items'][number] | null>(null); const [pending, setPending] = useState<Record<string, string>>({}); const [uncertain, setUncertain] = useState<Record<string, boolean>>({})
  const activeListRequest = useRef<AbortController | null>(null)
  const load = useCallback(async (signal: AbortSignal) => {
    setBusy(true); setError('')
    try {
      const [active, firstPage] = await Promise.all([
        apiRequest<ActiveAssignments>(ingestionAdminPath('admin/demo-runs/active/assignments'), token, signal),
        apiRequest<{ items: CaptainOption[]; totalPages: number }>(`${ingestionAdminPath('captains')}?available=true&page=0&size=100`, token, signal),
      ])
      if (!active || !firstPage) throw new Error('Assignment response was empty.')
      const rest = await Promise.all(Array.from({ length: Math.max(0, firstPage.totalPages - 1) }, (_, index) =>
        apiRequest<{ items: CaptainOption[] }>(`${ingestionAdminPath('captains')}?available=true&page=${index + 1}&size=100`, token, signal)))
      if (rest.some(page => !page)) throw new Error('Captain directory response was incomplete.')
      if (signal.aborted) return
      setData(active); setCaptains([firstPage, ...rest].flatMap(page => page!.items)); setUncertain({})
    } catch (failure) { if (!signal.aborted) { if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message); setError(errorText(failure)) } }
    finally { if (!signal.aborted) setBusy(false) }
  }, [token])
  const refresh = useCallback(() => { activeListRequest.current?.abort(); const controller = new AbortController(); activeListRequest.current = controller; void load(controller.signal) }, [load])
  useEffect(() => { const timer = window.setTimeout(refresh, 0); return () => { clearTimeout(timer); activeListRequest.current?.abort() } }, [refresh])
  const pollOperation = async (busId: string, operationId: string) => {
    setBusy(true); setError('')
    try {
      for (let attempt = 0; attempt < 12; attempt++) {
        const operation = await apiRequest<AssignmentOperation>(`/api/v1/planning/admin/assignment-operations/${operationId}`, token, new AbortController().signal)
        if (!operation) throw new Error('Operation response was empty.')
        if (!isPending(operation.status)) { setPending(current => { const next = { ...current }; delete next[busId]; return next }); setMessage(`Assignment ${operation.status.toLowerCase()}.`); refresh(); return }
        await new Promise(resolve => window.setTimeout(resolve, 1500))
      }
      setMessage(`Assignment remains pending (operation ${operationId}). It stays locked; check status before editing again.`)
    } catch (failure) { setError(errorText(failure)) } finally { setBusy(false) }
  }
  const save = async (bus: ActiveAssignments['items'][number]) => {
    if (!data?.runId || !canEditAssignments(data.status) || pending[bus.busId] || bus.pendingOperation || uncertain[bus.busId]) return
    const captainUserId = requestedCaptainId(choice[bus.busId], bus.captainUserId)
    setBusy(true); setMessage(''); setError('')
    try {
      const result = await apiRequest<AssignmentOperation>(`/api/v1/planning/admin/demo-runs/${data.runId}/buses/${encodeURIComponent(bus.busId)}/assignment`, token, new AbortController().signal, { method: 'PUT', body: { captainUserId, expectedVersion: bus.assignmentRevision }, headers: { 'Idempotency-Key': newIdempotencyKey() } })
      if (!result) throw new Error('Assignment response was empty.')
      if (isPending(result.status)) { setPending(current => ({ ...current, [bus.busId]: result.operationId })); setMessage(`Assignment pending (operation ${result.operationId}).`); await pollOperation(bus.busId, result.operationId) }
      else { setMessage(`Assignment ${result.status.toLowerCase()}.`); refresh() }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        const blockers = failure.extensions.blockingInstructions
        const summary = Array.isArray(blockers) ? blockers.map(item => { const blocker = item as { id?: string; status?: string }; return `${blocker.id ?? 'Instruction'} (${blocker.status ?? 'status unknown'})` }).join(', ') : ''
        setError(`Assignment conflict: ${failure.message}${summary ? ` Blocking instructions: ${summary}.` : ''} Refresh assignments and review the current blocker before trying again.`)
      } else {
        if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
        if (!(failure instanceof ApiError) || failure.status >= 500) {
          setUncertain(current => ({ ...current, [bus.busId]: true }))
          setMessage('The result is uncertain. This bus stays locked until a successful refresh confirms its assignment status.')
          refresh()
        }
        setError(errorText(failure))
      }
    } finally { setBusy(false) }
  }
  const previousCaptain = (bus: ActiveAssignments['items'][number]) => captains.find(person => person.id === bus.captainUserId)?.displayName ?? (bus.captainUserId ? `Unavailable captain (${bus.captainUserId})` : 'Unassigned')
  const proposedCaptain = confirmBus ? captains.find(person => person.id === (choice[confirmBus.busId] ?? confirmBus.captainUserId ?? ''))?.displayName ?? 'Unassigned' : ''
  const assignmentEditable = canEditAssignments(data?.status)
  return <Card><CardHeader><CardTitle>Active demo assignments</CardTitle><p className="text-sm text-muted-foreground">{data?.runId ? `Run ${data.runId} · ${data.status}` : 'No active demo run'}</p></CardHeader><CardContent className="space-y-4"><div className="flex justify-end"><Button variant="outline" disabled={busy} onClick={refresh}>Refresh</Button></div>{error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}{data?.items.length ? <div className="max-h-[65vh] overflow-auto"><Table><TableHeader><TableRow><TableHead>Bus</TableHead><TableHead>Service</TableHead><TableHead>Captain</TableHead><TableHead>Assignment revision</TableHead><TableHead>Assignment</TableHead></TableRow></TableHeader><TableBody>{data.items.map(bus => { const operationId = pending[bus.busId] ?? bus.pendingOperation?.operationId; return <TableRow key={bus.busId}><TableCell>{bus.registrationNumber}<span className="block text-xs text-muted-foreground">{bus.busId}</span></TableCell><TableCell>{bus.serviceId} · Direction {bus.directionId}</TableCell><TableCell><select aria-label={`Captain for ${bus.registrationNumber}`} className="h-9 rounded-md border bg-background px-2" disabled={!assignmentEditable || busy || !!bus.pendingOperation || !!operationId || !!uncertain[bus.busId]} value={choice[bus.busId] ?? bus.captainUserId ?? ''} onChange={event => setChoice(current => ({ ...current, [bus.busId]: event.target.value }))}><option value="">Unassigned</option>{bus.captainUserId && !captains.some(person => person.id === bus.captainUserId) && <option value={bus.captainUserId} disabled>Current captain unavailable</option>}{captains.map(person => <option key={person.id} value={person.id}>{person.displayName} · {person.email}</option>)}</select></TableCell><TableCell>v{bus.assignmentRevision}</TableCell><TableCell>{operationId ? <div className="space-y-1"><span className="block text-xs">{bus.pendingOperation?.status ?? 'Pending'} · {operationId}</span><Button size="sm" variant="outline" disabled={busy} onClick={() => void pollOperation(bus.busId, operationId)}>Check operation status</Button></div> : uncertain[bus.busId] ? <div className="space-y-1"><span className="block text-xs">Outcome uncertain</span><Button size="sm" variant="outline" disabled={busy} onClick={refresh}>Check assignment status</Button></div> : <Button size="sm" disabled={!assignmentEditable || busy || (choice[bus.busId] ?? bus.captainUserId ?? '') === (bus.captainUserId ?? '')} onClick={() => setConfirmBus(bus)}>Save</Button>}</TableCell></TableRow> })}</TableBody></Table></div> : data && <p>No buses in this active run.</p>}{data && !assignmentEditable && <p className="text-sm text-muted-foreground">Assignments are read-only when this run is not RUNNING or REPLAY_COMPLETE.</p>}</CardContent>
    <AlertDialog open={!!confirmBus} onOpenChange={open => { if (!open) setConfirmBus(null) }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Confirm captain assignment</AlertDialogTitle><AlertDialogDescription>{confirmBus && <>Bus {confirmBus.registrationNumber}: change captain from <strong>{previousCaptain(confirmBus)}</strong> to <strong>{proposedCaptain}</strong>?</>}</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel><AlertDialogAction disabled={busy} onClick={event => { event.preventDefault(); if (confirmBus) void save(confirmBus); setConfirmBus(null) }}>Confirm assignment</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
  </Card>
}

function Audit({ token }: { token: string }) {
  const [service, setService] = useState<(typeof services)[number]>('ingestion')
  const [from, setFrom] = useState(() => sgtDateTimeInputFromUtc(new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()))
  const [to, setTo] = useState(() => sgtDateTimeInputFromUtc(new Date().toISOString()))
  const [eventType, setEventType] = useState(''); const [category, setCategory] = useState(''); const [outcome, setOutcome] = useState(''); const [actorSubject, setActorSubject] = useState(''); const [runId, setRunId] = useState(''); const [entityId, setEntityId] = useState('')
  const [events, setEvents] = useState<AuditEvent[]>([]); const [detail, setDetail] = useState<AuditDetail | null>(null); const [pageNo, setPageNo] = useState(0); const [pages, setPages] = useState(0); const [retentionSeconds, setRetentionSeconds] = useState<number | null>(null); const [collectionStartedAt, setCollectionStartedAt] = useState<string | null>(null); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const activeListRequest = useRef<AbortController | null>(null)
  const filters = useMemo(() => ({ service, from, to, eventType, category, outcome, actorSubject, runId, entityId }), [service, from, to, eventType, category, outcome, actorSubject, runId, entityId])
  const filterKey = JSON.stringify(filters)
  const load = useCallback(async (page: number, signal: AbortSignal) => {
    setBusy(true); setError('')
    try {
      const q = new URLSearchParams({ page: String(page), size: '20' })
      for (const [key, value] of Object.entries({ eventType, category, outcome, actorSubject, runId, entityId })) if (value.trim()) q.set(key, value.trim())
      if (from) q.set('from', sgtDateTimeInputToUtc(from)); if (to) q.set('to', sgtDateTimeInputToUtc(to))
      const base = service === 'ingestion' ? ingestionAdminPath('admin/audit-events') : `/api/v1/${service}/admin/audit-events`
      const result = await apiRequest<AuditPage>(`${base}?${q}`, token, signal)
      if (!result) throw new Error('Audit response was empty.')
      setEvents(result.items); setPageNo(result.page); setPages(result.totalPages); setRetentionSeconds(result.retentionSeconds); setCollectionStartedAt(result.collectionStartedAt)
    } catch (failure) { if (!signal.aborted) { if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message); setError(errorText(failure)) } }
    finally { if (!signal.aborted) setBusy(false) }
  }, [actorSubject, category, entityId, eventType, from, outcome, runId, service, to, token])
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
  return <div className="space-y-4"><Card><CardContent className="space-y-3 pt-6"><div className="grid gap-3 sm:grid-cols-3"><div><Label htmlFor="audit-service">Service</Label><select id="audit-service" value={service} onChange={event => setService(event.target.value as typeof service)} className="mt-1 h-10 w-full rounded-md border bg-background px-3">{services.map(value => <option key={value} value={value}>{value[0].toUpperCase() + value.slice(1)}</option>)}</select></div><div className="rounded-md focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2"><Label htmlFor="audit-from">From (SGT)</Label><Input id="audit-from" type="datetime-local" min={minFrom} max={maxFrom} value={from} onChange={event => setFrom(event.target.value)}/></div><div className="rounded-md focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2"><Label htmlFor="audit-to">To (SGT)</Label><Input id="audit-to" type="datetime-local" min={minFrom} max={maxFrom} value={to} onChange={event => setTo(event.target.value)}/></div><div><Label htmlFor="audit-category">Category</Label><Input id="audit-category" placeholder="e.g. OPERATIONS" value={category} onChange={event => setCategory(event.target.value)}/></div><div><Label htmlFor="audit-event-type">Event type</Label><Input id="audit-event-type" value={eventType} onChange={event => setEventType(event.target.value)}/></div><div><Label htmlFor="audit-outcome">Outcome</Label><Input id="audit-outcome" value={outcome} onChange={event => setOutcome(event.target.value)}/></div><div><Label htmlFor="audit-actor">Actor subject</Label><Input id="audit-actor" value={actorSubject} onChange={event => setActorSubject(event.target.value)}/></div><div><Label htmlFor="audit-run">Run ID</Label><Input id="audit-run" value={runId} onChange={event => setRunId(event.target.value)}/></div><div><Label htmlFor="audit-entity">Entity ID</Label><Input id="audit-entity" value={entityId} onChange={event => setEntityId(event.target.value)}/></div></div><div className="flex items-center justify-between"><p className="text-xs text-muted-foreground">Default range is the last 24 hours. Available history is limited to 90 days.</p><Button variant="outline" disabled={busy} onClick={refresh}>Refresh</Button></div></CardContent></Card>
    <p className="text-xs text-muted-foreground">{retentionSeconds !== null ? `Retention: ${Math.round(retentionSeconds / 86400)} days` : ''}{collectionStartedAt ? ` · Collection started ${formatSgtDateTime(collectionStartedAt)}` : ''}</p>
    {error && <p role="alert">{error}</p>}{busy && !events.length && <p role="status">Loading audit events…</p>}{!busy && !error && !events.length && <p>No audit events match these filters.</p>}
    {!!events.length && <Card><CardContent className="pt-6"><div className="max-h-[55vh] overflow-auto"><Table><TableHeader><TableRow><TableHead>Time (SGT)</TableHead><TableHead>Event</TableHead><TableHead>Outcome</TableHead><TableHead>Actor</TableHead><TableHead>Summary</TableHead><TableHead>Details</TableHead></TableRow></TableHeader><TableBody>{events.map(event => <TableRow key={event.id}><TableCell className="whitespace-nowrap">{formatSgtDateTime(event.occurredAt)}</TableCell><TableCell>{event.category} · {event.eventType}</TableCell><TableCell>{event.outcome}</TableCell><TableCell>{event.actorSubject ?? '—'}</TableCell><TableCell>{event.summary}</TableCell><TableCell><Button variant="link" onClick={() => void open(event)}>View</Button></TableCell></TableRow>)}</TableBody></Table></div><div className="mt-4 flex justify-between"><span className="text-sm text-muted-foreground">Page {pageNo + 1} of {Math.max(pages, 1)}</span><div className="flex gap-2"><Button variant="outline" disabled={busy || pageNo === 0} onClick={() => requestPage(pageNo - 1)}>Previous</Button><Button variant="outline" disabled={busy || pageNo + 1 >= pages} onClick={() => requestPage(pageNo + 1)}>Next</Button></div></div></CardContent></Card>}
    <Dialog open={!!detail} onOpenChange={open => { if (!open) setDetail(null) }}><DialogContent className="max-h-[85vh] overflow-auto"><DialogHeader><DialogTitle>Audit event detail</DialogTitle><DialogDescription>{detail && `${detail.id} · ${formatSgtDateTime(detail.occurredAt)}`}</DialogDescription></DialogHeader>{detail && <><dl className="grid gap-3 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">Source</dt><dd>{detail.sourceService}</dd></div><div><dt className="text-muted-foreground">Entity</dt><dd>{detail.entityType} · {detail.entityId}</dd></div><div><dt className="text-muted-foreground">Summary</dt><dd>{detail.summary}</dd></div></dl><h3 className="mt-4 font-medium">Sanitized evidence</h3><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded bg-muted p-3 text-xs">{JSON.stringify(detail.details, null, 2)}</pre></>}</DialogContent></Dialog>
  </div>
}

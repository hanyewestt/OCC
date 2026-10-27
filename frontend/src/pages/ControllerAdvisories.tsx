import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { ApiError, apiRequest, type Advisory, type AdvisoryCandidate, type CaptainInstruction, type Hazard, type ManagedNotice, type NoticeSuggestion, type PageResult, type PlanningHazardHistory } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { dataClassificationLabel } from '@/lib/presentation'
import { formatSgtDateTime, sgtDateTimeInputToUtc } from '@/lib/time'
import { usePagedCollection } from '@/lib/paged-collection'

type Edit = { advisory: Advisory; type: AdvisoryCandidate['type']; stopSequence: string; speed: string; minutes: string; count: string; rationale: string; replacement: string }
type Draft = { id: string; previousId?: string; disruptionId: string; title: string; message: string; services: string; locations: string; from: string; until: string }
type HazardPageResponse = Omit<PageResult<Hazard>, 'totalItems' | 'totalPages'> & { size: number; totalElements: number }

export function ControllerAdvisories({ token, registerRefresh, onRefresh, onPendingChange, onRefreshTimeChange, view }: {
  token: string; registerRefresh: (callback: (signal: AbortSignal) => Promise<void>) => void
  onRefresh: () => void; onPendingChange: (pending: boolean) => void
  onRefreshTimeChange: (at: string | null) => void
  view: 'hazards' | 'planning' | 'audit'
}) {
  const [actionError, setActionError] = useState('')
  const [pending, setPending] = useState(false)
  const [edit, setEdit] = useState<Edit | null>(null)
  const [preview, setPreview] = useState<{ input: string; message: string } | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const draftIdentity = useRef<Draft | null>(null)
  const [kind, setKind] = useState('')
  const action = useRef<AbortController | null>(null)
  const hazardsEnabled = view === 'hazards' || edit !== null || draft !== null

  const collectionKey = token
  const hazardKind = view === 'hazards' ? kind : ''
  const hazards = usePagedCollection<Hazard, PageResult<Hazard>>(`${collectionKey}:${hazardKind}`, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    if (hazardKind) params.set('kind', hazardKind)
    try {
      const result = await apiRequest<HazardPageResponse>(`/api/v1/disruption/hazards?${params}`, token, signal)
      if (!result) throw new Error('Hazard response was empty.')
      return { ...result, totalItems: result.totalElements, totalPages: Math.ceil(result.totalElements / result.size) }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, hazard => hazard.disruptionId, hazardsEnabled)
  const advisories = usePagedCollection<Advisory, PageResult<Advisory>>(collectionKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100', pendingOnly: 'true' })
    try {
      const result = await apiRequest<PageResult<Advisory>>(`/api/v1/planning/advisories?${params}`, token, signal)
      if (!result) throw new Error('Advisory response was empty.')
      return result
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, advisory => advisory.id, view === 'planning')
  const instructions = usePagedCollection<CaptainInstruction, PageResult<CaptainInstruction>>(collectionKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<CaptainInstruction>>(`/api/v1/planning/instructions?${params}`, token, signal)
      if (!result) throw new Error('Instruction response was empty.')
      return result
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, instruction => instruction.id, view === 'planning' || view === 'audit')
  const notices = usePagedCollection<ManagedNotice, PageResult<ManagedNotice>>(collectionKey, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<ManagedNotice>>(`/api/v1/planning/notices?${params}`, token, signal)
      if (!result) throw new Error('Notice response was empty.')
      return result
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, notice => notice.id, view === 'planning' || view === 'audit')
  const hazardRequests = usePagedCollection<PlanningHazardHistory, PageResult<PlanningHazardHistory>>(`${collectionKey}:${view}`, async (page, signal) => {
    const params = new URLSearchParams({ page: String(page), size: '100' })
    try {
      const result = await apiRequest<PageResult<PlanningHazardHistory>>(`/api/v1/planning/diagnoses/hazard-history?${params}`, token, signal)
      if (!result) throw new Error('Hazard processing response was empty.')
      return result
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401 && !signal.aborted) void signOut(failure.message)
      throw failure
    }
  }, request => request.requestId, view === 'audit')
  const { items: hazardItems, hasMore: hazardHasMore, isLoading: hazardsLoading, isLoadingMore: hazardsLoadingMore,
    error: hazardError, loadNext: loadNextHazard } = hazards

  useEffect(() => {
    if (!edit || hazardItems.some(hazard => hazard.disruptionId === edit.advisory.disruptionId) ||
      hazardsLoading || hazardsLoadingMore || hazardError !== null || !hazardHasMore) return
    loadNextHazard()
  }, [edit, hazardError, hazardHasMore, hazardsLoading, hazardsLoadingMore, hazardItems, loadNextHazard])

  const collectionsLoading = view === 'hazards' ? hazards.isLoading : view === 'planning'
    ? hazards.isLoading || advisories.isLoading || instructions.isLoading || notices.isLoading
    : hazardRequests.isLoading || instructions.isLoading || notices.isLoading
  const refreshTimes = view === 'hazards' ? [hazards.lastSuccessAt] : view === 'planning'
    ? [advisories.lastSuccessAt, instructions.lastSuccessAt, notices.lastSuccessAt]
    : [hazardRequests.lastSuccessAt, instructions.lastSuccessAt, notices.lastSuccessAt]
  const lastRefreshTime = refreshTimes.includes(null) ? null : [...refreshTimes].sort()[0] ?? null
  useEffect(() => { onRefreshTimeChange(lastRefreshTime) }, [lastRefreshTime, onRefreshTimeChange])
  useEffect(() => { onPendingChange(pending || collectionsLoading) }, [pending, collectionsLoading, onPendingChange])
  useEffect(() => () => onPendingChange(false), [onPendingChange])
  useEffect(() => {
    registerRefresh(async signal => {
      const requests = view === 'hazards' ? [hazards.refresh(signal)] : view === 'planning'
        ? [advisories.refresh(signal), instructions.refresh(signal), notices.refresh(signal)]
        : [hazardRequests.refresh(signal), instructions.refresh(signal), notices.refresh(signal)]
      if (view !== 'hazards' && hazardsEnabled) requests.push(hazards.refresh(signal))
      await Promise.allSettled(requests)
    })
    return () => registerRefresh(async () => {})
  // usePagedCollection returns a new wrapper object each render; its reload callbacks are stable.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registerRefresh, view, hazardsEnabled, hazards.refresh, advisories.refresh, instructions.refresh, notices.refresh, hazardRequests.refresh])
  useEffect(() => () => action.current?.abort(), [])

  function updateDraft(value: Draft | null) {
    draftIdentity.current = value
    setDraft(value)
  }

  async function submit(path: string, body?: unknown) {
    action.current?.abort()
    const controller = new AbortController()
    action.current = controller
    setPending(true)
    try {
      await apiRequest(path, token, controller.signal, { method: 'POST', body })
      if (!controller.signal.aborted) setActionError('')
      return !controller.signal.aborted
    } catch (failure) {
      if (!controller.signal.aborted) {
        setActionError(failure instanceof Error ? failure.message : 'The change was not saved.')
        if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      }
      return false
    } finally {
      if (!controller.signal.aborted) { setPending(false); onRefresh() }
    }
  }

  async function suggestNotice() {
    if (!draft?.disruptionId) return
    const requestedDraft = draft
    action.current?.abort()
    const controller = new AbortController()
    action.current = controller
    setPending(true)
    try {
      const suggestion = await apiRequest<NoticeSuggestion>(
        `/api/v1/planning/notices/${requestedDraft.disruptionId}/suggestions`, token, controller.signal, { method: 'POST' })
      if (!suggestion) throw new Error('A suggestion could not be created.')
      if (!controller.signal.aborted && draftIdentity.current === requestedDraft) {
        updateDraft({ ...requestedDraft, title: suggestion.title, message: suggestion.message,
          services: suggestion.services.join(', '), locations: suggestion.locations.join(', ') })
        setActionError('')
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setActionError(failure instanceof Error ? failure.message : 'A suggestion could not be created.')
        if (failure instanceof ApiError && failure.status === 401) void signOut(failure.message)
      }
    } finally {
      if (!controller.signal.aborted) { setPending(false); onRefresh() }
    }
  }

  const stops = edit ? hazards.items.find(h => h.disruptionId === edit.advisory.disruptionId)?.exposures.filter(e => e.busId === edit.advisory.busId) ?? [] : []
  function instructionBody(value: Edit) {
    const stop = stops.find(s => s.stopSequence === Number(value.stopSequence))
    const parameters = value.type === 'REDUCE_SPEED'
      ? { stopCode: null, stopName: null, stopSequence: null, subsequentStopCount: null, speedReductionKmh: Number(value.speed), durationSeconds: Number(value.minutes) * 60 }
      : { stopCode: stop?.stopCode ?? null, stopName: stop?.stopName ?? null, stopSequence: stop?.stopSequence ?? null,
        subsequentStopCount: value.type === 'SLOW_DOWN_NEAR_STOPS' ? Number(value.count) : null, speedReductionKmh: null, durationSeconds: null }
    return { override: { type: value.type, parameters, rationale: value.rationale }, rationale: value.rationale, replaceInstructionId: value.replacement || null }
  }
  async function previewInstruction(value: Edit) {
    const controller = new AbortController()
    action.current?.abort()
    action.current = controller
    setPending(true)
    try {
      const result = await apiRequest<{ message: string }>(`/api/v1/planning/advisories/${value.advisory.id}/preview`, token, controller.signal,
        { method: 'POST', body: instructionBody(value) })
      if (!result) throw new Error('Preview unavailable.')
      if (!controller.signal.aborted) { setPreview({ input: JSON.stringify(value), message: result.message }); setActionError('') }
    } catch (failure) {
      if (!controller.signal.aborted) setActionError(failure instanceof Error ? failure.message : 'Preview unavailable.')
    } finally { if (!controller.signal.aborted) setPending(false) }
  }
  const pendingAdvisories = advisories.items.filter(advisory => advisory.status === 'PENDING')
  return <section aria-label="Multi-hazard advisories" className="space-y-5 border-t pt-6">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-semibold">{view === 'hazards' ? 'Multi-hazard conditions' : view === 'audit' ? 'Instruction and notice history' : 'Captain and public advisories'}</h2>
      <Button asChild variant="outline"><Link to="/notices" target="_blank" rel="noreferrer">Public notice board</Link></Button></div>
    {actionError && <p role="alert" className="rounded-lg border p-3 text-sm">{actionError}</p>}
    {view === 'hazards' && <>
      <div className="max-w-xs space-y-2"><Label htmlFor="hazard-kind">Hazard type</Label><select id="hazard-kind" value={kind} onChange={e => setKind(e.target.value)} className="h-9 w-full rounded-md border bg-background px-3 text-sm">
        <option value="">All types</option>{[['TRAFFIC_INCIDENT', 'Traffic Incidents'], ['FLOOD', 'Floods'], ['RAIL_DISRUPTION', 'Rail Disruptions'], ['ROADWORKS', 'Roadworks']].map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></div>
      {hazards.isLoading && !hazards.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading hazards…</p>}
      {hazards.error !== null && !hazards.items.length && <p role="alert" className="text-sm text-destructive">Hazards are unavailable.</p>}
      {!hazards.isLoading && hazards.error === null && hazards.latestPage && !hazards.items.length && <p className="text-sm text-muted-foreground">{kind ? 'No hazards found for the selected type.' : 'No hazards found.'}</p>}
      {!!hazards.items.length && <ScrollArea role="region" aria-label="Hazard conditions" tabIndex={0} className="h-[32rem] rounded-lg border"
        viewportProps={{ onScroll: hazards.onScroll }}>
        <div className="space-y-3 p-1 pr-3.5">{hazards.items.map(h => <Card key={h.disruptionId} className="min-h-28"><CardContent className="space-y-3">
          <div className="flex flex-wrap justify-between gap-2"><h3 className="font-semibold">{h.facts.title}</h3><p className="text-xs text-muted-foreground">{h.status} · {dataClassificationLabel(h.classification)}</p></div>
          <p className="text-sm">{h.facts.description}</p>{h.facts.instruction && <p className="text-sm font-medium">{h.facts.instruction}</p>}
          <p className="text-xs text-muted-foreground">Updated {formatSgtDateTime(h.updatedAt)} · Exposure uses simulated progress.</p>
          {h.exposures.length > 0 ? <p className="text-sm">Candidate stops: {h.exposures.map(e => `${e.serviceId} · ${e.stopName} (${e.basis})`).join('; ')}</p> : <p className="text-sm text-muted-foreground">No resolved upcoming bus exposure.</p>}
          {!!h.limitations.length && <details className="text-xs text-muted-foreground"><summary className="cursor-pointer font-medium">Evidence and limitations</summary><ul className="mt-2 space-y-1">{h.limitations.map(value => <li key={value}>{value}</li>)}</ul></details>}
        </CardContent></Card>)}</div>
      </ScrollArea>}
      <PagedCollectionControls label="hazards" hasItems={!!hazards.items.length} hasMore={hazards.hasMore}
        isLoadingMore={hazards.isLoadingMore} error={hazards.error} refreshError={hazards.refreshError} reload={hazards.reload} loadNext={hazards.loadNext} />
    </>}
    {view === 'audit' && <div className="space-y-3">
      <h3 className="font-semibold">Hazard processing history</h3>
      {hazardRequests.isLoading && !hazardRequests.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading hazard processing…</p>}
      {hazardRequests.error !== null && !hazardRequests.items.length && <p role="alert" className="text-sm text-destructive">Hazard processing status is unavailable.</p>}
      {!hazardRequests.isLoading && hazardRequests.error === null && hazardRequests.latestPage && !hazardRequests.items.length && <p className="text-sm text-muted-foreground">No completed or failed hazard processing in this scope.</p>}
      {!!hazardRequests.items.length && <ScrollArea role="region" aria-label="Hazard processing history" tabIndex={0}
        className="h-[28rem]" viewportProps={{ onScroll: hazardRequests.onScroll }}>
        <div className="space-y-3 p-1 pr-3.5">{hazardRequests.items.map(request => {
            const status = request.status === 'COMPLETED' ? 'Completed' : 'Failed'
            return <Card key={request.requestId} className="min-h-28"><CardContent className="space-y-1">
              <p className="font-medium">{request.title ?? `${request.eventType.replaceAll('_', ' ')} · ${request.disruptionId}`}</p>
              <p className="text-xs text-muted-foreground">{status}{request.failureCode ? ` · ${request.failureCode}` : ''} · {formatSgtDateTime(request.completedAt)}</p>
              <p className="text-xs text-muted-foreground">Event {request.eventId}</p>
            </CardContent></Card>
          })}</div>
      </ScrollArea>}
      <PagedCollectionControls label="hazard processing history" hasItems={!!hazardRequests.items.length} hasMore={hazardRequests.hasMore}
        isLoadingMore={hazardRequests.isLoadingMore} error={hazardRequests.error} refreshError={hazardRequests.refreshError} reload={hazardRequests.reload} loadNext={hazardRequests.loadNext} />
    </div>}
    {view === 'planning' && <>
      {advisories.isLoading && !advisories.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading pending advisories…</p>}
      {advisories.error !== null && !advisories.items.length && <p role="alert" className="text-sm text-destructive">Pending advisories are unavailable.</p>}
      {!advisories.isLoading && advisories.error === null && advisories.latestPage && !pendingAdvisories.length && <p className="text-sm text-muted-foreground">No pending captain advisories.</p>}
      {!!pendingAdvisories.length && <ScrollArea role="region" aria-label="Pending captain advisories" tabIndex={0}
        className="max-h-[28rem]" viewportProps={{ onScroll: advisories.onScroll }}>
        <div className="space-y-3 p-1 pr-3.5">{pendingAdvisories.map(a =>
        <Card key={a.id} className="min-h-28"><CardContent className="space-y-4"><p className="text-xs text-muted-foreground">Simulation · {a.busId} · Hazard: {dataClassificationLabel(a.hazardClassification)}</p>
          <h3 className="text-lg font-semibold">{a.preview}</h3><p className="text-sm text-muted-foreground">{a.candidate.rationale}</p>
          <div className="flex flex-wrap gap-2"><Button disabled={pending} onClick={() => void submit(`/api/v1/planning/advisories/${a.id}/decisions`, {})}>Approve and send</Button>
            <Button variant="outline" disabled={pending} onClick={() => setEdit({ advisory: a, type: a.candidate.type, stopSequence: String(a.candidate.parameters.stopSequence ?? ''), speed: String(a.candidate.parameters.speedReductionKmh ?? ''), minutes: a.candidate.parameters.durationSeconds ? String(a.candidate.parameters.durationSeconds / 60) : '', count: String(a.candidate.parameters.subsequentStopCount ?? 0), rationale: '', replacement: '' })}>Edit instruction</Button></div>
        </CardContent></Card>)}</div>
      </ScrollArea>}
      <PagedCollectionControls label="pending advisories" hasItems={!!advisories.items.length} hasMore={advisories.hasMore}
        isLoadingMore={advisories.isLoadingMore} error={advisories.error} refreshError={advisories.refreshError} reload={advisories.reload} loadNext={advisories.loadNext} />
      {edit && <Card><CardContent><form className="space-y-4" onSubmit={event => {
        event.preventDefault()
        if (preview?.input !== JSON.stringify(edit)) { void previewInstruction(edit); return }
        void submit(`/api/v1/planning/advisories/${edit.advisory.id}/decisions`, instructionBody(edit)).then(saved => { if (saved) setEdit(null) })
      }}>
        <h3 className="font-semibold">Edit instruction for {edit.advisory.busId}</h3>
        <div className="space-y-2"><Label htmlFor="instruction-type">Instruction</Label><select id="instruction-type" className="h-9 w-full rounded-md border bg-background px-3 text-sm" value={edit.type} onChange={e => setEdit({ ...edit, type: e.target.value as Edit['type'] })}>
          <option value="BE_CAUTIOUS">Be cautious</option><option value="REDUCE_SPEED">Reduce speed for a duration</option><option value="SLOW_DOWN_NEAR_STOPS">Slow down near stops</option></select></div>
        {edit.type === 'REDUCE_SPEED' ? <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-2"><Label htmlFor="speed-reduction">Reduction (km/h)</Label><Input id="speed-reduction" type="number" min="1" step="1" required value={edit.speed} onChange={e => setEdit({ ...edit, speed: e.target.value })} /></div>
          <div className="space-y-2"><Label htmlFor="speed-duration">Duration (min)</Label><Input id="speed-duration" type="number" min="1" step="1" required value={edit.minutes} onChange={e => setEdit({ ...edit, minutes: e.target.value })} /></div></div> :
          <div className="space-y-2"><Label htmlFor="instruction-stop">Starting stop</Label><select id="instruction-stop" required className="h-9 w-full rounded-md border bg-background px-3 text-sm" value={edit.stopSequence} onChange={e => setEdit({ ...edit, stopSequence: e.target.value })}>
            <option value="">Select a stop occurrence</option>{stops.map(stop => <option key={`${stop.stopCode}-${stop.stopSequence}`} value={stop.stopSequence}>{stop.stopName} · occurrence {stop.stopSequence}</option>)}</select></div>}
        {edit.type === 'SLOW_DOWN_NEAR_STOPS' && <div className="space-y-2"><Label htmlFor="subsequent-stops">Subsequent stops</Label><Input id="subsequent-stops" type="number" min="0" step="1" required value={edit.count} onChange={e => setEdit({ ...edit, count: e.target.value })} /></div>}
        <div className="space-y-2"><Label htmlFor="instruction-rationale">Reason for replacement</Label><Input id="instruction-rationale" required maxLength={2000} value={edit.rationale} onChange={e => setEdit({ ...edit, rationale: e.target.value })} /></div>
        <div className="space-y-2"><Label htmlFor="replace-instruction">Replace an active instruction</Label><select id="replace-instruction" className="h-9 w-full rounded-md border bg-background px-3 text-sm" value={edit.replacement} onChange={e => setEdit({ ...edit, replacement: e.target.value })}>
          <option value="">Do not replace</option>{instructions.items.filter(i => i.busId === edit.advisory.busId && ['ISSUED', 'ACKNOWLEDGED'].includes(i.status)).map(i => <option key={i.id} value={i.id}>{i.message}</option>)}</select></div>
        {preview?.input === JSON.stringify(edit) && <p className="rounded-lg border p-3 font-medium" aria-label="OBU preview">{preview.message}</p>}
        <div className="flex gap-2"><Button disabled={pending}>{preview?.input === JSON.stringify(edit) ? 'Approve edited instruction' : 'Preview instruction'}</Button><Button type="button" variant="outline" onClick={() => setEdit(null)}>Cancel edit</Button></div>
      </form></CardContent></Card>}
    </>}
    {(view === 'planning' || view === 'audit') && <>
      <h3 className="font-semibold">Issued instructions</h3>
      {instructions.isLoading && !instructions.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading instructions…</p>}
      {instructions.error !== null && !instructions.items.length && <p role="alert" className="text-sm text-destructive">Instruction history is unavailable.</p>}
      {!instructions.isLoading && instructions.error === null && instructions.latestPage && !instructions.items.length && <p className="text-sm text-muted-foreground">No issued instructions in this run.</p>}
      {!!instructions.items.length && <ScrollArea role="region" aria-label="Issued instructions" tabIndex={0} className="max-h-[28rem]"
        viewportProps={{ onScroll: instructions.onScroll }}><div className="space-y-3 p-1 pr-3.5">{instructions.items.map(i => <Card key={i.id} className="min-h-28"><CardContent className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1"><p className="font-medium">{i.message}</p><p className="text-xs text-muted-foreground">{i.busId} · {i.status}{i.acknowledgedAt ? ` · Acknowledged ${formatSgtDateTime(i.acknowledgedAt)}` : ''}</p></div>
          {['ISSUED', 'ACKNOWLEDGED'].includes(i.status) && <Button variant="outline" disabled={pending} onClick={() => void submit(`/api/v1/planning/instructions/${i.id}/cancellations`)}>Cancel instruction</Button>}
        </CardContent></Card>)}</div></ScrollArea>}
      <PagedCollectionControls label="instructions" hasItems={!!instructions.items.length} hasMore={instructions.hasMore}
        isLoadingMore={instructions.isLoadingMore} error={instructions.error} refreshError={instructions.refreshError} reload={instructions.reload} loadNext={instructions.loadNext} />
      <div className="flex items-center justify-between gap-3"><h3 className="font-semibold">Public notices</h3><Button variant="outline" disabled={pending} onClick={() => updateDraft({ id: crypto.randomUUID(), disruptionId: '', title: '', message: '', services: '', locations: '', from: '', until: '' })}>Draft notice</Button></div>
      {draft && <Card><CardContent><form className="space-y-4" onSubmit={event => {
        event.preventDefault()
        const body = { id: draft.id, disruptionId: draft.disruptionId, title: draft.title, message: draft.message,
          services: draft.services.split(',').map(s => s.trim()).filter(Boolean), locations: draft.locations.split(',').map(s => s.trim()).filter(Boolean),
          effectiveFrom: sgtDateTimeInputToUtc(draft.from), effectiveUntil: sgtDateTimeInputToUtc(draft.until) }
        void submit(draft.previousId ? `/api/v1/planning/notices/${draft.previousId}/replacements` : '/api/v1/planning/notices', body).then(saved => { if (saved) updateDraft(null) })
      }}>
        <h4 className="font-semibold">{draft.previousId ? 'Publish a correction' : 'New notice draft'}</h4>
        <div className="space-y-2"><Label htmlFor="notice-source">Source hazard</Label><select id="notice-source" required className="h-9 w-full rounded-md border bg-background px-3 text-sm" value={draft.disruptionId} disabled={!!draft.previousId} onChange={e => {
          const source = hazards.items.find(h => h.disruptionId === e.target.value)
          updateDraft({ ...draft, disruptionId: e.target.value, title: source?.facts.title ?? '', message: [source?.facts.description, source?.facts.instruction].filter(Boolean).join('\n') })
        }}><option value="">Select a hazard</option>{hazards.items.map(h => <option key={h.disruptionId} value={h.disruptionId}>{h.facts.title} · {dataClassificationLabel(h.classification)}</option>)}</select>
        <PagedCollectionControls label="source hazards" hasItems={!!hazards.items.length} hasMore={hazards.hasMore}
          isLoadingMore={hazards.isLoadingMore} error={hazards.error} refreshError={hazards.refreshError} reload={hazards.reload} loadNext={hazards.loadNext} /></div>
        <Button type="button" variant="outline" disabled={pending || !draft.disruptionId || hazards.items.find(h => h.disruptionId === draft.disruptionId)?.status === 'CLEARED'} onClick={() => void suggestNotice()}>Suggest passenger wording</Button>
        <div className="space-y-2"><Label htmlFor="notice-title">Title</Label><Input id="notice-title" required maxLength={160} value={draft.title} onChange={e => updateDraft({ ...draft, title: e.target.value })} /></div>
        <div className="space-y-2"><Label htmlFor="notice-message">Passenger message</Label><textarea id="notice-message" required maxLength={2000} rows={4} className="w-full rounded-md border bg-background p-3 text-sm" value={draft.message} onChange={e => updateDraft({ ...draft, message: e.target.value })} /></div>
        <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-2"><Label htmlFor="notice-services">Services (comma separated)</Label><Input id="notice-services" value={draft.services} onChange={e => updateDraft({ ...draft, services: e.target.value })} /></div><div className="space-y-2"><Label htmlFor="notice-locations">Locations (comma separated)</Label><Input id="notice-locations" value={draft.locations} onChange={e => updateDraft({ ...draft, locations: e.target.value })} /></div></div>
        <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-2"><Label htmlFor="notice-from">Starts (SGT)</Label><Input id="notice-from" required type="datetime-local" value={draft.from} onChange={e => updateDraft({ ...draft, from: e.target.value })} /></div><div className="space-y-2"><Label htmlFor="notice-until">Expires (SGT)</Label><Input id="notice-until" required type="datetime-local" value={draft.until} onChange={e => updateDraft({ ...draft, until: e.target.value })} /></div></div>
        <div className="space-y-2 rounded-lg border p-4"><p className="text-xs text-muted-foreground">Passenger preview</p><h4 className="font-semibold">{draft.title || 'Notice title'}</h4><p className="whitespace-pre-wrap text-sm">{draft.message || 'Notice message'}</p></div>
        <div className="flex gap-2"><Button disabled={pending}>{draft.previousId ? 'Publish correction' : 'Save draft'}</Button><Button type="button" variant="outline" onClick={() => updateDraft(null)}>Cancel draft</Button></div>
      </form></CardContent></Card>}
      {notices.isLoading && !notices.latestPage && <p role="status" className="text-sm text-muted-foreground">Loading notices…</p>}
      {notices.error !== null && !notices.items.length && <p role="alert" className="text-sm text-destructive">Notices are unavailable.</p>}
      {!notices.isLoading && notices.error === null && notices.latestPage && !notices.items.length && <p className="text-sm text-muted-foreground">No notices.</p>}
      {!!notices.items.length && <ScrollArea role="region" aria-label="Managed notices" tabIndex={0} className="max-h-[32rem]"
        viewportProps={{ onScroll: notices.onScroll }}><div className="space-y-3 p-1 pr-3.5">{notices.items.map(n => <Card key={n.id} className="min-h-28"><CardContent className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="font-medium">{n.title}</h4><p className="text-xs text-muted-foreground">{dataClassificationLabel(n.classification)} · {n.status}</p></div><p className="whitespace-pre-wrap text-sm">{n.message}</p>
          <div className="flex flex-wrap gap-2">{n.status === 'DRAFT' && <Button disabled={pending} onClick={() => void submit(`/api/v1/planning/notices/${n.id}/publications`)}>Publish notice</Button>}
            {n.status === 'PUBLISHED' && <><Button variant="outline" disabled={pending} onClick={() => void submit(`/api/v1/planning/notices/${n.id}/withdrawals`)}>Withdraw notice</Button>
              <Button variant="outline" disabled={pending} onClick={() => updateDraft({ id: crypto.randomUUID(), previousId: n.id, disruptionId: n.disruptionId, title: n.title, message: n.message, services: n.services.join(', '), locations: n.locations.join(', '), from: '', until: '' })}>Correct notice</Button></>}
            {n.classification === 'SIMULATION' && n.demoRunId && <Button asChild variant="outline"><Link target="_blank" rel="noreferrer" to={`/notices?classification=SIMULATION&demoRunId=${encodeURIComponent(n.demoRunId)}`}>Preview simulation board</Link></Button>}
          </div>
        </CardContent></Card>)}</div></ScrollArea>}
      <PagedCollectionControls label="managed notices" hasItems={!!notices.items.length} hasMore={notices.hasMore}
        isLoadingMore={notices.isLoadingMore} error={notices.error} refreshError={notices.refreshError} reload={notices.reload} loadNext={notices.loadNext} />
    </>}
  </section>
}

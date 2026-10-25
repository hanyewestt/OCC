import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, apiRequest, type Advisory, type AdvisoryCandidate, type CaptainInstruction, type Hazard, type ManagedNotice, type NoticeSuggestion, type PageResult, type PlanningHazardRequest } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { dataClassificationLabel, diagnosisStatusLabel } from '@/lib/presentation'
import { formatSgtDateTime, sgtDateTimeInputToUtc } from '@/lib/time'

type Edit = { advisory: Advisory; type: AdvisoryCandidate['type']; stopSequence: string; speed: string; minutes: string; count: string; rationale: string; replacement: string }
type Draft = { id: string; previousId?: string; disruptionId: string; title: string; message: string; services: string; locations: string; from: string; until: string }

function PageNavigation({ label, page, totalPages, onPageChange }: {
  label: string; page: number; totalPages: number; onPageChange: (page: number) => void
}) {
  if (totalPages <= 1 && page === 0) return null
  return <nav aria-label={`${label} pages`} className="flex items-center gap-3">
    <Button variant="outline" aria-label={`Previous ${label}`} disabled={page === 0} onClick={() => onPageChange(page - 1)}>Previous</Button>
    <p className="text-sm text-muted-foreground">Page {page + 1} of {Math.max(1, totalPages)}</p>
    <Button variant="outline" aria-label={`Next ${label}`} disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>Next</Button>
  </nav>
}

export function ControllerAdvisories({ token, refreshIntervalSeconds, view }: {
  token: string; refreshIntervalSeconds: number; view: 'hazards' | 'planning' | 'audit'
}) {
  const [hazards, setHazards] = useState<PageResult<Hazard> | null>(null)
  const [advisories, setAdvisories] = useState<PageResult<Advisory> | null>(null)
  const [instructions, setInstructions] = useState<PageResult<CaptainInstruction> | null>(null)
  const [notices, setNotices] = useState<PageResult<ManagedNotice> | null>(null)
  const [hazardRequests, setHazardRequests] = useState<PageResult<PlanningHazardRequest> | null>(null)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')
  const [pending, setPending] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [pages, setPages] = useState({ hazards: 0, hazardRequests: 0, advisories: 0, instructions: 0, notices: 0 })
  const [edit, setEdit] = useState<Edit | null>(null)
  const [preview, setPreview] = useState<{ input: string; message: string } | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const draftIdentity = useRef<Draft | null>(null)
  const [kind, setKind] = useState('')
  const poll = useRef<AbortController | null>(null)
  const action = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    poll.current = controller
    let timer: number | undefined
    async function load() {
      const results = await Promise.allSettled([
        apiRequest<PageResult<Hazard>>(`/api/v1/disruption/hazards?page=${pages.hazards}&size=100`, token, controller.signal),
        apiRequest<PageResult<Advisory>>(`/api/v1/planning/advisories?page=${pages.advisories}&size=100&pendingOnly=true`, token, controller.signal),
        apiRequest<PageResult<CaptainInstruction>>(`/api/v1/planning/instructions?page=${pages.instructions}&size=100`, token, controller.signal),
        apiRequest<PageResult<ManagedNotice>>(`/api/v1/planning/notices?page=${pages.notices}&size=100`, token, controller.signal),
        apiRequest<PageResult<PlanningHazardRequest>>(`/api/v1/planning/diagnoses/hazard-requests?page=${pages.hazardRequests}&size=100`, token, controller.signal),
      ])
      if (controller.signal.aborted) return
      setHazards(results[0].status === 'fulfilled' ? results[0].value : null)
      setAdvisories(results[1].status === 'fulfilled' ? results[1].value : null)
      setInstructions(results[2].status === 'fulfilled' ? results[2].value : null)
      setNotices(results[3].status === 'fulfilled' ? results[3].value : null)
      setHazardRequests(results[4].status === 'fulfilled' ? results[4].value : null)
      const totals = results.map(result => result.status === 'fulfilled' ? result.value?.totalPages ?? 0 : null)
      setPages(current => {
        const maximum = totals.map(total => total === null ? null : Math.max(0, total - 1))
        if (maximum.every((value, index) => value === null || current[(['hazards', 'advisories', 'instructions', 'notices', 'hazardRequests'] as const)[index]] <= value)) return current
        return {
          hazards: maximum[0] === null ? current.hazards : Math.min(current.hazards, maximum[0]),
          advisories: maximum[1] === null ? current.advisories : Math.min(current.advisories, maximum[1]),
          instructions: maximum[2] === null ? current.instructions : Math.min(current.instructions, maximum[2]),
          notices: maximum[3] === null ? current.notices : Math.min(current.notices, maximum[3]),
          hazardRequests: maximum[4] === null ? current.hazardRequests : Math.min(current.hazardRequests, maximum[4]),
        }
      })
      const failures = results.flatMap((result, index) => result.status === 'rejected' ? [`${['Hazards', 'Advisories', 'Instructions', 'Notices', 'Hazard processing'][index]} unavailable.`] : [])
      setError(failures.join(' '))
      if (results.some(result => result.status === 'rejected' && result.reason instanceof ApiError && result.reason.status === 401)) void signOut()
      timer = window.setTimeout(() => void load(), refreshIntervalSeconds * 1000)
    }
    void load()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [token, refreshIntervalSeconds, refresh, pages])
  useEffect(() => () => action.current?.abort(), [])

  function updateDraft(value: Draft | null) {
    draftIdentity.current = value
    setDraft(value)
  }

  async function submit(path: string, body?: unknown) {
    poll.current?.abort()
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
      if (!controller.signal.aborted) { setPending(false); setRefresh(value => value + 1) }
    }
  }

  async function suggestNotice() {
    if (!draft?.disruptionId) return
    const requestedDraft = draft
    poll.current?.abort()
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
      if (!controller.signal.aborted) { setPending(false); setRefresh(value => value + 1) }
    }
  }

  const stops = edit ? hazards?.items.find(h => h.disruptionId === edit.advisory.disruptionId)?.exposures.filter(e => e.busId === edit.advisory.busId) ?? [] : []
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
  return <section aria-label="Multi-hazard advisories" className="space-y-5 border-t pt-6">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-semibold">{view === 'hazards' ? 'Multi-hazard conditions' : view === 'audit' ? 'Instruction and notice history' : 'Captain and public advisories'}</h2>
      <Button asChild variant="outline"><Link to="/notices" target="_blank" rel="noreferrer">Public notice board</Link></Button></div>
    {error && <p role="alert" className="rounded-lg border p-3 text-sm">{error}</p>}
    {actionError && <p role="alert" className="rounded-lg border p-3 text-sm">{actionError}</p>}
    {view === 'hazards' && <>
      <div role="group" aria-label="Hazard feed freshness" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span className="font-medium">Feed freshness</span>
        {!hazards?.feeds?.length ? <span>Feed status unavailable.</span> : hazards.feeds.map(feed =>
          <span key={feed.kind}>{feed.kind.replace('_', ' ')} · {feed.status.toLowerCase()}
            {feed.lastSuccessAt ? ` · last success ${formatSgtDateTime(feed.lastSuccessAt)}` : ''}</span>)}
      </div>
      <div className="max-w-xs space-y-2"><Label htmlFor="hazard-kind">Hazard type</Label><select id="hazard-kind" value={kind} onChange={e => setKind(e.target.value)} className="h-9 w-full rounded-md border bg-background px-3 text-sm">
        <option value="">All types</option>{['TRAFFIC_INCIDENT', 'FLOOD', 'RAIL_DISRUPTION', 'ROADWORKS'].map(value => <option key={value}>{value}</option>)}
      </select></div>
      {hazards === null ? <p role="status" className="text-sm text-muted-foreground">Hazard data unavailable or loading.</p> : hazards.items.length === 0 ? <p className="text-sm text-muted-foreground">No current additional hazards.</p> :
        hazards.items.filter(h => !kind || h.kind === kind).map(h => <Card key={h.disruptionId}><CardContent className="space-y-3">
          <div className="flex flex-wrap justify-between gap-2"><h3 className="font-semibold">{h.facts.title}</h3><p className="text-xs text-muted-foreground">{h.status} · {dataClassificationLabel(h.classification)}</p></div>
          <p className="text-sm">{h.facts.description}</p>{h.facts.instruction && <p className="text-sm font-medium">{h.facts.instruction}</p>}
          <p className="text-xs text-muted-foreground">Updated {formatSgtDateTime(h.updatedAt)} · Candidate exposure uses simulated bus progress.</p>
          {h.exposures.length > 0 ? <p className="text-sm">Candidate stops: {h.exposures.map(e => `${e.serviceId} · ${e.stopName} (${e.basis})`).join('; ')}</p> : <p className="text-sm text-muted-foreground">No resolved upcoming bus exposure.</p>}
          <ul className="space-y-1 text-xs text-muted-foreground">{h.limitations.map(value => <li key={value}>{value}</li>)}</ul>
        </CardContent></Card>)}
      <PageNavigation label="hazards" page={pages.hazards} totalPages={hazards?.totalPages ?? 0}
        onPageChange={page => setPages(current => ({ ...current, hazards: page }))} />
    </>}
    {(view === 'planning' || view === 'hazards') && <div className="space-y-3">
      <h3 className="font-semibold">Hazard processing</h3>
      {hazardRequests === null ? <p role="status" className="text-sm text-muted-foreground">Hazard processing status unavailable or loading.</p> :
        hazardRequests.items.length === 0 ? <p className="text-sm text-muted-foreground">No pending or failed hazard processing.</p> :
          hazardRequests.items.map(request => {
            const status = diagnosisStatusLabel(request.status)
            const source = hazards?.items.find(hazard => hazard.disruptionId === request.disruptionId)
            return <Card key={request.requestId}><CardContent className="space-y-1">
              <p className="font-medium">{source?.facts.title ?? `${request.eventType.replaceAll('_', ' ')} · ${request.disruptionId}`}</p>
              <p className="text-xs text-muted-foreground">{status}{request.failureCode ? ` · ${request.failureCode}` : ''} · {formatSgtDateTime(request.createdAt)}</p>
              {request.status === 'FAILED_TERMINAL' && <p className="text-sm">No advisory was issued. Review the feed and bus exposure before retrying.</p>}
            </CardContent></Card>
          })}
      <PageNavigation label="hazard processing" page={pages.hazardRequests} totalPages={hazardRequests?.totalPages ?? 0}
        onPageChange={page => setPages(current => ({ ...current, hazardRequests: page }))} />
    </div>}
    {view === 'planning' && <>
      {advisories === null ? <p role="status" className="text-sm text-muted-foreground">Advisories unavailable or loading.</p> : advisories.items.filter(a => a.status === 'PENDING').length === 0 ? <p className="text-sm text-muted-foreground">No pending captain advisories.</p> : advisories.items.filter(a => a.status === 'PENDING').map(a =>
        <Card key={a.id}><CardContent className="space-y-4"><p className="text-xs text-muted-foreground">Simulation · {a.busId} · Hazard: {dataClassificationLabel(a.hazardClassification)}</p>
          <h3 className="text-lg font-semibold">{a.preview}</h3><p className="text-sm text-muted-foreground">{a.candidate.rationale}</p>
          <div className="flex flex-wrap gap-2"><Button disabled={pending} onClick={() => void submit(`/api/v1/planning/advisories/${a.id}/decisions`, {})}>Approve and send</Button>
            <Button variant="outline" disabled={pending} onClick={() => setEdit({ advisory: a, type: a.candidate.type, stopSequence: String(a.candidate.parameters.stopSequence ?? ''), speed: String(a.candidate.parameters.speedReductionKmh ?? ''), minutes: a.candidate.parameters.durationSeconds ? String(a.candidate.parameters.durationSeconds / 60) : '', count: String(a.candidate.parameters.subsequentStopCount ?? 0), rationale: '', replacement: '' })}>Edit instruction</Button></div>
        </CardContent></Card>)}
      <PageNavigation label="pending advisories" page={pages.advisories} totalPages={advisories?.totalPages ?? 0}
        onPageChange={page => setPages(current => ({ ...current, advisories: page }))} />
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
          <option value="">Do not replace</option>{instructions?.items.filter(i => i.busId === edit.advisory.busId && ['ISSUED', 'ACKNOWLEDGED'].includes(i.status)).map(i => <option key={i.id} value={i.id}>{i.message}</option>)}</select></div>
        {preview?.input === JSON.stringify(edit) && <p className="rounded-lg border p-3 font-medium" aria-label="OBU preview">{preview.message}</p>}
        <div className="flex gap-2"><Button disabled={pending}>{preview?.input === JSON.stringify(edit) ? 'Approve edited instruction' : 'Preview instruction'}</Button><Button type="button" variant="outline" onClick={() => setEdit(null)}>Cancel edit</Button></div>
      </form></CardContent></Card>}
    </>}
    {(view === 'planning' || view === 'audit') && <>
      <h3 className="font-semibold">Issued instructions</h3>
      {instructions === null ? <p className="text-sm text-muted-foreground">Instruction history unavailable or loading.</p> : instructions.items.length === 0 ? <p className="text-sm text-muted-foreground">No issued instructions in this run.</p> : instructions.items.map(i => <Card key={i.id}><CardContent className="flex flex-wrap items-center justify-between gap-4">
        <div className="space-y-1"><p className="font-medium">{i.message}</p><p className="text-xs text-muted-foreground">{i.busId} · {i.status}{i.acknowledgedAt ? ` · Acknowledged ${formatSgtDateTime(i.acknowledgedAt)}` : ''}</p></div>
        {['ISSUED', 'ACKNOWLEDGED'].includes(i.status) && <Button variant="outline" disabled={pending} onClick={() => void submit(`/api/v1/planning/instructions/${i.id}/cancellations`)}>Cancel instruction</Button>}
      </CardContent></Card>)}
      <PageNavigation label="instructions" page={pages.instructions} totalPages={instructions?.totalPages ?? 0}
        onPageChange={page => setPages(current => ({ ...current, instructions: page }))} />
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
          const source = hazards?.items.find(h => h.disruptionId === e.target.value)
          updateDraft({ ...draft, disruptionId: e.target.value, title: source?.facts.title ?? '', message: [source?.facts.description, source?.facts.instruction].filter(Boolean).join('\n') })
        }}><option value="">Select a hazard</option>{hazards?.items.map(h => <option key={h.disruptionId} value={h.disruptionId}>{h.facts.title} · {dataClassificationLabel(h.classification)}</option>)}</select></div>
        <Button type="button" variant="outline" disabled={pending || !draft.disruptionId || hazards?.items.find(h => h.disruptionId === draft.disruptionId)?.status === 'CLEARED'} onClick={() => void suggestNotice()}>Suggest passenger wording</Button>
        <div className="space-y-2"><Label htmlFor="notice-title">Title</Label><Input id="notice-title" required maxLength={160} value={draft.title} onChange={e => updateDraft({ ...draft, title: e.target.value })} /></div>
        <div className="space-y-2"><Label htmlFor="notice-message">Passenger message</Label><textarea id="notice-message" required maxLength={2000} rows={4} className="w-full rounded-md border bg-background p-3 text-sm" value={draft.message} onChange={e => updateDraft({ ...draft, message: e.target.value })} /></div>
        <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-2"><Label htmlFor="notice-services">Services (comma separated)</Label><Input id="notice-services" value={draft.services} onChange={e => updateDraft({ ...draft, services: e.target.value })} /></div><div className="space-y-2"><Label htmlFor="notice-locations">Locations (comma separated)</Label><Input id="notice-locations" value={draft.locations} onChange={e => updateDraft({ ...draft, locations: e.target.value })} /></div></div>
        <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-2"><Label htmlFor="notice-from">Starts (SGT)</Label><Input id="notice-from" required type="datetime-local" value={draft.from} onChange={e => updateDraft({ ...draft, from: e.target.value })} /></div><div className="space-y-2"><Label htmlFor="notice-until">Expires (SGT)</Label><Input id="notice-until" required type="datetime-local" value={draft.until} onChange={e => updateDraft({ ...draft, until: e.target.value })} /></div></div>
        <div className="space-y-2 rounded-lg border p-4"><p className="text-xs text-muted-foreground">Passenger preview</p><h4 className="font-semibold">{draft.title || 'Notice title'}</h4><p className="whitespace-pre-wrap text-sm">{draft.message || 'Notice message'}</p></div>
        <div className="flex gap-2"><Button disabled={pending}>{draft.previousId ? 'Publish correction' : 'Save draft'}</Button><Button type="button" variant="outline" onClick={() => updateDraft(null)}>Cancel draft</Button></div>
      </form></CardContent></Card>}
      {notices === null ? <p className="text-sm text-muted-foreground">Notices unavailable or loading.</p> : notices.items.length === 0 ? <p className="text-sm text-muted-foreground">No notices.</p> : notices.items.map(n => <Card key={n.id}><CardContent className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="font-medium">{n.title}</h4><p className="text-xs text-muted-foreground">{dataClassificationLabel(n.classification)} · {n.status}</p></div><p className="whitespace-pre-wrap text-sm">{n.message}</p>
        <div className="flex flex-wrap gap-2">{n.status === 'DRAFT' && <Button disabled={pending} onClick={() => void submit(`/api/v1/planning/notices/${n.id}/publications`)}>Publish notice</Button>}
          {n.status === 'PUBLISHED' && <><Button variant="outline" disabled={pending} onClick={() => void submit(`/api/v1/planning/notices/${n.id}/withdrawals`)}>Withdraw notice</Button>
            <Button variant="outline" disabled={pending} onClick={() => updateDraft({ id: crypto.randomUUID(), previousId: n.id, disruptionId: n.disruptionId, title: n.title, message: n.message, services: n.services.join(', '), locations: n.locations.join(', '), from: '', until: '' })}>Correct notice</Button></>}
          {n.classification === 'SIMULATION' && n.demoRunId && <Button asChild variant="outline"><Link target="_blank" rel="noreferrer" to={`/notices?classification=SIMULATION&demoRunId=${encodeURIComponent(n.demoRunId)}`}>Preview simulation board</Link></Button>}
        </div>
      </CardContent></Card>)}
      <PageNavigation label="notices" page={pages.notices} totalPages={notices?.totalPages ?? 0}
        onPageChange={page => setPages(current => ({ ...current, notices: page }))} />
    </>}
  </section>
}

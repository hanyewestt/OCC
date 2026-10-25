import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import { PagedCollectionControls } from '@/components/PagedCollectionControls'
import { ApiError, apiRequest, type ControllerBusInstruction, type Decision, type DiagnosisEvidenceDetail, type PendingRecommendation, type PlanningDiagnosisPage } from '@/lib/api'
import { signOut } from '@/lib/auth'
import { dataClassificationLabel, diagnosisStatusLabel } from '@/lib/presentation'
import type { Planning } from './ControllerPage'
import type { PagedCollection } from '@/lib/paged-collection'
import { DataMessage, timestamp } from './ControllerViews'

function seconds(value: number) {
  const minutes = Math.floor(value / 60)
  const remainder = value % 60
  return minutes ? `${minutes}:${String(remainder).padStart(2, '0')}` : `${remainder}s`
}

function suggestedInstruction(recommendation: PendingRecommendation) {
  if (recommendation.mitigationType === 'HOLD') return `Hold for ${recommendation.adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  if (recommendation.mitigationType === 'DWELL_ADJUSTMENT') return `Adjust dwell by ${recommendation.adjustmentSeconds} seconds at stop ${recommendation.controlStopCode}.`
  return `Dispatch standby support for ${recommendation.targetBusReference} at stop ${recommendation.controlStopCode}.`
}

function affectedBuses(recommendation: PendingRecommendation) {
  return recommendation.affectedBusReferences.length
    ? recommendation.affectedBusReferences : [recommendation.targetBusReference]
}

function initialInstructions(recommendation: PendingRecommendation): ControllerBusInstruction[] {
  return affectedBuses(recommendation).slice(0, 3).map((busReference, index) => ({
    busReference,
    instruction: index === 0 ? suggestedInstruction(recommendation) : '',
  }))
}

function incidentTiming(item: DiagnosisEvidenceDetail) {
  if (item.reportTime === null) {
    return `Provider report time unavailable${item.fetchedAt ? `; fetched ${timestamp(item.fetchedAt)}, which does not establish incident chronology` : ''}.`
  }
  if (item.firstConfirmedAt === null || item.secondsBeforeFirstConfirmed === null) {
    return `Report time ${timestamp(item.reportTime)} is available, but timing relative to the first confirmed irregularity is unavailable.`
  }
  if (item.reportTime === item.firstConfirmedAt) {
    return `Reported at the same time as the first confirmed irregularity (${timestamp(item.reportTime)}).`
  }

  const differenceMs = Date.parse(item.firstConfirmedAt) - Date.parse(item.reportTime)
  if (Math.abs(differenceMs) < 1_000) {
    const direction = item.secondsBeforeFirstConfirmed < 0 ? 'after' : 'before'
    return `Reported less than 1 second ${direction} the first confirmed irregularity (${timestamp(item.reportTime)}).`
  }

  const offset = Math.abs(item.secondsBeforeFirstConfirmed)
  const unit = offset === 1 ? 'second' : 'seconds'
  const direction = item.secondsBeforeFirstConfirmed > 0 ? 'before' : 'after'
  return `Reported ${offset} ${unit} ${direction} the first confirmed irregularity (${timestamp(item.reportTime)}).`
}

function EvidenceDetailList({ items }: { items: DiagnosisEvidenceDetail[] }) {
  if (!items.length) return null
  return <ul className="mt-2 list-disc space-y-3 pl-5 text-xs text-muted-foreground">
    {items.map(item => <li key={item.evidenceId}>
      {item.incidentType && <p className="font-medium text-foreground">{item.incidentType}</p>}
      {item.message && <p>{item.message}</p>}
      <p>{incidentTiming(item)}</p>
      {item.distanceMetersFromSuppliedBusPosition !== null && <p>Approx. {Math.round(item.distanceMetersFromSuppliedBusPosition).toLocaleString('en-SG')} m from the supplied bus position near stop {item.monitoredStopCode}.</p>}
      <p>Incident evidence: {item.evidenceId}{item.busPositionEvidenceId ? ` · Bus-position evidence: ${item.busPositionEvidenceId}` : ''}</p>
    </li>)}
  </ul>
}

type EditingRecommendation = { identity: string; scopeIdentity: string; recommendation: PendingRecommendation }

export function ControllerPlanning({ recommendations, diagnoses, loading, modeError, mode, demoRunId, token, onSaved }: {
  recommendations: PagedCollection<PendingRecommendation, Planning['recommendations']>
  diagnoses: PagedCollection<PlanningDiagnosisPage['items'][number], PlanningDiagnosisPage>
  loading: boolean; modeError: string; mode: 'LIVE' | 'DEMO'; demoRunId: string | null; token: string
  onSaved: () => void
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const [filter, setFilter] = useState('')
  const [message, setMessage] = useState('')
  const [editingRecommendation, setEditingRecommendation] = useState<EditingRecommendation | null>(null)
  const recommendationPage = recommendations.latestPage
  const recommendationScopeMatches = !modeError && recommendationPage?.mode === mode &&
    (recommendationPage.demoRunId ?? null) === demoRunId
  const recommendationError = modeError ? 'Simulation state is unavailable.' : recommendations.error !== null && !recommendations.items.length
    ? 'Unable to load recommendations.' : recommendationPage && !recommendationScopeMatches && !loading
      ? 'Data selection changed. Refresh before making a decision.' : ''
  const diagnosisPage = diagnoses.latestPage
  const diagnosisScopeMatches = !modeError && diagnosisPage?.mode === mode && (diagnosisPage.demoRunId ?? null) === demoRunId
  const diagnosisError = modeError ? 'Simulation state is unavailable.' : diagnoses.error !== null && !diagnoses.items.length
    ? 'Unable to load diagnoses.' : diagnosisPage && !diagnosisScopeMatches && !loading
      ? 'Data selection changed. Refresh to load the active mode.' : ''
  const recommendationItems = !recommendationError && recommendationScopeMatches ? recommendations.items : []
  const diagnosisItems = !loading && !diagnosisError && diagnosisScopeMatches ? diagnoses.items : []
  const visible = recommendationItems.filter(item => item.serviceId.includes(filter.trim()))
  const requestedId = searchParams.get('recommendation')
  const scopeIdentity = `${mode}:${demoRunId ?? 'none'}`
  const { error: recommendationLoadError, hasMore: recommendationHasMore, isLoading: recommendationIsLoading,
    isLoadingMore: recommendationIsLoadingMore, loadNext: loadNextRecommendation } = recommendations
  const editingInScope = editingRecommendation?.scopeIdentity === scopeIdentity ? editingRecommendation : null
  const editingItem = editingInScope && recommendationItems.find(item => item.recommendationId === editingInScope.recommendation.recommendationId)
  const editingItemIsLoaded = !!editingItem
  const editNeedsRevalidation = !!editingInScope && !editingItem && (recommendations.isLoading || recommendations.isLoadingMore ||
    recommendationHasMore || recommendationLoadError !== null)
  const selected = editNeedsRevalidation ? editingInScope.recommendation
    : recommendationItems.find(item => item.recommendationId === requestedId) ?? visible[0] ?? recommendationItems[0]
  const selectedIdentity = selected ? `${scopeIdentity}:${JSON.stringify(selected)}` : null
  const editorUnavailable = !!editingInScope && !editingItem && recommendationLoadError !== null
  const editorStale = editingRecommendation !== null && (editingRecommendation.scopeIdentity !== scopeIdentity ||
    !editNeedsRevalidation && selectedIdentity !== editingRecommendation.identity)
  const detailRecommendation = selected
  const detailIdentity = detailRecommendation ? `${scopeIdentity}:${JSON.stringify(detailRecommendation)}` : null
  const selectionIsCurrent = !!selected && recommendationItems.some(item => item.recommendationId === selected.recommendationId &&
    JSON.stringify(item) === JSON.stringify(selected))

  useEffect(() => {
    if (!editingInScope || editingItemIsLoaded || recommendationIsLoading || recommendationIsLoadingMore ||
      recommendationLoadError !== null || !recommendationHasMore) return
    loadNextRecommendation()
  }, [editingInScope, editingItemIsLoaded, loadNextRecommendation, recommendationHasMore, recommendationIsLoading,
    recommendationIsLoadingMore, recommendationLoadError])

  useEffect(() => {
    if (selected && selected.recommendationId !== requestedId) {
      setSearchParams({ recommendation: selected.recommendationId }, { replace: true })
    }
  }, [requestedId, selected, setSearchParams])

  function select(recommendationId: string) {
    setSearchParams({ recommendation: recommendationId })
  }

  return <section aria-label="Mitigation recommendations" className="space-y-4">
    <section aria-labelledby="planning-diagnoses-title" className="space-y-4">
      <h2 id="planning-diagnoses-title" className="font-medium">Root-cause diagnoses</h2>
      <p className="text-sm text-muted-foreground">Live diagnoses are advisory; actions require validated vehicle, control-stop and crowd-safety inputs.</p>
      <DataMessage error={diagnosisError} loading={loading || diagnoses.isLoading && !diagnosisPage} empty={!diagnosisItems.length}>No confirmed bunching events have reached Planning in this mode.</DataMessage>
      {!!diagnosisItems.length && <ScrollArea role="region" aria-label="Root-cause diagnoses" tabIndex={0} className="max-h-[28rem]"
        viewportProps={{ onScroll: diagnoses.onScroll }}><div className="space-y-3">{diagnosisItems.map(item => <Card key={item.requestId} className="min-h-28">
          <CardContent className="space-y-2 text-sm">
            <p className="font-medium">Service {item.serviceId ?? 'unavailable'} · {item.directionId === null ? 'Direction unavailable' : `Direction ${item.directionId}`} · Stop {item.stopCode ?? 'unavailable'}</p>
            <p className="text-xs text-muted-foreground">{dataClassificationLabel(item.classification)} · {diagnosisStatusLabel(item.status)} · {timestamp(item.createdAt)}</p>
            {item.likelyCause && <p>{item.likelyCause}</p>}
            {item.failureCode && <p role="alert">Diagnosis unavailable: {item.failureCode.replaceAll('_', ' ')}.</p>}
            {!!item.evidenceDetails?.length || !!item.limitations.length ? <details className="text-xs text-muted-foreground"><summary className="cursor-pointer font-medium">Evidence and limitations</summary>
              <EvidenceDetailList items={item.evidenceDetails ?? []} />
              {!!item.limitations.length && <ul className="mt-2 list-disc space-y-1 pl-5">{item.limitations.map(value => <li key={value}>{value}</li>)}</ul>}
            </details> : null}
          </CardContent>
        </Card>)}</div></ScrollArea>}
      {!!diagnosisPage && diagnosisPage.totalItems > diagnosisItems.length && <p className="text-xs text-muted-foreground">Showing {diagnosisItems.length} of {diagnosisPage.totalItems} diagnoses.</p>}
      <PagedCollectionControls label="diagnoses" hasItems={!!diagnoses.items.length} hasMore={diagnoses.hasMore}
        isLoadingMore={diagnoses.isLoadingMore} error={diagnoses.error} reload={diagnoses.reload} loadNext={diagnoses.loadNext} />
    </section>
    <DataMessage error={recommendationError} loading={loading || recommendations.isLoading && !recommendationPage} empty={!recommendationItems.length && !detailRecommendation}>No recommendations are awaiting a decision.</DataMessage>
    {message && <p role="status" className="text-sm">{message}</p>}
    {editorStale && <p role="status" className="text-sm">This recommendation is no longer current. The editor was closed; review the latest data before making a decision.</p>}
    {editorUnavailable && !editorStale && <p role="status" className="text-sm">Unable to confirm this recommendation is current. Your draft is preserved; retry the check before deciding.</p>}
    {!!recommendationItems.length || !!detailRecommendation ? <div className="grid items-start gap-4 lg:grid-cols-[minmax(16rem,0.36fr)_minmax(0,1fr)]">
      <Card className="gap-0 py-0">
        <div className="space-y-3 border-b p-4">
          <div className="flex items-center justify-between gap-3"><h2 className="text-sm font-medium">Awaiting decision</h2><span className="text-xs text-muted-foreground">Oldest first</span></div>
          <Label htmlFor="service-filter" className="sr-only">Filter by bus service number</Label>
          <Input id="service-filter" inputMode="numeric" placeholder="Filter bus no. e.g. 190" value={filter} onChange={event => setFilter(event.target.value)} />
        </div>
        <ScrollArea role="listbox" aria-label="Recommendations awaiting a decision" tabIndex={0} className="max-h-[28rem]"
          viewportProps={{ onScroll: recommendations.onScroll }}><div className="divide-y">
          {visible.map(item => <button type="button" role="option" aria-selected={selected?.recommendationId === item.recommendationId} key={item.recommendationId} onClick={() => select(item.recommendationId)}
            className="min-h-28 w-full space-y-2 px-4 py-4 text-left outline-none transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring aria-selected:bg-muted">
            <div className="flex items-start justify-between gap-3"><span className="font-medium">Service {item.serviceId} · Direction {item.directionId}</span><span className="shrink-0 text-xs text-muted-foreground">{timestamp(item.detectedAt)}</span></div>
            <p className="text-xs text-muted-foreground">Stop {item.monitoredStopCode}</p>
            <p className="text-sm">{item.explanation.likelyCause}</p>
            <p className="text-xs text-muted-foreground">{item.mitigationType.replaceAll('_', ' ')} · {item.targetBusReference}</p>
          </button>)}
          {!visible.length && <p className="p-6 text-center text-sm text-muted-foreground">No matching bus service.</p>}
        </div></ScrollArea>
        <div className="p-3"><PagedCollectionControls label="recommendations" hasItems={!!recommendations.items.length} hasMore={recommendations.hasMore}
          isLoadingMore={recommendations.isLoadingMore} error={recommendations.error} reload={recommendations.reload} loadNext={recommendations.loadNext} /></div>
      </Card>

      {detailRecommendation && detailIdentity && <RecommendationDetail key={detailIdentity} selected={detailRecommendation} identity={detailIdentity}
        scopeIdentity={scopeIdentity} token={token} isCurrent={selectionIsCurrent} validationFailed={editorUnavailable} retryCurrentCheck={recommendations.reload}
        onSaved={onSaved} onMessage={setMessage} onEditorChange={setEditingRecommendation} />}
    </div> : null}
  </section>
}

function RecommendationDetail({ selected, identity, scopeIdentity, token, isCurrent, validationFailed, retryCurrentCheck, onSaved, onMessage, onEditorChange }: {
  selected: PendingRecommendation; identity: string; scopeIdentity: string; token: string
  isCurrent: boolean; validationFailed: boolean; retryCurrentCheck: () => void
  onSaved: () => void; onMessage: (message: string) => void; onEditorChange: (value: EditingRecommendation | null) => void
}) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)
  const [instructions, setInstructions] = useState<ControllerBusInstruction[]>(() => initialInstructions(selected))
  const [rationale, setRationale] = useState('')
  const [openingDraft, setOpeningDraft] = useState<{ instructions: ControllerBusInstruction[]; rationale: string } | null>(null)
  const submitting = useRef(false)
  const mutationRef = useRef<AbortController | null>(null)
  const firstBusReference = useRef<HTMLInputElement>(null)
  const dirty = openingDraft !== null && (rationale !== openingDraft.rationale || JSON.stringify(instructions) !== JSON.stringify(openingDraft.instructions))

  useEffect(() => () => mutationRef.current?.abort(), [])

  function openEditor() {
    const nextInstructions = initialInstructions(selected)
    setInstructions(nextInstructions)
    setRationale('')
    setOpeningDraft({ instructions: nextInstructions, rationale: '' })
    setMessage('')
    setDiscardOpen(false)
    setEditorOpen(true)
    onEditorChange({ identity, scopeIdentity, recommendation: selected })
  }

  function closeEditor(force = false) {
    if (pending && !force) return
    setEditorOpen(false)
    setDiscardOpen(false)
    setInstructions(initialInstructions(selected))
    setRationale('')
    setOpeningDraft(null)
    setMessage('')
    onEditorChange(null)
  }

  function requestClose() {
    if (pending) return
    if (dirty) setDiscardOpen(true)
    else closeEditor()
  }

  function changeEditorOpen(open: boolean) {
    if (open) openEditor()
    else requestClose()
  }

  function updateInstruction(index: number, field: keyof ControllerBusInstruction, value: string) {
    setMessage('')
    setInstructions(items => items.map((item, itemIndex) => itemIndex === index ? { ...item, [field]: value } : item))
  }

  async function decide(action: Decision['action']) {
    if (submitting.current || !isCurrent) return
    const overrideInstructions = action === 'REJECTED' ? instructions.map(item => ({
      busReference: item.busReference.trim(), instruction: item.instruction.trim(),
    })) : []
    if (action === 'REJECTED' && (!rationale.trim() || overrideInstructions.some(item => !item.busReference || !item.instruction))) {
      setMessage('Enter a bus reference and instruction for every affected bus, plus a controller rationale.')
      return
    }
    submitting.current = true; setPending(true); setMessage('')
    mutationRef.current?.abort()
    const controller = new AbortController()
    mutationRef.current = controller
    try {
      await apiRequest<Decision>(`/api/v1/planning/recommendations/${encodeURIComponent(selected.recommendationId)}/decision`, token, controller.signal, {
        method: 'POST', body: {
          action,
          overrideInstructions,
          controllerRationale: action === 'REJECTED' ? rationale.trim() : null,
        },
      })
      if (!controller.signal.aborted) {
        onMessage(action === 'APPROVED' ? 'Recommendation approved.' : 'AI recommendation replaced with controller instructions.')
        closeEditor(true)
        onSaved()
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        onMessage('This recommendation has changed or already has a decision. The latest state has been requested.')
        closeEditor(true); onSaved()
      } else setMessage(error instanceof ApiError && error.status === 403 ? 'You do not have permission to record this decision.' : 'Unable to save the decision. Check the instructions and try again.')
    } finally {
      submitting.current = false
      if (!controller.signal.aborted) setPending(false)
    }
  }

  async function approveAndSendToObu() {
    if (submitting.current || !isCurrent) return
    submitting.current = true; setPending(true); setMessage('')
    const controller = new AbortController()
    mutationRef.current?.abort(); mutationRef.current = controller
    try {
      await apiRequest(`/api/v1/planning/recommendations/${encodeURIComponent(selected.recommendationId)}/obu-decisions`, token, controller.signal, { method: 'POST' })
      if (!controller.signal.aborted) { setMessage('Validated instruction issued to the assigned captain.'); onSaved() }
    } catch (error) {
      if (!controller.signal.aborted) setMessage(error instanceof Error ? error.message : 'Unable to issue the instruction.')
    } finally {
      submitting.current = false
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return <Card><CardContent className="space-y-5 px-5">
        {message && <p role="status" className="text-sm">{message}</p>}
        <div className="flex flex-wrap items-start justify-between gap-4 border-b pb-4">
          <div><h2 className="text-lg font-semibold">Service {selected.serviceId} · Direction {selected.directionId}</h2><p className="mt-1 text-xs text-muted-foreground">Stop {selected.monitoredStopCode} · detected {timestamp(selected.detectedAt)}</p></div>
          <span className="text-xs text-muted-foreground">{selected.recommendationId}</span>
        </div>

        <div className="flex flex-wrap justify-end gap-2 border-b pb-4">
          <Dialog open={editorOpen} onOpenChange={changeEditorOpen}>
            <DialogTrigger asChild><Button variant="outline" disabled={pending}>Enter controller recommendation</Button></DialogTrigger>
            <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-3xl"
              onOpenAutoFocus={event => { event.preventDefault(); firstBusReference.current?.focus() }}
              onEscapeKeyDown={event => { if (pending) event.preventDefault() }}
              onInteractOutside={event => { if (pending) event.preventDefault() }}>
              <DialogHeader>
                <DialogTitle>Enter controller recommendation</DialogTitle>
                <DialogDescription>Replace the AI proposal with instructions for each affected bus. Enter a rationale to save.</DialogDescription>
              </DialogHeader>
              {!isCurrent && <div role="alert" className="rounded-lg border p-3 text-sm">
                <p>{validationFailed ? 'Unable to confirm this recommendation is current. Your draft is preserved; decisions are paused.'
                  : 'Checking that this recommendation is still pending. Decisions are paused.'}</p>
                {validationFailed && <Button type="button" variant="outline" className="mt-3" onClick={retryCurrentCheck}>Retry recommendation check</Button>}
              </div>}
              {message && <p role="alert" className="rounded-lg border p-3 text-sm">{message}</p>}
              <form className="space-y-4" noValidate onSubmit={event => { event.preventDefault(); void decide('REJECTED') }}>
                {instructions.map((item, index) => <div key={index} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr_auto]">
                  <div className="space-y-2"><Label htmlFor={`bus-reference-${index}`}>Bus reference</Label><Input ref={index === 0 ? firstBusReference : undefined} id={`bus-reference-${index}`} maxLength={80} value={item.busReference} onChange={event => updateInstruction(index, 'busReference', event.target.value)} required /></div>
                  <div className="space-y-2"><Label htmlFor={`bus-instruction-${index}`}>Instruction</Label><textarea id={`bus-instruction-${index}`} maxLength={500} rows={2} value={item.instruction} onChange={event => updateInstruction(index, 'instruction', event.target.value)} required className="min-h-16 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50" /></div>
                  {instructions.length > 1 && <Button type="button" variant="ghost" className="self-end" disabled={pending} onClick={() => setInstructions(items => items.filter((_, itemIndex) => itemIndex !== index))}>Remove</Button>}
                </div>)}
                {instructions.length < 3 && <Button type="button" variant="outline" disabled={pending} onClick={() => setInstructions(items => [...items, { busReference: '', instruction: '' }])}>Add bus instruction</Button>}
                <div className="space-y-2"><Label htmlFor="controller-rationale">Controller rationale</Label><textarea id="controller-rationale" maxLength={1000} rows={3} value={rationale} onChange={event => { setRationale(event.target.value); setMessage('') }} required className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50" /></div>
                <DialogFooter>
                  <DialogClose asChild><Button type="button" variant="outline" disabled={pending}>Close</Button></DialogClose>
                  <Button type="button" variant="outline" disabled={pending} onClick={requestClose}>Cancel</Button>
                  <Button type="submit" disabled={pending || !isCurrent}>{pending ? 'Saving…' : 'Reject AI suggestion & save instructions'}</Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
          <AlertDialog open={discardOpen} onOpenChange={open => { if (!open) setDiscardOpen(false) }}>
            <AlertDialogContent onEscapeKeyDown={event => {
              event.preventDefault()
              event.stopPropagation()
              setDiscardOpen(false)
            }} onCloseAutoFocus={event => {
              if (editorOpen) { event.preventDefault(); firstBusReference.current?.focus() }
            }}>
              <AlertDialogHeader>
                <AlertDialogTitle>Discard unsaved changes?</AlertDialogTitle>
                <AlertDialogDescription>Your controller recommendation edits will be lost.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep editing</AlertDialogCancel>
                <AlertDialogAction onClick={() => closeEditor()}>Discard changes</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
          <Button disabled={pending || !isCurrent} onClick={() => void decide('APPROVED')}>{pending ? 'Saving…' : 'Approve recommendation'}</Button>
          {['HOLD', 'DWELL_ADJUSTMENT'].includes(selected.mitigationType) && <Button variant="outline" disabled={pending || !isCurrent} onClick={() => void approveAndSendToObu()}>Approve and send to bus</Button>}
        </div>

        <div className="grid gap-4 rounded-lg bg-muted/50 p-4 sm:grid-cols-[1fr_auto]">
          <div><p className="text-xs text-muted-foreground">Diagnosis</p><p className="mt-1 font-medium">{selected.explanation.likelyCause}</p></div>
          <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-1"><div><dt className="text-xs text-muted-foreground">Observed gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.observedHeadwaySeconds)}</dd></div><div><dt className="text-xs text-muted-foreground">Target gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.expectedHeadwaySeconds)}</dd></div></dl>
        </div>

        <section aria-labelledby="affected-buses" className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><h3 id="affected-buses" className="text-sm font-medium">Buses bunching</h3><span className="text-xs text-muted-foreground">Expected gap {seconds(selected.expectedImpact.headwayAfterSeconds)}</span></div>
          <div className="divide-y rounded-lg border">{affectedBuses(selected).map(busReference => {
            const isTarget = busReference === selected.targetBusReference
            return <div key={busReference} className="grid gap-1 p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr] sm:gap-4"><p className="font-medium">{busReference}</p><p className="text-sm text-muted-foreground">{isTarget ? suggestedInstruction(selected) : 'No individual instruction proposed.'}</p></div>
          })}</div>
        </section>

        <div className="grid gap-5 border-t pt-4 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Why this action</h3><p className="mt-2 text-sm text-muted-foreground">{selected.explanation.rationale}</p></div><dl className="space-y-2 text-sm"><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Passenger minutes</dt><dd>{selected.expectedImpact.passengerMinutesBefore} → {selected.expectedImpact.passengerMinutesAfter}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Queue passengers</dt><dd>{selected.expectedImpact.queueBeforePassengers} → {selected.expectedImpact.queueAfterPassengers}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Adjustment</dt><dd>{selected.adjustmentSeconds} seconds</dd></div></dl></div>

        <details className="border-t pt-4"><summary className="cursor-pointer text-sm font-medium">Evidence and limitations</summary>
          <div className="mt-4 space-y-4">
            {!!selected.explanation.evidenceDetails?.length && <div><h3 className="text-sm font-medium">Supporting incident evidence</h3><EvidenceDetailList items={selected.explanation.evidenceDetails} /></div>}
            <div className="grid gap-5 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Trade-offs</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.tradeOffs.map((item, index) => <li key={index}>{item}</li>)}</ul></div><div><h3 className="text-sm font-medium">Limitations</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></div></div>
          </div>
        </details>

      </CardContent></Card>
}

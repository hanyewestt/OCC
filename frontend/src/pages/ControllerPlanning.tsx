import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, apiRequest, type ControllerBusInstruction, type Decision, type PendingRecommendation } from '@/lib/api'
import { signOut } from '@/lib/auth'
import type { Planning, Resource } from './ControllerPage'
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

export function ControllerPlanning({ planning, loading, token, onSaved }: {
  planning: Resource<Planning>; loading: boolean; token: string
  onSaved: () => void
}) {
  const recommendations = useMemo(
    () => !loading && !planning.error ? planning.data?.recommendations.items ?? [] : [],
    [loading, planning.data?.recommendations, planning.error],
  )
  const [searchParams, setSearchParams] = useSearchParams()
  const [filter, setFilter] = useState('')
  const [message, setMessage] = useState('')
  const visible = useMemo(() => recommendations.filter(item => item.serviceId.includes(filter.trim())), [filter, recommendations])
  const requestedId = searchParams.get('recommendation')
  const selected = recommendations.find(item => item.recommendationId === requestedId) ?? visible[0] ?? recommendations[0]

  useEffect(() => {
    if (selected && selected.recommendationId !== requestedId) {
      setSearchParams({ recommendation: selected.recommendationId }, { replace: true })
    }
  }, [requestedId, selected, setSearchParams])

  function select(recommendationId: string) {
    setSearchParams({ recommendation: recommendationId })
  }

  return <section aria-label="Mitigation recommendations" className="space-y-4">
    <DataMessage error={planning.error} loading={loading} empty={!recommendations.length}>No recommendations are awaiting a decision.</DataMessage>
    {message && <p role="status" className="text-sm">{message}</p>}
    {!!recommendations.length && <div className="grid items-start gap-4 lg:grid-cols-[minmax(16rem,0.36fr)_minmax(0,1fr)]">
      <Card className="gap-0 py-0">
        <div className="space-y-3 border-b p-4">
          <div className="flex items-center justify-between gap-3"><h2 className="text-sm font-medium">Awaiting decision</h2><span className="text-xs text-muted-foreground">Oldest first</span></div>
          <Label htmlFor="service-filter" className="sr-only">Filter by bus service number</Label>
          <Input id="service-filter" inputMode="numeric" placeholder="Filter bus no. e.g. 190" value={filter} onChange={event => setFilter(event.target.value)} />
        </div>
        <div role="listbox" aria-label="Recommendations awaiting a decision" className="divide-y">
          {visible.map(item => <button type="button" role="option" aria-selected={selected?.recommendationId === item.recommendationId} key={item.recommendationId} onClick={() => select(item.recommendationId)}
            className="w-full space-y-2 px-4 py-4 text-left outline-none transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring aria-selected:bg-muted">
            <div className="flex items-start justify-between gap-3"><span className="font-medium">Service {item.serviceId} · Direction {item.directionId}</span><span className="shrink-0 text-xs text-muted-foreground">{timestamp(item.detectedAt)}</span></div>
            <p className="text-xs text-muted-foreground">Stop {item.monitoredStopCode}</p>
            <p className="text-sm">{item.explanation.likelyCause}</p>
            <p className="text-xs text-muted-foreground">{item.mitigationType.replaceAll('_', ' ')} · {item.targetBusReference}</p>
          </button>)}
          {!visible.length && <p className="p-6 text-center text-sm text-muted-foreground">No matching bus service.</p>}
        </div>
      </Card>

      {selected && <RecommendationDetail key={selected.recommendationId} selected={selected} token={token} onSaved={onSaved} onMessage={setMessage} />}
    </div>}
  </section>
}

function RecommendationDetail({ selected, token, onSaved, onMessage }: {
  selected: PendingRecommendation; token: string; onSaved: () => void; onMessage: (message: string) => void
}) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [replacing, setReplacing] = useState(false)
  const [instructions, setInstructions] = useState<ControllerBusInstruction[]>(() => initialInstructions(selected))
  const [rationale, setRationale] = useState('')
  const submitting = useRef(false)
  const mutationRef = useRef<AbortController | null>(null)

  useEffect(() => () => mutationRef.current?.abort(), [])

  function updateInstruction(index: number, field: keyof ControllerBusInstruction, value: string) {
    setInstructions(items => items.map((item, itemIndex) => itemIndex === index ? { ...item, [field]: value } : item))
  }

  async function decide(action: Decision['action']) {
    if (submitting.current) return
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
        setReplacing(false)
        onSaved()
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        onMessage('This recommendation has changed or already has a decision. The latest state has been requested.'); onSaved()
      } else setMessage(error instanceof ApiError && error.status === 403 ? 'You do not have permission to record this decision.' : 'Unable to save the decision. Check the instructions and try again.')
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
          <Button variant="outline" disabled={pending} onClick={() => setReplacing(true)}>Enter controller recommendation</Button>
          <Button disabled={pending} onClick={() => void decide('APPROVED')}>{pending ? 'Saving…' : 'Approve recommendation'}</Button>
        </div>

        <div className="grid gap-4 rounded-lg bg-muted/50 p-4 sm:grid-cols-[1fr_auto]">
          <div><p className="text-xs text-muted-foreground">Diagnosis</p><p className="mt-1 font-medium">{selected.explanation.likelyCause}</p><p className="mt-1 text-sm text-muted-foreground">Confidence {Math.round(selected.explanation.diagnosisConfidence * 100)}%</p></div>
          <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-1"><div><dt className="text-xs text-muted-foreground">Observed gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.observedHeadwaySeconds)}</dd></div><div><dt className="text-xs text-muted-foreground">Target gap</dt><dd className="mt-1 font-medium tabular-nums">{seconds(selected.expectedHeadwaySeconds)}</dd></div></dl>
        </div>

        <section aria-labelledby="affected-buses" className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><div><h3 id="affected-buses" className="text-sm font-medium">Buses bunching</h3><p className="mt-1 text-xs text-muted-foreground">Recommended instructions are shown where applicable.</p></div><span className="text-xs text-muted-foreground">Expected gap {seconds(selected.expectedImpact.headwayAfterSeconds)}</span></div>
          <div className="divide-y rounded-lg border">{affectedBuses(selected).map(busReference => {
            const isTarget = busReference === selected.targetBusReference
            return <div key={busReference} className="grid gap-1 p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr] sm:gap-4"><p className="font-medium">{busReference}</p><p className="text-sm text-muted-foreground">{isTarget ? suggestedInstruction(selected) : 'No individual instruction proposed.'}</p></div>
          })}</div>
        </section>

        <div className="grid gap-5 border-t pt-4 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Why this action</h3><p className="mt-2 text-sm text-muted-foreground">{selected.explanation.rationale}</p></div><dl className="space-y-2 text-sm"><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Passenger minutes</dt><dd>{selected.expectedImpact.passengerMinutesBefore} → {selected.expectedImpact.passengerMinutesAfter}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Queue passengers</dt><dd>{selected.expectedImpact.queueBeforePassengers} → {selected.expectedImpact.queueAfterPassengers}</dd></div><div className="flex justify-between gap-4"><dt className="text-muted-foreground">Adjustment</dt><dd>{selected.adjustmentSeconds} seconds</dd></div></dl></div>

        <details className="border-t pt-4"><summary className="cursor-pointer text-sm font-medium">AI suggestion breakdown</summary><div className="mt-4 grid gap-5 sm:grid-cols-2"><div><h3 className="text-sm font-medium">Trade-offs</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.tradeOffs.map((item, index) => <li key={index}>{item}</li>)}</ul></div><div><h3 className="text-sm font-medium">Limitations</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{selected.explanation.limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></div></div></details>

        {replacing && <form className="space-y-4 border-t pt-5" onSubmit={event => { event.preventDefault(); void decide('REJECTED') }}>
          <div><h3 className="font-medium">Controller recommendation</h3><p className="mt-1 text-xs text-muted-foreground">Replace the AI suggestion with instructions for each affected bus.</p></div>
          {instructions.map((item, index) => <div key={index} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[minmax(10rem,0.35fr)_1fr_auto]">
            <div className="space-y-2"><Label htmlFor={`bus-reference-${index}`}>Bus reference</Label><Input id={`bus-reference-${index}`} maxLength={80} value={item.busReference} onChange={event => updateInstruction(index, 'busReference', event.target.value)} required /></div>
            <div className="space-y-2"><Label htmlFor={`bus-instruction-${index}`}>Instruction</Label><textarea id={`bus-instruction-${index}`} maxLength={500} rows={2} value={item.instruction} onChange={event => updateInstruction(index, 'instruction', event.target.value)} required className="min-h-16 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50" /></div>
            {instructions.length > 1 && <Button type="button" variant="ghost" className="self-end" onClick={() => setInstructions(items => items.filter((_, itemIndex) => itemIndex !== index))}>Remove</Button>}
          </div>)}
          {instructions.length < 3 && <Button type="button" variant="outline" onClick={() => setInstructions(items => [...items, { busReference: '', instruction: '' }])}>Add bus instruction</Button>}
          <div className="space-y-2"><Label htmlFor="controller-rationale">Controller rationale</Label><textarea id="controller-rationale" maxLength={1000} rows={3} value={rationale} onChange={event => setRationale(event.target.value)} required className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50" /></div>
          <div className="flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" onClick={() => setReplacing(false)}>Cancel</Button><Button type="submit" disabled={pending}>{pending ? 'Saving…' : 'Reject AI suggestion & save instructions'}</Button></div>
        </form>}
      </CardContent></Card>
}

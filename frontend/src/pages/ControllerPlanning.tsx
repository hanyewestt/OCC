import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError, apiRequest, type Decision } from '@/lib/api'
import { signOut } from '@/lib/auth'
import type { Planning, Resource } from './ControllerPage'
import { DataMessage, timestamp } from './ControllerViews'

export function ControllerPlanning({ planning, loading, token, onSaved }: {
  planning: Resource<Planning>; loading: boolean; token: string
  onSaved: () => void
}) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const submitting = useRef(false)
  const mutationRef = useRef<AbortController | null>(null)
  useEffect(() => () => mutationRef.current?.abort(), [])
  const recommendation = !loading && !planning.error ? planning.data?.recommendation : null
  const decision = planning.data?.decision
  async function decide(action: Decision['action']) {
    if (!recommendation || submitting.current) return
    submitting.current = true; setPending(true); setMessage('')
    mutationRef.current?.abort()
    const controller = new AbortController()
    mutationRef.current = controller
    try {
      await apiRequest<Decision>(`/api/v1/planning/recommendations/${encodeURIComponent(recommendation.recommendationId)}/decision`, token, controller.signal, { method: 'POST', body: { action } })
      if (!controller.signal.aborted) { setMessage(`Decision saved: ${action.toLowerCase()}.`); onSaved() }
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 401) void signOut(error.message)
      else if (error instanceof ApiError && error.status === 409) {
        setMessage('This recommendation has changed or already has a decision. The latest state has been requested.'); onSaved()
      } else setMessage(error instanceof ApiError && error.status === 403 ? 'You do not have permission to record this decision.' : 'Unable to save the decision. Try again.')
    } finally {
      submitting.current = false
      if (!controller.signal.aborted) setPending(false)
    }
  }
  return <section aria-label="Mitigation recommendations" className="space-y-4">
    <p className="text-sm text-muted-foreground">Showing the current simulation’s recommendation. A full mitigation queue is not connected yet.</p>
    <DataMessage error={planning.error} loading={loading} empty={!recommendation}>No recommendation available for the current simulation. Use Start simulation above, then Refresh to check for results.</DataMessage>
    {message && <p role="status" className="text-sm">{message}</p>}
    {recommendation && <Card><CardHeader><div className="mb-2 text-xs text-muted-foreground">{recommendation.classification} · {recommendation.status}</div><CardTitle>{recommendation.mitigationType} · {recommendation.targetBusReference || 'No target bus'}</CardTitle></CardHeader><CardContent className="space-y-5">
      <dl className="grid gap-4 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">Control stop</dt><dd className="mt-1">{recommendation.controlStopCode || '—'}</dd></div><div><dt className="text-muted-foreground">Adjustment</dt><dd className="mt-1">{recommendation.adjustmentSeconds} seconds</dd></div></dl>
      <div className="space-y-1"><h3 className="text-sm font-medium">Diagnosis</h3><p className="text-sm text-muted-foreground">{recommendation.likelyCause || 'No diagnosis provided.'}</p></div>
      <div className="space-y-1"><h3 className="text-sm font-medium">Rationale</h3><p className="text-sm text-muted-foreground">{recommendation.rationale}</p></div>
      {!!recommendation.tradeOffs?.length && <div><h3 className="text-sm font-medium">Trade-offs</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{recommendation.tradeOffs.map((item, index) => <li key={index}>{item}</li>)}</ul></div>}
      {!!recommendation.limitations?.length && <div><h3 className="text-sm font-medium">Limitations</h3><ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">{recommendation.limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></div>}
      <div className="space-y-3 border-t pt-4"><p className="text-xs text-muted-foreground">Records your review only. Approval does not dispatch a bus instruction. The first decision is final.</p>
        {decision ? <p className="text-sm font-medium">{decision.action} · {timestamp(decision.decidedAt)}</p> : recommendation.classification === 'SIMULATION' && recommendation.status === 'RECOMMENDED' ? <div className="flex gap-2"><Button disabled={pending} onClick={() => void decide('APPROVED')}>{pending ? 'Saving…' : 'Approve'}</Button><Button variant="outline" disabled={pending} onClick={() => void decide('REJECTED')}>Reject</Button></div> : <p className="text-sm text-muted-foreground">This recommendation is for review only.</p>}
      </div>
    </CardContent></Card>}
  </section>
}

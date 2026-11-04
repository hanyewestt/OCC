import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { signOut } from '@/lib/auth'
import { cn } from '@/lib/utils'
import { CaptainInstructions } from './CaptainInstructions'
import { CaptainRouteView } from './CaptainRoute'
import { usePageRefresh } from '@/lib/page-refresh'
import { PageRefreshStatus } from '@/components/PageRefreshStatus'

function initialTheme(): 'day' | 'night' {
  try { return localStorage.getItem('occ-obu-theme') === 'day' ? 'day' : 'night' }
  catch { return 'night' }
}

export function CaptainPage({ email, token, refreshIntervalSeconds }: { email: string; token: string; refreshIntervalSeconds: number }) {
  const [theme, setTheme] = useState(initialTheme)
  const [instructionPending, setInstructionPending] = useState(false)
  const [lastInstructionRefresh, setLastInstructionRefresh] = useState<string | null>(null)
  const instructionRefresh = useRef<(signal: AbortSignal) => Promise<void>>(() => Promise.resolve())
  const routeRefresh = useRef<(signal: AbortSignal) => Promise<void>>(() => Promise.resolve())
  const { requestRefresh, isRefreshing } = usePageRefresh(refreshIntervalSeconds,
    signal => Promise.all([instructionRefresh.current(signal), routeRefresh.current(signal)]).then(() => undefined),
    instructionPending, token)

  useEffect(() => {
    try { localStorage.setItem('occ-obu-theme', theme) }
    catch { /* Theme remains usable when browser storage is unavailable. */ }
  }, [theme])

  return <section aria-label="Captain display" data-theme={theme}
    className={cn('w-full max-w-7xl space-y-6 rounded-xl border bg-background p-4 text-foreground sm:p-6', theme === 'night' && 'dark')}>
    <header className="flex flex-wrap items-center justify-between gap-4 border-b pb-4">
      <div className="space-y-3"><h1 className="text-2xl font-semibold">Captain</h1>
        <PageRefreshStatus lastSuccessAt={lastInstructionRefresh} intervalSeconds={refreshIntervalSeconds} />
        <p role="status" className="text-sm text-muted-foreground">Access verified</p></div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={instructionPending || isRefreshing} onClick={() => { void requestRefresh() }}>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}</Button>
        <Button variant="outline" onClick={() => setTheme(current => current === 'night' ? 'day' : 'night')}>
          {theme === 'night' ? 'Day mode' : 'Night mode'}</Button>
        <Button variant="outline" onClick={() => void signOut()}>Sign out</Button>
      </div>
    </header>

    <CaptainInstructions key={token} token={token} registerRefresh={callback => { instructionRefresh.current = callback }}
      onPendingChange={setInstructionPending} onSaved={() => { void requestRefresh('mutation') }} onRefreshTimeChange={setLastInstructionRefresh} />
    <CaptainRouteView key={`route-${token}`} token={token} registerRefresh={callback => { routeRefresh.current = callback }} />
    <footer className="space-y-1 border-t pt-4 text-sm text-muted-foreground">
      <p>Route stops come from LTA route data; bus position, pacing and controller instructions come from the current simulation run.</p>
      <p className="break-all">Signed in as {email} · Role: Captain</p>
    </footer>
  </section>
}

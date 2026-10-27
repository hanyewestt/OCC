import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { signOut } from '@/lib/auth'
import { cn } from '@/lib/utils'

export const controllerPages = [
  { path: '/controller', title: 'Overview', icon: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z' },
  { path: '/controller/disruptions', title: 'Disruptions', icon: 'm12 3 10 18H2L12 3Z M12 9v5 M12 17v1' },
  { path: '/controller/planning', title: 'Planning & mitigation', icon: 'M9 5h11 M9 12h11 M9 19h11 m-17-14 1 1 2-2 m-3 8 1 1 2-2 m-3 8 1 1 2-2' },
  { path: '/controller/audit', title: 'Audit log', icon: 'M5 3h14v18H5z M8 7h8 M8 12h8 M8 17h5' },
]

export function ControllerSidebar({ email }: { email: string }) {
  const { pathname } = useLocation()
  const [expanded, setExpanded] = useState(false)
  const closeMobile = () => { if (!window.matchMedia('(min-width: 768px)').matches) setExpanded(false) }
  return <>
    {expanded && <button aria-label="Close navigation" className="fixed inset-0 z-20 bg-black/20 md:hidden" onClick={() => setExpanded(false)} />}
    <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
      <aside aria-label="Controller sidebar" onKeyDown={event => { if (event.key === 'Escape') setExpanded(false) }}
        className={cn('sticky top-0 flex h-svh w-16 shrink-0 flex-col border-r bg-sidebar p-3', expanded && 'fixed inset-y-0 left-0 z-30 w-60 md:sticky')}>
        <div className="mb-8 flex h-9 items-center justify-between gap-2">
          {expanded && <Link to="/controller" className="px-2 text-lg font-semibold tracking-tight" onClick={closeMobile}>OCC<span className="ml-2 text-xs font-normal text-muted-foreground">Controller</span></Link>}
          <CollapsibleTrigger asChild><Button size="icon" variant="ghost" aria-label={expanded ? 'Collapse sidebar' : 'Expand sidebar'}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></svg>
          </Button></CollapsibleTrigger>
        </div>
        <nav aria-label="Controller navigation" className="space-y-1">
          {controllerPages.map(page => <Button key={page.path} asChild variant={pathname === page.path ? 'secondary' : 'ghost'} className={cn('h-10 w-full justify-start gap-3 px-2.5', !expanded && 'justify-center px-0')}>
            <Link to={page.path} aria-label={page.title} aria-current={pathname === page.path ? 'page' : undefined} title={expanded ? undefined : page.title} onClick={closeMobile}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={page.icon} /></svg>
              <span className={expanded ? '' : 'sr-only'}>{page.title}</span>
            </Link>
          </Button>)}
        </nav>
        <div className="mt-auto space-y-3 border-t pt-4">
          <CollapsibleContent><div className="space-y-1 px-2 text-xs text-muted-foreground"><p className="break-all">{email}</p><p>Access verified</p></div></CollapsibleContent>
          <Button variant="ghost" className="h-10 w-full gap-3" onClick={() => void signOut()} aria-label="Sign out" title="Sign out">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M9 4H4v16h5 M9 12h12 m-5-5 5 5-5 5" /></svg>
            {expanded && 'Sign out'}
          </Button>
        </div>
      </aside>
    </Collapsible>
  </>
}

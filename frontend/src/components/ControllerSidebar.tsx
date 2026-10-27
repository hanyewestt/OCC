import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ClipboardList, LayoutDashboard, ListChecks, LogOut, Newspaper, PanelLeft, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { signOut } from '@/lib/auth'
import { cn } from '@/lib/utils'

export const controllerPages = [
  { path: '/controller', title: 'Overview', icon: LayoutDashboard },
  { path: '/controller/disruptions', title: 'Disruptions', icon: TriangleAlert },
  { path: '/controller/planning', title: 'Planning & mitigation', icon: ListChecks },
  { path: '/controller/notices', title: 'Public notices', icon: Newspaper },
  { path: '/controller/audit', title: 'Audit log', icon: ClipboardList },
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
            <PanelLeft aria-hidden="true" />
          </Button></CollapsibleTrigger>
        </div>
        <nav aria-label="Controller navigation" className="space-y-1">
          {controllerPages.map(page => <Button key={page.path} asChild variant={pathname === page.path ? 'secondary' : 'ghost'} className={cn('h-10 w-full justify-start gap-3 px-2.5', !expanded && 'justify-center px-0')}>
            <Link to={page.path} aria-label={page.title} aria-current={pathname === page.path ? 'page' : undefined} title={expanded ? undefined : page.title} onClick={closeMobile}>
              <page.icon aria-hidden="true" />
              <span className={expanded ? '' : 'sr-only'}>{page.title}</span>
            </Link>
          </Button>)}
        </nav>
        <div className="mt-auto space-y-3 border-t pt-4">
          <CollapsibleContent><div className="space-y-1 px-2 text-xs text-muted-foreground"><p className="break-all">{email}</p><p>Access verified</p></div></CollapsibleContent>
          <Button variant="ghost" className="h-10 w-full gap-3" onClick={() => void signOut()} aria-label="Sign out" title="Sign out">
            <LogOut aria-hidden="true" />
            {expanded && 'Sign out'}
          </Button>
        </div>
      </aside>
    </Collapsible>
  </>
}

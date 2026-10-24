import { useEffect, useState } from 'react'
import { Navigate, useLocation, Link } from 'react-router-dom'
import { ApiError, homeFor, identityRequest } from '@/lib/api'
import { retryAuth, signOut, useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { LoginPage } from '@/pages/LoginPage'
import { ControllerPage } from '@/pages/ControllerPage'
import { controllerPages } from '@/components/ControllerSidebar'
import { CaptainPage } from '@/pages/CaptainPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ErrorPage } from '@/components/ErrorPage'
import { loadConfig, type PublicConfig } from '@/lib/config'

export function App() {
  const auth = useAuth()
  const { pathname } = useLocation()
  const [retry, setRetry] = useState(0)
  const [access, setAccess] = useState({ key: '', status: '' })
  const [publicConfig, setPublicConfig] = useState<PublicConfig | null>(null)
  const token = auth.session?.access_token
  const pageHome = controllerPages.some(page => page.path === pathname) ? '/controller' : pathname
  const key = `${token}:${pageHome}:${retry}`
  const home = auth.identity ? homeFor(auth.identity.role) : null

  useEffect(() => {
    let active = true
    void loadConfig().then(config => { if (active) setPublicConfig(config) }).catch(() => {})
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (auth.status !== 'ready' || !token || pageHome !== home) return
    const controller = new AbortController()
    void identityRequest(`/api/v1/auth/access/${pageHome.slice(1)}`, token, controller.signal)
      .then(identity => {
        if (controller.signal.aborted) return
        setAccess({ key, status: identity.subject === auth.identity?.subject && identity.role === auth.identity?.role ? 'allowed' : 'error' })
      }).catch(error => {
        if (controller.signal.aborted) return
        if (error instanceof ApiError && error.status === 401) void signOut(error.message)
        else setAccess({ key, status: error instanceof ApiError && error.status === 403 ? 'denied' : 'error' })
      })
    return () => controller.abort()
  }, [auth.status, auth.identity, token, pageHome, home, key])

  let content
  if (pathname === '/404' || !['/', '/login', ...controllerPages.map(page => page.path), '/captain', '/401', '/403'].includes(pathname)) {
    content = <NotFoundPage />
  } else if (pathname === '/401') {
    content = <ErrorPage code={401} title="Sign-in required" description="Your session is missing or has expired. Sign in to continue.">
      <Button variant="outline" asChild><Link to="/login">Sign in</Link></Button>
    </ErrorPage>
  } else if (pathname === '/403' || (auth.status === 'ready' && (
    !home || (pathname !== '/' && pathname !== '/login' && pageHome !== home) ||
    (access.key === key && access.status === 'denied')
  ))) {
    content = <ErrorPage code={403} title="Access denied" description="This page isn’t available for your account.">
      {home && <Button variant="outline" asChild><Link to={home}>Return to my page</Link></Button>}
      {auth.session ? <Button variant="outline" onClick={() => void signOut()}>Sign out</Button> :
        <Button variant="outline" asChild><Link to="/login">Sign in</Link></Button>}
    </ErrorPage>
  } else if (auth.status === 'loading') {
    content = <p role="status" className="text-sm text-muted-foreground">Checking session…</p>
  } else if (auth.status === 'error') {
    content = <div className="max-w-sm space-y-4"><p role="alert">{auth.message}</p>
      <div className="flex gap-2"><Button onClick={retryAuth}>Try again</Button><Button variant="outline" onClick={() => void signOut()}>Sign out</Button></div></div>
  } else if (auth.status === 'unauthorized' && pathname !== '/login') {
    content = <Navigate to="/401" replace />
  } else if (auth.status === 'signedOut' || auth.status === 'unauthorized') {
    content = pathname === '/login' ? <LoginPage message={auth.message} /> : <Navigate to="/login" replace />
  } else if (pathname === '/' || pathname === '/login') {
    content = <Navigate to={home!} replace />
  } else if (access.key !== key) {
    content = <p role="status" className="text-sm text-muted-foreground">Verifying access…</p>
  } else if (home === '/controller' && !publicConfig) {
    content = <p role="status" className="text-sm text-muted-foreground">Loading controller settings…</p>
  } else if (access.status === 'error') {
    content = <div className="space-y-4"><p role="alert">Unable to verify access.</p>
      <Button onClick={() => setRetry(value => value + 1)}>Try again</Button>
      <Button className="ml-2" variant="outline" onClick={() => void signOut()}>Sign out</Button></div>
  } else {
    const email = auth.session?.user.email ?? auth.identity?.subject ?? ''
    if (home === '/controller') return <main><ControllerPage key={token} email={email} token={token!}
      controllerRefreshIntervalSeconds={publicConfig!.controllerRefreshIntervalSeconds}
      ingestionPollIntervalSeconds={publicConfig!.ingestionPollIntervalSeconds}
      routeIncidentGapToleranceSeconds={publicConfig!.routeIncidentGapToleranceSeconds} /></main>
    content = <CaptainPage email={email} />
  }
  return <main className="flex min-h-svh items-center justify-center px-5 py-12">{content}</main>
}

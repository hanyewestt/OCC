import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
type RefreshReason = 'manual' | 'mutation' | 'auto'

// Resource owners publish errors; the scheduler waits for the complete cycle.
export function usePageRefresh(intervalSeconds: number | null, refresh: (signal: AbortSignal, reason: RefreshReason) => Promise<void>, paused = false, scopeKey = '') {
  const refreshRef = useRef(refresh)
  const pausedRef = useRef(paused)
  const requestRefreshRef = useRef<(reason?: RefreshReason) => Promise<void>>(() => Promise.resolve())
  const scope = useMemo(() => ({ key: scopeKey }), [scopeKey])
  const lifecycle = useRef({ controller: new AbortController(), active: null as Promise<void> | null, queued: false, scope })
  const [refreshingScope, setRefreshingScope] = useState<typeof scope | null>(null)
  const isRefreshing = refreshingScope === scope

  const requestRefresh = useCallback((reason: RefreshReason = 'manual') => {
    const current = lifecycle.current
    if (current.controller.signal.aborted) return Promise.resolve()
    if (pausedRef.current) {
      current.queued = true
      return current.active ?? Promise.resolve()
    }
    if (current.active) {
      if (reason === 'mutation') current.queued = true
      return current.active
    }
    current.queued = false
    setRefreshingScope(current.scope)
    const operation = Promise.resolve().then(() => refreshRef.current(current.controller.signal, reason)).finally(() => {
      if (lifecycle.current !== current || current.controller.signal.aborted) return
      current.active = null
      setRefreshingScope(null)
      if (current.queued && !pausedRef.current) queueMicrotask(() => { void requestRefreshRef.current().catch(() => {}) })
    })
    current.active = operation
    return operation
  }, [])

  useEffect(() => {
    refreshRef.current = refresh
    pausedRef.current = paused
    requestRefreshRef.current = requestRefresh
  }, [refresh, paused, requestRefresh])

  useEffect(() => {
    const current = { controller: new AbortController(), active: null as Promise<void> | null, queued: false, scope }
    lifecycle.current = current
    return () => current.controller.abort()
  }, [scope])

  useEffect(() => {
    if (!paused && lifecycle.current.queued) void requestRefresh().catch(() => {})
  }, [paused, requestRefresh])

  useEffect(() => {
    if (paused || isRefreshing || intervalSeconds === null) return
    const timer = window.setTimeout(() => { void requestRefresh('auto').catch(() => {}) }, intervalSeconds * 1000)
    return () => window.clearTimeout(timer)
  }, [intervalSeconds, paused, isRefreshing, requestRefresh, scopeKey])

  return { requestRefresh, isRefreshing }
}

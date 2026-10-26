import { useCallback, useEffect, useRef, useState, type UIEventHandler } from 'react'

export type PagedResult<T> = { items: T[]; page: number; totalItems: number; totalPages: number }
type CollectionState<T, P> = {
  collectionKey: string
  items: T[]
  latestPage: P | null
  nextPage: number
  hasMore: boolean
  isLoading: boolean
  isLoadingMore: boolean
  error: unknown
  refreshError: boolean
  lastSuccessAt: string | null
}

export type PagedCollection<T, P extends PagedResult<T>> = CollectionState<T, P> & {
  reload: () => Promise<void>
  refresh: (signal?: AbortSignal) => Promise<void>
  loadNext: () => void
  updateItem: (item: T) => void
  onScroll: UIEventHandler<HTMLDivElement>
}

function emptyState<T, P>(collectionKey: string, isLoading: boolean): CollectionState<T, P> {
  return { collectionKey, items: [], latestPage: null, nextPage: 0, hasMore: false,
    isLoading, isLoadingMore: false, error: null, refreshError: false, lastSuccessAt: null }
}

export function usePagedCollection<T, P extends PagedResult<T>>(
  collectionKey: string,
  loadPage: (page: number, signal: AbortSignal) => Promise<P>,
  itemId: (item: T) => string,
  enabled = true,
) {
  const [state, setState] = useState(() => emptyState<T, P>(collectionKey, enabled))
  const stateRef = useRef(state)
  const latestKey = useRef(collectionKey)
  const loadPageRef = useRef(loadPage)
  const itemIdRef = useRef(itemId)
  const generation = useRef(0)
  const activeRequest = useRef<{ generation: number; controller: AbortController } | null>(null)
  const viewport = useRef<HTMLDivElement | null>(null)

  const publish = useCallback((next: CollectionState<T, P>) => {
    stateRef.current = next
    setState(next)
  }, [])

  const fetchPage = useCallback(async (page: number, requestGeneration: number, key: string,
    controller: AbortController) => {
    try {
      const response = await loadPageRef.current(page, controller.signal)
      if (controller.signal.aborted || requestGeneration !== generation.current || key !== latestKey.current) return

      const current = stateRef.current.collectionKey === key ? stateRef.current : emptyState<T, P>(key, false)
      const items = page === 0 ? [] : current.items
      const ids = new Set(items.map(itemIdRef.current))
      const merged = [...items]
      for (const item of response.items) {
        const id = itemIdRef.current(item)
        if (!ids.has(id)) { ids.add(id); merged.push(item) }
      }
      publish({ ...current, collectionKey: key, items: merged, latestPage: response,
        nextPage: page + 1, hasMore: response.items.length > 0 && page + 1 < response.totalPages,
        isLoading: false, isLoadingMore: false, error: null, refreshError: false,
        lastSuccessAt: page === 0 ? new Date().toISOString() : current.lastSuccessAt })
      if (page === 0 && viewport.current) viewport.current.scrollTop = 0
    } catch (error) {
      if (!controller.signal.aborted && requestGeneration === generation.current && key === latestKey.current) {
        const current = stateRef.current.collectionKey === key ? stateRef.current : emptyState<T, P>(key, false)
        publish({ ...current, isLoading: false, isLoadingMore: false, error, refreshError: page === 0 })
      }
    } finally {
      if (activeRequest.current?.controller === controller) activeRequest.current = null
    }
  }, [publish])

  const refresh = useCallback((signal?: AbortSignal) => {
    if (signal?.aborted) return Promise.resolve()
    activeRequest.current?.controller.abort()
    const requestGeneration = ++generation.current
    const controller = new AbortController()
    const abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    activeRequest.current = { generation: requestGeneration, controller }
    const previous = stateRef.current.collectionKey === collectionKey ? stateRef.current : emptyState<T, P>(collectionKey, true)
    publish({ ...previous, isLoading: true, isLoadingMore: false, error: null, refreshError: false })
    return fetchPage(0, requestGeneration, collectionKey, controller).finally(() => signal?.removeEventListener('abort', abort))
  }, [collectionKey, fetchPage, publish])
  const reload = useCallback(() => refresh(), [refresh])

  const updateItem = useCallback((item: T) => {
    const current = stateRef.current
    if (current.collectionKey !== collectionKey) return
    const id = itemIdRef.current(item)
    const items = current.items.map(existing => itemIdRef.current(existing) === id ? item : existing)
    if (items.some((value, index) => value !== current.items[index])) publish({ ...current, items })
  }, [collectionKey, publish])

  const loadNext = useCallback(() => {
    const current = stateRef.current
    if (current.collectionKey !== collectionKey || !current.hasMore ||
      activeRequest.current?.generation === generation.current) return

    const requestGeneration = generation.current
    const controller = new AbortController()
    activeRequest.current = { generation: requestGeneration, controller }
    publish({ ...current, isLoadingMore: true, error: null })
    void fetchPage(current.nextPage, requestGeneration, collectionKey, controller)
  }, [collectionKey, fetchPage, publish])

  const onScroll: UIEventHandler<HTMLDivElement> = useCallback(event => {
    const element = event.currentTarget
    viewport.current = element
    if (stateRef.current.error !== null) return
    if (element.scrollHeight - element.scrollTop - element.clientHeight <= 48) loadNext()
  }, [loadNext])

  useEffect(() => {
    latestKey.current = collectionKey
    loadPageRef.current = loadPage
    itemIdRef.current = itemId
  }, [collectionKey, itemId, loadPage])

  useEffect(() => {
    if (!enabled) return
    void refresh()
    return () => {
      generation.current += 1
      activeRequest.current?.controller.abort()
      activeRequest.current = null
    }
  }, [enabled, refresh])

  const current = state.collectionKey === collectionKey ? state : emptyState<T, P>(collectionKey, enabled)
  return { ...current, isLoading: enabled && current.isLoading, isLoadingMore: enabled && current.isLoadingMore,
    reload, refresh, loadNext, updateItem, onScroll }
}

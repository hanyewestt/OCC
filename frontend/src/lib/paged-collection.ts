import { useCallback, useEffect, useRef, useState, type UIEventHandler } from 'react'

type PagedResult<T> = { items: T[]; page: number; totalItems: number; totalPages: number }
type CollectionState<T, P> = {
  collectionKey: string
  items: T[]
  latestPage: P | null
  nextPage: number
  hasMore: boolean
  isLoading: boolean
  isLoadingMore: boolean
  error: unknown
}

function emptyState<T, P>(collectionKey: string, isLoading: boolean): CollectionState<T, P> {
  return { collectionKey, items: [], latestPage: null, nextPage: 0, hasMore: false,
    isLoading, isLoadingMore: false, error: null }
}

export function usePagedCollection<T, P extends PagedResult<T>>(
  collectionKey: string,
  loadPage: (page: number, signal: AbortSignal) => Promise<P>,
  itemId: (item: T) => string,
) {
  const [state, setState] = useState(() => emptyState<T, P>(collectionKey, true))
  const stateRef = useRef(state)
  const latestKey = useRef(collectionKey)
  const loadPageRef = useRef(loadPage)
  const itemIdRef = useRef(itemId)
  const generation = useRef(0)
  const activeRequest = useRef<{ generation: number; controller: AbortController } | null>(null)

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
        isLoading: false, isLoadingMore: false, error: null })
    } catch (error) {
      if (!controller.signal.aborted && requestGeneration === generation.current && key === latestKey.current) {
        const current = stateRef.current.collectionKey === key ? stateRef.current : emptyState<T, P>(key, false)
        publish({ ...current, isLoading: false, isLoadingMore: false, error })
      }
    } finally {
      if (activeRequest.current?.controller === controller) activeRequest.current = null
    }
  }, [publish])

  const reload = useCallback(() => {
    activeRequest.current?.controller.abort()
    const requestGeneration = ++generation.current
    const controller = new AbortController()
    activeRequest.current = { generation: requestGeneration, controller }
    publish(emptyState<T, P>(collectionKey, true))
    void fetchPage(0, requestGeneration, collectionKey, controller)
  }, [collectionKey, fetchPage, publish])

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
    const viewport = event.currentTarget
    if (viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= 48) loadNext()
  }, [loadNext])

  useEffect(() => {
    latestKey.current = collectionKey
    loadPageRef.current = loadPage
    itemIdRef.current = itemId
  }, [collectionKey, itemId, loadPage])

  useEffect(() => {
    const requestGeneration = ++generation.current
    const controller = new AbortController()
    activeRequest.current = { generation: requestGeneration, controller }
    void fetchPage(0, requestGeneration, collectionKey, controller)
    return () => {
      generation.current += 1
      activeRequest.current?.controller.abort()
      activeRequest.current = null
    }
  }, [collectionKey, fetchPage])

  const current = state.collectionKey === collectionKey ? state : emptyState<T, P>(collectionKey, true)
  return { ...current, reload, loadNext, onScroll }
}

import { Button } from '@/components/ui/button'

export function PagedCollectionControls({ label, hasItems, hasMore, isLoadingMore, error, refreshError, reload, loadNext }: {
  label: string; hasItems: boolean; hasMore: boolean; isLoadingMore: boolean; error: unknown
  refreshError?: boolean
  reload: () => void; loadNext: () => void
}) {
  if (error !== null && refreshError && hasItems) return <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-destructive">
    <span>Unable to refresh {label}.</span><Button variant="outline" size="sm" onClick={reload}>Retry {label}</Button>
  </div>
  if (error !== null) return hasItems
    ? <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-destructive">
      <span>Unable to load more {label}.</span>
      <Button variant="outline" size="sm" disabled={isLoadingMore} onClick={loadNext}>Retry loading more {label}</Button>
    </div>
    : <Button variant="outline" onClick={reload}>Retry {label}</Button>
  if (!hasMore) return null
  return <Button variant="outline" disabled={isLoadingMore} onClick={loadNext}>
    {isLoadingMore ? `Loading more ${label}…` : `Load more ${label}`}
  </Button>
}

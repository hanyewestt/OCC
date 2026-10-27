import { formatSgtDateTime } from '@/lib/time'

export function PageRefreshStatus({ lastSuccessAt, intervalSeconds }: {
  lastSuccessAt: string | null; intervalSeconds: number | null
}) {
  return <p aria-label="Page refresh status" className="text-xs text-muted-foreground">
    {lastSuccessAt ? `Last successful refresh ${formatSgtDateTime(lastSuccessAt)}` : 'Not refreshed yet'}
    {intervalSeconds !== null && ` · Refreshes every ${intervalSeconds} seconds`}
  </p>
}

export function captainDirectoryPath(search: string, page: number, available: boolean): string {
  const params = new URLSearchParams()
  if (available) params.set('available', 'true')
  if (search.trim()) params.set('search', search.trim())
  params.set('page', String(page))
  params.set('size', String(available ? 100 : 20))
  return `/api/v1/ingestion/captains?${params}`
}

const captainIdFormat = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function selectedCaptainIds(ids: string[], busCount: number): string[] {
  return ids.slice(0, busCount).map(id => id.trim())
}

export function captainSelectionIsValid(ids: string[], busCount: number): boolean {
  const selected = selectedCaptainIds(ids, busCount)
  return !selected.some(Boolean) || (selected.every(id => captainIdFormat.test(id)) &&
    new Set(selected.map(id => id.toLowerCase())).size === selected.length)
}

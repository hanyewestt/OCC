// Fixed demonstration data for the Captain upcoming-stops display; no live instructions.
export type UpcomingStop = { stopCode: string; name: string; skipped: boolean }
export type StopSegment =
  | { kind: 'stop'; stop: UpcomingStop }
  | { kind: 'skip'; stops: UpcomingStop[] }

export const sampleUpcomingStops: UpcomingStop[] = [
  { stopCode: '44251', name: 'Opp Blk 123', skipped: false },
  { stopCode: '44259', name: 'Blk 45', skipped: true },
  { stopCode: '44261', name: 'Bt Batok Stn', skipped: true },
  { stopCode: '44269', name: 'Opp Bt Batok Stn', skipped: true },
  { stopCode: '44271', name: 'Bt Batok West Ave 6', skipped: false },
  { stopCode: '44279', name: 'Blk 211', skipped: true },
  { stopCode: '44281', name: 'Choa Chu Kang Int', skipped: false },
]

/** Merges each run of consecutive skipped stops into one segment, keeping route order. */
export function groupStops(stops: UpcomingStop[]): StopSegment[] {
  const segments: StopSegment[] = []
  for (const stop of stops) {
    const last = segments.at(-1)
    if (!stop.skipped) segments.push({ kind: 'stop', stop })
    else if (last?.kind === 'skip') last.stops.push(stop)
    else segments.push({ kind: 'skip', stops: [stop] })
  }
  return segments
}

export function skipLabel(count: number): string {
  return `Skip ${count} ${count === 1 ? 'stop' : 'stops'}`
}

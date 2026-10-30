// Presentation helpers for the Captain pacing display. Gap states come from the backend pacing service.
function isSeconds(value: number | null): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

export function clockText(seconds: number | null): string {
  if (!isSeconds(seconds)) return '--:--'
  const whole = Math.round(seconds)
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

export function deltaText(seconds: number | null, target: number): string {
  if (!isSeconds(seconds) || !isSeconds(target)) return 'No bus detected'
  const difference = seconds - target
  if (Math.abs(difference) <= 15) return 'About on target'
  return `${clockText(Math.abs(difference))} ${difference < 0 ? 'shorter' : 'longer'} than target`
}

/** One plain sentence for a pacing tile, e.g. "Bus ahead is 1:35 away · 6:25 shorter than target". */
export function gapSummary(side: 'ahead' | 'behind', seconds: number | null, target: number): string {
  if (!isSeconds(seconds)) return `No bus detected ${side}`
  return `Bus ${side} is ${clockText(seconds)} away · ${deltaText(seconds, target)}`
}

export function gapFlex(seconds: number | null, target: number): number {
  if (!isSeconds(seconds) || !isSeconds(target) || target === 0) return 1
  return Math.min(2, Math.max(0.25, seconds / target))
}

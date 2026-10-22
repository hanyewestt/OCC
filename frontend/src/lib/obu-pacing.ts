// Fixed demonstration data for the Captain pacing display; no live telemetry.
export const SAMPLE_REFRESH_MS = 10_000
export const SAMPLE_TARGET_SECONDS = 480
const driftSeconds = [0, 6, -4, 9, -7, 3]
export const scenarios = {
  'on-target': { label: 'On target', ahead: 470, behind: 505 },
  bunched: { label: 'Bunched behind bus ahead', ahead: 95, behind: 840 },
  'gap-ahead': { label: 'Big gap ahead', ahead: 900, behind: 130 },
  'no-bus-behind': { label: 'No bus behind', ahead: 455, behind: null },
} as const
export type Scenario = keyof typeof scenarios
export type GapState = 'close' | 'ok' | 'far' | 'unknown'

function isSeconds(value: number | null): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

export function classify(seconds: number | null, target: number): GapState {
  if (!isSeconds(seconds) || !isSeconds(target) || target === 0) return 'unknown'
  if (seconds <= target * 0.5) return 'close'
  if (seconds >= target * 1.5) return 'far'
  return 'ok'
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

export function gapFlex(seconds: number | null, target: number): number {
  if (!isSeconds(seconds) || !isSeconds(target) || target === 0) return 1
  return Math.min(2, Math.max(0.25, seconds / target))
}

export function sampleGaps(scenario: Scenario, tick: number) {
  const values = scenarios[scenario]
  const drift = driftSeconds[tick % driftSeconds.length]
  return {
    ahead: Math.max(0, values.ahead + drift),
    behind: values.behind === null ? null : Math.max(0, values.behind - drift),
  }
}

export function initialScenario(search: string): Scenario {
  const name = new URLSearchParams(search).get('scenario')
  return name && Object.hasOwn(scenarios, name) ? name as Scenario : 'bunched'
}

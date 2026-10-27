import type { CaptainInstruction, InstructionParameters, InstructionType } from './api'

export type InstructionState = 'action' | 'acknowledged' | 'closed' | 'cancelled'

/** Short spoken-style duration for glanceable text: 45 s, 5 min, 1 min 30 s. */
export function durationText(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds))
  if (whole < 60) return `${whole} s`
  const minutes = Math.floor(whole / 60)
  const rest = whole % 60
  return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`
}

/** The instruction in a few large words, built from its typed parameters. */
export function instructionHeadline(type: InstructionType, parameters: InstructionParameters): string {
  const duration = parameters.durationSeconds ? durationText(parameters.durationSeconds) : null
  const at = parameters.stopName ?? parameters.stopCode
  switch (type) {
    case 'HOLD':
      return ['Hold', duration, at && `at ${at}`].filter(Boolean).join(' ')
    case 'DWELL_ADJUSTMENT':
      return ['Stay', duration ?? 'longer', duration && 'longer', at && `at ${at}`].filter(Boolean).join(' ')
    case 'REDUCE_SPEED':
      return ['Reduce speed', parameters.speedReductionKmh && `by ${parameters.speedReductionKmh} km/h`,
        duration && `for ${duration}`].filter(Boolean).join(' ')
    case 'SLOW_DOWN_NEAR_STOPS':
      return parameters.subsequentStopCount
        ? `Slow down near the next ${parameters.subsequentStopCount} ${parameters.subsequentStopCount === 1 ? 'stop' : 'stops'}`
        : 'Slow down near stops'
    case 'BE_CAUTIOUS':
      return 'Be cautious'
  }
}

/** Whether the captain still has to act, judged against server time when it is known. */
export function instructionState(item: CaptainInstruction, serverNow: number | null): InstructionState {
  if (item.status === 'CANCELLED') return 'cancelled'
  if (item.acknowledgedAt) return 'acknowledged'
  if (item.status === 'EXPIRED' || (serverNow !== null && serverNow >= Date.parse(item.acknowledgementDeadline))) return 'closed'
  return 'action'
}

/** Remaining acknowledgement time as m:ss, or null when server time is unknown. */
export function acknowledgementTimeLeft(deadline: string, serverNow: number | null): string | null {
  if (serverNow === null) return null
  const seconds = Math.max(0, Math.floor((Date.parse(deadline) - serverNow) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

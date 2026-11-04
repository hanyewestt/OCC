import { expect, test } from '@playwright/test'
import { formatSgtDateTime, formatSgtTime, sgtDateTimeInputFromUtc, sgtDateTimeInputToUtc } from '../../src/lib/time'
import { dataClassificationLabel, diagnosisStatusLabel } from '../../src/lib/presentation'

test('formats UTC instants in SGT regardless of the host timezone', () => {
  const instant = '2026-10-25T06:55:23Z'

  expect(formatSgtTime(instant)).toBe('14:55:23 SGT')
  expect(formatSgtDateTime(instant)).toBe('Oct 25, 14:55:23 SGT')
})

test('formats SGT midnight across the UTC date boundary', () => {
  expect(formatSgtDateTime('2026-10-25T16:00:00Z')).toBe('Oct 26, 00:00:00 SGT')
})

test('converts an SGT datetime-local value to the matching UTC instant', () => {
  expect(sgtDateTimeInputToUtc('2026-10-25T14:55')).toBe('2026-10-25T06:55:00.000Z')
})

test('returns a dash for missing and invalid timestamps', () => {
  for (const value of [null, undefined, '', 'not-a-date']) {
    expect(formatSgtTime(value)).toBe('—')
    expect(formatSgtDateTime(value)).toBe('—')
  }
})

test('prefills Singapore wall-clock inputs from UTC across date boundaries', () => {
  expect(sgtDateTimeInputFromUtc('2026-10-31T16:05:00Z')).toBe('2026-11-01T00:05')
  expect(sgtDateTimeInputFromUtc('2026-10-31T04:55:23Z')).toBe('2026-10-31T12:55')
  expect(sgtDateTimeInputToUtc(sgtDateTimeInputFromUtc('2026-10-31T04:55:00Z'))).toBe('2026-10-31T04:55:00.000Z')
  for (const value of [null, undefined, '', 'invalid']) expect(sgtDateTimeInputFromUtc(value)).toBe('')
})

test('uses contextual labels for every diagnosis processing state', () => {
  expect(['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_TERMINAL'].map(diagnosisStatusLabel)).toEqual([
    'Diagnosis pending', 'Diagnosis in progress', 'Diagnosis completed', 'Diagnosis retrying', 'Diagnosis failed',
  ])
})

test('uses human-readable labels for supported provenance classifications', () => {
  expect(['OBSERVATION_ONLY', 'SIMULATION', 'LIVE', 'unexpected'].map(dataClassificationLabel)).toEqual([
    'Live', 'Simulation', 'Live', 'Unknown',
  ])
})

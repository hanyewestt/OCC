import { expect, test } from '@playwright/test'
import { canEditAssignments, requestedCaptainId } from '../../src/pages/admin-assignments'

test('empty captain selection explicitly requests unassignment', () => {
  expect(requestedCaptainId('', 'captain-current')).toBeNull()
  expect(requestedCaptainId(undefined, 'captain-current')).toBe('captain-current')
  expect(requestedCaptainId('captain-next', 'captain-current')).toBe('captain-next')
})

test('only running and replay-complete runs allow assignment edits', () => {
  expect(canEditAssignments('RUNNING')).toBe(true)
  expect(canEditAssignments('REPLAY_COMPLETE')).toBe(true)
  expect(canEditAssignments('CLOSED')).toBe(false)
  expect(canEditAssignments(null)).toBe(false)
})

import { expect, test } from '@playwright/test'
import { captainDirectoryPath, captainSelectionIsValid, selectedCaptainIds } from '../../src/pages/controller-captains'

test('captain directory query supports available search and paged full directory requests', () => {
  expect(captainDirectoryPath('Ari', 0, true)).toBe('/api/v1/ingestion/captains?available=true&search=Ari&page=0&size=100')
  expect(captainDirectoryPath('', 2, false)).toBe('/api/v1/ingestion/captains?page=2&size=20')
})

test('simulation captain IDs stay UUIDs and require all unique IDs or none', () => {
  const ids = ['10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', '']
  expect(selectedCaptainIds(ids, 3)).toEqual(ids)
  expect(captainSelectionIsValid(ids, 3)).toBe(false)
  expect(captainSelectionIsValid(['', '', ''], 3)).toBe(true)
  expect(captainSelectionIsValid([ids[0], ids[0]], 2)).toBe(false)
  expect(selectedCaptainIds([ids[0], ids[1], ids[0]], 2)).toEqual([ids[0], ids[1]])
})

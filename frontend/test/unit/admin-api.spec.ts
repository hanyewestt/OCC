import { expect, test } from '@playwright/test'
import { ingestionAdminPath, newIdempotencyKey } from '../../src/lib/api'
import { adminRoutePaths, adminRoutes } from '../../src/lib/admin-routes'

test('admin Ingestion routes use the proxy prefix that Nginx strips', () => {
  expect(ingestionAdminPath('admin/users')).toBe('/api/v1/ingestion/admin/users')
  expect(ingestionAdminPath('/admin/audit-events/123')).toBe('/api/v1/ingestion/admin/audit-events/123')
  expect(ingestionAdminPath('admin/provisioning-operations/123')).toBe('/api/v1/ingestion/admin/provisioning-operations/123')
  expect(ingestionAdminPath('captains?available=true')).toBe('/api/v1/ingestion/captains?available=true')
})

test('provisioning attempts receive fresh opaque idempotency keys', () => {
  const first = newIdempotencyKey()
  const second = newIdempotencyKey()
  expect(first).toMatch(/^[0-9a-f-]{36}$/i)
  expect(second).toMatch(/^[0-9a-f-]{36}$/i)
  expect(second).not.toBe(first)
})

test('admin shell recognizes provision and account detail routes and redirects its root to audit', () => {
  expect(adminRoutes.root).toBe('/admin')
  expect(adminRoutes.audit).toBe('/admin/audit')
  expect(adminRoutes.provision).toBe('/admin/accounts/new')
  expect(adminRoutePaths).toContain('/admin/accounts/new')
  expect(adminRoutePaths).not.toContain('/admin/provision')
})

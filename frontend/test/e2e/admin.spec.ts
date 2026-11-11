import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { auditFocus, auditPage } from './accessibility'

const adminId = '90000000-0000-4000-8000-000000000001'
const captainOne = '10000000-0000-4000-8000-000000000001'
const runId = '20000000-0000-4000-8000-000000000001'
const operationId = '30000000-0000-4000-8000-000000000001'
const eventId = '40000000-0000-4000-8000-000000000001'
const now = '2026-11-11T04:00:00Z'

function session() {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const expiry = Math.floor(Date.now() / 1000) + 3600
  return {
    access_token: `${encode({ alg: 'ES256', typ: 'JWT' })}.${encode({ sub: adminId, aud: 'authenticated', exp: expiry, iat: expiry - 3600, occ_role: 'ADMIN' })}.synthetic`,
    token_type: 'bearer', expires_in: 3600, expires_at: expiry, refresh_token: 'synthetic-refresh',
    user: { id: adminId, aud: 'authenticated', role: 'authenticated', email: 'admin@example.test',
      app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {}, created_at: now },
  }
}

function account(id: string, role: string, name: string, email: string) {
  return { id, role, displayName: name, email, available: true, createdAt: now,
    lastSignInAt: null, synchronizedAt: now, currentAssignment: role === 'CAPTAIN'
      ? { demoRunId: runId, busId: 'bus-1', vehicleRegistrationNumber: 'SG1001A', serviceId: '190', directionId: 1 } : null }
}

async function mockAdmin(context: BrowserContext, handlers: {
  provision?: (route: import('@playwright/test').Route) => Promise<void>
} = {}) {
  const createdAccounts = new Map<string, ReturnType<typeof account>>()
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 30, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20,
    captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  await context.route('https://example.supabase.co/auth/v1/**', route =>
    route.fulfill({ json: session() }))
  await context.route('**/api/v1/auth/**', async route => {
    const url = new URL(route.request().url())
    if (!route.request().headers().authorization?.startsWith('Bearer ')) return route.fulfill({ status: 401 })
    if (url.pathname.endsWith('/session')) return route.fulfill({ json: { subject: adminId, role: 'ADMIN' } })
    return route.fulfill({ json: { subject: adminId, role: 'ADMIN' } })
  })
  await context.route('**/api/v1/ingestion/admin/users**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/synchronizations')) return route.fulfill({ json: { directorySynchronizedAt: now } })
    if (route.request().method() === 'POST') {
      const request = route.request().postDataJSON() as { displayName: string; email: string; role: string }
      const created = account('50000000-0000-4000-8000-000000000001', request.role, request.displayName, request.email)
      createdAccounts.set(created.id, created)
      if (handlers.provision) return handlers.provision(route)
      return route.fulfill({ status: 201, json: created })
    }
    if (path === '/api/v1/ingestion/admin/users') {
      return route.fulfill({ json: { items: [
        account(captainOne, 'CAPTAIN', 'Captain One', 'captain1@example.test'),
        account('90000000-0000-4000-8000-000000000002', 'ADMIN', 'Admin Two', 'admin2@example.test'),
      ], page: Number(url.searchParams.get('page') ?? 0), size: 20, totalItems: 2, totalPages: 1, directorySynchronizedAt: now } })
    }
    const id = path.split('/').pop()!
    if (id === captainOne) return route.fulfill({ json: account(captainOne, 'CAPTAIN', 'Captain One', 'captain1@example.test') })
    if (createdAccounts.has(id)) return route.fulfill({ json: createdAccounts.get(id) })
    return route.fulfill({ status: 404, json: { detail: 'Account not found' } })
  })
  await context.route('**/api/v1/ingestion/admin/audit-events**', async route => {
    const path = new URL(route.request().url()).pathname
    const event = { id: eventId, occurredAt: now, category: 'ACCOUNT', eventType: 'ACCOUNT_PROVISIONED', outcome: 'SUCCEEDED',
      sourceService: 'INGESTION', actorSubject: adminId, actorType: 'ADMIN', entityType: 'ACCOUNT', entityId: captainOne,
      runId: null, busId: null, captainId: captainOne, summary: 'Captain provisioned', backfilled: false }
    if (path.endsWith(eventId)) return route.fulfill({ json: { ...event, recordedAt: now, correlationId: null,
      details: { modelOutput: '<img src=x onerror=alert(1)>sanitized plain text' } } })
    return route.fulfill({ json: { items: [event], page: 0, size: 20, totalItems: 1, totalPages: 1,
      retentionSeconds: 7_776_000, collectionStartedAt: now } })
  })
}

async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email').fill('admin@example.test')
  await page.getByLabel('Password', { exact: true }).fill('synthetic-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

test('admin sidebar routes, searchable account directory, synchronization and details', async ({ page, context }) => {
  const queries: string[] = []
  await mockAdmin(context)
  await context.route('**/api/v1/ingestion/admin/users?*', async route => {
    queries.push(new URL(route.request().url()).search)
    return route.fulfill({ json: { items: [account(captainOne, 'CAPTAIN', 'Captain One', 'captain1@example.test')],
      page: 0, size: 20, totalItems: 1, totalPages: 1, directorySynchronizedAt: now } })
  })
  let syncs = 0
  await context.route('**/api/v1/ingestion/admin/users/synchronizations', async route => {
    syncs++
    return route.fulfill({ json: { directorySynchronizedAt: now } })
  })
  await signIn(page)
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/admin\/audit$/)
  await expect(page.getByRole('heading', { name: 'Audit', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Expand sidebar' }).click()
  for (const label of ['Audit', 'Accounts', 'Provision account']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toBeVisible()
  }
  await page.getByRole('link', { name: 'Accounts', exact: true }).click()
  await expect(page.getByText('Account directory', { exact: true })).toBeVisible()
  await page.getByLabel('Search accounts').fill('Captain One')
  await expect.poll(() => queries.some(value => value.includes('search=Captain+One'))).toBe(true)
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Directory synchronized' })).toBeVisible()
  expect(syncs).toBe(1)
  await page.getByRole('button', { name: 'View', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Account details' })).toContainText('SG1001A')
})

test('admin provisions only controller or captain and clears the password after confirmed creation', async ({ page, context }) => {
  let submitted: Record<string, unknown> | null = null
  await mockAdmin(context, { provision: async route => {
    submitted = route.request().postDataJSON() as Record<string, unknown>
    return route.fulfill({ status: 201, json: account('50000000-0000-4000-8000-000000000001', 'CONTROLLER', 'New Controller', 'new@example.test') })
  } })
  await signIn(page)
  await page.goto('/admin/accounts/new')
  await page.getByLabel('Display name').fill('New Controller')
  await page.getByLabel('Email').fill('new@example.test')
  await page.getByLabel('Role').selectOption('CONTROLLER')
  const password = page.getByLabel('Temporary password')
  await password.fill('synthetic-one-time-password')
  await page.getByRole('button', { name: 'Show password' }).click()
  await expect(password).toHaveAttribute('type', 'text')
  await page.getByRole('button', { name: 'Hide password' }).click()
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page).toHaveURL(/\/admin\/accounts\?userId=/)
  await expect(page.getByRole('dialog', { name: 'Account details' })).toContainText('new@example.test')
  expect(submitted).toMatchObject({ displayName: 'New Controller', email: 'new@example.test', role: 'CONTROLLER' })
  expect(submitted).not.toHaveProperty('user_metadata')
})

test('uncertain provisioning stays locked until the durable operation succeeds', async ({ page, context }) => {
  let reads = 0
  await mockAdmin(context, {
    provision: route => route.fulfill({ status: 202, json: { id: operationId, status: 'PENDING' } }),
  })
  await context.route(`**/api/v1/ingestion/admin/provisioning-operations/${operationId}`, route => {
    reads++
    return route.fulfill({ json: reads === 1 ? { id: operationId, status: 'PENDING' } : {
      id: operationId, status: 'SUCCEEDED', result: account('50000000-0000-4000-8000-000000000001', 'CAPTAIN', 'New Captain', 'new-captain@example.test'),
    } })
  })
  await context.route('**/api/v1/ingestion/admin/users/50000000-0000-4000-8000-000000000001', route => route.fulfill({
    json: account('50000000-0000-4000-8000-000000000001', 'CAPTAIN', 'New Captain', 'new-captain@example.test'),
  }))
  await signIn(page)
  await page.goto('/admin/accounts/new')
  await page.getByLabel('Display name').fill('New Captain')
  await page.getByLabel('Email').fill('new-captain@example.test')
  await page.getByLabel('Temporary password').fill('synthetic-one-time-password')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('status')).toContainText('Outcome is still uncertain')
  await expect(page.getByRole('button', { name: 'Waiting for operation' })).toBeDisabled()
  await page.getByRole('button', { name: 'Check status' }).click()
  await expect(page).toHaveURL(/\/admin\/accounts\?userId=/)
  await expect(page.getByRole('dialog', { name: 'Account details' })).toContainText('new-captain@example.test')
})

test('audit filters owning service and renders evidence as escaped text', async ({ page, context }) => {
  const queries: string[] = []
  await mockAdmin(context)
  await context.route('**/api/v1/disruption/admin/audit-events**', async route => {
    queries.push(new URL(route.request().url()).search)
    const event = { id: eventId, occurredAt: now, category: 'AI_PROCESSING', eventType: 'MODEL_COMPLETED', outcome: 'SUCCEEDED',
      sourceService: 'DISRUPTION', actorSubject: 'controller-1', actorType: 'CONTROLLER', entityType: 'DIAGNOSIS', entityId: 'diag-1',
      runId, busId: 'bus-1', captainId: captainOne, summary: 'Diagnosis completed', backfilled: false }
    if (new URL(route.request().url()).pathname.endsWith(eventId)) return route.fulfill({ json: { ...event, recordedAt: now, correlationId: null,
      details: { modelOutput: '<img src=x onerror=alert(1)>sanitized plain text' } } })
    return route.fulfill({ json: { items: [event], page: 0, size: 20, totalItems: 1, totalPages: 1, retentionSeconds: 7_776_000, collectionStartedAt: now } })
  })
  await signIn(page)
  await page.goto('/admin/audit')
  await page.getByLabel('Service').selectOption('disruption')
  await page.getByLabel('Event').fill('MODEL_COMPLETED')
  await expect.poll(() => queries.some(value => value.includes('eventType=MODEL_COMPLETED'))).toBe(true)
  await expect(page.getByText(/Retention: 90 days/)).toBeVisible()
  await page.getByRole('button', { name: 'View', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Audit event detail' })
  await expect(dialog).toContainText('<img src=x onerror=alert(1)>sanitized plain text')
  await expect(dialog.locator('img')).toHaveCount(0)
})

for (const size of [{ width: 1280, height: 720 }, { width: 800, height: 480 }]) {
  test(`admin workspace remains accessible at ${size.width}x${size.height}`, async ({ page, context }) => {
    test.setTimeout(120_000)
    await page.setViewportSize(size)
    await mockAdmin(context)
    await signIn(page)
    for (const path of ['/admin/audit', '/admin/accounts', '/admin/accounts/new']) {
      await test.step(path, async () => {
        await page.goto(path)
        await page.waitForLoadState('networkidle')
        expect(await auditPage(page)).toEqual([])
        expect(await auditFocus(page)).toEqual([])
      })
    }
  })
}

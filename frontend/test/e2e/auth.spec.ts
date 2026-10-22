import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { classify, clockText, deltaText, initialScenario } from '../../src/lib/obu-pacing'

// Synthetic Supabase and Gateway responses. These tests do not prove provider
// behavior or backend authorization; Gateway tests and the opt-in live smoke do.
const userId = '11111111-1111-4111-8111-111111111111'
function session(role: string, lifetime = 3600) {
  const now = Math.floor(Date.now() / 1000)
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return {
    access_token: `${encode({ alg: 'ES256', typ: 'JWT' })}.${encode({ sub: userId, aud: 'authenticated', exp: now + lifetime, iat: now, occ_role: role })}.synthetic`,
    token_type: 'bearer', expires_in: lifetime, expires_at: now + lifetime,
    refresh_token: 'synthetic-refresh-token',
    user: { id: userId, aud: 'authenticated', role: 'authenticated', email: 'user@example.test',
      app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {}, created_at: '2026-01-01T00:00:00Z' },
  }
}
type Options = { invalidPassword?: boolean; sessionFailure?: boolean; accessFailure?: boolean; refreshFailure?: boolean }
async function mockAuth(context: BrowserContext, role: string, options: Options = {}) {
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
  } }))
  await context.route('https://example.supabase.co/auth/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/logout')) return route.fulfill({ status: 204 })
    if ((options.invalidPassword && url.searchParams.get('grant_type') === 'password') ||
      (options.refreshFailure && url.searchParams.get('grant_type') === 'refresh_token')) {
      return route.fulfill({ status: 400, json: { error: 'invalid_grant', error_code: 'refresh_token_not_found', msg: 'Synthetic rejected session' } })
    }
    return route.fulfill({ json: session(role) })
  })
  await context.route('**/api/v1/auth/**', async route => {
    const url = new URL(route.request().url())
    if (!route.request().headers().authorization?.startsWith('Bearer ')) return route.fulfill({ status: 401 })
    if ((options.sessionFailure && url.pathname.endsWith('/session')) || options.accessFailure) {
      return route.fulfill({ status: 503 })
    }
    const page = url.pathname.split('/').pop()
    if (page !== 'session' && !((page === 'controller' && role === 'CONTROLLER') || (page === 'captain' && role === 'CAPTAIN'))) {
      return route.fulfill({ status: 403 })
    }
    return route.fulfill({ json: { subject: userId,
      role: ['CONTROLLER', 'CAPTAIN', 'ADMIN', 'UNASSIGNED'].includes(role) ? role : 'UNASSIGNED' } })
  })
}
async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email').fill('user@example.test')
  await page.getByLabel('Password', { exact: true }).fill('synthetic-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

for (const [role, destination, title, other] of [
  ['CONTROLLER', '/controller', 'Controller', '/captain'],
  ['CAPTAIN', '/captain', 'Captain', '/controller'],
]) {
  test(`${role} can access only its own page and survives reload`, async ({ page, context }) => {
    await mockAuth(context, role)
    await login(page)
    await expect(page).toHaveURL(new RegExp(`${destination}$`))
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await page.goto(other)
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Captain pacing display' })).toHaveCount(0)
    await page.getByRole('link', { name: 'Return to my page' }).click()
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  })
}

for (const role of ['ADMIN', 'UNASSIGNED', 'DRIVER']) {
  test(`${role} receives no role page`, async ({ page, context }) => {
    await mockAuth(context, role)
    await login(page)
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
    await page.goto('/captain')
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
  })
}

test('unauthenticated direct navigation returns to login', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await page.goto('/controller')
  await expect(page).toHaveURL(/\/login$/)
})
test('invalid credentials keep the user on login without echoing the password', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER', { invalidPassword: true })
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Check your email and password')
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('')
})
test('failed session verification never renders protected content and can retry', async ({ page, context }) => {
  const options = { sessionFailure: true }
  await mockAuth(context, 'CONTROLLER', options)
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Unable to verify your session')
  await expect(page.getByText('Access verified')).toHaveCount(0)
  options.sessionFailure = false
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByText('Access verified')).toBeVisible()
})
test('page verification failures do not grant access', async ({ page, context }) => {
  const options = { accessFailure: false }
  await mockAuth(context, 'CONTROLLER', options)
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  options.accessFailure = true
  await page.reload()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByText('Access verified')).toHaveCount(0)
})
test('expired persisted session with rejected refresh returns to login', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER', { refreshFailure: true })
  await page.addInitScript(saved => {
    localStorage.setItem('sb-example-auth-token', JSON.stringify(saved))
  }, session('CONTROLLER', -60))
  await page.goto('/controller')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('Access verified')).toHaveCount(0)
})
test('sign-out propagates to another open tab', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  const other = await context.newPage()
  await other.goto('/controller')
  await expect(other.getByText('Access verified')).toBeVisible()
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(other).toHaveURL(/\/login$/)
  await expect(other.getByText('Access verified')).toHaveCount(0)
})
test('configuration failure offers retry without a sign-in form', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/frontend-config', route => route.fulfill({ status: 503 }))
  await page.goto('/login')
  await expect(page.getByRole('alert')).toContainText('configuration is unavailable')
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toHaveCount(0)
})

test('restored expired session refreshes and verifies the new role', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.addInitScript(saved => {
    localStorage.setItem('sb-example-auth-token', JSON.stringify(saved))
  }, session('CONTROLLER', -60))
  await page.goto('/')
  await expect(page).toHaveURL(/\/captain$/)
  await expect(page.getByRole('heading', { name: 'Captain', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Controller', exact: true })).toHaveCount(0)
})

test('saved browser session works in a new browser context after verification', async ({ page, context, browser }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  const restored = await browser.newContext({ storageState: await context.storageState(), baseURL: page.url(),
    ignoreHTTPSErrors: ['localhost', '127.0.0.1'].includes(new URL(page.url()).hostname) })
  try {
    await mockAuth(restored, 'CONTROLLER')
    const tab = await restored.newPage()
    await tab.goto('/')
    await expect(tab.getByRole('heading', { name: 'Controller', exact: true })).toBeVisible()
  } finally {
    await restored.close()
  }
})

test('Gateway rejection clears a restored session', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  await context.route('**/api/v1/auth/session', route => route.fulfill({ status: 401 }))
  await page.reload()
  await expect(page).toHaveURL(/\/401$/)
  await expect(page.getByRole('heading', { name: 'Sign-in required' })).toBeVisible()
  await expect(page.getByText('401', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('Access verified')).toHaveCount(0)
})

for (const [status, heading] of [[401, 'Sign-in required'], [403, 'Access denied']] as const) {
  test(`page access rejection displays ${status} without protected content`, async ({ page, context }) => {
    await mockAuth(context, 'CONTROLLER')
    await login(page)
    await expect(page.getByText('Access verified')).toBeVisible()
    await context.route('**/api/v1/auth/access/controller', route => route.fulfill({ status }))
    await page.reload()
    await expect(page.getByRole('heading', { name: heading })).toBeVisible()
    await expect(page.getByText(String(status), { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
  })
}

test('pacing treats threshold boundaries and missing measurements consistently', () => {
  expect(classify(240, 480)).toBe('close')
  expect(classify(241, 480)).toBe('ok')
  expect(classify(719, 480)).toBe('ok')
  expect(classify(720, 480)).toBe('far')
  for (const missing of [null, Number.NaN, -1]) {
    expect(classify(missing, 480)).toBe('unknown')
    expect(clockText(missing)).toBe('--:--')
  }
  expect(classify(300, 0)).toBe('unknown')
  expect(deltaText(490, 480)).toBe('About on target')
  expect(initialScenario('?scenario=invalid')).toBe('bunched')
})

test('captain pacing updates samples and switches scenarios without operational API calls', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.clock.install()
  const operationalRequests: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) operationalRequests.push(path)
  })
  await login(page)
  const ahead = page.getByRole('region', { name: 'Gap to bus ahead', exact: true })
  const behind = page.getByRole('region', { name: 'Gap to bus behind', exact: true })
  await expect(page.getByText('SAMPLE DATA', { exact: true })).toBeVisible()
  await expect(ahead.getByText('1:35', { exact: true })).toBeVisible()
  await expect(ahead).toHaveAttribute('data-state', 'close')
  await expect(behind).toHaveAttribute('data-state', 'far')
  await page.clock.fastForward(10_000)
  await expect(ahead.getByText('1:41', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'On target', exact: true }).click()
  await expect(ahead).toHaveAttribute('data-state', 'ok')
  await expect(behind).toHaveAttribute('data-state', 'ok')
  await page.getByRole('button', { name: 'Big gap ahead', exact: true }).click()
  await expect(ahead).toHaveAttribute('data-state', 'far')
  await expect(behind).toHaveAttribute('data-state', 'close')
  await page.getByRole('button', { name: 'No bus behind', exact: true }).click()
  await expect(behind.getByText('--:--', { exact: true })).toBeVisible()
  await expect(behind).toHaveAttribute('data-state', 'unknown')
  expect(operationalRequests).toEqual([])
})

test('captain pacing supports narrow screens, scenario links and persistent day mode', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.setViewportSize({ width: 375, height: 812 })
  await login(page)
  await page.goto('/captain?scenario=no-bus-behind')
  const display = page.getByRole('region', { name: 'Captain pacing display', exact: true })
  await expect(page.getByRole('region', { name: 'Gap to bus behind', exact: true })).toHaveAttribute('data-state', 'unknown')
  await page.getByRole('button', { name: 'Day mode', exact: true }).click()
  await expect(display).toHaveAttribute('data-theme', 'day')
  await page.reload()
  await expect(display).toHaveAttribute('data-theme', 'day')
  await expect(page.getByRole('button', { name: 'Night mode', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

import { test, expect, type BrowserContext } from '@playwright/test'

// Synthetic notice responses prove anonymous rendering and request behavior,
// not provider ingestion or backend publication/authorization rules.
const notice = {
  id: 'notice-synthetic-1', classification: 'OBSERVATION_ONLY', title: 'Delays near Example Stop',
  message: 'Service 190 may be delayed near Example Stop. Allow extra travel time.',
  services: ['190', '972'], locations: ['Example Stop'], effectiveFrom: '2026-10-25T00:00:00Z',
  effectiveUntil: '2026-11-25T12:00:00Z', publishedAt: '2026-10-25T00:00:00Z',
}

async function publicConfig(context: BrowserContext) {
  // Deliberately omit Supabase URL/key. Public notices must not depend on sign-in configuration.
  await context.route('**/frontend-config', route => route.fulfill({ json: { publicNoticeRefreshIntervalSeconds: 30 } }))
}

for (const viewport of [{ name: 'mobile', width: 375, height: 812 }, { name: 'desktop', width: 1440, height: 1000 }]) {
  test(`anonymous public board follows the shared layout at ${viewport.name} width despite invalid sign-in configuration`, async ({ page, context }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await publicConfig(context)
    await context.route('**/api/v1/planning/public/notices?*', route => {
      const query = new URL(route.request().url()).searchParams
      expect(query.get('classification')).toBe('OBSERVATION_ONLY')
      expect(query.has('demoRunId')).toBe(false)
      expect(route.request().headers().authorization).toBeUndefined()
      return route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } })
    })

    await page.goto('/notices')
    await expect(page.getByRole('heading', { name: 'Service advisories', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
    await expect(page.getByText(notice.message, { exact: true })).toBeVisible()
    await expect(page.getByText('Services: 190, 972', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Staff sign in', exact: true })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Controller navigation' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Publish|Withdraw|Draft notice/ })).toHaveCount(0)
    await expect(page.getByText('SIMULATION', { exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    const screenshot = testInfo.outputPath(`public-board-${viewport.name}.png`)
    await page.screenshot({ path: screenshot, fullPage: true })
    await testInfo.attach(`Public board ${viewport.name}`, { path: screenshot, contentType: 'image/png' })

    await page.reload()
    await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  })
}

test('public board shows an explicit empty state for a successful empty response', async ({ page, context }) => {
  await publicConfig(context)
  await context.route('**/api/v1/planning/public/notices?*', route => route.fulfill({
    json: { items: [], page: 0, totalItems: 0, totalPages: 0 },
  }))
  await page.goto('/notices')
  await expect(page.getByText('No current service advisories.', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('public board reports API failure and recovers through an explicit retry', async ({ page, context }) => {
  await publicConfig(context)
  let failed = true
  await context.route('**/api/v1/planning/public/notices?*', route => failed
    ? route.fulfill({ status: 503, json: { detail: 'Service advisories are temporarily unavailable.' } })
    : route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } }))
  await page.goto('/notices')
  await expect(page.getByRole('alert')).toContainText('Service advisories are temporarily unavailable.')
  await expect(page.getByText('No current service advisories.', { exact: true })).toHaveCount(0)
  failed = false
  await page.getByRole('button', { name: 'Try again', exact: true }).click()
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('simulation board uses explicit run scope and labels demonstration notices', async ({ page, context }) => {
  await publicConfig(context)
  const runId = '00000000-0000-0000-0000-000000000019'
  await context.route('**/api/v1/planning/public/notices?*', route => {
    const query = new URL(route.request().url()).searchParams
    expect(query.get('classification')).toBe('SIMULATION')
    expect(query.get('demoRunId')).toBe(runId)
    return route.fulfill({ json: { items: [{ ...notice, classification: 'SIMULATION' }], page: 0, totalItems: 1, totalPages: 1 } })
  })
  await page.goto(`/notices?classification=SIMULATION&demoRunId=${runId}`)
  await expect(page.getByRole('status').filter({ hasText: 'SIMULATION · Demonstration notices' })).toBeVisible()
  await expect(page.getByText('SIMULATION', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
})

test('simulation board rejects missing run identity before requesting notices', async ({ page, context }) => {
  await publicConfig(context)
  let requests = 0
  await context.route('**/api/v1/planning/public/notices?*', route => {
    requests++
    return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })
  await page.goto('/notices?classification=SIMULATION')
  await expect(page.getByRole('alert')).toContainText('The simulation view needs a demo run ID.')
  expect(requests).toBe(0)
})

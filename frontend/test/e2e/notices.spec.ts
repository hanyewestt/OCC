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
      const items = Array.from({ length: 5 }, (_, index) => ({ ...notice, id: `notice-synthetic-${index + 1}`,
        title: index === 0 ? notice.title : `Service advisory ${index + 1}`, message: index === 0 ? notice.message : `Service advisory message ${index + 1}.` }))
      return route.fulfill({ json: { items, page: 0, totalItems: items.length, totalPages: 1 } })
    })

    await page.goto('/notices')
    await expect(page.getByRole('heading', { name: 'Service advisories', exact: true })).toBeVisible()
    await expect(page.getByLabel('Page refresh status')).toHaveCount(1)
    await expect(page.getByRole('heading', { level: 1 }).locator('..').getByLabel('Page refresh status')).toContainText('Refreshes every 30 seconds')
    await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
    await expect(page.getByText(notice.message, { exact: true })).toBeVisible()
    await expect(page.getByText('Services: 190, 972', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('link', { name: 'Staff sign in', exact: true })).toBeVisible()
    const noticeRegion = page.getByRole('region', { name: 'Public service advisories', exact: true })
    const regionBounds = await noticeRegion.boundingBox()
    expect(regionBounds).not.toBeNull()
    const visibleNotices = await noticeRegion.locator('[data-slot="card"]').evaluateAll((elements, bounds) => elements.filter(element => {
      const rect = element.getBoundingClientRect()
      return rect.top >= bounds.top && rect.bottom <= bounds.top + bounds.height
    }).length, { top: regionBounds!.y, height: regionBounds!.height })
    expect(visibleNotices).toBeLessThanOrEqual(4)
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
  await expect(page.getByRole('status').filter({ hasText: 'Simulation · Demonstration notices' })).toBeVisible()
  await expect(page.getByText('Simulation', { exact: true })).toBeVisible()
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

test('public board scrolls through notices and refresh returns to the latest first page', async ({ page, context }) => {
  await page.clock.install()
  await publicConfig(context)
  const laterNotice = { ...notice, id: 'notice-synthetic-2', title: 'Later page notice' }
  let shrunk = false
  const requestedPages: number[] = []
  await context.route('**/api/v1/planning/public/notices?*', route => {
    const requestedPage = Number(new URL(route.request().url()).searchParams.get('page'))
    requestedPages.push(requestedPage)
    if (requestedPage === 1 && shrunk) {
      return route.fulfill({ json: { items: [], page: 1, totalItems: 1, totalPages: 1 } })
    }
    return requestedPage === 1
      ? route.fulfill({ json: { items: [laterNotice], page: 1, totalItems: 21, totalPages: 2 } })
      : route.fulfill({ json: { items: [notice], page: 0, totalItems: 21, totalPages: 2 } })
  })

  await page.goto('/notices')
  const refreshedAt = page.getByLabel('Page refresh status')
  await expect(refreshedAt).toContainText('Last successful refresh')
  const firstRefresh = await refreshedAt.innerText()
  await page.clock.runFor(2000)
  await page.getByRole('button', { name: 'Load more service advisories', exact: true }).click()
  await expect(page.getByRole('heading', { name: laterNotice.title, exact: true })).toBeVisible()
  await expect(refreshedAt).toHaveText(firstRefresh)
  await expect(page.getByRole('navigation', { name: 'Notice pages', exact: true })).toHaveCount(0)
  shrunk = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()

  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  await expect(refreshedAt).not.toHaveText(firstRefresh)
  expect(requestedPages.slice(-2)).toEqual([1, 0])
})

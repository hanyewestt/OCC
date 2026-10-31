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
  await expect(page.getByRole('alert')).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Retry service advisories', exact: true })).toHaveCount(0)
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

for (const scope of [
  { name: 'unknown classification', query: 'classification=LIVE', error: 'Invalid notice view.' },
  { name: 'empty classification', query: 'classification=', error: 'Invalid notice view.' },
  { name: 'malformed run identity', query: 'classification=SIMULATION&demoRunId=not-a-uuid', error: 'The simulation view needs a valid demo run ID.' },
  { name: 'run identity on Live', query: 'demoRunId=00000000-0000-0000-0000-000000000019', error: 'Live notices do not accept a demo run ID.' },
  { name: 'empty run identity on Live', query: 'demoRunId=', error: 'Live notices do not accept a demo run ID.' },
]) {
  test(`public board rejects ${scope.name} without requests and offers a valid Live link`, async ({ page, context }) => {
    let configReads = 0
    let noticeReads = 0
    await context.route('**/frontend-config', route => {
      configReads++
      return route.fulfill({ json: { publicNoticeRefreshIntervalSeconds: 30 } })
    })
    await context.route('**/api/v1/planning/public/notices?*', route => {
      noticeReads++
      const query = new URL(route.request().url()).searchParams
      expect(query.get('classification')).toBe('OBSERVATION_ONLY')
      expect(query.has('demoRunId')).toBe(false)
      return route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } })
    })
    await page.goto(`/notices?${scope.query}`)
    await expect(page.getByRole('alert')).toContainText(scope.error)
    await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeDisabled()
    // App bootstrap reads shared settings; an invalid board adds no timing request.
    expect(configReads).toBe(1)
    expect(noticeReads).toBe(0)
    await page.getByRole('link', { name: 'View Live notices', exact: true }).click()
    await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
    await expect(page.getByText('Live service advisories', { exact: true })).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(noticeReads).toBe(1)
  })
}

test('switching a populated simulation board to Live clears old content while the new scope loads', async ({ page, context }) => {
  await publicConfig(context)
  let releaseLive: (() => void) | undefined
  const liveWaiting = new Promise<void>(resolve => { releaseLive = resolve })
  await context.route('**/api/v1/planning/public/notices?*', async route => {
    const query = new URL(route.request().url()).searchParams
    if (query.get('classification') === 'SIMULATION') {
      return route.fulfill({ json: { items: [{ ...notice, classification: 'SIMULATION', title: 'Run A demonstration' }], page: 0, totalItems: 1, totalPages: 1 } })
    }
    await liveWaiting
    return route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } })
  })
  await page.goto('/notices?classification=SIMULATION&demoRunId=00000000-0000-0000-0000-000000000019')
  await expect(page.getByRole('heading', { name: 'Run A demonstration', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'View Live notices', exact: true }).click()
  await expect(page.getByText('Live service advisories', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Run A demonstration', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('Page refresh status')).toContainText('Not refreshed yet')
  releaseLive?.()
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
})

test('a late simulation response cannot populate the Live board after navigation', async ({ page, context }) => {
  await publicConfig(context)
  let markStarted: (() => void) | undefined
  let releaseSimulation: (() => void) | undefined
  let markFinished: (() => void) | undefined
  const started = new Promise<void>(resolve => { markStarted = resolve })
  const waiting = new Promise<void>(resolve => { releaseSimulation = resolve })
  const finished = new Promise<void>(resolve => { markFinished = resolve })
  await context.route('**/api/v1/planning/public/notices?*', async route => {
    if (new URL(route.request().url()).searchParams.get('classification') === 'SIMULATION') {
      markStarted?.()
      await waiting
      await route.fulfill({ json: { items: [{ ...notice, classification: 'SIMULATION', title: 'Obsolete simulation response' }], page: 0, totalItems: 1, totalPages: 1 } })
      markFinished?.()
      return
    }
    return route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } })
  })
  await page.goto('/notices?classification=SIMULATION&demoRunId=00000000-0000-0000-0000-000000000019')
  await started
  await page.getByRole('link', { name: 'View Live notices', exact: true }).click()
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  releaseSimulation?.()
  await finished
  await expect(page.getByRole('heading', { name: 'Obsolete simulation response', exact: true })).toHaveCount(0)
  await expect(page.getByText('Simulation', { exact: true })).toHaveCount(0)
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

for (const configFailure of ['unavailable', 'invalid interval']) {
  test(`public board recovers from ${configFailure} configuration without requiring sign-in`, async ({ page, context }) => {
    let failed = true
    let noticeReads = 0
    await context.route('**/frontend-config', route => failed
      ? configFailure === 'unavailable' ? route.fulfill({ status: 503 })
        : route.fulfill({ json: { publicNoticeRefreshIntervalSeconds: 0 } })
      : route.fulfill({ json: { publicNoticeRefreshIntervalSeconds: 30 } }))
    await context.route('**/api/v1/planning/public/notices?*', route => {
      noticeReads++
      expect(route.request().headers().authorization).toBeUndefined()
      return route.fulfill({ json: { items: [notice], page: 0, totalItems: 1, totalPages: 1 } })
    })
    await page.goto('/notices')
    await expect(page.getByRole('alert')).toContainText('settings are unavailable')
    expect(noticeReads).toBe(0)
    await expect(page.getByLabel('Email', { exact: true })).toHaveCount(0)
    failed = false
    await page.getByRole('button', { name: 'Try again', exact: true }).click()
    await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
  })
}

test('public board keeps notices and refresh time when refresh fails then retries page zero', async ({ page, context }) => {
  await page.clock.install()
  await publicConfig(context)
  let failed = false
  let updated = false
  const pages: number[] = []
  await context.route('**/api/v1/planning/public/notices?*', route => {
    pages.push(Number(new URL(route.request().url()).searchParams.get('page')))
    return failed ? route.fulfill({ status: 503, json: { detail: 'Temporary refresh failure.' } })
      : route.fulfill({ json: { items: [{ ...notice, title: updated ? 'Updated passenger advice' : notice.title }], page: 0, totalItems: 1, totalPages: 1 } })
  })
  await page.goto('/notices')
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  const refreshTime = page.getByLabel('Page refresh status')
  const firstTime = await refreshTime.innerText()
  await page.clock.runFor(2000)
  failed = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(1)
  await expect(page.getByRole('alert')).toContainText('Unable to refresh service advisories.')
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  await expect(refreshTime).toHaveText(firstTime)
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toHaveCount(0)
  failed = false
  updated = true
  await page.getByRole('button', { name: 'Retry service advisories', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: 'Updated passenger advice', exact: true })).toBeVisible()
  await expect(refreshTime).not.toHaveText(firstTime)
  expect(pages).toEqual([0, 0, 0])
})

test('public board retries a failed later page without discarding notices or duplicating overlapping results', async ({ page, context }) => {
  await publicConfig(context)
  const later = { ...notice, id: 'notice-later', title: 'Later page advice' }
  let failed = true
  const pages: number[] = []
  await context.route('**/api/v1/planning/public/notices?*', route => {
    const pageNumber = Number(new URL(route.request().url()).searchParams.get('page'))
    pages.push(pageNumber)
    if (pageNumber === 1 && failed) return route.fulfill({ status: 503, json: { detail: 'Later page unavailable.' } })
    return route.fulfill({ json: { items: pageNumber === 0 ? [notice] : [notice, later], page: pageNumber, totalItems: 21, totalPages: 2 } })
  })
  await page.goto('/notices')
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  const refreshTime = await page.getByLabel('Page refresh status').innerText()
  await page.getByRole('button', { name: 'Load more service advisories', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(1)
  await expect(page.getByRole('alert')).toContainText('Unable to load more service advisories.')
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  failed = false
  await page.getByRole('button', { name: 'Retry loading more service advisories', exact: true }).click()
  await expect(page.getByRole('heading', { name: later.title, exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toHaveCount(1)
  await expect(page.getByLabel('Page refresh status')).toHaveText(refreshTime)
  expect(pages).toEqual([0, 1, 1])
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('public board automatically discovers published changes with one refresh cycle', async ({ page, context }) => {
  await page.clock.install()
  await publicConfig(context)
  let reads = 0
  await context.route('**/api/v1/planning/public/notices?*', route => {
    reads++
    return route.fulfill({ json: { items: reads === 1 ? [] : [notice], page: 0, totalItems: reads === 1 ? 0 : 1, totalPages: reads === 1 ? 0 : 1 } })
  })
  await page.goto('/notices')
  await expect(page.getByText('No current service advisories.', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  await page.clock.runFor(31000)
  await expect(page.getByRole('heading', { name: notice.title, exact: true })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('Service advisories loaded: 1.')
  expect(reads).toBe(2)
})

test('public board supports keyboard scrolling and renders long passenger content as text without horizontal overflow', async ({ page, context }, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await publicConfig(context)
  const longTitle = 'Service'.repeat(25)
  const htmlMessage = '<img src=x onerror="window.noticeInjected=true">'
  await context.route('**/api/v1/planning/public/notices?*', route => route.fulfill({ json: {
    items: Array.from({ length: 6 }, (_, index) => ({ ...notice, id: `long-${index}`, title: index ? `Advisory ${index + 1}` : longTitle,
      message: index ? notice.message : htmlMessage, services: index ? [] : ['9'.repeat(250)], locations: index ? [] : ['Location'.repeat(35)] })),
    page: 0, totalItems: 6, totalPages: 1,
  } }))
  await page.goto('/notices')
  await expect(page.getByRole('heading', { name: longTitle, exact: true })).toBeVisible()
  await expect(page.getByText(htmlMessage, { exact: true })).toBeVisible()
  await expect(page.locator('main img')).toHaveCount(0)
  expect(await page.evaluate(() => 'noticeInjected' in window)).toBe(false)
  const region = page.getByRole('region', { name: 'Public service advisories', exact: true })
  await expect(region).toHaveAttribute('tabindex', '0')
  expect(await region.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  const screenshot = testInfo.outputPath('public-board-long-content-mobile.png')
  await page.screenshot({ path: screenshot, fullPage: true })
  await testInfo.attach('Public board long content', { path: screenshot, contentType: 'image/png' })
  await region.focus()
  await expect(region).toBeFocused()
  await page.keyboard.press('End')
  await expect.poll(() => region.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  await expect(page.getByRole('heading', { name: 'Advisory 6', exact: true })).toBeInViewport()
})

import { expect, test } from '@playwright/test'

type PageRequest = { scope: string; page: number }

test('a failed refresh retains rows and timestamp while successful refresh resets pagination', async ({ page }) => {
  await page.clock.install()
  let fail = false
  await page.route('**/__test/pages?**', route => {
    const query = new URL(route.request().url()).searchParams
    return fail ? route.fulfill({ status: 503 }) : route.fulfill({ json: rows(query.get('scope')!, Number(query.get('page'))) })
  })
  await page.goto('/test/fixtures/paged-collection.html')
  await expect(page.getByTestId('arrival-row')).toHaveCount(10)
  const timestamp = page.getByLabel('Last successful refresh')
  const originalTimestamp = await timestamp.innerText()
  await page.clock.runFor(2000)
  await page.getByRole('button', { name: 'Load more rows', exact: true }).click()
  await expect(page.getByTestId('arrival-row')).toHaveCount(19)
  await expect(timestamp).toHaveText(originalTimestamp)
  fail = true
  await page.getByRole('button', { name: 'Refresh rows' }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByTestId('arrival-row')).toHaveCount(19)
  await expect(timestamp).toHaveText(originalTimestamp)
  fail = false
  await page.getByRole('button', { name: 'Refresh rows' }).click()
  await expect(page.getByTestId('arrival-row')).toHaveCount(10)
  await expect(timestamp).not.toHaveText(originalTimestamp)
})

function rows(scope: string, page: number) {
  const numbers = page === 0 ? Array.from({ length: 10 }, (_, index) => index + 1) :
    page === 1 ? [10, ...Array.from({ length: 9 }, (_, index) => index + 11)] :
      [19, ...Array.from({ length: 6 }, (_, index) => index + 20)]
  const items = numbers.map(number => ({ id: `${scope}-${number}`, label: `Row ${number}` }))
  return { items, page, totalItems: 25, totalPages: 3 }
}

async function mockPages(page: import('@playwright/test').Page, options: {
  slow?: PageRequest; failOnce?: PageRequest
} = {}) {
  const requests: string[] = []
  const failed = new Set<string>()
  await page.route('**/__test/pages?**', async route => {
    const url = new URL(route.request().url())
    const scope = url.searchParams.get('scope') ?? ''
    const pageNumber = Number(url.searchParams.get('page'))
    const key = `${scope}:${pageNumber}`
    requests.push(key)
    if (options.slow?.scope === scope && options.slow.page === pageNumber) {
      await new Promise(resolve => setTimeout(resolve, 250))
    }
    if (options.failOnce?.scope === scope && options.failOnce.page === pageNumber && !failed.has(key)) {
      failed.add(key)
      return route.fulfill({ status: 503, json: { message: 'Temporary failure' } })
    }
    return route.fulfill({ json: rows(scope, pageNumber) })
  })
  return requests
}

test('scrolls bounded rows, keeps the header sticky, loads each page once, and refreshes from page zero', async ({ page }) => {
  const requests = await mockPages(page, { slow: { scope: 'alpha', page: 1 } })
  await page.goto('/test/fixtures/paged-collection.html')
  const viewport = page.getByRole('region', { name: 'Arrival rows' })
  const bodyRows = page.getByTestId('arrival-row')
  await expect(bodyRows).toHaveCount(10)
  const viewportBounds = await viewport.boundingBox()
  const initiallyVisible = await bodyRows.evaluateAll((elements, bounds) => elements.filter(element => {
    const rect = element.getBoundingClientRect()
    return rect.top >= bounds.top && rect.bottom <= bounds.top + bounds.height
  }).length, { top: viewportBounds!.y, height: viewportBounds!.height })
  expect(initiallyVisible).toBeLessThanOrEqual(10)

  await viewport.evaluate(element => {
    element.scrollTop = element.scrollHeight
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
  })
  await expect(bodyRows).toHaveCount(19)
  expect(requests.filter(value => value === 'alpha:1')).toHaveLength(1)

  const tableHeader = viewport.locator('thead th').first()
  await viewport.evaluate(element => {
    element.scrollTop = element.scrollHeight
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
  })
  await expect(bodyRows).toHaveCount(25)
  await expect.poll(async () => {
    const viewportBox = await viewport.boundingBox()
    const headerBox = await tableHeader.boundingBox()
    return viewportBox && headerBox ? Math.abs(headerBox.y - viewportBox.y) : Number.POSITIVE_INFINITY
  }).toBeLessThan(2)
  await page.setViewportSize({ width: 375, height: 720 })
  const widths = await viewport.evaluate(element => [element.scrollWidth, element.clientWidth])
  expect(widths[0]).toBeGreaterThan(widths[1])
  await viewport.evaluate(element => { element.scrollLeft = element.scrollWidth })
  expect(await viewport.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
  await viewport.evaluate(element => { element.scrollTop = element.scrollHeight })
  await page.waitForTimeout(50)
  expect(requests.filter(value => value === 'alpha:2')).toHaveLength(1)

  await page.getByRole('button', { name: 'Refresh rows' }).click()
  await expect(bodyRows).toHaveCount(10)
  expect(requests.filter(value => value === 'alpha:0')).toHaveLength(2)
})

test('ignores late results from an old scope and allows a later-page retry', async ({ page }) => {
  const requests = await mockPages(page, { slow: { scope: 'alpha', page: 1 }, failOnce: { scope: 'beta', page: 1 } })
  await page.goto('/test/fixtures/paged-collection.html')
  const viewport = page.getByRole('region', { name: 'Arrival rows' })
  await expect(page.getByTestId('arrival-row')).toHaveCount(10)
  await viewport.evaluate(element => {
    element.scrollTop = element.scrollHeight
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
  })
  await expect.poll(() => requests.includes('alpha:1')).toBe(true)

  await page.getByLabel('Collection scope').selectOption('beta')
  await expect(page.getByTestId('arrival-row').first()).toContainText('beta-1')
  await page.waitForTimeout(300)
  await expect(page.getByTestId('arrival-row')).toHaveCount(10)
  await viewport.evaluate(element => {
    element.scrollTop = element.scrollHeight
  })
  await expect.poll(() => requests.filter(value => value === 'beta:1')).toHaveLength(1)
  await expect(page.getByRole('alert')).toHaveText('Could not load more rows.')
  await expect(page.getByTestId('arrival-row')).toHaveCount(10)
  await viewport.evaluate(element => element.dispatchEvent(new Event('scroll', { bubbles: true })))
  await page.waitForLoadState('networkidle')
  expect(requests.filter(value => value === 'beta:1')).toHaveLength(1)
  await page.getByRole('button', { name: 'Retry loading more' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('arrival-row')).toHaveCount(19)
  expect(requests.filter(value => value === 'beta:1')).toHaveLength(2)

  await page.getByLabel('Collection scope').selectOption('alpha')
  await expect(page.getByTestId('arrival-row').first()).toContainText('alpha-1')
  expect(await page.getByTestId('arrival-row').allTextContents()).not.toContain('beta-1')
})

test('bounds larger card lists to four visible cards and lets keyboard users scroll them', async ({ page }) => {
  await mockPages(page)
  await page.goto('/test/fixtures/paged-collection.html')
  const viewport = page.getByRole('region', { name: 'Incident cards' })
  const bounds = await viewport.boundingBox()
  expect(bounds).not.toBeNull()
  const visibleCards = await page.getByTestId('incident-card').evaluateAll((elements, top) =>
    elements.filter(element => {
      const rect = element.getBoundingClientRect()
      return rect.top >= top.top && rect.bottom <= top.top + top.height
    }).length, { top: bounds!.y, height: bounds!.height })
  expect(visibleCards).toBeLessThanOrEqual(4)
  const shortList = page.getByRole('region', { name: 'Short collection' })
  expect(await shortList.boundingBox()).not.toBeNull()
  expect((await shortList.boundingBox())!.height).toBeLessThan(288)
  await viewport.locator('[data-slot="scroll-area-viewport"]').focus()
  await page.keyboard.press('End')
  await expect(viewport).toHaveText(/Incident 9/)
})

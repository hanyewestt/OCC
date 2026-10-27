import { test, expect, type Route } from '@playwright/test'

test('slow refresh coalesces clicks, queues one mutation follow-up, and restarts polling after completion', async ({ page }) => {
  await page.clock.install()
  let requests = 0
  let held: Route | undefined
  await page.route('**/__test/refresh?*', async route => {
    requests++
    if (requests === 1) { held = route; return }
    await route.fulfill({ body: `Result ${requests}` })
  })
  await page.goto('/test/fixtures/page-refresh.html')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => requests).toBe(1)
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await page.getByRole('button', { name: 'Saved' }).click()
  await page.getByRole('button', { name: 'Saved' }).click()
  await page.clock.runFor(5000)
  expect(requests).toBe(1)
  await held!.fulfill({ body: 'First result' })
  await expect(page.getByLabel('Result')).toHaveText('Result 2')
  await expect(page.getByRole('status')).toHaveText('Idle')
  expect(requests).toBe(2)
  await page.clock.runFor(1100)
  await expect(page.getByLabel('Result')).toHaveText('Result 3')
})

test('a scope change cancels obsolete refresh and discards its queued mutation', async ({ page }) => {
  await page.clock.install()
  let held: Route | undefined
  const scopes: string[] = []
  await page.route('**/__test/refresh?*', async route => {
    const scope = new URL(route.request().url()).searchParams.get('scope')!
    scopes.push(scope)
    if (scope === 'alpha') { held = route; return }
    await route.fulfill({ body: scope })
  })
  await page.goto('/test/fixtures/page-refresh.html')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => scopes).toEqual(['alpha'])
  await page.getByRole('button', { name: 'Saved' }).click()
  await page.getByLabel('Scope', { exact: true }).selectOption('beta')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByLabel('Result')).toHaveText('beta')
  await held!.fulfill({ body: 'Obsolete alpha' }).catch(() => {})
  await expect(page.getByLabel('Result')).toHaveText('beta')
  expect(scopes).toEqual(['alpha', 'beta'])
})

test('paused refresh defers mutation reconciliation until saving ends', async ({ page }) => {
  await page.clock.install()
  let requests = 0
  await page.route('**/__test/refresh?*', route => { requests++; return route.fulfill({ body: 'Reconciled' }) })
  await page.goto('/test/fixtures/page-refresh.html')
  await page.getByRole('button', { name: 'Pause', exact: true }).click()
  await page.getByRole('button', { name: 'Saved' }).click()
  await page.clock.runFor(5000)
  expect(requests).toBe(0)
  await page.getByRole('button', { name: 'Resume' }).click()
  await expect(page.getByLabel('Result')).toHaveText('Reconciled')
  expect(requests).toBe(1)
})

test('returning to an aborted scope restores its idle refresh lifecycle', async ({ page }) => {
  await page.clock.install()
  let requests = 0
  await page.route('**/__test/refresh?*', route => {
    requests++
    if (requests === 1) return
    return route.fulfill({ body: 'New alpha generation' })
  })
  await page.goto('/test/fixtures/page-refresh.html')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('Refreshing')
  await page.getByLabel('Scope', { exact: true }).selectOption('beta')
  await page.getByLabel('Scope', { exact: true }).selectOption('alpha')
  await expect(page.getByRole('status')).toHaveText('Idle')
  await page.clock.runFor(1100)
  await expect(page.getByLabel('Result')).toHaveText('New alpha generation')
})

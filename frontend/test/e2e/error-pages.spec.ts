import { test, expect } from '@playwright/test'

for (const [code, title] of [['401', 'Sign-in required'], ['403', 'Access denied']]) {
  test(`${code} uses the shared error page and leads back to sign-in`, async ({ page }) => {
    await page.route('**/frontend-config', route => route.fulfill({ json: {
      projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
      controllerRefreshIntervalSeconds: 30, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20,
    } }))
    await page.goto(`/${code}`)
    await expect(page.getByText(code, { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
    await page.getByRole('link', { name: 'Sign in', exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  })
}

test('404 has a direct route and remains available after refresh', async ({ page }) => {
  await page.goto('/404')
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  await expect(page).toHaveURL(/\/404$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Return home' })).toHaveAttribute('href', '/')
})

for (const [code, title] of [['401', 'Sign-in required'], ['403', 'Access denied'], ['404', 'Page not found']]) {
  test(`${code} remains visible when authentication configuration is unavailable`, async ({ page }) => {
    await page.route('**/frontend-config', route => route.fulfill({ status: 503, body: 'Unavailable' }))
    await page.goto(`/${code}`)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    await expect(page.getByText(code, { exact: true })).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`/${code}$`))
  })
}

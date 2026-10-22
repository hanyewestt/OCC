import { test, expect } from '@playwright/test'

test('unknown website URL shows a minimal 404 with a home link', async ({ page, baseURL }) => {
  let bootstrapRequests = 0
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/frontend-config') bootstrapRequests++
  })
  const response = await page.goto('/this-page-does-not-exist')
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Return home' })).toHaveAttribute('href', '/')
  // Vite's dev server uses its SPA fallback; Nginx serves the dedicated 404 entry.
  if (new URL(baseURL!).port !== (process.env.OCC_FRONTEND_DEV_PORT ?? '5173')) {
    expect(response?.status()).toBe(404)
    expect(bootstrapRequests).toBe(0)
    await expect(page).toHaveTitle('Page not found · OCC')
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  }
})

test('missing assets and unauthorized API responses do not use the website 404', async ({ request, baseURL }) => {
  test.skip(new URL(baseURL!).port === (process.env.OCC_FRONTEND_DEV_PORT ?? '5173'), 'Nginx response boundaries')
  const asset = await request.get('/assets/not-present.js')
  expect(asset.status()).toBe(404)
  expect(await asset.text()).not.toContain('Page not found · OCC')
  const api = await request.get('/api/v1/auth/session')
  expect(api.status()).toBe(401)
  expect(await api.text()).not.toContain('Page not found · OCC')
})

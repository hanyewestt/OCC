import { test, expect } from '@playwright/test'

for (const [code, title] of [['401', 'Sign-in required'], ['403', 'Access denied']]) {
  test(`${code} uses the shared error page and leads back to sign-in`, async ({ page }) => {
    await page.route('**/frontend-config', route => route.fulfill({ json: {
      projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
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

import { expect, test } from '@playwright/test'

test('the served application starts and renders sign-in without a JavaScript exception', async ({ page, context }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_startup_fixture',
    controllerRefreshIntervalSeconds: 30, ingestionPollIntervalSeconds: 60,
    routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5,
    publicNoticeRefreshIntervalSeconds: 30,
  } }))
  await page.goto('/login')
  await expect(page.getByRole('textbox', { name: 'Email', exact: true })).toBeVisible()
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeEnabled()
  expect(errors).toEqual([])
})

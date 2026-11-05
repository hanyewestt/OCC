import { defineConfig } from '@playwright/test'

const devPort = process.env.OCC_FRONTEND_DEV_PORT ?? '5173'
const devURL = `http://127.0.0.1:${devPort}`
const baseURL = process.env.OCC_FRONTEND_TEST_PUBLIC_URL ?? devURL
const hookTests = ['**/page-refresh.spec.ts', '**/paged-collection.spec.ts']
export default defineConfig({
  testDir: './test/e2e',
  timeout: Number(process.env.OCC_FRONTEND_TEST_TIMEOUT_MS ?? 30000),
  expect: { timeout: Number(process.env.OCC_FRONTEND_TEST_EXPECT_TIMEOUT_MS ?? 5000) },
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  use: { baseURL, browserName: 'chromium',
    // Disposable HTTPS certificates are accepted only for loopback test targets.
    ignoreHTTPSErrors: ['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname) },
  projects: [
    { name: 'application', testIgnore: hookTests },
    // Source fixtures stay outside the production build and Nginx routes.
    { name: 'hooks', testMatch: hookTests, use: { baseURL: devURL } },
  ],
  webServer: {
    command: 'npm run dev', url: devURL, reuseExistingServer: !process.env.CI,
  },
})

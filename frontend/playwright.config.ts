import { defineConfig } from '@playwright/test'

const baseURL = process.env.OCC_FRONTEND_TEST_PUBLIC_URL ?? 'http://127.0.0.1:5173'
const devPort = process.env.OCC_FRONTEND_DEV_PORT ?? '5173'
export default defineConfig({
  testDir: './test/e2e',
  timeout: Number(process.env.OCC_FRONTEND_TEST_TIMEOUT_MS ?? 30000),
  expect: { timeout: Number(process.env.OCC_FRONTEND_TEST_EXPECT_TIMEOUT_MS ?? 5000) },
  fullyParallel: true,
  use: { baseURL, browserName: 'chromium',
    // Disposable HTTPS certificates are accepted only for loopback test targets.
    ignoreHTTPSErrors: ['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname) },
  // An Nginx URL uses the already-running built app instead of starting Vite.
  webServer: new URL(baseURL).port === devPort ? {
    command: 'npm run dev', url: baseURL, reuseExistingServer: !process.env.CI,
  } : undefined,
})

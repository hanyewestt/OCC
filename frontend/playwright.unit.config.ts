import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './test/unit',
  fullyParallel: true,
})

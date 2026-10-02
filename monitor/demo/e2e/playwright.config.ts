import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  timeout: 210_000,
  expect: { timeout: 15_000 },
  retries: 2,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5174',
    browserName: 'chromium',
    headless: true
  }
});

import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

// Load test environment variables from the Docker deploy directory
dotenv.config({ path: path.resolve(__dirname, '../../deploy/docker/.env.test') })

/**
 * Playwright configuration for Portal E2E tests.
 *
 * These tests exercise the full OIDC flow: Next.js portal -> STS -> Keycloak
 * (simulating SURF federation) and the OID4VP auth bridge reconciliation flow.
 *
 * Prerequisites:
 *   1. Start the test stack:  cd deploy/docker && ./test-e2e.sh --infra-only
 *   2. Start the portal dev server:  cd apps/portal && npm run dev
 *   3. Run tests:  npx playwright test
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',

  /* Maximum time one test can run — OIDC redirects need extra time */
  timeout: 60_000,

  /* Expect timeout for assertions */
  expect: {
    timeout: 10_000,
  },

  /* Run tests serially — OIDC flows share browser state */
  fullyParallel: false,

  /* Fail the build on CI if test.only is left in the source */
  forbidOnly: !!process.env.CI,

  /* Retry on CI for flaky OIDC redirect timing */
  retries: process.env.CI ? 2 : 0,

  /* Single worker — OIDC flows are stateful */
  workers: 1,

  /* Reporter */
  reporter: process.env.CI
    ? [['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]]
    : [['list'], ['html', { open: 'on-failure' }]],

  use: {
    /* Base URL for the Next.js dev server */
    baseURL: process.env.NEXTAUTH_URL || 'http://localhost:3000',

    /* Collect trace on first retry for debugging */
    trace: 'on-first-retry',

    /* Screenshot on failure */
    screenshot: 'only-on-failure',

    /* Video recording for debugging OIDC redirect flows */
    video: 'on-first-retry',

    /* Extra HTTP headers */
    extraHTTPHeaders: {
      'Accept-Language': 'nl-NL',
    },

    /* Ignore HTTPS errors (local dev uses HTTP) */
    ignoreHTTPSErrors: true,

    /* Navigation timeout — OIDC redirects can be slow */
    navigationTimeout: 30_000,

    /* Action timeout */
    actionTimeout: 10_000,
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        /* Viewport large enough for the portal UI */
        viewport: { width: 1280, height: 720 },
      },
    },
    {
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
      },
    },
    {
      name: 'mobile-chrome',
      use: {
        ...devices['Pixel 5'],
      },
    },
  ],

  /* Start the Next.js dev server before running tests */
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    timeout: 30_000,
    reuseExistingServer: !process.env.CI,
    env: {
      STS_ISSUER_URL: process.env.STS_BASE_URL || 'http://localhost:8080',
      STS_CLIENT_ID: 'portal',
      STS_CLIENT_SECRET: process.env.STS_CLIENT_SECRET || 'dev-client-secret',
      AUTH_BRIDGE_URL: process.env.AUTH_BRIDGE_BASE_URL || 'http://localhost:8090',
      NEXTAUTH_URL: process.env.NEXTAUTH_URL || 'http://localhost:3000',
      NEXTAUTH_SECRET: process.env.NEXTAUTH_SECRET || 'test-nextauth-secret-for-e2e-testing',
      NEXT_PUBLIC_PRIMARY_COLOR: '#7276F7',
      NEXT_PUBLIC_APP_NAME: 'Portal',
    },
  },
})

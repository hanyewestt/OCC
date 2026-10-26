import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { classify, clockText, deltaText, initialScenario } from '../../src/lib/obu-pacing'
import { bunchingArrivals } from '../../src/lib/bunching'
import { groupStops, skipLabel, type UpcomingStop } from '../../src/lib/skip-stops'

// Synthetic Supabase and Gateway responses. These tests do not prove provider
// behavior or backend authorization; Gateway tests and the opt-in live smoke do.
const userId = '11111111-1111-4111-8111-111111111111'
function session(role: string, lifetime = 3600) {
  const now = Math.floor(Date.now() / 1000)
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return {
    access_token: `${encode({ alg: 'ES256', typ: 'JWT' })}.${encode({ sub: userId, aud: 'authenticated', exp: now + lifetime, iat: now, occ_role: role })}.synthetic`,
    token_type: 'bearer', expires_in: lifetime, expires_at: now + lifetime,
    refresh_token: 'synthetic-refresh-token',
    user: { id: userId, aud: 'authenticated', role: 'authenticated', email: 'user@example.test',
      app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {}, created_at: '2026-01-01T00:00:00Z' },
  }
}
type Options = { invalidPassword?: boolean; sessionFailure?: boolean; accessFailure?: boolean; refreshFailure?: boolean }
async function mockAuth(context: BrowserContext, role: string, options: Options = {}) {
  const stop = (stopCode: string, stopSequence: number) => ({
    stopCode, roadName: 'Sample Road', description: `Stop ${stopCode}`,
    latitude: 1.38, longitude: 103.75, stopSequence, distanceKm: stopSequence * 0.2,
  })
  const directions = (serviceId: string) => serviceId === '190' ? [
    { directionId: 1, stops: [stop('A01', 1), stop('B01', 2), stop('C01', 3)] },
    { directionId: 2, stops: [stop('D01', 1), stop('E01', 2), stop('F01', 3)] },
  ] : [
    { directionId: 1, stops: [stop('G01', 1), stop('G02', 2), stop('G03', 3)] },
    { directionId: 2, stops: [stop('H01', 1), stop('H02', 2), stop('H03', 3)] },
  ]
  await context.route('**/api/v1/bus-routes/*', route => {
    const serviceId = new URL(route.request().url()).pathname.split('/').pop() ?? ''
    if (!['190', '972'].includes(serviceId)) return route.fulfill({ status: 404 })
    return route.fulfill({ json: { serviceId, operators: [{ operator: 'SMRT', category: null, directions: directions(serviceId) }] } })
  })
  await context.route('**/api/v1/route-positions?*', route => {
    const serviceId = new URL(route.request().url()).searchParams.get('service') ?? ''
    return route.fulfill({ json: {
      serviceId, computedAt: '2026-10-23T04:00:00Z', refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120,
      directions: [
        { operator: 'SMRT', directionId: 1, routeLengthM: 12_000,
          buses: serviceId === '190' ? [
            { order: 1, distanceAlongRouteM: 4_300, uncertaintyM: 70, speedMps: 6.2 },
            { order: 2, distanceAlongRouteM: 3_500, uncertaintyM: 70, speedMps: 4.2 },
          ] : [
            { order: 1, distanceAlongRouteM: 3_300, uncertaintyM: 60, speedMps: 6.4 },
            { order: 2, distanceAlongRouteM: 2_700, uncertaintyM: 60, speedMps: 6.1 },
          ] },
        { operator: 'SMRT', directionId: 2, routeLengthM: 11_000, buses: [] },
      ],
    } })
  })
  await context.route('**/api/v1/bus-stops/*', route => route.fulfill({ json: { description: 'Teck Whye Secondary School', roadName: 'Teck Whye Ave' } }))
  await context.route('**/api/v1/arrivals?*', route => route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/live-bunching-alerts?*', route => route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/planning/recommendations/decisions?*', route => route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ status: 204 }))
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 30, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20,
  } }))
  await context.route('https://example.supabase.co/auth/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/logout')) return route.fulfill({ status: 204 })
    if ((options.invalidPassword && url.searchParams.get('grant_type') === 'password') ||
      (options.refreshFailure && url.searchParams.get('grant_type') === 'refresh_token')) {
      return route.fulfill({ status: 400, json: { error: 'invalid_grant', error_code: 'refresh_token_not_found', msg: 'Synthetic rejected session' } })
    }
    return route.fulfill({ json: session(role) })
  })
  await context.route('**/api/v1/auth/**', async route => {
    const url = new URL(route.request().url())
    if (!route.request().headers().authorization?.startsWith('Bearer ')) return route.fulfill({ status: 401 })
    if ((options.sessionFailure && url.pathname.endsWith('/session')) || options.accessFailure) {
      return route.fulfill({ status: 503 })
    }
    const page = url.pathname.split('/').pop()
    if (page !== 'session' && !((page === 'controller' && role === 'CONTROLLER') || (page === 'captain' && role === 'CAPTAIN'))) {
      return route.fulfill({ status: 403 })
    }
    return route.fulfill({ json: { subject: userId,
      role: ['CONTROLLER', 'CAPTAIN', 'ADMIN', 'UNASSIGNED'].includes(role) ? role : 'UNASSIGNED' } })
  })
}
async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email').fill('user@example.test')
  await page.getByLabel('Password', { exact: true }).fill('synthetic-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

// Dashboard journeys use synthetic API fixtures and never write real decisions.
async function mockDashboard(context: BrowserContext, options: { arrivalFailure?: boolean; conflict?: boolean } = {}) {
  await mockAuth(context, 'CONTROLLER')
  const alert = { eventId: 'event-1', action: 'OPENED', classification: 'SIMULATION', serviceId: '190', stopCode: 'B01', stopName: 'Teck Whye Secondary School', directionId: 1, detectedAt: '2026-10-22T01:00:00Z', evidence: arrivalEvidence('SIMULATION', 1) }
  const recommendation = {
    recommendationId: 'recommendation-1', classification: 'SIMULATION', status: 'RECOMMENDED',
    serviceId: '190', directionId: 1, monitoredStopCode: 'B01', detectedAt: '2026-10-22T01:00:00Z', createdAt: '2026-10-22T01:01:00Z',
    expectedHeadwaySeconds: 180, observedHeadwaySeconds: 60, mitigationType: 'HOLD', targetBusReference: 'SAMPLE-BUS', affectedBusReferences: ['SAMPLE-BUS'], controlStopCode: 'B01', adjustmentSeconds: 120,
    explanation: { rationale: 'Restore spacing between the buses.', likelyCause: 'Sample congestion', diagnosisConfidence: 0.86, tradeOffs: ['Adds waiting time at the stop.'], limitations: ['Simulation only.'] },
    expectedImpact: { headwayBeforeSeconds: 60, headwayAfterSeconds: 180, queueBeforePassengers: 20, queueAfterPassengers: 12, passengerMinutesBefore: 40, passengerMinutesAfter: 28 },
  }
  let decision: { recommendationId: string; action: string; actorSubject: string; overrideInstructions: { busReference: string; instruction: string }[]; controllerRationale: string | null; decidedAt: string } | null = null
  await context.route('**/api/v1/arrivals?*', route => options.arrivalFailure ? route.fulfill({ status: 503 }) : route.fulfill({ json: {
    items: [{ observationId: 'arrival-1', serviceId: '190', stopCode: 'B01', estimatedArrivalAt: '2026-10-22T01:05:00Z', fetchedAt: '2026-10-22T01:00:00Z', loadCategory: 'SEA' }], page: 0, totalItems: 1, totalPages: 1,
  } }))
  await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ json: { id: 'run-1', status: 'REPLAY_COMPLETE', alerts: [alert] } }))
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: {
    items: decision ? [] : [recommendation], page: 0, totalItems: decision ? 0 : 1, totalPages: decision ? 0 : 1,
  } }))
  await context.route('**/api/v1/planning/recommendations/decisions?*', route => route.fulfill({ json: {
    items: decision ? [decision] : [], page: 0, totalItems: decision ? 1 : 0, totalPages: decision ? 1 : 0,
  } }))
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', route => {
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      if (options.conflict) {
        decision = { recommendationId: 'recommendation-1', action: 'REJECTED', actorSubject: userId, overrideInstructions: [{ busReference: 'SAMPLE-BUS', instruction: 'Hold for 60 seconds.' }], controllerRationale: 'Earlier controller decision.', decidedAt: '2026-10-22T01:10:00Z' }
        return route.fulfill({ status: 409 })
      }
      decision = { recommendationId: 'recommendation-1', action: body.action, actorSubject: userId, overrideInstructions: body.overrideInstructions ?? [], controllerRationale: body.controllerRationale ?? null, decidedAt: '2026-10-22T01:10:00Z' }
    }
    return decision ? route.fulfill({ json: decision }) : route.fulfill({ status: 204 })
  })
}

function arrivalEvidence(classification: string, directionId: number) {
  return JSON.stringify({ snapshot: { classification, arrivals: [3, 1, 2].map(visitOrder => ({
    serviceId: '190', stopCode: 'B01', directionId, visitOrder, estimatedArrivalAt: `2026-10-22T01:0${visitOrder}:00Z`,
  })) } })
}

test('controller keeps live positions on the route strip and shows incidents separately', async ({ page, context }) => {
  await mockDashboard(context)
  await login(page)
  await expect(page.getByRole('heading', { name: 'Live route strip', exact: true })).toBeVisible()
  await expect(page.getByText('Simulation incident data is shown separately; the strip keeps live position estimates.', { exact: true })).toBeVisible()
  const service190Direction1 = page.getByRole('group', { name: 'Service 190, direction 1, approximate bus positions and headways' })
  await expect(service190Direction1.getByText('B01', { exact: true })).toBeVisible()
  await expect(service190Direction1.locator('tspan').filter({ hasText: /^Stop B01$/ })).toBeVisible()
  await expect(service190Direction1.getByRole('img', { name: /Estimated bus 1, approximate position/ })).toBeVisible()
  await expect(page.getByRole('group', { name: /Service 972, direction 1, approximate bus positions/ })
    .getByRole('img', { name: /Estimated bus 1, approximate position/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Bunching incidents', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Show historical (1)', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /bunching detected at stop/ })).toHaveCount(0)
  await page.locator('html').evaluate(element => element.classList.add('dark'))
  expect(await page.locator('html').evaluate(element => getComputedStyle(element).getPropertyValue('--background').trim())).toBe('oklch(0.145 0 0)')
  await page.screenshot({ path: 'node_modules/.cache/controller-route-strip.png', fullPage: true })
  await page.locator('section[aria-labelledby="route-strip-title"]').screenshot({ path: 'node_modules/.cache/route-strip-services-190-972.png' })
  await page.getByRole('button', { name: 'Show historical (1)', exact: true }).click()
  await expect(page.getByText('Teck Whye Secondary School', { exact: true })).toBeVisible()
})

test('controller route strip shows inferred live bus positions with uncertainty', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByRole('heading', { name: 'Live route strip', exact: true })).toBeVisible()
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position.*plus or minus 70 metres/ })).toBeVisible()
  await expect(page.getByRole('img', { name: /Estimated buses 1 and 2: ~2m 34s estimate\. Approximate time from position gap divided by estimated speed\./ })).toBeVisible()
  await expect(page.getByText(/Last updated .* · Refreshes every 30 seconds/)).toBeVisible()
})

test('an older periodic response cannot replace a newer manual position snapshot', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20,
  } }))

  type Phase = 'initial' | 'blocked-periodic' | 'newer'
  let phase: Phase = 'initial'
  let releaseOlder!: () => void
  const olderGate = new Promise<void>(resolve => { releaseOlder = resolve })
  let resolveBlocked!: () => void
  const blockedStarted = new Promise<void>(resolve => { resolveBlocked = resolve })
  const blockedServices = new Set<string>()
  let resolveNextNewerBatch!: () => void
  const nextNewerBatchStarted = new Promise<void>(resolve => { resolveNextNewerBatch = resolve })
  const newerCalls = new Map<string, number>()
  const snapshot = (serviceId: string, distanceAlongRouteM: number, computedAt: string) => ({
    serviceId, computedAt, refreshIntervalSeconds: 60, maxSourceAgeSeconds: 120,
    directions: [{ operator: 'SMRT', directionId: 1, routeLengthM: 12_000, buses: [
      { order: 1, distanceAlongRouteM, uncertaintyM: 70, speedMps: 6.2 },
      { order: 2, distanceAlongRouteM: distanceAlongRouteM - 800, uncertaintyM: 70, speedMps: 4.2 },
    ] }],
  })

  await context.route('**/api/v1/route-positions?*', async route => {
    const serviceId = new URL(route.request().url()).searchParams.get('service')!
    if (phase === 'blocked-periodic') {
      blockedServices.add(serviceId)
      if (blockedServices.size === 2) resolveBlocked()
      await olderGate
      return route.fulfill({ json: snapshot(serviceId, 1_200, '2026-10-23T04:00:10Z') })
    }
    if (phase === 'newer') {
      const calls = (newerCalls.get(serviceId) ?? 0) + 1
      newerCalls.set(serviceId, calls)
      if ([...newerCalls.values()].filter(count => count >= 2).length === 2) resolveNextNewerBatch()
      return route.fulfill({ json: snapshot(serviceId, 6_500, '2026-10-23T04:00:30Z') })
    }
    return route.fulfill({ json: snapshot(serviceId, 4_300, '2026-10-23T04:00:00Z') })
  })

  await login(page)
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 4300 metres/ }).first()).toBeVisible()
  phase = 'blocked-periodic'
  await blockedStarted

  phase = 'newer'
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 6500 metres/ }).first()).toBeVisible()
  await page.evaluate(() => {
    const state = window as typeof window & { sawOlderPosition?: boolean; positionObserver?: MutationObserver }
    state.sawOlderPosition = false
    const check = () => {
      if (document.querySelector('[aria-label*="approximate position 1200 metres"]')) state.sawOlderPosition = true
    }
    state.positionObserver = new MutationObserver(check)
    state.positionObserver.observe(document.body, {
      subtree: true, childList: true, attributes: true, attributeFilter: ['aria-label'],
    })
  })

  releaseOlder()
  await nextNewerBatchStarted
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 6500 metres/ }).first()).toBeVisible()
  expect(await page.evaluate(() => {
    const state = window as typeof window & { sawOlderPosition?: boolean; positionObserver?: MutationObserver }
    state.positionObserver?.disconnect()
    return state.sawOlderPosition
  })).toBe(false)
})

test('bunching predictions preserve slots and reject unrelated or malformed evidence', () => {
  const alert = { eventId: 'event-1', action: 'OPENED', classification: 'SIMULATION', serviceId: '190', stopCode: 'B01', directionId: 1, detectedAt: '2026-10-22T01:00:00Z', evidence: arrivalEvidence('SIMULATION', 1) }
  expect(bunchingArrivals(alert)).toEqual(['2026-10-22T01:01:00Z', '2026-10-22T01:02:00Z', '2026-10-22T01:03:00Z'])
  expect(bunchingArrivals({ ...alert, evidence: '{invalid' })).toEqual(['Unavailable', 'Unavailable', 'Unavailable'])
  expect(bunchingArrivals({ ...alert, evidence: arrivalEvidence('OBSERVATION_ONLY', 1) })).toEqual(['Unavailable', 'Unavailable', 'Unavailable'])
  expect(bunchingArrivals({ ...alert, evidence: arrivalEvidence('SIMULATION', 2) })).toEqual(['No prediction', 'No prediction', 'No prediction'])
  const evidence = JSON.parse(alert.evidence)
  evidence.snapshot.arrivals = [evidence.snapshot.arrivals[1], { ...evidence.snapshot.arrivals[2], serviceId: '999' }]
  expect(bunchingArrivals({ ...alert, evidence: JSON.stringify(evidence) })).toEqual(['2026-10-22T01:01:00Z', 'No prediction', 'No prediction'])
})

for (const simulation of [true, false]) {
  test(`bunching ${simulation ? 'simulation' : 'live'} table shows stop name, direction and three saved arrival times`, async ({ page, context }) => {
    await mockDashboard(context)
    if (!simulation) {
      await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ status: 204 }))
      await context.route('**/api/v1/live-bunching-alerts?*', route => route.fulfill({ json: {
        items: [{ eventId: 'event-1', action: 'OPENED', classification: 'OBSERVATION_ONLY', serviceId: '190', stopCode: 'B01', directionId: 2, detectedAt: '2026-10-22T01:00:00Z', evidence: arrivalEvidence('OBSERVATION_ONLY', 2) }], page: 0, totalItems: 1, totalPages: 1,
      } }))
    }
    await login(page)
    await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
    await expect(page.getByText('Teck Whye Secondary School', { exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: `Direction ${simulation ? 1 : 2}`, exact: true })).toBeVisible()
    for (const slot of [1, 2, 3]) {
      await expect(page.getByRole('columnheader', { name: `Bus ${slot} arrival`, exact: true })).toBeVisible()
      const formatted = await page.evaluate(value => new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }), `2026-10-22T01:0${slot}:00Z`)
      await expect(page.getByRole('cell', { name: formatted, exact: true })).toBeVisible()
    }
    await page.screenshot({ path: `node_modules/.cache/bunching-${simulation ? 'simulation' : 'live'}.png`, fullPage: true })
  })
}

test('controller switches the shared backend mode from the dashboard without leaving the page', async ({ page, context }) => {
  await mockDashboard(context)
  const simulation = { id: 'run-1', status: 'REPLAY_COMPLETE', alerts: [{ eventId: 'event-1', action: 'OPENED', classification: 'SIMULATION', serviceId: '190', stopCode: 'B01', detectedAt: '2026-10-22T01:00:00Z' }] }
  let active = false
  let starts = 0
  let closes = 0
  await context.route('**/api/v1/demo-runs/active', route => active ? route.fulfill({ json: simulation }) : route.fulfill({ status: 204 }))
  await context.route('**/api/v1/demo-runs', route => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    active = true; starts++
    return route.fulfill({ status: 201, json: simulation })
  })
  await context.route('**/api/v1/demo-runs/run-1/close', route => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    active = false; closes++
    return route.fulfill({ json: { ...simulation, status: 'CLOSED' } })
  })
  await login(page)
  await page.getByRole('button', { name: 'Start simulation', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Return to live', exact: true })).toBeEnabled()
  await expect(page.getByRole('status').filter({ hasText: /^Simulation$/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Simulation detections/ })).toContainText('1')
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Return to live', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Start simulation', exact: true })).toBeEnabled()
  await expect(page.getByRole('status').filter({ hasText: /^Live$/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/controller\/planning/)
  expect(starts).toBe(1); expect(closes).toBe(1)
})

test('failed mode changes keep the verified mode and show a useful error', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/demo-runs', route => route.fulfill({ status: 503 }))
  await login(page)
  await page.getByRole('button', { name: 'Start simulation', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Unable to switch modes')
  await expect(page.getByRole('status').filter({ hasText: /^Live$/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start simulation', exact: true })).toBeEnabled()
  await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ status: 503 }))
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: /^Mode unavailable$/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start simulation', exact: true })).toBeDisabled()
})

test('controller sidebar, summary links and deep links work through all four screens', async ({ page, context }) => {
  await mockDashboard(context)
  await login(page)
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible()
  await expect(page.getByRole('status', { name: '' }).filter({ hasText: /^Simulation$/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '190', exact: true })).toBeVisible()
  await page.screenshot({ path: 'node_modules/.cache/controller-overview.png', fullPage: true })
  await page.getByRole('button', { name: 'Collapse sidebar' }).click()
  await expect(page.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('link', { name: /Simulation detections/ }).click()
  await expect(page).toHaveURL(/\/controller\/disruptions$/)
  await expect(page.getByRole('heading', { name: 'Traffic disruptions' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Disruptions', exact: true })).toBeVisible()
  await page.getByRole('navigation', { name: 'Controller navigation' }).getByRole('link', { name: 'Planning & mitigation' }).click()
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Audit log', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'AI audit logs' })).toBeVisible()
  await expect(page.getByText('No controller decisions have been saved.')).toBeVisible()
  await page.goto('/controller/unknown')
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
})

for (const [action, button] of [['APPROVED', 'Approve recommendation'], ['REJECTED', 'Enter controller recommendation']] as const) {
  test(`controller saves ${action.toLowerCase()} and shows it in the audit log after reload`, async ({ page, context }) => {
    await mockDashboard(context)
    await login(page)
    await page.getByRole('link', { name: /Awaiting decision/ }).click()
    const posted = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/decision'))
    await page.getByRole('button', { name: button, exact: true }).click()
    if (action === 'REJECTED') {
      await page.getByLabel('Controller rationale').fill('Use a shorter hold to reduce passenger delay.')
      await page.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true }).click()
    }
    const body = (await posted).postDataJSON()
    expect(body.action).toBe(action)
    expect(body.overrideInstructions).toEqual(action === 'REJECTED'
      ? [{ busReference: 'SAMPLE-BUS', instruction: 'Hold for 120 seconds at stop B01.' }] : [])
    expect(body.controllerRationale).toBe(action === 'REJECTED' ? 'Use a shorter hold to reduce passenger delay.' : null)
    await expect(page.getByText(action === 'APPROVED' ? 'Recommendation approved.' : 'AI recommendation replaced with controller instructions.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toHaveCount(0)
    await page.getByRole('link', { name: 'Audit log', exact: true }).click()
    await expect(page.getByRole('cell', { name: action, exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('cell', { name: action, exact: true })).toBeVisible()
  })
}

test('controller handles conflicting decisions by retrieving the saved result', async ({ page, context }) => {
  await mockDashboard(context, { conflict: true })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Approve recommendation', exact: true }).click()
  await expect(page.getByText(/already has a decision/)).toBeVisible()
})

test('controller distinguishes failed arrivals from empty arrivals and can retry', async ({ page, context }) => {
  const options = { arrivalFailure: true }
  await mockDashboard(context, options)
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Unable to load this data')
  await expect(page.getByText('No current arrivals.')).toHaveCount(0)
  options.arrivalFailure = false
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('cell', { name: '190', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('controller narrow layout keeps navigation and tables within the viewport', async ({ page, context }) => {
  await mockDashboard(context)
  await page.setViewportSize({ width: 375, height: 812 })
  await login(page)
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Expand sidebar' }).click()
  await expect(page.getByRole('button', { name: 'Collapse sidebar' })).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Expand sidebar' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Disruptions', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'node_modules/.cache/controller-mobile.png', fullPage: true })
})

test('captain cannot open any controller dashboard route', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await login(page)
  for (const route of ['/controller', '/controller/disruptions', '/controller/planning', '/controller/audit']) {
    await page.goto(route)
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Controller navigation' })).toHaveCount(0)
  }
})

test('operational API 401 clears dashboard content and the session', async ({ page, context }) => {
  await mockDashboard(context)
  await login(page)
  await expect(page.getByRole('cell', { name: '190', exact: true })).toBeVisible()
  await context.route('**/api/v1/arrivals?*', route => route.fulfill({ status: 401 }))
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Sign-in required' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Controller navigation' })).toHaveCount(0)
})

for (const [role, destination, title, other] of [
  ['CONTROLLER', '/controller', 'Overview', '/captain'],
  ['CAPTAIN', '/captain', 'Captain', '/controller'],
]) {
  test(`${role} can access only its own page and survives reload`, async ({ page, context }) => {
    await mockAuth(context, role)
    await login(page)
    await expect(page).toHaveURL(new RegExp(`${destination}$`))
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await page.goto(other)
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Captain pacing display' })).toHaveCount(0)
    await page.getByRole('link', { name: 'Return to my page' }).click()
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  })
}

for (const role of ['ADMIN', 'UNASSIGNED', 'DRIVER']) {
  test(`${role} receives no role page`, async ({ page, context }) => {
    await mockAuth(context, role)
    await login(page)
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
    await page.goto('/captain')
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
    await expect(page.getByText('403', { exact: true })).toBeVisible()
  })
}

test('unauthenticated direct navigation returns to login', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await page.goto('/controller')
  await expect(page).toHaveURL(/\/login$/)
})
test('invalid credentials keep the user on login without echoing the password', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER', { invalidPassword: true })
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Check your email and password')
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('')
})
test('failed session verification never renders protected content and can retry', async ({ page, context }) => {
  const options = { sessionFailure: true }
  await mockAuth(context, 'CONTROLLER', options)
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Unable to verify your session')
  await expect(page.getByText('Access verified')).toHaveCount(0)
  options.sessionFailure = false
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByText('Access verified')).toBeVisible()
})
test('page verification failures do not grant access', async ({ page, context }) => {
  const options = { accessFailure: false }
  await mockAuth(context, 'CONTROLLER', options)
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  options.accessFailure = true
  await page.reload()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByText('Access verified')).toHaveCount(0)
})
test('expired persisted session with rejected refresh returns to login', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER', { refreshFailure: true })
  await page.addInitScript(saved => {
    localStorage.setItem('sb-example-auth-token', JSON.stringify(saved))
  }, session('CONTROLLER', -60))
  await page.goto('/controller')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('Access verified')).toHaveCount(0)
})
test('sign-out propagates to another open tab', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  const other = await context.newPage()
  await other.goto('/controller')
  await expect(other.getByText('Access verified')).toBeVisible()
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(other).toHaveURL(/\/login$/)
  await expect(other.getByText('Access verified')).toHaveCount(0)
})
test('configuration failure offers retry without a sign-in form', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/frontend-config', route => route.fulfill({ status: 503 }))
  await page.goto('/login')
  await expect(page.getByRole('alert')).toContainText('configuration is unavailable')
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toHaveCount(0)
})

test('restored expired session refreshes and verifies the new role', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.addInitScript(saved => {
    localStorage.setItem('sb-example-auth-token', JSON.stringify(saved))
  }, session('CONTROLLER', -60))
  await page.goto('/')
  await expect(page).toHaveURL(/\/captain$/)
  await expect(page.getByRole('heading', { name: 'Captain', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Controller', exact: true })).toHaveCount(0)
})

test('saved browser session works in a new browser context after verification', async ({ page, context, browser }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  const restored = await browser.newContext({ storageState: await context.storageState(), baseURL: page.url(),
    ignoreHTTPSErrors: ['localhost', '127.0.0.1'].includes(new URL(page.url()).hostname) })
  try {
    await mockAuth(restored, 'CONTROLLER')
    const tab = await restored.newPage()
    await tab.goto('/')
    await expect(tab.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible()
  } finally {
    await restored.close()
  }
})

test('Gateway rejection clears a restored session', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByText('Access verified')).toBeVisible()
  await context.route('**/api/v1/auth/session', route => route.fulfill({ status: 401 }))
  await page.reload()
  await expect(page).toHaveURL(/\/401$/)
  await expect(page.getByRole('heading', { name: 'Sign-in required' })).toBeVisible()
  await expect(page.getByText('401', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('Access verified')).toHaveCount(0)
})

for (const [status, heading] of [[401, 'Sign-in required'], [403, 'Access denied']] as const) {
  test(`page access rejection displays ${status} without protected content`, async ({ page, context }) => {
    await mockAuth(context, 'CONTROLLER')
    await login(page)
    await expect(page.getByText('Access verified')).toBeVisible()
    await context.route('**/api/v1/auth/access/controller', route => route.fulfill({ status }))
    await page.reload()
    await expect(page.getByRole('heading', { name: heading })).toBeVisible()
    await expect(page.getByText(String(status), { exact: true })).toBeVisible()
    await expect(page.getByText('Access verified')).toHaveCount(0)
  })
}

test('pacing treats threshold boundaries and missing measurements consistently', () => {
  expect(classify(240, 480)).toBe('close')
  expect(classify(241, 480)).toBe('ok')
  expect(classify(719, 480)).toBe('ok')
  expect(classify(720, 480)).toBe('far')
  for (const missing of [null, Number.NaN, -1]) {
    expect(classify(missing, 480)).toBe('unknown')
    expect(clockText(missing)).toBe('--:--')
  }
  expect(classify(300, 0)).toBe('unknown')
  expect(deltaText(490, 480)).toBe('About on target')
  expect(initialScenario('?scenario=invalid')).toBe('bunched')
})

test('captain pacing updates samples and switches scenarios without operational API calls', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.clock.install()
  const operationalRequests: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) operationalRequests.push(path)
  })
  await login(page)
  const ahead = page.getByRole('region', { name: 'Gap to bus ahead', exact: true })
  const behind = page.getByRole('region', { name: 'Gap to bus behind', exact: true })
  await expect(page.getByText('SAMPLE DATA', { exact: true })).toBeVisible()
  await expect(ahead.getByText('1:35', { exact: true })).toBeVisible()
  await expect(ahead).toHaveAttribute('data-state', 'close')
  await expect(behind).toHaveAttribute('data-state', 'far')
  await page.clock.fastForward(10_000)
  await expect(ahead.getByText('1:41', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'On target', exact: true }).click()
  await expect(ahead).toHaveAttribute('data-state', 'ok')
  await expect(behind).toHaveAttribute('data-state', 'ok')
  await page.getByRole('button', { name: 'Big gap ahead', exact: true }).click()
  await expect(ahead).toHaveAttribute('data-state', 'far')
  await expect(behind).toHaveAttribute('data-state', 'close')
  await page.getByRole('button', { name: 'No bus behind', exact: true }).click()
  await expect(behind.getByText('--:--', { exact: true })).toBeVisible()
  await expect(behind).toHaveAttribute('data-state', 'unknown')
  expect(operationalRequests).toEqual([])
})

test('consecutive skipped stops form one group in route order', () => {
  const stop = (stopCode: string, skipped: boolean): UpcomingStop => ({ stopCode, name: `Stop ${stopCode}`, skipped })
  const segments = groupStops([stop('1', true), stop('2', true), stop('3', false), stop('4', true), stop('5', false)])
  expect(segments.map(segment => segment.kind === 'stop' ? segment.stop.stopCode
    : segment.stops.map(skipped => skipped.stopCode).join('+'))).toEqual(['1+2', '3', '4', '5'])
  expect(groupStops([stop('1', false), stop('2', false)]).every(segment => segment.kind === 'stop')).toBe(true)
  expect(groupStops([])).toEqual([])
  expect(skipLabel(1)).toBe('Skip 1 stop')
  expect(skipLabel(3)).toBe('Skip 3 stops')
})

test('captain sees skipped stops grouped among upcoming stops', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await login(page)
  const stops = page.getByRole('list', { name: 'Upcoming stops in route order', exact: true })
  await expect(page.getByRole('heading', { name: 'Upcoming stops', exact: true })).toBeVisible()
  await expect(page.getByText('Skip 4 stops on this trip', { exact: true })).toBeVisible()
  await expect(stops.locator(':scope > li')).toHaveText([
    /Opp Blk 123.*STOP/, /Skip 3 stops.*Blk 45.*Bt Batok Stn.*Opp Bt Batok Stn/,
    /Bt Batok West Ave 6.*STOP/, /Skip 1 stop.*Blk 211/, /Choa Chu Kang Int.*STOP/,
  ])
  const longRun = page.getByRole('list', { name: 'Skip 3 stops', exact: true })
  await expect(longRun.getByRole('listitem')).toHaveCount(3)
  await expect(longRun.getByRole('listitem').first()).toContainText('(skipped)')
  await expect(page.getByRole('list', { name: 'Skip 1 stop', exact: true }).getByRole('listitem')).toHaveCount(1)
})

test('captain pacing supports narrow screens, scenario links and persistent day mode', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.setViewportSize({ width: 375, height: 812 })
  await login(page)
  await page.goto('/captain?scenario=no-bus-behind')
  const display = page.getByRole('region', { name: 'Captain pacing display', exact: true })
  await expect(page.getByRole('region', { name: 'Gap to bus behind', exact: true })).toHaveAttribute('data-state', 'unknown')
  await page.getByRole('button', { name: 'Day mode', exact: true }).click()
  await expect(display).toHaveAttribute('data-theme', 'day')
  await page.reload()
  await expect(display).toHaveAttribute('data-theme', 'day')
  await expect(page.getByRole('button', { name: 'Night mode', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

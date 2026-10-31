import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { classify, clockText, deltaText, initialScenario } from '../../src/lib/obu-pacing'
import { bunchingArrivals } from '../../src/lib/bunching'
import { groupStops, skipLabel, type UpcomingStop } from '../../src/lib/skip-stops'
import { formatSgtDateTime } from '../../src/lib/time'
import type { Advisory, CaptainInstruction } from '../../src/lib/api'

function advisoryGroup(members: Advisory[], serviceId = '190', directionId = 1) {
  const first = members[0]
  return { id: first.id, disruptionId: first.disruptionId, demoRunId: first.demoRunId, serviceId, directionId,
    hazardTitle: 'Reported hazard', hazardClassification: first.hazardClassification, candidate: first.candidate,
    preview: first.preview, members }
}

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
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: { mode: 'LIVE', demoRunId: null, items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/planning/recommendations/decisions?*', route => route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: { mode: 'LIVE', demoRunId: null, items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: {
    items: [], page: 0, size: 100, totalElements: 0, feeds: [],
  } }))
  for (const endpoint of ['planning/advisories', 'planning/advisory-groups', 'planning/instructions', 'planning/notices', 'planning/diagnoses/hazard-requests', 'planning/diagnoses/hazard-history', 'planning/captain/instructions']) {
    await context.route(`**/api/v1/${endpoint}?*`, route => route.fulfill({ json: { items: [], page: 0, size: 100, totalItems: 0, totalPages: 0 } }))
  }
  await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ status: 204 }))
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 30, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
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

async function expectAccessVerified(page: Page) {
  await expect(page.getByRole('heading', { name: /^(Overview|Captain)$/ })).toBeVisible()
  const expand = page.getByRole('button', { name: 'Expand sidebar' })
  if (await expand.count()) await expand.click()
  await expect(page.getByText('Access verified')).toBeVisible()
}

async function expectPageRefreshStatus(page: Page, intervalSeconds: number) {
  const status = page.getByLabel('Page refresh status', { exact: true })
  await expect(status).toHaveCount(1)
  await expect(status).toContainText(`Refreshes every ${intervalSeconds} seconds`)
  if (new URL(page.url()).pathname.startsWith('/controller')) {
    const title = page.locator('header').getByRole('heading', { level: 1 })
    await expect(title).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toHaveCount(1)
    await expect(page.locator('header').getByRole('button', { name: 'Refresh', exact: true })).toBeVisible()
    await expect(title).toHaveCSS('font-weight', '700')
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
  }
  await expect(page.getByLabel('Page refresh status')).toBeVisible()
}

test.describe('SGT notice scheduling', () => {
  test.use({ timezoneId: 'America/New_York' })

  test('serializes notice wall-clock inputs as Singapore time', async ({ page, context }) => {
    await mockAuth(context, 'CONTROLLER')
    await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
      disruptionId: 'notice-hazard', kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [],
    }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
    const posted: { effectiveFrom: string; effectiveUntil: string }[] = []
    await context.route('**/api/v1/planning/notices**', route => {
      if (route.request().method() === 'POST') posted.push(route.request().postDataJSON())
      return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    })

    await login(page)
    expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)).toBe('America/New_York')
    await page.getByRole('link', { name: 'Public notices', exact: true }).click()
    await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
    const advisories = page.getByRole('dialog', { name: 'New notice draft', exact: true })
    await advisories.getByLabel('Source hazard', { exact: true }).selectOption('notice-hazard')
    await advisories.getByLabel('Title', { exact: true }).fill('Caution near Example Stop')
    await advisories.getByLabel('Passenger message', { exact: true }).fill('Water reported near Example Stop.')
    await advisories.getByLabel('Starts (SGT)', { exact: true }).fill('2026-10-25T14:55')
    await advisories.getByLabel('Expires (SGT)', { exact: true }).fill('2026-10-25T15:55')
    await advisories.getByRole('button', { name: 'Save draft', exact: true }).click()

    await expect.poll(() => posted.at(-1)?.effectiveFrom).toBe('2026-10-25T06:55:00.000Z')
    expect(posted.at(-1)?.effectiveUntil).toBe('2026-10-25T07:55:00.000Z')
  })
})

function syntheticInstruction(now: Date): CaptainInstruction {
  return {
    id: '00000000-0000-4000-8000-000000000101', demoRunId: '00000000-0000-4000-8000-000000000102',
    busId: 'demo-190-b', type: 'REDUCE_SPEED',
    parameters: { stopCode: null, stopName: null, stopSequence: null, subsequentStopCount: null, speedReductionKmh: 5, durationSeconds: 300 },
    message: 'Reduce speed by 5 km/h for 5 min.', busClassification: 'SIMULATION', hazardClassification: 'OBSERVATION_ONLY',
    status: 'ISSUED', issuedAt: now.toISOString(), acknowledgementDeadline: new Date(now.getTime() + 300_000).toISOString(),
    acknowledgedAt: null, applicableUntil: null, inactiveReason: null,
  }
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
    items: [{ observationId: 'arrival-1', serviceId: '190', stopCode: 'B01', operator: 'SMRT', directionId: 1, destinationCode: 'C01', estimatedArrivalAt: '2026-10-22T01:05:00Z', fetchedAt: '2026-10-22T01:00:00Z', loadCategory: 'SEA' }], page: 0, totalItems: 1, totalPages: 1,
  } }))
  await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [], page: 0, totalItems: 0, totalPages: 0 } }))
  await context.route('**/api/v1/demo-runs/active', route => route.fulfill({ json: { id: 'run-1', status: 'REPLAY_COMPLETE', alerts: [alert] } }))
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: {
    mode: 'DEMO', demoRunId: 'run-1', items: decision ? [] : [recommendation], page: 0, totalItems: decision ? 0 : 1, totalPages: decision ? 0 : 1,
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

test('controller shows one selectable live route strip and keeps the overview detection count', async ({ page, context }) => {
  await mockDashboard(context)
  await login(page)
  await expect(page.getByRole('heading', { name: 'Approximate Live Route Strip', exact: true })).toBeVisible()
  await expect(page.getByText('Simulation alerts · route positions remain live.', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /Simulation detections/ })).toContainText('1')
  await expect(page.getByRole('heading', { name: 'Service 190 · Towards Stop C01', exact: true })).toBeVisible()
  await expect(page.getByLabel('Route', { exact: true }).locator('option:checked')).toHaveText('Service 190 · Towards Stop C01 · SMRT')
  const service190Direction1 = page.getByRole('group', { name: /Service 190, Towards Stop C01, SMRT, direction 1/ })
  await expect(service190Direction1.getByText('B01', { exact: true })).toBeVisible()
  await expect(service190Direction1.locator('tspan').filter({ hasText: /^Stop B01$/ })).toBeVisible()
  await expect(service190Direction1.getByRole('img', { name: /Estimated bus 1, approximate position/ })).toBeVisible()
  await expect(page.getByRole('group', { name: /Service 972/ })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Bunching incidents', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /historical/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /bunching detected at stop/ })).toHaveCount(0)
  const header = page.locator('header').first()
  await expect(header).toHaveCSS('position', 'sticky')
  await expect(header).toHaveCSS('z-index', '10')
  await expect(page.getByRole('status').filter({ hasText: /^Simulation$/ })).toHaveClass(/text-amber-700/)
  await page.locator('html').evaluate(element => element.classList.add('dark'))
  expect(await page.locator('html').evaluate(element => getComputedStyle(element).getPropertyValue('--background').trim())).toMatch(/^oklch\((?:0\.145|14\.5%) 0 0\)$/)
  await page.screenshot({ path: 'node_modules/.cache/controller-route-strip.png', fullPage: true })
  await page.locator('section[aria-labelledby="route-strip-title"]').screenshot({ path: 'node_modules/.cache/route-strip-services-190-972.png' })
  await page.getByLabel('Route', { exact: true }).selectOption('972:SMRT:1')
  const service972Direction1 = page.getByRole('group', { name: /Service 972, Towards Stop G03, SMRT, direction 1/ })
  await expect(service972Direction1).toBeVisible()
  await expect(page.locator('svg[role="group"]')).toHaveCount(1)
  await expect(page.getByRole('group', { name: /Service 190/ })).toHaveCount(0)
  await service972Direction1.getByRole('button', { name: /Stop G01/ }).click()
  await expect(page.getByText('Next arrivals at Stop G01')).toBeVisible()
  await page.getByLabel('Route', { exact: true }).selectOption('190:SMRT:1')
  await expect(page.getByRole('group', { name: /Service 190, Towards Stop C01/ })).toBeVisible()
  await expect(page.getByText('Next arrivals at Stop G01')).toHaveCount(0)
})

test('controller route strip shows inferred live bus positions with uncertainty', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await login(page)
  await expect(page.getByRole('heading', { name: 'Approximate Live Route Strip', exact: true })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: /^Live$/ })).toHaveClass(/text-emerald-700/)
  await expect(page.getByRole('group', { name: /Service 972/ })).toHaveCount(0)
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position.*plus or minus 70 metres/ })).toBeVisible()
  await expect(page.getByRole('img', { name: 'Estimated buses 1 and 2: ~2m 34s estimate. Approximate time from position gap divided by estimated speed.', exact: true })).toBeVisible()
  await expect(page.getByLabel('Page refresh status')).toContainText(/Last successful refresh .* · Refreshes every 30 seconds/)
})

test('route destination uses all service pages and reports conflicts instead of trusting page zero', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const pageRequests: string[] = []
  await context.route('**/api/v1/arrivals?*', route => {
    const params = new URL(route.request().url()).searchParams
    const serviceId = params.get('serviceId')
    if (!serviceId) return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    const pageNumber = Number(params.get('page'))
    pageRequests.push(`${serviceId}:${pageNumber}`)
    if (serviceId === '190') {
      const destinationCode = pageNumber === 0 ? 'C01' : 'B01'
      return route.fulfill({ json: { items: [{ observationId: `arrival-${pageNumber}`, serviceId, stopCode: 'A01', operator: 'SMRT', directionId: 1,
        destinationCode, estimatedArrivalAt: '2026-10-25T07:01:00Z', fetchedAt: '2026-10-25T07:00:00Z', loadCategory: null }], page: pageNumber, totalItems: 2, totalPages: 2 } })
    }
    return route.fulfill({ json: { items: [], page: pageNumber, totalItems: 0, totalPages: 0 } })
  })
  await login(page)
  await expect(page.getByRole('heading', { name: 'Service 190 · Multiple destinations', exact: true })).toBeVisible()
  expect(pageRequests).toContain('190:1')
  await expect(page.getByRole('option', { name: /Service 190 · Multiple destinations/ })).toBeAttached()
})

test('incomplete route arrival pages never produce a guessed destination', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/arrivals?*', route => {
    const params = new URL(route.request().url()).searchParams
    const serviceId = params.get('serviceId')
    if (!serviceId) return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    if (serviceId === '190' && params.get('page') === '1') return route.fulfill({ status: 503 })
    return route.fulfill({ json: { items: serviceId === '190' ? [{ observationId: 'partial', serviceId, stopCode: 'A01', operator: 'SMRT', directionId: 1,
      destinationCode: 'C01', estimatedArrivalAt: '2026-10-25T07:01:00Z', fetchedAt: '2026-10-25T07:00:00Z', loadCategory: null }] : [],
    page: 0, totalItems: 2, totalPages: serviceId === '190' ? 2 : 0 } })
  })
  await login(page)
  await expect(page.getByRole('heading', { name: 'Service 190 · Destination unavailable', exact: true })).toBeVisible()
  await expect(page.getByText('Route arrivals are incomplete; destinations and same-stop gaps may be unavailable.', { exact: true })).toBeVisible()
})

test('unknown destination names use the bus stop reference and reuse its lookup', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  let stopLookups = 0
  await context.route('**/api/v1/arrivals?*', route => {
    const params = new URL(route.request().url()).searchParams
    const serviceId = params.get('serviceId')
    if (!serviceId) return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    const directionId = 1
    const destinationCode = 'Z99'
    return route.fulfill({ json: { items: [{ observationId: `${serviceId}-${directionId}`, serviceId, stopCode: 'A01', operator: 'SMRT', directionId,
      destinationCode, estimatedArrivalAt: '2026-10-25T07:01:00Z', fetchedAt: '2026-10-25T07:00:00Z', loadCategory: null }], page: 0, totalItems: 1, totalPages: 1 } })
  })
  await context.route('**/api/v1/bus-stops/Z99', route => {
    stopLookups++
    return route.fulfill({ json: { description: 'Reference Destination', roadName: 'Reference Road' } })
  })
  await login(page)
  await expect(page.getByRole('heading', { name: 'Service 190 · Towards Reference Destination', exact: true })).toBeVisible()
  await page.getByLabel('Route', { exact: true }).selectOption('972:SMRT:1')
  await expect(page.getByRole('heading', { name: 'Service 972 · Towards Reference Destination', exact: true })).toBeVisible()
  await page.getByLabel('Route', { exact: true }).selectOption('190:SMRT:1')
  await expect(page.getByRole('heading', { name: 'Service 190 · Towards Reference Destination', exact: true })).toBeVisible()
  expect(stopLookups).toBe(1)
})

test('route selection resets to the first route when its route identity disappears', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  let secondRouteAvailable = true
  await context.route('**/api/v1/bus-routes/972', route => secondRouteAvailable
    ? route.fulfill({ json: { serviceId: '972', operators: [{ operator: 'SMRT', category: null, directions: [
      { directionId: 1, stops: [{ stopCode: 'G01', roadName: null, description: 'Stop G01', latitude: 1.38, longitude: 103.75, stopSequence: 1, distanceKm: 0 }] },
    ] }] } }) : route.fulfill({ status: 503 }))
  await login(page)
  const selector = page.getByLabel('Route', { exact: true })
  await selector.selectOption('972:SMRT:1')
  secondRouteAvailable = false
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(selector).toHaveValue('190:SMRT:1')
  secondRouteAvailable = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('option', { name: /Service 972/ })).toBeAttached()
  await expect(selector).toHaveValue('190:SMRT:1')
})

test('partial position refresh publishes successful services while retaining failed services', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  let refresh = false
  await context.route('**/api/v1/route-positions?*', route => {
    if (!refresh) return route.fallback()
    const serviceId = new URL(route.request().url()).searchParams.get('service')
    if (serviceId === '972') return route.fulfill({ status: 503 })
    return route.fulfill({ json: { serviceId, computedAt: '2026-10-23T05:00:00Z', refreshIntervalSeconds: 60,
      directions: [{ operator: 'SMRT', directionId: 1, routeLengthM: 12000,
        buses: [{ order: 1, distanceAlongRouteM: 4800, uncertaintyM: 70, speedMps: 6.2 }] }] } })
  })
  await login(page)
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 4300 metres/ })).toBeVisible()
  refresh = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 4800 metres/ })).toBeVisible()
  await page.getByLabel('Route', { exact: true }).selectOption('972:SMRT:1')
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 3300 metres/ })).toBeVisible()
})

test('arrivals search, sorting and scrolling use isolated server pages and preserve route selection', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const timestamp = '2026-10-25T07:00:00Z'
  const allArrivals = Array.from({ length: 60 }, (_, index) => ({
    observationId: `table-arrival-${index}`, serviceId: '190', stopCode: String(5000 + index), stopName: `Stop Name ${index}`,
    operator: 'SMRT', directionId: 1, originCode: '1000', destinationCode: '9000',
    estimatedArrivalAt: new Date(Date.parse(timestamp) + index * 60_000).toISOString(), fetchedAt: timestamp,
    loadCategory: ['SEA', 'SDA', 'LSD', 'FUTURE', null][index % 5],
  }))
  const requests: URLSearchParams[] = []
  let oldQueryResolved = false
  await context.route('**/api/v1/arrivals?*', async route => {
    const params = new URL(route.request().url()).searchParams
    if (params.has('serviceId') || params.has('stopCode')) {
      return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    }
    requests.push(new URLSearchParams(params))
    const stopName = params.get('stopName')?.toLowerCase()
    if (stopName === 'market') return route.fulfill({ json: {
      items: [{ ...allArrivals[59], observationId: 'market-arrival', stopCode: 'MKT01', stopName: 'Example Market' }],
      page: 0, totalItems: 1, totalPages: 1,
    } })
    if (stopName === 'none') return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    if (stopName === 'old') {
      await new Promise(resolve => setTimeout(resolve, 250))
      oldQueryResolved = true
      return route.fulfill({ json: { items: [{ ...allArrivals[0], observationId: 'old-arrival', stopName: 'Old result' }], page: 0, totalItems: 1, totalPages: 1 } })
    }
    const pageNumber = Number(params.get('page'))
    return route.fulfill({ json: {
      items: pageNumber === 0 ? allArrivals.slice(0, 50) : allArrivals.slice(50),
      page: pageNumber, totalItems: allArrivals.length, totalPages: 2,
    } })
  })
  await login(page)
  const viewport = page.locator('[data-slot="table-container"][role="region"][aria-label="Current bus arrivals"]')
  const rows = viewport.locator('tbody tr')
  await expect(rows).toHaveCount(50)
  await expect(rows.nth(0).getByRole('cell').nth(1)).toHaveText('Stop Name 0 (5000)')
  await expect(rows.nth(0).getByRole('cell').nth(3)).toHaveText('Seats Available')
  await expect(rows.nth(1).getByRole('cell').nth(3)).toHaveText('Standing Available')
  await expect(rows.nth(2).getByRole('cell').nth(3)).toHaveText('Limited Standing')
  await expect(rows.nth(3).getByRole('cell').nth(3)).toHaveText('FUTURE')
  await expect(rows.nth(4).getByRole('cell').nth(3)).toHaveText('—')

  const stopHeader = viewport.locator('thead th').nth(1)
  const arrivalHeader = viewport.locator('thead th').nth(2)
  await stopHeader.getByRole('button').click()
  await expect(stopHeader).toHaveAttribute('aria-sort', 'ascending')
  await expect(arrivalHeader).not.toHaveAttribute('aria-sort')
  await expect.poll(() => requests.some(params => params.get('sortBy') === 'stopName' && params.get('sortDirection') === 'asc')).toBe(true)
  await stopHeader.getByRole('button').click()
  await expect(stopHeader).toHaveAttribute('aria-sort', 'descending')
  await expect.poll(() => requests.some(params => params.get('sortBy') === 'stopName' && params.get('sortDirection') === 'desc')).toBe(true)
  await arrivalHeader.getByRole('button').click()
  await expect(arrivalHeader).toHaveAttribute('aria-sort', 'ascending')
  await arrivalHeader.getByRole('button').click()
  await expect(arrivalHeader).toHaveAttribute('aria-sort', 'descending')

  const search = page.getByRole('searchbox', { name: 'Search bus stop name' })
  await search.fill('MARKET')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('Example Market (MKT01)')
  await expect.poll(() => requests.some(params => params.get('stopName') === 'MARKET' && params.get('sortBy') === 'arrival' && params.get('sortDirection') === 'desc')).toBe(true)
  await page.getByLabel('Route', { exact: true }).selectOption('972:SMRT:1')
  await expect(page.getByRole('heading', { name: /Service 972 ·/ })).toBeVisible()
  await expect(rows.first()).toContainText('Example Market')

  await search.fill('none')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('No arrivals match this stop name', { exact: true })).toBeVisible()
  await search.fill('')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(rows).toHaveCount(50)
  await viewport.evaluate(element => {
    element.scrollTop = element.scrollHeight
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
  })
  await expect(rows).toHaveCount(60)
  expect(requests.some(params => params.get('page') === '1')).toBe(true)
  await expect(page.getByRole('button', { name: /Previous|Next/ })).toHaveCount(0)

  await search.fill('old')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect.poll(() => requests.some(params => params.get('stopName') === 'old')).toBe(true)
  await search.fill('MARKET')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(rows.first()).toContainText('Example Market')
  await expect.poll(() => oldQueryResolved).toBe(true)
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).not.toContainText('Old result')

  await page.getByRole('link', { name: /Current arrivals/ }).click()
  await expect(page).toHaveURL(/#arrivals$/)
  await expect(page.getByRole('heading', { name: 'Current bus arrivals' })).toBeFocused()
  await page.goto('/controller/disruptions')
  await page.goto('/controller#arrivals')
  await expect(page.getByRole('heading', { name: 'Current bus arrivals' })).toBeFocused()
})

test('an obsolete page-scope response cannot replace a newer position snapshot', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20,
    captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))

  type Phase = 'initial' | 'blocked-periodic' | 'newer'
  let phase: Phase = 'initial'
  let releaseOlder!: () => void
  const olderGate = new Promise<void>(resolve => { releaseOlder = resolve })
  let resolveBlocked!: () => void
  const blockedStarted = new Promise<void>(resolve => { resolveBlocked = resolve })
  const blockedServices = new Set<string>()
  let resolveOlderFinished!: () => void
  const olderFinished = new Promise<void>(resolve => { resolveOlderFinished = resolve })
  const finishedOlderServices = new Set<string>()
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
      try {
        await route.fulfill({ json: snapshot(serviceId, 1_200, '2026-10-23T04:00:10Z') })
      } catch {
        // The old page scope normally aborts these requests before the held response is released.
      } finally {
        finishedOlderServices.add(serviceId)
        if (finishedOlderServices.size === 2) resolveOlderFinished()
      }
      return
    }
    if (phase === 'newer') {
      return route.fulfill({ json: snapshot(serviceId, 6_500, '2026-10-23T04:00:30Z') })
    }
    return route.fulfill({ json: snapshot(serviceId, 4_300, '2026-10-23T04:00:00Z') })
  })

  await login(page)
  await expect(page.getByRole('img', { name: /Estimated bus 1, approximate position 4300 metres/ }).first()).toBeVisible()
  phase = 'blocked-periodic'
  await blockedStarted

  phase = 'newer'
  await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
  await page.getByRole('link', { name: 'Overview', exact: true }).click()
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
  await olderFinished
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
      await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: { mode: 'LIVE', demoRunId: null, items: [], page: 0, totalItems: 0, totalPages: 0 } }))
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
      const formatted = formatSgtDateTime(`2026-10-22T01:0${slot}:00Z`)
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
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toHaveCount(0)
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
  await expectPageRefreshStatus(page, 30)
  await expect(page.getByRole('status', { name: '' }).filter({ hasText: /^Simulation$/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '190', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('button', { name: 'Expand sidebar' }).click()
  await page.screenshot({ path: 'node_modules/.cache/controller-overview.png', fullPage: true })
  await page.getByRole('button', { name: 'Collapse sidebar' }).click()
  await expect(page.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('link', { name: /Simulation detections/ }).click()
  await expect(page).toHaveURL(/\/controller\/disruptions$/)
  await expect(page.getByRole('heading', { name: 'Multi-hazard conditions' })).toBeVisible()
  await expectPageRefreshStatus(page, 30)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Disruptions', exact: true })).toBeVisible()
  await page.getByRole('navigation', { name: 'Controller navigation' }).getByRole('link', { name: 'Planning & mitigation' }).click()
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toBeVisible()
  await expectPageRefreshStatus(page, 30)
  await page.getByRole('link', { name: 'Audit log', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'AI audit logs' })).toBeVisible()
  await expect(page.getByText('No controller decisions have been saved.')).toBeVisible()
  await expectPageRefreshStatus(page, 30)
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
      const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
      await expect(editor).toBeVisible()
      await expect(editor.getByLabel('Bus reference').first()).toBeFocused()
      await editor.getByLabel('Controller rationale').fill('Use a shorter hold to reduce passenger delay.')
      await editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true }).click()
      await expect(editor).toHaveCount(0)
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
    const header = page.locator('header').first()
    const tableHeader = page.getByRole('columnheader', { name: 'Decision', exact: true })
    await tableHeader.scrollIntoViewIfNeeded()
    await page.evaluate(() => window.scrollBy(0, 80))
    const headerBox = (await header.boundingBox())!
    const elementAtHeaderPoint = await page.evaluate(({ x, y }) => {
      const el = document.elementFromPoint(x, y)
      return el?.closest('header') ? 'header' : el?.tagName
    }, { x: headerBox.x + 50, y: headerBox.y + headerBox.height / 2 })
    expect(elementAtHeaderPoint).toBe('header')
  })
}

test('controller handles conflicting decisions by retrieving the saved result', async ({ page, context }) => {
  await mockDashboard(context, { conflict: true })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Approve recommendation', exact: true }).click()
  await expect(page.getByText(/already has a decision/)).toBeVisible()
})

test('controller scrolls root-cause diagnoses within their collection viewport', async ({ page, context }) => {
  await mockDashboard(context)
  await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: {
    mode: 'DEMO', demoRunId: 'run-1', page: 0, size: 100, totalItems: 5, totalPages: 1,
    items: Array.from({ length: 5 }, (_, index) => ({
      requestId: `request-${index}`, eventId: `event-${index}`, diagnosisId: `diagnosis-${index}`,
      classification: 'SIMULATION', demoRunId: 'run-1', serviceId: '190', directionId: 2,
      stopCode: `09${String(index).padStart(3, '0')}`, status: 'COMPLETED',
      likelyCause: `Diagnosis ${index + 1}`, confidence: null, limitations: [], evidenceDetails: [],
      failureCode: null, policyOutcome: null, createdAt: '2026-10-25T07:05:31.590871Z',
    })),
  } }))

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const viewport = page.getByRole('region', { name: 'Root-cause diagnoses', exact: true })
    .locator('[data-slot="scroll-area-viewport"]')
  await expect.poll(() => viewport.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await viewport.hover()
  await page.mouse.wheel(0, 400)
  await expect.poll(() => viewport.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
})

test('controller sees concise diagnosis summaries and source facts in the evidence disclosure', async ({ page, context }) => {
  await mockDashboard(context)
  const likelyCause = 'Possible contributor: Vehicle Breakdown. The provider reported: Breakdown near Teck Whye Avenue.'
  const evidenceDetails = [{ evidenceId: 'traffic-incident:4b61cb14-7a2a-4ebd-8245-7e65eec5d23c',
    incidentType: 'Vehicle Breakdown', message: 'Breakdown near Teck Whye Avenue',
    reportTime: '2026-10-25T07:05:31.590871Z', fetchedAt: '2026-10-25T07:05:32Z',
    firstConfirmedAt: '2026-10-25T07:05:31.590871Z', secondsBeforeFirstConfirmed: 0,
    distanceMetersFromSuppliedBusPosition: 1807, busPositionEvidenceId: 'bus-arrival:077f6b4f-4da2-41dd-bd85-8740a9244f7c',
    monitoredStopCode: '44251' },
    { evidenceId: 'traffic-incident:missing-confirmation', incidentType: 'Road obstruction',
      message: 'Road obstruction reported', reportTime: '2026-10-25T07:04:00Z', fetchedAt: '2026-10-25T07:05:32Z',
      firstConfirmedAt: null, secondsBeforeFirstConfirmed: null, distanceMetersFromSuppliedBusPosition: null,
      busPositionEvidenceId: null, monitoredStopCode: '44251' },
    { evidenceId: 'traffic-incident:subsecond-report', incidentType: 'Road works',
      message: 'Road works reported', reportTime: '2026-10-25T07:05:31.090871Z', fetchedAt: '2026-10-25T07:05:32Z',
      firstConfirmedAt: '2026-10-25T07:05:31.590871Z', secondsBeforeFirstConfirmed: 0,
      distanceMetersFromSuppliedBusPosition: null, busPositionEvidenceId: null, monitoredStopCode: '44251' }]
  await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [{
    requestId: 'request-1', eventId: 'event-1', diagnosisId: 'diagnosis-1', classification: 'SIMULATION', demoRunId: 'run-1',
    serviceId: '190', directionId: 1, stopCode: '44251', status: 'COMPLETED', likelyCause, confidence: null,
    limitations: ['Road and direction relevance have not been verified; proximity does not establish impact or causation.'],
    evidenceDetails, failureCode: null, policyOutcome: null, createdAt: '2026-10-25T07:05:31.590871Z',
  }], page: 0, size: 100, totalItems: 1, totalPages: 1 } }))
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [{
    recommendationId: 'recommendation-1', classification: 'SIMULATION', status: 'RECOMMENDED',
    serviceId: '190', directionId: 1, monitoredStopCode: '44251', detectedAt: '2026-10-25T07:05:31Z', createdAt: '2026-10-25T07:06:00Z',
    expectedHeadwaySeconds: 180, observedHeadwaySeconds: 60, mitigationType: 'HOLD', targetBusReference: 'SAMPLE-BUS',
    affectedBusReferences: ['SAMPLE-BUS'], controlStopCode: '44251', adjustmentSeconds: 120,
    explanation: { rationale: 'Restore spacing between buses.', likelyCause, diagnosisConfidence: null,
      tradeOffs: ['Adds waiting time.'], limitations: ['Simulation only.'], evidenceDetails },
    expectedImpact: { headwayBeforeSeconds: 60, headwayAfterSeconds: 180, queueBeforePassengers: 20, queueAfterPassengers: 12,
      passengerMinutesBefore: 40, passengerMinutesAfter: 28 },
  }], page: 0, size: 50, totalItems: 1, totalPages: 1 } }))

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const diagnoses = page.getByRole('region', { name: 'Root-cause diagnoses', exact: true })
  await expect(diagnoses.getByText(likelyCause)).toBeVisible()
  await expect.poll(async () => {
    const [viewport, card] = await Promise.all([diagnoses.locator('[data-slot="scroll-area-viewport"]').boundingBox(), diagnoses.locator('[data-slot="card"]').first().boundingBox()])
    return viewport !== null && card !== null && card.x > viewport.x && card.y > viewport.y
  }).toBe(true)
  await diagnoses.getByText('Evidence and limitations').click()
  await expect(diagnoses.getByText(/same time as the first confirmed irregularity/).first()).toBeVisible()
  await expect(diagnoses.getByText(/but timing relative to the first confirmed irregularity is unavailable/).first()).toBeVisible()
  await expect(diagnoses.getByText(/Reported less than 1 second before the first confirmed irregularity/).first()).toBeVisible()
  await expect(diagnoses.getByText(/1,807 m from the supplied bus position near stop 44251/).first()).toBeVisible()

  const recommendationEvidence = page.locator('details').filter({ hasText: 'Supporting incident evidence' })
  await expect(page.getByText(likelyCause).nth(1)).toBeVisible()
  await page.getByText('Evidence and limitations').nth(1).click()
  await expect(recommendationEvidence.getByText(/Bus-position evidence/)).toBeVisible()
})

test('dirty controller recommendation editor confirms dismissal and discards only explicitly', async ({ page, context }) => {
  await mockDashboard(context)
  let decisionRequests = 0
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', async route => {
    if (route.request().method() === 'POST') decisionRequests++
    return route.fallback()
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const opener = page.getByRole('button', { name: 'Enter controller recommendation', exact: true })
  await opener.focus()
  await page.keyboard.press('Enter')
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  await expect(editor).toBeVisible()
  const busReference = editor.getByLabel('Bus reference').first()
  const instruction = editor.getByLabel('Instruction').first()
  const rationale = editor.getByLabel('Controller rationale')
  await expect(busReference).toBeFocused()
  const submit = editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })
  await submit.focus()
  await page.keyboard.press('Tab')
  await expect(busReference).toBeFocused()
  await busReference.fill('SAMPLE-BUS-EDITED')
  await instruction.fill('Take the next stop slowly.')
  await submit.click()
  await expect(editor.getByRole('alert')).toContainText('Enter a bus reference and instruction for every affected bus, plus a controller rationale.')
  expect(decisionRequests).toBe(0)
  await rationale.fill('Avoid sudden braking near the report.')
  await expect(editor.getByRole('alert')).toHaveCount(0)

  await page.keyboard.press('Escape')
  let confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes?', exact: true })
  await expect(confirmation).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(confirmation).toHaveCount(0)
  await expect(editor).toBeVisible()
  await expect(busReference).toHaveValue('SAMPLE-BUS-EDITED')
  await expect(instruction).toHaveValue('Take the next stop slowly.')
  await expect(rationale).toHaveValue('Avoid sudden braking near the report.')

  await editor.getByRole('button', { name: 'Cancel', exact: true }).click()
  confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes?', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: 'Keep editing', exact: true }).click()
  await expect(editor).toBeVisible()
  await expect(busReference).toHaveValue('SAMPLE-BUS-EDITED')
  await expect(instruction).toHaveValue('Take the next stop slowly.')
  await expect(rationale).toHaveValue('Avoid sudden braking near the report.')

  await page.mouse.click(4, 4)
  confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes?', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click()
  await expect(editor).toHaveCount(0)
  await expect(opener).toBeFocused()
  expect(decisionRequests).toBe(0)

  await opener.click()
  const reopened = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  await expect(reopened.getByLabel('Bus reference').first()).toHaveValue('SAMPLE-BUS')
  await expect(reopened.getByLabel('Instruction').first()).toHaveValue('Hold for 120 seconds at stop B01.')
  await reopened.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(reopened).toHaveCount(0)
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await expect(opener).toBeFocused()
})

test('recommendation editor keeps edits on API failure and cannot close or submit twice while saving', async ({ page, context }) => {
  await mockDashboard(context)
  let attempts = 0
  let releaseFirstRequest!: () => void
  let signalRequestStarted!: () => void
  const requestStarted = new Promise<void>(resolve => { signalRequestStarted = resolve })
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    attempts++
    if (attempts === 1) {
      signalRequestStarted()
      await new Promise<void>(resolve => { releaseFirstRequest = resolve })
      return route.fulfill({ status: 503, json: { detail: 'Decision service is temporarily unavailable.' } })
    }
    return route.fulfill({ json: { recommendationId: 'recommendation-1', action: 'REJECTED', actorSubject: userId,
      overrideInstructions: [{ busReference: 'SAMPLE-BUS', instruction: 'Hold for 120 seconds at stop B01.' }],
      controllerRationale: 'Use the controller proposal.', decidedAt: '2026-10-25T07:00:00Z' } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  const rationale = editor.getByLabel('Controller rationale')
  await rationale.fill('Use the controller proposal.')
  const save = editor.locator('form button[type="submit"]')
  await save.click()
  await requestStarted
  await expect(save).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(editor).toBeVisible()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  expect(attempts).toBe(1)
  releaseFirstRequest()
  await expect(editor.getByRole('alert')).toContainText('Unable to save the decision.')
  await expect(rationale).toHaveValue('Use the controller proposal.')
  await expect(save).toBeEnabled()

  await save.click()
  await expect.poll(() => attempts).toBe(2)
  await expect(editor).toHaveCount(0)
  await expect(page.getByText('AI recommendation replaced with controller instructions.')).toBeVisible()
})

test('saving pauses polling and reconciles the decision after the response', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  let saving = false
  let pollsWhileSaving = 0
  let release!: () => void
  await context.route('**/api/v1/planning/recommendations/pending?*', route => {
    if (!saving) return route.fallback()
    pollsWhileSaving++
    return route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    saving = true
    await new Promise<void>(resolve => { release = resolve })
    await route.fulfill({ json: { recommendationId: 'recommendation-1', action: 'REJECTED' } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  await editor.getByLabel('Controller rationale').fill('Keep the submitted decision visible until its response.')
  await editor.locator('form button[type="submit"]').click()
  await expect.poll(() => saving).toBe(true)
  await page.clock.runFor(30000)
  expect(pollsWhileSaving).toBe(0)
  await expect(editor).toBeVisible()
  await expect(editor.getByRole('button', { name: 'Saving…', exact: true })).toBeDisabled()
  release()
  await expect.poll(() => pollsWhileSaving).toBe(1)
  await expect(editor).toHaveCount(0)
  await expect(page.getByText('AI recommendation replaced with controller instructions.')).toBeVisible()
})

test('recommendation editor retains its draft during an unrelated refresh', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  let holdRouteRefresh = false
  let releaseRoute!: () => void
  let signalRouteRefresh!: () => void
  const routeRefreshStarted = new Promise<void>(resolve => { signalRouteRefresh = resolve })
  await context.route('**/api/v1/planning/recommendations/pending?*', async route => {
    if (!holdRouteRefresh) return route.fallback()
    signalRouteRefresh()
    await new Promise<void>(resolve => { releaseRoute = resolve })
    return route.fallback()
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  const rationale = editor.getByLabel('Controller rationale')
  await rationale.fill('Keep the current draft while route data refreshes.')

  holdRouteRefresh = true
  await page.clock.fastForward(1000)
  await routeRefreshStarted
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep the current draft while route data refreshes.')
  releaseRoute()
  await expect(editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })).toBeEnabled()
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep the current draft while route data refreshes.')
})

test('recommendation editor retains a later-page draft while refresh checks all pending pages', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  const makeRecommendation = (recommendationId: string, serviceId: string, likelyCause: string) => ({
    recommendationId, classification: 'SIMULATION', status: 'RECOMMENDED', serviceId, directionId: 1, monitoredStopCode: 'B01',
    detectedAt: '2026-10-22T01:00:00Z', createdAt: '2026-10-22T01:01:00Z', expectedHeadwaySeconds: 180, observedHeadwaySeconds: 60,
    mitigationType: 'HOLD', targetBusReference: 'SAMPLE-BUS', affectedBusReferences: ['SAMPLE-BUS'], controlStopCode: 'B01', adjustmentSeconds: 120,
    explanation: { rationale: 'Restore spacing between buses.', likelyCause, diagnosisConfidence: 0.86, tradeOffs: ['Adds waiting time.'], limitations: ['Simulation only.'] },
    expectedImpact: { headwayBeforeSeconds: 60, headwayAfterSeconds: 180, queueBeforePassengers: 20, queueAfterPassengers: 12, passengerMinutesBefore: 40, passengerMinutesAfter: 28 },
  })
  const firstPage = Array.from({ length: 100 }, (_, index) => makeRecommendation(`page-zero-${index}`, String(index + 1), `Page zero proposal ${index + 1}`))
  const laterPage = makeRecommendation('recommendation-later-page', '999', 'Selected later-page proposal')
  let pageZeroRequests = 0
  let laterPageRequests = 0
  await context.route('**/api/v1/planning/recommendations/pending?*', route => {
    const url = new URL(route.request().url())
    const requestedPage = Number(url.searchParams.get('page'))
    if (requestedPage === 0) {
      pageZeroRequests += 1
      return route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: firstPage, page: 0, totalItems: 101, totalPages: 2 } })
    }
    laterPageRequests += 1
    return route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [laterPage], page: 1, totalItems: 101, totalPages: 2 } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const list = page.getByRole('listbox', { name: 'Recommendations awaiting a decision', exact: true })
  const loadSecondPage = page.getByRole('button', { name: 'Load more recommendations', exact: true })
  await expect(loadSecondPage).toBeVisible()
  const firstLaterPageResponse = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/planning/recommendations/pending' && url.searchParams.get('page') === '1'
  })
  await loadSecondPage.click()
  await firstLaterPageResponse
  const laterProposal = list.getByRole('option').filter({ hasText: 'Selected later-page proposal' })
  await expect(laterProposal).toBeVisible()
  await laterProposal.click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  const rationale = editor.getByLabel('Controller rationale')
  await rationale.fill('Keep this later-page draft during its revalidation.')

  const previousPageZeroRequests = pageZeroRequests
  const refreshedPageZero = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/planning/recommendations/pending' && url.searchParams.get('page') === '0' && pageZeroRequests > previousPageZeroRequests
  })
  await page.clock.fastForward(1000)
  await refreshedPageZero
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep this later-page draft during its revalidation.')
  await expect.poll(() => laterPageRequests).toBeGreaterThan(1)
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep this later-page draft during its revalidation.')
})

test('recommendation editor keeps its draft and blocks decisions when refresh fails', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  let failRefresh = false
  await context.route('**/api/v1/planning/recommendations/pending?*', route => failRefresh
    ? route.fulfill({ status: 503 })
    : route.fallback())

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  const rationale = editor.getByLabel('Controller rationale')
  await rationale.fill('Keep this draft until a refresh can confirm it.')

  const failedRefresh = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/planning/recommendations/pending' && response.status() === 503)
  failRefresh = true
  await page.clock.fastForward(1000)
  await failedRefresh
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep this draft until a refresh can confirm it.')
  await expect(editor.getByRole('alert')).toContainText('Unable to confirm this recommendation is current')
  await expect(editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })).toBeDisabled()
  await expect(page.locator('button').filter({ hasText: /^Approve recommendation$/ })).toBeDisabled()

  failRefresh = false
  await editor.getByRole('button', { name: 'Retry recommendation check', exact: true }).click()
  await expect(rationale).toHaveValue('Keep this draft until a refresh can confirm it.')
  await expect(editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })).toBeEnabled()
})

test('recommendation editor remains during a poll and closes if its recommendation is removed', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  let removed = false
  let releasePage!: () => void
  let signalRefreshStarted!: () => void
  const refreshStarted = new Promise<void>(resolve => { signalRefreshStarted = resolve })
  let decisionRequests = 0
  await context.route('**/api/v1/planning/recommendations/pending?*', async route => {
    if (!removed) return route.fallback()
    signalRefreshStarted()
    await new Promise<void>(resolve => { releasePage = resolve })
    return route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-1', items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', async route => {
    if (route.request().method() === 'POST') decisionRequests++
    return route.fallback()
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  await editor.getByLabel('Controller rationale').fill('Keep the saved draft only while current.')

  removed = true
  await page.clock.fastForward(1000)
  await refreshStarted
  await expect(editor).toBeVisible()
  releasePage()
  await expect(editor).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: 'recommendation is no longer current' })).toBeVisible()
  expect(decisionRequests).toBe(0)
})

test('recommendation editor closes when the active simulation run changes', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  let runId = 'run-1'
  let signalNewRun!: () => void
  const newRunLoaded = new Promise<void>(resolve => { signalNewRun = resolve })
  await context.route('**/api/v1/demo-runs/active', route => {
    if (runId === 'run-2') signalNewRun()
    return route.fulfill({ json: { id: runId, status: 'REPLAY_COMPLETE', alerts: [] } })
  })
  let newRunPending = false
  await context.route('**/api/v1/planning/recommendations/pending?*', route => newRunPending
    ? route.fulfill({ json: { mode: 'DEMO', demoRunId: 'run-2', items: [], page: 0, totalItems: 0, totalPages: 0 } })
    : route.fallback())
  let decisionRequests = 0
  await context.route('**/api/v1/planning/recommendations/recommendation-1/decision', async route => {
    if (route.request().method() === 'POST') decisionRequests++
    return route.fallback()
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  await editor.getByLabel('Controller rationale').fill('Only use this draft in the current run.')

  runId = 'run-2'
  newRunPending = true
  await page.clock.fastForward(1000)
  await newRunLoaded
  await expect(editor).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: 'recommendation is no longer current' })).toBeVisible()
  expect(decisionRequests).toBe(0)
})

test('recommendation editor keeps its draft while simulation state is unavailable', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await context.route('**/frontend-config', route => route.fulfill({ json: {
    projectUrl: 'https://example.supabase.co', publishableKey: 'sb_publishable_synthetic',
    controllerRefreshIntervalSeconds: 1, ingestionPollIntervalSeconds: 60, routeIncidentGapToleranceSeconds: 20, captainRefreshIntervalSeconds: 5, publicNoticeRefreshIntervalSeconds: 30,
  } }))
  let failNextCheck = false
  let successfulChecks = 0
  await context.route('**/api/v1/demo-runs/active', route => {
    if (failNextCheck) {
      failNextCheck = false
      return route.fulfill({ status: 503 })
    }
    successfulChecks += 1
    return route.fulfill({ json: { id: 'run-1', status: 'REPLAY_COMPLETE', alerts: [] } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Enter controller recommendation', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Enter controller recommendation', exact: true })
  const rationale = editor.getByLabel('Controller rationale')
  await rationale.fill('Keep this simulation draft until the run is verified.')

  const checksBeforeRefresh = successfulChecks
  const failedRunCheck = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/demo-runs/active' && response.status() === 503)
  failNextCheck = true
  await page.clock.fastForward(1000)
  await failedRunCheck
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep this simulation draft until the run is verified.')
  await expect(editor.getByRole('alert')).toContainText('Unable to confirm')
  await expect(editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })).toBeDisabled()

  await editor.getByRole('button', { name: 'Retry recommendation check', exact: true }).click()
  await expect.poll(() => successfulChecks).toBeGreaterThan(checksBeforeRefresh)
  await expect(editor).toBeVisible()
  await expect(rationale).toHaveValue('Keep this simulation draft until the run is verified.')
  await expect(editor.getByRole('button', { name: 'Reject AI suggestion & save instructions', exact: true })).toBeEnabled()
})

test('controller distinguishes failed arrivals from empty arrivals and can retry', async ({ page, context }) => {
  const options = { arrivalFailure: true }
  await mockDashboard(context, options)
  await login(page)
  await expect(page.getByRole('alert')).toContainText('Unable to load arrivals')
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
  const header = page.locator('header').first()
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await expect.poll(async () => (await header.boundingBox())?.y).toBe(0)
  const headerZIndex = Number(await header.evaluate(element => getComputedStyle(element).zIndex))
  await page.getByRole('button', { name: 'Expand sidebar' }).click()
  const navigationOverlay = page.getByRole('button', { name: 'Close navigation' })
  expect(Number(await navigationOverlay.evaluate(element => getComputedStyle(element).zIndex))).toBeGreaterThan(headerZIndex)
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
  for (const route of ['/controller', '/controller/disruptions', '/controller/planning', '/controller/notices', '/controller/audit']) {
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
    await expectAccessVerified(page)
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
  await expectAccessVerified(page)
})
test('page verification failures do not grant access', async ({ page, context }) => {
  const options = { accessFailure: false }
  await mockAuth(context, 'CONTROLLER', options)
  await login(page)
  await expectAccessVerified(page)
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
  await expectAccessVerified(page)
  const other = await context.newPage()
  await other.goto('/controller')
  await expectAccessVerified(other)
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
  await expectAccessVerified(page)
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
  await expectAccessVerified(page)
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
    await expectAccessVerified(page)
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

test('captain retries failed acknowledgement and keeps the persisted receipt after refresh', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  const now = new Date()
  await page.clock.install({ time: now })
  let instruction = syntheticInstruction(now)
  let acknowledgementCalls = 0
  let releaseFirstResponse!: () => void
  const firstResponse = new Promise<void>(resolve => { releaseFirstResponse = resolve })
  await context.route('**/api/v1/planning/captain/instructions?*', route => route.fulfill({
    json: { items: [instruction], page: 0, totalItems: 1, totalPages: 1, serverTime: now.toISOString() },
  }))
  await context.route(`**/api/v1/planning/captain/instructions/${instruction.id}/acknowledgements`, async route => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    expect(route.request().postData()).toBeNull()
    acknowledgementCalls++
    if (acknowledgementCalls === 1) {
      await firstResponse
      return route.fulfill({ status: 503, json: { detail: 'Acknowledgement was not saved. Try again.' } })
    }
    instruction = { ...instruction, status: 'ACKNOWLEDGED', acknowledgedAt: now.toISOString(),
      applicableUntil: new Date(now.getTime() + 300_000).toISOString() }
    return route.fulfill({ json: instruction })
  })

  await login(page)
  const display = page.getByRole('region', { name: 'Controller instructions', exact: true })
  const stops = page.getByRole('region', { name: 'Upcoming stops in route order', exact: true })
  const stopBounds = await stops.boundingBox()
  const visibleStops = await stops.locator('li').evaluateAll((elements, bounds) => elements.filter(element => {
    const rect = element.getBoundingClientRect()
    return rect.top >= bounds.top && rect.bottom <= bounds.top + bounds.height
  }).length, { top: stopBounds!.y, height: stopBounds!.height })
  expect(visibleStops).toBeLessThanOrEqual(10)
  await expect(display.getByText(instruction.message, { exact: true })).toBeVisible()
  await expect(display.getByText('Simulation · demo-190-b · Hazard: Live', { exact: true })).toBeVisible()
  await expect(display.getByText(/sec remaining/)).toHaveCount(0)
  await display.getByRole('button', { name: 'Acknowledge', exact: true }).click()
  await expect(display.getByRole('button', { name: 'Saving…', exact: true })).toBeDisabled()
  await expect.poll(() => acknowledgementCalls).toBe(1)
  releaseFirstResponse()
  await expect(display.getByRole('alert')).toContainText('Acknowledgement was not saved. Try again.')
  await expect(display.getByText(/^Acknowledged /)).toHaveCount(0)
  await display.getByRole('button', { name: 'Acknowledge', exact: true }).click()
  await expect(display.getByText(/^Acknowledged /)).toBeVisible()
  await expect(display.getByText(/sec remaining · advisory window/)).toBeVisible()
  await expect(display.getByRole('button', { name: 'Acknowledge', exact: true })).toHaveCount(0)
  await expect(display.getByRole('alert')).toHaveCount(0)
  const before = Number((await display.getByText(/sec remaining · advisory window/).innerText()).split(' ')[0])
  await page.clock.fastForward(10_000)
  await expect.poll(async () => Number((await display.getByText(/sec remaining · advisory window/).innerText()).split(' ')[0]))
    .toBeLessThan(before)
  const receipt = await display.getByText(/^Acknowledged /).innerText()
  await page.reload()
  await expect(display.getByText(receipt, { exact: true })).toBeVisible()
  await expect(display.getByRole('button', { name: 'Acknowledge', exact: true })).toHaveCount(0)
  expect(acknowledgementCalls).toBe(2)
})

test('captain instruction cards scroll and load later server pages without losing acknowledgement state', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  const now = new Date()
  const instructions = Array.from({ length: 6 }, (_, index) => ({
    ...syntheticInstruction(now), id: `captain-instruction-${index}`, message: `Instruction ${index + 1}`,
  }))
  let acknowledgedInstruction: CaptainInstruction | null = null
  const requestedPages: number[] = []
  await context.route('**/api/v1/planning/captain/instructions?*', route => {
    const query = new URL(route.request().url()).searchParams
    const pageNumber = Number(query.get('page'))
    requestedPages.push(pageNumber)
    return route.fulfill({ json: { items: pageNumber === 0 ? instructions.slice(0, 5) : [acknowledgedInstruction ?? instructions[5]],
      page: pageNumber, totalItems: instructions.length, totalPages: 2, serverTime: now.toISOString() } })
  })
  await context.route('**/api/v1/planning/captain/instructions/captain-instruction-5/acknowledgements', route => {
    acknowledgedInstruction = { ...instructions[5], status: 'ACKNOWLEDGED', acknowledgedAt: now.toISOString(),
      applicableUntil: new Date(now.getTime() + 300_000).toISOString() }
    return route.fulfill({ json: acknowledgedInstruction })
  })
  await login(page)
  const display = page.getByRole('region', { name: 'Controller instructions', exact: true })
  const viewport = display.getByRole('region', { name: 'Captain instructions', exact: true })
  await expect(viewport.getByText('Instruction 1', { exact: true })).toBeVisible()
  const bounds = await viewport.boundingBox()
  const visibleCards = await viewport.locator('[data-slot="card"]').evaluateAll((elements, viewportBounds) => elements.filter(element => {
    const rect = element.getBoundingClientRect()
    return rect.top >= viewportBounds.top && rect.bottom <= viewportBounds.top + viewportBounds.height
  }).length, { top: bounds!.y, height: bounds!.height })
  expect(visibleCards).toBeLessThanOrEqual(4)
  await display.getByRole('button', { name: 'Load more captain instructions', exact: true }).click()
  await expect(viewport.getByText('Instruction 6', { exact: true })).toBeVisible()
  const pageZeroRequests = requestedPages.filter(pageNumber => pageNumber === 0).length
  await viewport.getByRole('button', { name: 'Acknowledge', exact: true }).last().click()
  await expect.poll(() => requestedPages.filter(pageNumber => pageNumber === 0).length).toBeGreaterThan(pageZeroRequests)
  await expect(viewport.locator('[data-slot="card"]')).toHaveCount(5)
  await display.getByRole('button', { name: 'Load more captain instructions', exact: true }).click()
  await expect(viewport.getByText(/^Acknowledged /)).toHaveCount(1)
  await expect(viewport.getByText('Instruction 6', { exact: true })).toBeVisible()
  await expect(viewport.locator('[data-slot="card"]')).toHaveCount(6)
  expect(requestedPages.filter(pageNumber => pageNumber === 1)).toHaveLength(2)
})

test('controller reviews a service-direction broadcast and sends only the reviewed captain recipients', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const issued = syntheticInstruction(new Date())
  const advisory: Advisory = {
    id: issued.id, disruptionId: '00000000-0000-4000-8000-000000000103', demoRunId: issued.demoRunId,
    busId: issued.busId, status: 'PENDING', candidate: { type: issued.type, parameters: issued.parameters, rationale: 'Permitted synthetic pacing adjustment.' },
    finalCandidate: null, preview: issued.message, hazardClassification: issued.hazardClassification,
    limitations: ['Simulation only.'], createdAt: issued.issuedAt,
  }
  let sent = false
  const other = { ...advisory, id: 'second-proposal', busId: 'demo-190-c' }
  const reverse = { ...advisory, id: 'reverse-proposal', busId: 'demo-190-reverse' }
  const deliveries = [issued, { ...issued, id: other.id, busId: other.busId }]
  await context.route('**/api/v1/planning/advisory-groups?*', route => route.fulfill({
    json: { items: [...(sent ? [] : [advisoryGroup([advisory, other])]), advisoryGroup([reverse], '190', 2)], page: 0, totalItems: sent ? 1 : 2, totalPages: 1 },
  }))
  await context.route('**/api/v1/planning/instructions?*', route => route.fulfill({
    json: { items: sent ? deliveries : [], page: 0, totalItems: sent ? 2 : 0, totalPages: sent ? 1 : 0 },
  }))
  await context.route('**/api/v1/planning/advisory-groups/decisions', route => {
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    expect(route.request().postDataJSON()).toEqual({ proposalIds: [advisory.id, other.id] })
    sent = true
    return route.fulfill({ json: { items: deliveries, page: 0, totalItems: 2, totalPages: 1 } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByRole('heading', { name: 'Service 190 · Direction 1', exact: true })).toBeVisible()
  await expect(advisories.getByRole('heading', { name: 'Service 190 · Direction 2', exact: true })).toBeVisible()
  await expect(advisories.getByText('2 affected buses', { exact: false })).toBeVisible()
  await page.screenshot({ path: 'node_modules/.cache/controller-advisory-groups-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'node_modules/.cache/controller-advisory-groups-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  await advisories.getByRole('button', { name: 'Review and send to affected captains', exact: true }).first().click()
  const review = page.getByRole('dialog', { name: 'Send advisory to affected captains', exact: true })
  await expect(review.getByText(advisory.busId, { exact: true })).toBeVisible()
  await expect(review.getByText(other.busId, { exact: true })).toBeVisible()
  await expect(review.getByText(reverse.busId, { exact: true })).toHaveCount(0)
  expect(sent).toBe(false)
  await review.getByRole('button', { name: 'Approve and send group', exact: true }).click()
  await expect(advisories.getByRole('heading', { name: 'Service 190 · Direction 1', exact: true })).toHaveCount(0)
  await expect(advisories.getByRole('heading', { name: 'Service 190 · Direction 2', exact: true })).toBeVisible()
  await expect(advisories.getByRole('region', { name: 'Issued instructions', exact: true }).getByText(issued.message, { exact: true })).toHaveCount(2)
  await expect(advisories.getByText('demo-190-b · ISSUED', { exact: true })).toBeVisible()
  await expect(advisories.getByText('demo-190-c · ISSUED', { exact: true })).toBeVisible()
  expect(sent).toBe(true)
})

test('changed advisory groups preserve the review and show the server conflict without issuing', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const sample = syntheticInstruction(new Date())
  const first: Advisory = { id: 'group-one', disruptionId: 'hazard-one', demoRunId: sample.demoRunId, busId: sample.busId,
    status: 'PENDING', candidate: { type: sample.type, parameters: sample.parameters, rationale: 'Reported hazard.' },
    finalCandidate: null, preview: sample.message, hazardClassification: 'OBSERVATION_ONLY', limitations: [], createdAt: sample.issuedAt }
  const second = { ...first, id: 'group-two', busId: 'demo-190-c' }
  let changed = false
  let sends = 0
  await context.route('**/api/v1/planning/advisory-groups?*', route => route.fulfill({ json: {
    items: [advisoryGroup(changed ? [first] : [first, second])], page: 0, totalItems: 1, totalPages: 1,
  } }))
  await context.route('**/api/v1/planning/advisory-groups/decisions', route => {
    sends++
    changed = true
    return route.fulfill({ status: 409, json: { detail: 'Advisory group recipients changed; refresh and review before sending' } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Review and send to affected captains', exact: true }).click()
  const review = page.getByRole('dialog', { name: 'Send advisory to affected captains', exact: true })
  await review.getByRole('button', { name: 'Approve and send group', exact: true }).click()
  await expect(review.getByRole('alert')).toContainText('recipients changed')
  expect(sends).toBe(1)
  await expect(page.getByRole('region', { name: 'Issued instructions', exact: true })).toHaveCount(0)
  await review.getByRole('button', { name: 'Cancel', exact: true }).click()
  await page.getByRole('button', { name: 'Review and send to affected captains', exact: true }).click()
  await expect(review.getByText(second.busId, { exact: true })).toHaveCount(0)
  await expect(review.getByText(first.busId, { exact: true })).toBeVisible()
})

test('controller manages public notices on a dedicated page below planning', async ({ page, context }) => {
  await page.clock.install()
  await mockAuth(context, 'CONTROLLER')
  let noticeReads = 0
  await context.route('**/api/v1/planning/notices?*', route => {
    noticeReads++
    return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Multi-hazard conditions', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Public notice board', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Captain advisories', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Draft notice', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Public notice board', exact: true })).toHaveCount(0)
  expect(noticeReads).toBe(0)
  const navigation = page.getByRole('navigation', { name: 'Controller navigation' })
  await expect(navigation.getByRole('link')).toHaveCount(5)
  expect(await navigation.getByRole('link').evaluateAll(links => links.map(link => link.getAttribute('href'))))
    .toEqual(['/controller', '/controller/disruptions', '/controller/planning', '/controller/notices', '/controller/audit'])
  await page.getByRole('link', { name: 'Public notices', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Public notices', level: 1, exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Public notices', exact: true })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('link', { name: 'Public notice board', exact: true })).toHaveAttribute('href', '/notices')
  await expect(page.getByText('No notices.', { exact: true })).toBeVisible()
  const beforeRefresh = noticeReads
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => noticeReads).toBeGreaterThan(beforeRefresh)
  const afterRefresh = noticeReads
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  await page.clock.runFor(31000)
  await expect.poll(() => noticeReads).toBeGreaterThan(afterRefresh)
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await editor.getByLabel('Title', { exact: true }).fill('Keep this draft')
  const beforeDraftRefresh = noticeReads
  await page.clock.runFor(31000)
  await expect.poll(() => noticeReads).toBeGreaterThan(beforeDraftRefresh)
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('Keep this draft')
  await editor.getByRole('button', { name: 'Cancel draft', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Keep editing', exact: true }).click()
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('Keep this draft')
  await editor.getByRole('button', { name: 'Cancel draft', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard draft', exact: true }).click()
  await expect(editor).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Public notices', level: 1, exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('link', { name: 'Public notice board', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'node_modules/.cache/controller-public-notices-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.screenshot({ path: 'node_modules/.cache/controller-public-notices-desktop.png', fullPage: true })
})

test('public notice editor preserves its draft when saving fails', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: {
    items: [{ disruptionId: 'source-hazard', kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY',
      demoRunId: null, updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported.', instruction: null, effectiveUntil: null }, exposures: [], limitations: [] }],
    page: 0, size: 100, totalElements: 1, feeds: [],
  } }))
  await context.route('**/api/v1/planning/notices', route => route.fulfill({ status: 503, json: { detail: 'Notice save unavailable.' } }))
  await login(page)
  await page.goto('/controller/notices')
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await editor.getByLabel('Source hazard', { exact: true }).selectOption('source-hazard')
  await editor.getByLabel('Title', { exact: true }).fill('Keep this passenger notice')
  await editor.getByLabel('Starts (SGT)', { exact: true }).fill('2026-10-27T15:00')
  await editor.getByLabel('Expires (SGT)', { exact: true }).fill('2026-10-27T16:00')
  await editor.getByRole('button', { name: 'Save draft', exact: true }).click()
  await expect(editor.getByRole('alert')).toContainText('Notice save unavailable.')
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('Keep this passenger notice')
  await expect(editor.getByRole('button', { name: 'Save draft', exact: true })).toBeEnabled()
})

test('controller can apply a passenger notice suggestion without saving or publishing it', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000104'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: 'Use caution.', effectiveUntil: null },
    exposures: [], limitations: [],
  }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
  let suggestions = 0
  let drafts = 0
  await context.route(`**/api/v1/planning/notices/${disruptionId}/suggestions`, route => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    suggestions++
    return route.fulfill({ json: { title: 'Caution near Example Stop', message: 'Water reported near Example Stop. Use caution.',
      services: ['190'], locations: ['Example Stop'], generator: 'SOURCE_FACTS' } })
  })
  await context.route('**/api/v1/planning/notices', route => {
    if (route.request().method() === 'POST') drafts++
    return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Public notices', exact: true }).click()
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const advisories = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  const suggest = advisories.getByRole('button', { name: 'Suggest passenger wording', exact: true })
  await expect(suggest).toBeDisabled()
  await advisories.getByLabel('Source hazard', { exact: true }).selectOption(disruptionId)
  await expect(suggest).toBeEnabled()
  await suggest.click()
  await expect(advisories.getByLabel('Title', { exact: true })).toHaveValue('Caution near Example Stop')
  await expect(advisories.getByLabel('Passenger message', { exact: true })).toHaveValue('Water reported near Example Stop. Use caution.')
  await expect(advisories.getByLabel('Services (comma separated)', { exact: true })).toHaveValue('190')
  await expect(advisories.getByLabel('Locations (comma separated)', { exact: true })).toHaveValue('Example Stop')
  await expect(advisories.getByRole('heading', { name: 'New notice draft', exact: true })).toBeVisible()
  expect(suggestions).toBe(1)
  expect(drafts).toBe(0)
})

test('late notice suggestions cannot overwrite the selected hazard draft', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const hazardA = '00000000-0000-4000-8000-000000000201'
  const hazardB = '00000000-0000-4000-8000-000000000202'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [hazardA, hazardB].map((id, index) => ({
    disruptionId: id, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: `Hazard ${index ? 'B' : 'A'}`, description: `Message for hazard ${index ? 'B' : 'A'}.`, instruction: null, effectiveUntil: null },
    exposures: [], limitations: [],
  })), page: 0, size: 100, totalElements: 2, feeds: [] } }))
  let releaseSuggestion: (() => void) | undefined
  let markStarted: (() => void) | undefined
  const started = new Promise<void>(resolve => { markStarted = resolve })
  const waiting = new Promise<void>(resolve => { releaseSuggestion = resolve })
  await context.route(`**/api/v1/planning/notices/${hazardA}/suggestions`, async route => {
    markStarted?.()
    await waiting
    return route.fulfill({ json: { title: 'Suggested for A', message: 'Suggested message for A.', services: ['190'], locations: ['Stop A'], generator: 'SOURCE_FACTS' } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Public notices', exact: true }).click()
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const advisories = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  const source = advisories.getByLabel('Source hazard', { exact: true })
  await source.selectOption(hazardA)
  await advisories.getByRole('button', { name: 'Suggest passenger wording', exact: true }).click()
  await started
  await source.selectOption(hazardB)
  releaseSuggestion?.()

  await expect(advisories.getByLabel('Title', { exact: true })).toHaveValue('Hazard B')
  await expect(advisories.getByLabel('Passenger message', { exact: true })).toHaveValue('Message for hazard B.')
})

test('changing notice source clears prior passenger scope and validates the new schedule before saving', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const hazardA = '00000000-0000-4000-8000-000000000301'
  const hazardB = '00000000-0000-4000-8000-000000000302'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: {
    items: [hazardA, hazardB].map((id, index) => ({ disruptionId: id, kind: 'FLOOD', status: 'ACTIVE',
      classification: 'OBSERVATION_ONLY', demoRunId: null, updatedAt: '2026-10-31T04:00:00Z',
      facts: { title: `Hazard ${index ? 'B' : 'A'}`, description: `Message ${index ? 'B' : 'A'}.`, instruction: null, effectiveUntil: null },
      exposures: [], limitations: [] })), page: 0, size: 100, totalElements: 2, feeds: [],
  } }))
  const saved: Record<string, unknown>[] = []
  await context.route('**/api/v1/planning/notices', route => {
    saved.push(route.request().postDataJSON())
    return route.fulfill({ json: { ...saved.at(-1), status: 'DRAFT' } })
  })
  await login(page)
  await page.goto('/controller/notices')
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await editor.getByLabel('Source hazard', { exact: true }).selectOption(hazardA)
  await editor.getByLabel('Services (comma separated)', { exact: true }).fill('190')
  await editor.getByLabel('Locations (comma separated)', { exact: true }).fill('Stop A')
  await editor.getByLabel('Starts (SGT)', { exact: true }).fill('2030-01-01T15:00')
  await editor.getByLabel('Expires (SGT)', { exact: true }).fill('2030-01-01T16:00')
  await editor.getByLabel('Source hazard', { exact: true }).selectOption(hazardB)
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('Hazard B')
  await expect(editor.getByLabel('Passenger message', { exact: true })).toHaveValue('Message B.')
  for (const field of ['Services (comma separated)', 'Locations (comma separated)', 'Starts (SGT)', 'Expires (SGT)']) {
    await expect(editor.getByLabel(field, { exact: true })).toHaveValue('')
  }
  await editor.getByLabel('Services (comma separated)', { exact: true }).fill('972, 190')
  await editor.getByLabel('Locations (comma separated)', { exact: true }).fill('Stop B')
  await editor.getByLabel('Starts (SGT)', { exact: true }).fill('2030-01-01T16:00')
  await editor.getByLabel('Expires (SGT)', { exact: true }).fill('2030-01-01T15:00')
  await editor.getByRole('button', { name: 'Save draft', exact: true }).click()
  await expect(editor.getByRole('alert')).toHaveText('Expiry must be after the start time.')
  expect(saved).toHaveLength(0)
  await editor.getByLabel('Expires (SGT)', { exact: true }).fill('2030-01-01T17:00')
  await editor.getByRole('button', { name: 'Save draft', exact: true }).click()
  await expect(editor).toHaveCount(0)
  expect(saved).toHaveLength(1)
  expect(saved[0]).toMatchObject({ disruptionId: hazardB, title: 'Hazard B', message: 'Message B.',
    services: ['972', '190'], locations: ['Stop B'], effectiveFrom: '2030-01-01T08:00:00.000Z', effectiveUntil: '2030-01-01T09:00:00.000Z' })
})

test('failed notice suggestions preserve manual wording and allow retry without publishing', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000303'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-31T04:00:00Z', facts: { title: 'Source report', description: 'Water reported.', instruction: null, effectiveUntil: null },
    exposures: [], limitations: [],
  }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
  let failed = true
  let writes = 0
  await context.route('**/api/v1/planning/notices/**', route => {
    if (!route.request().url().endsWith('/suggestions')) {
      writes++
      return route.fulfill({ status: 500 })
    }
    return failed ? route.fulfill({ status: 503, json: { detail: 'Wording suggestions are unavailable. Review the source and try again.' } })
      : route.fulfill({ json: { title: 'Passenger update', message: 'Water reported near Stop A.', services: ['190'], locations: ['Stop A'], generator: 'SOURCE_FACTS' } })
  })
  await login(page)
  await page.goto('/controller/notices')
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await editor.getByLabel('Source hazard', { exact: true }).selectOption(disruptionId)
  await editor.getByLabel('Title', { exact: true }).fill('My reviewed title')
  await editor.getByRole('button', { name: 'Suggest passenger wording', exact: true }).click()
  await expect(editor.getByRole('alert')).toContainText('Wording suggestions are unavailable.')
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('My reviewed title')
  failed = false
  await editor.getByRole('button', { name: 'Suggest passenger wording', exact: true }).click()
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue('Passenger update')
  await expect(editor.getByRole('alert')).toHaveCount(0)
  await editor.getByLabel('Passenger message', { exact: true }).fill('Reviewed passenger message.')
  await expect(editor.getByLabel('Passenger message', { exact: true })).toHaveValue('Reviewed passenger message.')
  expect(writes).toBe(0)
})

test('controller publishes corrects and withdraws notices while the public board follows each saved change', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000306'
  const from = new Date(Date.now() + 8 * 3600_000 - 60_000).toISOString().slice(0, 16)
  const until = new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 16)
  let items: Record<string, unknown>[] = []
  let failPublication = true
  let failCorrection = true
  let corrections = 0
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: new Date().toISOString(), facts: { title: 'Flood report', description: 'Water reported.', instruction: null, effectiveUntil: null },
    exposures: [], limitations: [],
  }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
  await context.route('**/api/v1/planning/notices?*', route => route.fulfill({ json: { items, page: 0, totalItems: items.length, totalPages: 1 } }))
  await context.route('**/api/v1/planning/notices', route => {
    const saved = { ...route.request().postDataJSON(), classification: 'OBSERVATION_ONLY', demoRunId: null, status: 'DRAFT' }
    items.push(saved)
    return route.fulfill({ json: saved })
  })
  await context.route('**/api/v1/planning/notices/*/publications', route => {
    if (failPublication) return route.fulfill({ status: 503, json: { detail: 'Publication temporarily unavailable.' } })
    items[0] = { ...items[0], status: 'PUBLISHED', publishedAt: new Date().toISOString() }
    return route.fulfill({ json: items[0] })
  })
  await context.route('**/api/v1/planning/notices/*/replacements', route => {
    corrections++
    if (failCorrection) return route.fulfill({ status: 409, json: { detail: 'Source changed; review the correction before retrying.' } })
    const corrected = { ...route.request().postDataJSON(), classification: 'OBSERVATION_ONLY', demoRunId: null,
      status: 'PUBLISHED', publishedAt: new Date().toISOString() }
    items = [{ ...items[0], status: 'WITHDRAWN' }, corrected]
    return route.fulfill({ json: corrected })
  })
  await context.route('**/api/v1/planning/notices/*/withdrawals', route => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)
    items = items.map(item => item.id === id ? { ...item, status: 'WITHDRAWN' } : item)
    return route.fulfill({ json: items.find(item => item.id === id) })
  })
  await context.route('**/api/v1/planning/public/notices?*', route => {
    expect(route.request().headers().authorization).toBeUndefined()
    const visible = items.filter(item => item.status === 'PUBLISHED')
    return route.fulfill({ json: { items: visible, page: 0, totalItems: visible.length, totalPages: 1 } })
  })
  await login(page)
  await page.goto('/controller/notices')
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const draft = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await draft.getByLabel('Source hazard', { exact: true }).selectOption(disruptionId)
  await draft.getByLabel('Title', { exact: true }).fill('Original passenger notice')
  await draft.getByLabel('Starts (SGT)', { exact: true }).fill(from)
  await draft.getByLabel('Expires (SGT)', { exact: true }).fill(until)
  await draft.getByRole('button', { name: 'Save draft', exact: true }).click()
  await expect(draft).toHaveCount(0)
  const board = await context.newPage()
  await board.goto('/notices')
  await expect(board.getByText('No current service advisories.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Publish notice', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Publication temporarily unavailable.')
  expect(items[0].status).toBe('DRAFT')
  failPublication = false
  await page.getByRole('button', { name: 'Publish notice', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Correct notice', exact: true })).toBeVisible()
  await board.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(board.getByRole('heading', { name: 'Original passenger notice', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Correct notice', exact: true }).click()
  const correction = page.getByRole('dialog', { name: 'Publish a correction', exact: true })
  await expect(correction.getByLabel('Source hazard', { exact: true })).toBeDisabled()
  await correction.getByLabel('Title', { exact: true }).fill('Corrected passenger notice')
  await correction.getByLabel('Starts (SGT)', { exact: true }).fill('2039-01-01T15:00')
  await correction.getByLabel('Expires (SGT)', { exact: true }).fill('2040-01-01T15:00')
  await correction.getByRole('button', { name: 'Publish correction', exact: true }).click()
  await expect(correction.getByRole('alert')).toContainText('A correction must take effect immediately.')
  expect(corrections).toBe(0)
  await correction.getByLabel('Starts (SGT)', { exact: true }).fill(from)
  await correction.getByLabel('Expires (SGT)', { exact: true }).fill(until)
  await correction.getByRole('button', { name: 'Publish correction', exact: true }).click()
  await expect(correction.getByRole('alert')).toContainText('Source changed; review the correction before retrying.')
  await expect(correction.getByLabel('Title', { exact: true })).toHaveValue('Corrected passenger notice')
  expect(items[0].status).toBe('PUBLISHED')
  failCorrection = false
  await correction.getByRole('button', { name: 'Publish correction', exact: true }).click()
  await expect(correction).toHaveCount(0)
  await board.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(board.getByRole('heading', { name: 'Corrected passenger notice', exact: true })).toBeVisible()
  await expect(board.getByRole('heading', { name: 'Original passenger notice', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Withdraw notice', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Withdraw notice', exact: true })).toHaveCount(0)
  await board.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(board.getByText('No current service advisories.', { exact: true })).toBeVisible()
  await board.close()
})

test('notice history identifies each scope and copied simulation drafts keep their original run', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const runA = '00000000-0000-4000-8000-000000000401'
  const runB = '00000000-0000-4000-8000-000000000402'
  const base = { message: 'Reviewed passenger advice.', services: ['190'], locations: ['Example Stop'],
    effectiveFrom: new Date(Date.now() - 3600_000).toISOString(), effectiveUntil: new Date(Date.now() + 3600_000).toISOString(),
    publishedAt: null, withdrawnAt: null }
  const items = [
    { ...base, id: 'live-notice', disruptionId: 'live-source', title: 'Live notice history', classification: 'OBSERVATION_ONLY', demoRunId: null, status: 'PUBLISHED' },
    { ...base, id: 'run-a-notice', disruptionId: 'run-a-source', title: 'Run A draft history', classification: 'SIMULATION', demoRunId: runA, status: 'DRAFT' },
    { ...base, id: 'run-b-notice', disruptionId: 'run-b-source', title: 'Expired Run B draft', classification: 'SIMULATION', demoRunId: runB,
      effectiveUntil: new Date(Date.now() - 60_000).toISOString(), status: 'DRAFT' },
  ]
  await context.route('**/api/v1/planning/notices?*', route => route.fulfill({ json: { items, page: 0, totalItems: items.length, totalPages: 1 } }))
  await login(page)
  await page.goto('/controller/notices')
  await expect(page.getByText('History includes Live notices and all Simulation runs. Copies keep their original source scope.', { exact: true })).toBeVisible()
  const history = page.getByRole('region', { name: 'Managed notices', exact: true })
  const runARecord = history.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: 'Run A draft history', exact: true }) })
  const runBRecord = history.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: 'Expired Run B draft', exact: true }) })
  await expect(runARecord.getByText(`Simulation run: ${runA}`, { exact: true })).toBeVisible()
  await expect(runARecord.getByRole('link', { name: 'Preview simulation board', exact: true })).toHaveAttribute('href', `/notices?classification=SIMULATION&demoRunId=${runA}`)
  await expect(runBRecord.getByRole('link', { name: 'Preview simulation board', exact: true })).toHaveAttribute('href', `/notices?classification=SIMULATION&demoRunId=${runB}`)
  await expect(runBRecord.getByText('Simulation · Draft · Expired', { exact: true })).toBeVisible()
  await expect(runBRecord.getByRole('button', { name: 'Publish notice', exact: true })).toBeDisabled()
  await expect(runBRecord.getByRole('button', { name: 'Copy to new draft', exact: true })).toBeEnabled()
  await runARecord.getByRole('button', { name: 'Copy to new draft', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await expect(editor.getByLabel('Notice scope')).toContainText(`Notice scope: Simulation · Run ${runA}`)
})

test.describe('SGT correction scheduling', () => {
  test.use({ timezoneId: 'America/New_York' })
  test('notice corrections prefill immediate SGT start and preserve an unexpired end time', async ({ page, context }) => {
    await mockAuth(context, 'CONTROLLER')
    await context.route('**/api/v1/planning/notices?*', route => route.fulfill({ json: {
      items: [{ id: 'scheduled-notice', disruptionId: 'source-hazard', title: 'Scheduled passenger update', message: 'Passenger advice.',
        services: ['190'], locations: [], classification: 'OBSERVATION_ONLY', demoRunId: null, status: 'PUBLISHED',
        effectiveFrom: '2030-01-01T15:00:00Z', effectiveUntil: '2030-01-01T16:05:00Z', publishedAt: '2026-10-31T04:00:00Z', withdrawnAt: null }],
      page: 0, totalItems: 1, totalPages: 1,
    } }))
    await login(page)
    await page.goto('/controller/notices')
    await expect(page.getByText('Live · Published · Scheduled', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Correct notice', exact: true }).click()
    const editor = page.getByRole('dialog', { name: 'Publish a correction', exact: true })
    const start = await editor.getByLabel('Starts (SGT)', { exact: true }).inputValue()
    expect(start).not.toBe('')
    expect(Date.parse(`${start}:00+08:00`)).toBeLessThanOrEqual(Date.now())
    await expect(editor.getByLabel('Expires (SGT)', { exact: true })).toHaveValue('2030-01-02T00:05')
    await expect(editor.getByLabel('Notice scope')).toContainText('Notice scope: Live')
  })
})

test('controller repairs a saved notice by copying it into a separate draft', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const original = { id: '00000000-0000-4000-8000-000000000304', disruptionId: '00000000-0000-4000-8000-000000000305',
    title: 'Original draft', message: 'Water reported.', services: ['190'], locations: ['Example Stop'],
    classification: 'OBSERVATION_ONLY', demoRunId: null, status: 'DRAFT', effectiveFrom: '2026-10-31T04:00:00Z',
    effectiveUntil: '2026-10-31T05:00:00Z', publishedAt: null, withdrawnAt: null }
  await context.route('**/api/v1/planning/notices?*', route => route.fulfill({ json: {
    items: [original], page: 0, totalItems: 1, totalPages: 1,
  } }))
  let saved: Record<string, unknown> | undefined
  await context.route('**/api/v1/planning/notices', route => {
    saved = route.request().postDataJSON()
    return route.fulfill({ json: { ...saved, status: 'DRAFT' } })
  })
  await login(page)
  await page.goto('/controller/notices')
  await page.getByRole('button', { name: 'Copy to new draft', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'New notice draft', exact: true })
  await expect(editor.getByLabel('Title', { exact: true })).toHaveValue(original.title)
  await expect(editor.getByLabel('Source hazard', { exact: true })).toHaveValue(original.disruptionId)
  await expect(editor.getByLabel('Services (comma separated)', { exact: true })).toHaveValue('190')
  await expect(editor.getByLabel('Starts (SGT)', { exact: true })).toHaveValue('')
  await editor.getByLabel('Title', { exact: true }).fill('Revised draft')
  await editor.getByLabel('Starts (SGT)', { exact: true }).fill('2030-01-01T15:00')
  await editor.getByLabel('Expires (SGT)', { exact: true }).fill('2030-01-01T16:00')
  await editor.getByRole('button', { name: 'Save draft', exact: true }).click()
  await expect(editor).toHaveCount(0)
  expect(saved).toMatchObject({ disruptionId: original.disruptionId, title: 'Revised draft', message: original.message })
  expect(saved?.id).not.toBe(original.id)
  await expect(page.getByRole('heading', { name: original.title, exact: true })).toBeVisible()
})

test('controller reviews the server preview before issuing an edited instruction', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const issued = syntheticInstruction(new Date())
  const advisory: Advisory = {
    id: issued.id, disruptionId: '00000000-0000-4000-8000-000000000103', demoRunId: issued.demoRunId,
    busId: issued.busId, status: 'PENDING', candidate: { type: 'BE_CAUTIOUS',
      parameters: { stopCode: 'B01', stopName: 'Example Stop', stopSequence: 2, subsequentStopCount: null, speedReductionKmh: null, durationSeconds: null },
      rationale: 'Reported disruption near the next stop.' },
    finalCandidate: null, preview: 'Disruption near Example Stop. Be cautious.', hazardClassification: 'OBSERVATION_ONLY',
    limitations: ['Simulation only.'], createdAt: issued.issuedAt,
  }
  let sent = false
  let previews = 0
  let previewBody: unknown
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId: advisory.disruptionId, kind: 'TRAFFIC_INCIDENT', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: issued.issuedAt, facts: { title: 'Reported incident', description: 'A reported incident near Example Stop.', instruction: null, effectiveUntil: null },
    exposures: [{ busId: advisory.busId, serviceId: '190', stopCode: 'B01', stopName: 'Example Stop', stopSequence: 2, basis: 'POINT_PROXIMITY' }],
    limitations: ['Road relevance has not been verified.'],
  }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
  await context.route('**/api/v1/planning/advisory-groups?*', route => route.fulfill({
    json: { items: sent ? [] : [advisoryGroup([advisory])], page: 0, totalItems: 1, totalPages: 1 },
  }))
  await context.route('**/api/v1/planning/instructions?*', route => route.fulfill({
    json: { items: sent ? [issued] : [], page: 0, totalItems: sent ? 1 : 0, totalPages: sent ? 1 : 0 },
  }))
  await context.route(`**/api/v1/planning/advisories/${advisory.id}/preview`, route => {
    previews++
    previewBody = route.request().postDataJSON()
    expect(previewBody).toEqual({ override: { type: issued.type, parameters: issued.parameters,
      rationale: 'Permitted synthetic pacing adjustment.' }, rationale: 'Permitted synthetic pacing adjustment.', replaceInstructionId: null })
    return route.fulfill({ json: { message: issued.message } })
  })
  await context.route(`**/api/v1/planning/advisories/${advisory.id}/decisions`, route => {
    expect(route.request().postDataJSON()).toEqual(previewBody)
    sent = true
    return route.fulfill({ json: issued })
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await advisories.getByRole('button', { name: 'Edit instruction', exact: true }).click()
  await advisories.getByLabel('Instruction', { exact: true }).selectOption('REDUCE_SPEED')
  await advisories.getByLabel('Reduction (km/h)', { exact: true }).fill('5')
  await advisories.getByLabel('Duration (min)', { exact: true }).fill('5')
  await advisories.getByLabel('Reason for replacement', { exact: true }).fill('Permitted synthetic pacing adjustment.')
  await advisories.getByRole('button', { name: 'Preview instruction', exact: true }).click()
  await expect(advisories.getByLabel('OBU preview', { exact: true })).toHaveText(issued.message)
  expect(previews).toBe(1)
  expect(sent).toBe(false)
  await advisories.getByRole('button', { name: 'Approve edited instruction', exact: true }).click()
  await expect(advisories.getByText('No pending captain advisories.', { exact: true })).toBeVisible()
  await expect(advisories.getByText(issued.message, { exact: true })).toBeVisible()
  expect(sent).toBe(true)
})

test('controller pending queue filters on the server and can page through all pending proposals', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const proposal = (id: string, preview: string): Advisory => ({
    id, disruptionId: '00000000-0000-4000-8000-000000000203', demoRunId: '00000000-0000-4000-8000-000000000204',
    busId: 'demo-190-b', status: 'PENDING', candidate: { type: 'BE_CAUTIOUS',
      parameters: { stopCode: 'B01', stopName: 'Example Stop', stopSequence: 2, subsequentStopCount: null, speedReductionKmh: null, durationSeconds: null },
      rationale: 'Reported disruption near the next stop.' },
    finalCandidate: null, preview, hazardClassification: 'SIMULATION', limitations: [], createdAt: '2026-10-25T04:00:00Z',
  })
  const pending = [proposal('pending-new-1', 'Newest pending advisory'), proposal('pending-new-2', 'Next pending advisory')]
  const instructions = [
    { id: 'instruction-0', busId: 'demo-190-b', message: 'Instruction page 1', status: 'ISSUED', hazardClassification: 'SIMULATION', issuedAt: '2026-10-25T04:00:00Z' },
    { id: 'instruction-1', busId: 'demo-190-b', message: 'Instruction page 2', status: 'ISSUED', hazardClassification: 'SIMULATION', issuedAt: '2026-10-25T04:01:00Z' },
  ]
  const notices = [
    { id: 'notice-0', title: 'Managed notice page 1', message: 'First notice', classification: 'SIMULATION', status: 'DRAFT', services: [], locations: [] },
    { id: 'notice-1', title: 'Managed notice page 2', message: 'Second notice', classification: 'SIMULATION', status: 'DRAFT', services: [], locations: [] },
  ]
  const requested: URL[] = []
  await context.route('**/api/v1/planning/instructions?*', route => {
    const page = Number(new URL(route.request().url()).searchParams.get('page'))
    return route.fulfill({ json: { items: [instructions[page]], page, size: 100, totalItems: 101, totalPages: 2 } })
  })
  await context.route('**/api/v1/planning/notices?*', route => {
    const page = Number(new URL(route.request().url()).searchParams.get('page'))
    return route.fulfill({ json: { items: [notices[page]], page, size: 100, totalItems: 101, totalPages: 2 } })
  })
  await context.route('**/api/v1/disruption/hazards?*', route => {
    const requestedPage = Number(new URL(route.request().url()).searchParams.get('page'))
    const item = { disruptionId: `hazard-${requestedPage}`, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: `Hazard page ${requestedPage + 1}`, description: 'Reported water near the stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [] }
    return route.fulfill({ json: { items: [item], page: requestedPage, size: 100, totalElements: 101, feeds: [] } })
  })
  await context.route('**/api/v1/planning/diagnoses/hazard-requests?*', route => {
    const page = Number(new URL(route.request().url()).searchParams.get('page'))
    return route.fulfill({ json: { items: [{ requestId: `request-${page}`, eventType: 'HAZARD', status: 'COMPLETED', failureCode: null,
      disruptionId: `hazard-${page}`, createdAt: '2026-10-25T04:00:00Z' }], page, size: 100, totalItems: 101, totalPages: 2 } })
  })
  await context.route('**/api/v1/planning/advisory-groups?*', route => {
    const url = new URL(route.request().url())
    requested.push(url)
    const requestedPage = Number(url.searchParams.get('page'))
    return route.fulfill({ json: { items: [advisoryGroup([pending[requestedPage]])], page: requestedPage, size: 100, totalItems: 101, totalPages: 2 } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByText(pending[0].preview, { exact: true })).toBeVisible()
  await expect(advisories.getByRole('region', { name: 'Pending captain advisories', exact: true })).toBeVisible()
  await advisories.getByRole('button', { name: 'Load more pending advisories', exact: true }).click()
  await expect(advisories.getByText(pending[1].preview, { exact: true })).toBeVisible()
  await advisories.getByRole('button', { name: 'Load more instructions', exact: true }).click()
  await expect(advisories.getByText(instructions[1].message, { exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: /pages/ })).toHaveCount(0)
  expect(requested.every(url => url.pathname.endsWith('/advisory-groups'))).toBe(true)
  expect(requested.map(url => url.searchParams.get('page'))).toContain('1')

  await page.goto('/controller/disruptions')
  const hazards = page.getByRole('region', { name: 'Hazard conditions', exact: true })
  await page.getByRole('button', { name: 'Load more hazards', exact: true }).click()
  await expect(hazards.getByRole('heading', { name: 'Hazard page 2', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Hazard processing', exact: true })).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: /pages/ })).toHaveCount(0)

  await page.goto('/controller/audit')
  const managed = page.getByRole('region', { name: 'Managed notices', exact: true })
  await page.getByRole('button', { name: 'Load more managed notices', exact: true }).click()
  await expect(managed.getByRole('heading', { name: notices[1].title, exact: true })).toBeVisible()
})

test('planning loads later source hazards and resolves an advisory source independently', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const hazard = (id: string) => ({ disruptionId: id, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: id, description: `Description ${id}`, instruction: null, effectiveUntil: null },
    exposures: [{ busId: 'demo-190-b', serviceId: '190', stopCode: 'B01', stopName: 'Later source stop', stopSequence: 2, basis: 'POINT_PROXIMITY' }], limitations: [] })
  await context.route('**/api/v1/disruption/hazards?*', route => {
    const page = Number(new URL(route.request().url()).searchParams.get('page'))
    return route.fulfill({ json: { items: [hazard(page ? 'later-source' : 'first-source')], page, size: 100, totalElements: 101, feeds: [] } })
  })
  await context.route('**/api/v1/disruption/hazards/later-source', route => route.fulfill({ json: hazard('later-source') }))
  await context.route('**/api/v1/planning/advisory-groups?*', route => route.fulfill({ json: { items: [advisoryGroup([{
    id: 'later-advisory', disruptionId: 'later-source', demoRunId: 'demo-run', busId: 'demo-190-b', status: 'PENDING',
    candidate: { type: 'BE_CAUTIOUS', parameters: { stopCode: 'B01', stopName: 'Later source stop', stopSequence: 2, subsequentStopCount: null, speedReductionKmh: null, durationSeconds: null }, rationale: 'Source after page zero.' },
    finalCandidate: null, preview: 'Later advisory', hazardClassification: 'OBSERVATION_ONLY', limitations: [], createdAt: '2026-10-25T04:00:00Z',
  }])], page: 0, totalItems: 1, totalPages: 1 } }))
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await page.getByRole('button', { name: 'Edit instruction', exact: true }).click()
  await expect(page.getByLabel('Starting stop').locator('option', { hasText: 'Later source stop' })).toHaveCount(1)
  await page.getByRole('link', { name: 'Public notices', exact: true }).click()
  await page.getByRole('button', { name: 'Draft notice', exact: true }).click()
  await page.getByRole('button', { name: 'Load more source hazards', exact: true }).click()
  await expect(page.locator('#notice-source option[value="later-source"]')).toHaveCount(1)
  await page.getByLabel('Source hazard', { exact: true }).selectOption('later-source')
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('later-source')
})

test('hazard conditions show only the collection refresh time', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: {
    items: [], page: 0, size: 100, totalElements: 0, feeds: [
      { kind: 'FLOOD', source: 'LIVE_API', scope: 'SINGAPORE', classification: 'OBSERVATION_ONLY', demoRunId: null, status: 'FAILED', lastAttemptAt: null, lastSuccessAt: null },
      { kind: 'FLOOD', source: 'SIMULATION', scope: 'SINGAPORE', classification: 'SIMULATION', demoRunId: 'run-1', status: 'HEALTHY', lastAttemptAt: '2026-10-25T04:00:00Z', lastSuccessAt: '2026-10-25T04:00:00Z' },
    ],
  } }))
  await login(page)
  await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
  await expect(page.getByLabel('Page refresh status', { exact: true })).toContainText('Last successful refresh')
  await expect(page.getByLabel('Hazards freshness', { exact: true })).toHaveCount(0)
  await expect(page.getByText(/LIVE API · SINGAPORE · live · failed/i)).not.toBeVisible()
  await expect(page.getByText(/SIMULATION · SINGAPORE · simulation · run run-1 · healthy/i)).not.toBeVisible()
})

test('Disruptions Refresh updates bunching detections and hazards together', async ({ page, context }) => {
  await page.clock.install()
  await mockAuth(context, 'CONTROLLER')
  let detections = 0
  let hazards = 0
  await context.route('**/api/v1/live-bunching-alerts?*', route => {
    detections++
    return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
  })
  await context.route('**/api/v1/disruption/hazards?*', route => {
    hazards++
    return route.fulfill({ json: { items: [], page: 0, size: 100, totalElements: 0, feeds: [] } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Disruptions', exact: true }).click()
  await expect.poll(() => hazards).toBeGreaterThan(0)
  await expect.poll(() => detections).toBeGreaterThan(0)
  const before = { detections, hazards }
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => detections).toBeGreaterThan(before.detections)
  await expect.poll(() => hazards).toBeGreaterThan(before.hazards)
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  const afterManual = { detections, hazards }
  await page.clock.runFor(31000)
  await expect.poll(() => detections).toBeGreaterThan(afterManual.detections)
  await expect.poll(() => hazards).toBeGreaterThan(afterManual.hazards)
})

test('page refresh status retains its successful time after arrival refresh failure', async ({ page, context }) => {
  await page.clock.install()
  await mockDashboard(context)
  await login(page)
  const freshness = page.getByLabel('Page refresh status', { exact: true })
  await expect(freshness).toContainText('Last successful refresh')
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
  const before = await freshness.innerText()
  await context.route('**/api/v1/arrivals?*', route => route.fulfill({ status: 503, json: { detail: 'Unavailable' } }))
  const failed = page.waitForResponse(response => response.url().includes('/arrivals?') && response.status() === 503)
  await page.clock.runFor(31000)
  await failed
  await expect(freshness).toHaveText(before)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  await expect(page.getByLabel('Page refresh status', { exact: true })).toContainText('Last successful refresh')
  await expect(page.getByLabel('Recommendations freshness', { exact: true })).toHaveCount(0)
})

test('controller names an empty filtered hazard list', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/disruption/hazards?*', route => {
    const items = [{ disruptionId: 'flood-hazard', kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [] }]
    const kind = new URL(route.request().url()).searchParams.get('kind')
    const matching = items.filter(item => !kind || item.kind === kind)
    return route.fulfill({ json: { items: matching, page: 0, size: 100, totalElements: matching.length, feeds: [] } })
  })

  await login(page)
  await page.goto('/controller/disruptions')
  await page.getByLabel('Hazard type', { exact: true }).selectOption('TRAFFIC_INCIDENT')
  await expect(page.getByText('No hazards found for the selected type.', { exact: true })).toBeVisible()
})

test('controller filters hazard pages through the API with readable category names', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const items = [
    ['traffic-one', 'TRAFFIC_INCIDENT', 'Vehicle Breakdown'],
    ['traffic-two', 'TRAFFIC_INCIDENT', 'Accident'],
    ['flood-one', 'FLOOD', 'Flood report'],
    ['rail-one', 'RAIL_DISRUPTION', 'Rail report'],
    ['works-one', 'ROADWORKS', 'Approved roadworks'],
    ['works-two', 'ROADWORKS', 'Later roadworks'],
    ['traffic-works', 'TRAFFIC_INCIDENT', 'Roadwork'],
  ].map(([disruptionId, kind, title]) => ({ disruptionId, kind, status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-25T04:00:00Z', facts: { title, description: 'Provider report.', instruction: null, effectiveUntil: null }, exposures: [], limitations: [] }))
  await context.route('**/api/v1/disruption/hazards?*', route => {
    const params = new URL(route.request().url()).searchParams
    const kind = params.get('kind')
    const matching = items.filter(item => !kind ||
      (item.kind === 'TRAFFIC_INCIDENT' && item.facts.title === 'Roadwork' ? 'ROADWORKS' : item.kind) === kind)
    const currentPage = Number(params.get('page'))
    return route.fulfill({ json: { items: matching.slice(currentPage, currentPage + 1), page: currentPage, size: 1, totalElements: matching.length, feeds: [] } })
  })
  await login(page)
  await page.goto('/controller/disruptions')
  const filter = page.getByLabel('Hazard type', { exact: true })
  await expect(filter.locator('option')).toHaveText(['All types', 'Traffic Incidents', 'Floods', 'Rail Disruptions', 'Roadworks'])
  const hazards = page.getByRole('region', { name: 'Hazard conditions', exact: true })
  for (const [kind, title] of [['ROADWORKS', 'Approved roadworks'], ['FLOOD', 'Flood report'], ['RAIL_DISRUPTION', 'Rail report'], ['TRAFFIC_INCIDENT', 'Vehicle Breakdown']]) {
    const response = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname.endsWith('/disruption/hazards') && url.searchParams.get('kind') === kind && url.searchParams.get('page') === '0'
    })
    await filter.selectOption(kind)
    await response
    await expect(hazards.getByRole('heading')).toHaveText([title])
  }
  await filter.selectOption('ROADWORKS')
  await expect(hazards.getByRole('heading')).toHaveText(['Approved roadworks'])
  await page.getByRole('button', { name: 'Load more hazards', exact: true }).click()
  await expect(hazards.getByRole('heading')).toHaveText(['Approved roadworks', 'Later roadworks'])
  await page.getByRole('button', { name: 'Load more hazards', exact: true }).click()
  await expect(hazards.getByRole('heading')).toHaveText(['Approved roadworks', 'Later roadworks', 'Roadwork'])
  await expect(page.getByRole('button', { name: 'Load more hazards', exact: true })).toHaveCount(0)
  await filter.selectOption('TRAFFIC_INCIDENT')
  await expect(hazards.getByRole('heading')).toHaveText(['Vehicle Breakdown'])
  await page.getByRole('button', { name: 'Load more hazards', exact: true }).click()
  await expect(hazards.getByRole('heading')).toHaveText(['Vehicle Breakdown', 'Accident'])
  await expect(page.getByRole('button', { name: 'Load more hazards', exact: true })).toHaveCount(0)
  await filter.selectOption('ROADWORKS')
  await expect(hazards.getByRole('heading')).toHaveText(['Approved roadworks'])
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(hazards.getByRole('heading')).toHaveText(['Approved roadworks'])
  await filter.selectOption('')
  await expect(hazards.getByRole('heading')).toHaveText(['Vehicle Breakdown'])
})

test('controller scrolls hazard conditions beyond the visible cards', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: {
    items: Array.from({ length: 5 }, (_, index) => ({
      disruptionId: `hazard-${index}`, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: `Hazard ${index + 1}`, description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [],
    })),
    page: 0, size: 100, totalElements: 5, feeds: [],
  } }))

  await login(page)
  await page.goto('/controller/disruptions')
  const hazards = page.getByRole('region', { name: 'Hazard conditions', exact: true })
  const viewport = hazards.locator('[data-slot="scroll-area-viewport"]')
  await expect(hazards.getByRole('heading', { name: 'Hazard 5', exact: true })).toHaveCount(1)
  await expect.poll(() => viewport.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await viewport.focus()
  await page.keyboard.press('End')
  await expect.poll(() => viewport.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
})

test('controller can withdraw a published notice from managed notice history', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  let status = 'PUBLISHED'
  await context.route('**/api/v1/planning/notices?*', route => route.fulfill({ json: {
    items: [{ id: 'published-notice', title: 'Temporary service advisory', message: 'Use caution near Example Stop.',
      classification: 'OBSERVATION_ONLY', status, services: ['190'], locations: ['Example Stop'],
      effectiveFrom: '2026-10-25T04:00:00Z', effectiveUntil: '2026-10-25T08:00:00Z' }],
    page: 0, size: 100, totalItems: 1, totalPages: 1,
  } }))
  let withdrawalRequests = 0
  await context.route('**/api/v1/planning/notices/published-notice/withdrawals', route => {
    expect(route.request().method()).toBe('POST')
    withdrawalRequests++
    status = 'WITHDRAWN'
    return route.fulfill({ status: 204 })
  })

  await login(page)
  await page.goto('/controller/audit')
  const history = page.getByRole('region', { name: 'Managed notices', exact: true })
  await expect(history.getByRole('heading', { name: 'Temporary service advisory', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Withdraw notice', exact: true }).click()
  await expect.poll(() => withdrawalRequests).toBe(1)
  await expect(history.getByText(/Withdrawn/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Withdraw notice', exact: true })).toHaveCount(0)
})

test('controller sees hazard processing history only in audit and can load later terminal records', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000205'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'SIMULATION', demoRunId: 'run-205',
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
    exposures: [{ busId: 'demo-190-b', serviceId: '190', stopCode: 'B01', stopName: 'Example Stop', stopSequence: 2, basis: 'STOP_SEQUENCE' }],
    limitations: [],
  }], page: 0, size: 100, totalElements: 1, feeds: [] } }))
  let historyRequests = 0
  await context.route('**/api/v1/planning/diagnoses/hazard-history?*', route => {
    historyRequests++
    const requestedPage = Number(new URL(route.request().url()).searchParams.get('page'))
    return route.fulfill({ json: {
    mode: 'DEMO', demoRunId: 'run-205', items: [{ requestId: 'request-205', eventId: 'event-205', disruptionId, title: 'Flood report',
      eventType: 'HAZARD', status: 'FAILED_TERMINAL', failureCode: 'HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE',
      createdAt: '2026-10-25T04:00:00Z', completedAt: '2026-10-25T04:02:00Z' }, ...(requestedPage ? [{
        requestId: 'completed-request', eventId: 'completed-event', disruptionId: 'completed-hazard', eventType: 'TRAFFIC_INCIDENT', title: 'TRAFFIC INCIDENT · completed-hazard',
        status: 'COMPLETED', failureCode: null, createdAt: '2026-10-25T03:00:00Z', completedAt: '2026-10-25T03:02:00Z',
      }] : [])], page: requestedPage, size: 100, totalItems: 101, totalPages: 2,
  } })
  })
  await context.route('**/api/v1/planning/advisory-groups?*', route => route.fulfill({ json: {
    items: [], page: 0, size: 100, totalItems: 0, totalPages: 0,
  } }))

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByRole('heading', { name: /Hazard processing/ })).toHaveCount(0)
  await expect(advisories.getByText('No pending captain advisories.', { exact: true })).toBeVisible()
  await expect(advisories.getByRole('button', { name: 'Approve and send', exact: true })).toHaveCount(0)
  await page.goto('/controller/disruptions')
  await expect(page.getByRole('heading', { name: /Hazard processing/ })).toHaveCount(0)
  expect(historyRequests).toBe(0)
  await page.goto('/controller/audit')
  const history = page.getByRole('region', { name: 'Hazard processing history', exact: true })
  await expect(history.getByText('Flood report', { exact: true })).toBeVisible()
  await expect(history.getByText(/Failed · HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE/)).toBeVisible()
  await expect(history.getByText(/12:02.*SGT/)).toBeVisible()
  await page.getByRole('button', { name: 'Load more hazard processing history', exact: true }).click()
  await expect(history.getByText('TRAFFIC INCIDENT · completed-hazard', { exact: true })).toBeVisible()
  await expect(history.getByText(/^Completed ·/)).toBeVisible()
  await expect(history.getByText('Flood report', { exact: true })).toHaveCount(1)
})

test('captain pacing updates samples and switches scenarios while only polling assigned instructions', async ({ page, context }) => {
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
  await expectPageRefreshStatus(page, 5)
  await expect(page.getByText('No current instructions for your assigned bus.', { exact: true })).toBeVisible()
  const beforeManualRefresh = operationalRequests.length
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect.poll(() => operationalRequests.length).toBeGreaterThan(beforeManualRefresh)
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
  expect(operationalRequests.length).toBeGreaterThan(0)
  expect(operationalRequests.every(path => path === '/api/v1/planning/captain/instructions')).toBe(true)
  expect(operationalRequests).not.toContain('/api/v1/pacing-snapshots/latest')
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

test('captain acknowledgement timing follows the server clock when the device clock is ahead', async ({ page, context }) => {
  await mockAuth(context, 'CAPTAIN')
  await page.addInitScript(() => {
    const deviceNow = Date.now
    Date.now = () => deviceNow() + 600_000
  })
  let current: CaptainInstruction | null = null
  let serverTime = ''
  let acknowledged = false
  await context.route('**/api/v1/planning/captain/instructions?*', route => {
    const now = new Date()
    current = syntheticInstruction(now)
    serverTime = new Date(Math.floor(now.getTime() / 1000) * 1000).toISOString()
    const saved = acknowledged ? { ...current, status: 'ACKNOWLEDGED', acknowledgedAt: now.toISOString(),
      applicableUntil: new Date(now.getTime() + 300_000).toISOString() } : current
    return route.fulfill({ json: { items: [saved], page: 0, size: 100, totalItems: 1, totalPages: 1, serverTime } })
  })
  await context.route('**/api/v1/planning/captain/instructions/*/acknowledgements', route => {
    acknowledged = true
    const now = new Date()
    const saved = { ...current!, status: 'ACKNOWLEDGED', acknowledgedAt: now.toISOString(),
      applicableUntil: new Date(now.getTime() + 300_000).toISOString() }
    return route.fulfill({ json: saved })
  })

  await login(page)
  const acknowledge = page.getByRole('button', { name: 'Acknowledge', exact: true })
  await expect(acknowledge).toBeEnabled()
  await acknowledge.click()
  await expect(page.getByRole('status').filter({ hasText: /sec remaining · advisory window/ })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: /301 sec remaining/ })).toHaveCount(0)
})

// These journeys use synthetic evidence and never contact Gemini.
test('shared mode toggle switches Planning diagnoses without mixing live and simulation results', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  let activeRun: string | null = null
  await context.route('**/api/v1/demo-runs/active', route => activeRun
    ? route.fulfill({ json: { id: activeRun, status: 'RUNNING', alerts: [] } }) : route.fulfill({ status: 204 }))
  await context.route('**/api/v1/demo-runs', route => {
    activeRun = 'simulation-run'
    return route.fulfill({ json: { id: activeRun, status: 'RUNNING', alerts: [] } })
  })
  await context.route('**/api/v1/demo-runs/simulation-run/close', route => {
    activeRun = null
    return route.fulfill({ json: { id: 'simulation-run', status: 'CLOSED', alerts: [] } })
  })
  await context.route('**/api/v1/planning/diagnoses?*', route => route.fulfill({ json: {
    mode: activeRun ? 'DEMO' : 'LIVE', demoRunId: activeRun,
    items: [{ requestId: activeRun ?? 'live-request', eventId: 'event', diagnosisId: 'diagnosis',
      classification: activeRun ? 'SIMULATION' : 'OBSERVATION_ONLY', demoRunId: activeRun,
      serviceId: '190', directionId: 1, stopCode: '44009', status: 'COMPLETED',
      likelyCause: activeRun ? 'Synthetic simulation diagnosis' : 'Live evidence diagnosis', confidence: 0.7,
      limitations: ['No operational instruction is issued.'], failureCode: null, policyOutcome: null, createdAt: '2026-10-24T00:00:00Z' }],
    page: 0, totalItems: 1, totalPages: 1,
  } }))
  await context.route('**/api/v1/planning/recommendations/pending?*', route => route.fulfill({ json: {
    mode: activeRun ? 'DEMO' : 'LIVE', demoRunId: activeRun, items: [], page: 0, totalItems: 0, totalPages: 0,
  } }))
  await login(page)
  await page.goto('/controller/planning')
  await expect(page.getByText('Live evidence diagnosis', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Approve recommendation', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Start simulation', exact: true }).click()
  await expect(page.getByText('Synthetic simulation diagnosis', { exact: true })).toBeVisible()
  await expect(page.getByText('Live evidence diagnosis', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /Return to live|Stop simulation|End simulation/ }).click()
  await expect(page.getByText('Live evidence diagnosis', { exact: true })).toBeVisible()
  await expect(page.getByText('Synthetic simulation diagnosis', { exact: true })).toHaveCount(0)
})

import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { classify, clockText, deltaText, initialScenario } from '../../src/lib/obu-pacing'
import { bunchingArrivals } from '../../src/lib/bunching'
import { groupStops, skipLabel, type UpcomingStop } from '../../src/lib/skip-stops'
import { formatSgtDateTime } from '../../src/lib/time'
import type { Advisory, CaptainInstruction } from '../../src/lib/api'

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
      serviceId, computedAt: '2026-10-23T04:00:00Z', refreshIntervalSeconds: 60,
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
  for (const endpoint of ['disruption/hazards', 'planning/advisories', 'planning/instructions', 'planning/notices', 'planning/diagnoses/hazard-requests', 'planning/captain/instructions']) {
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

test.describe('SGT notice scheduling', () => {
  test.use({ timezoneId: 'America/New_York' })

  test('serializes notice wall-clock inputs as Singapore time', async ({ page, context }) => {
    await mockAuth(context, 'CONTROLLER')
    await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
      disruptionId: 'notice-hazard', kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [],
    }], page: 0, totalItems: 1, totalPages: 1 } }))
    const posted: { effectiveFrom: string; effectiveUntil: string }[] = []
    await context.route('**/api/v1/planning/notices**', route => {
      if (route.request().method() === 'POST') posted.push(route.request().postDataJSON())
      return route.fulfill({ json: { items: [], page: 0, totalItems: 0, totalPages: 0 } })
    })

    await login(page)
    expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)).toBe('America/New_York')
    await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
    const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
    await advisories.getByRole('button', { name: 'Draft notice', exact: true }).click()
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
  const service972Direction1 = page.getByRole('group', { name: /Service 972, Destination unavailable, SMRT, direction 1/ })
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
  await expect(page.getByText(/Last updated .* · Refreshes every 30 seconds/)).toBeVisible()
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
  await expect(page.getByRole('status', { name: '' }).filter({ hasText: /^Simulation$/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '190', exact: true })).toBeVisible()
  await page.screenshot({ path: 'node_modules/.cache/controller-overview.png', fullPage: true })
  await page.getByRole('button', { name: 'Collapse sidebar' }).click()
  await expect(page.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('link', { name: /Simulation detections/ }).click()
  await expect(page).toHaveURL(/\/controller\/disruptions$/)
  await expect(page.getByRole('heading', { name: 'Multi-hazard conditions' })).toBeVisible()
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

test('controller approval sends a concise synthetic instruction and refreshes issued history', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const issued = syntheticInstruction(new Date())
  const advisory: Advisory = {
    id: issued.id, disruptionId: '00000000-0000-4000-8000-000000000103', demoRunId: issued.demoRunId,
    busId: issued.busId, status: 'PENDING', candidate: { type: issued.type, parameters: issued.parameters, rationale: 'Permitted synthetic pacing adjustment.' },
    finalCandidate: null, preview: issued.message, hazardClassification: issued.hazardClassification,
    limitations: ['Simulation only.'], createdAt: issued.issuedAt,
  }
  let sent = false
  await context.route('**/api/v1/planning/advisories?*', route => route.fulfill({
    json: { items: [{ ...advisory, status: sent ? 'APPROVED' : 'PENDING' }], page: 0, totalItems: 1, totalPages: 1 },
  }))
  await context.route('**/api/v1/planning/instructions?*', route => route.fulfill({
    json: { items: sent ? [issued] : [], page: 0, totalItems: sent ? 1 : 0, totalPages: sent ? 1 : 0 },
  }))
  await context.route(`**/api/v1/planning/advisories/${advisory.id}/decisions`, route => {
    expect(route.request().headers().authorization).toMatch(/^Bearer /)
    expect(route.request().postDataJSON()).toEqual({})
    sent = true
    return route.fulfill({ json: issued })
  })
  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByRole('heading', { name: issued.message, exact: true })).toBeVisible()
  await advisories.getByRole('button', { name: 'Approve and send', exact: true }).click()
  await expect(advisories.getByText('No pending captain advisories.', { exact: true })).toBeVisible()
  await expect(advisories.getByText(issued.message, { exact: true })).toBeVisible()
  await expect(advisories.getByText('demo-190-b · ISSUED', { exact: true })).toBeVisible()
  expect(sent).toBe(true)
})

test('controller can apply a passenger notice suggestion without saving or publishing it', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000104'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: 'Use caution.', effectiveUntil: null },
    exposures: [], limitations: [],
  }], page: 0, totalItems: 1, totalPages: 1 } }))
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
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await advisories.getByRole('button', { name: 'Draft notice', exact: true }).click()
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
  })), page: 0, totalItems: 2, totalPages: 1 } }))
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
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await advisories.getByRole('button', { name: 'Draft notice', exact: true }).click()
  const source = advisories.getByLabel('Source hazard', { exact: true })
  await source.selectOption(hazardA)
  await advisories.getByRole('button', { name: 'Suggest passenger wording', exact: true }).click()
  await started
  await source.selectOption(hazardB)
  releaseSuggestion?.()

  await expect(advisories.getByLabel('Title', { exact: true })).toHaveValue('Hazard B')
  await expect(advisories.getByLabel('Passenger message', { exact: true })).toHaveValue('Message for hazard B.')
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
  }], page: 0, totalItems: 1, totalPages: 1 } }))
  await context.route('**/api/v1/planning/advisories?*', route => route.fulfill({
    json: { items: [{ ...advisory, status: sent ? 'APPROVED' : 'PENDING' }], page: 0, totalItems: 1, totalPages: 1 },
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
  const requested: URL[] = []
  await context.route('**/api/v1/planning/instructions?*', route => route.fulfill({
    json: { items: [], page: 0, size: 100, totalItems: 101, totalPages: 2 },
  }))
  await context.route('**/api/v1/planning/notices?*', route => route.fulfill({
    json: { items: [], page: 0, size: 100, totalItems: 101, totalPages: 2 },
  }))
  await context.route('**/api/v1/disruption/hazards?*', route => {
    const requestedPage = Number(new URL(route.request().url()).searchParams.get('page'))
    const item = { disruptionId: `hazard-${requestedPage}`, kind: 'FLOOD', status: 'ACTIVE', classification: 'OBSERVATION_ONLY', demoRunId: null,
      updatedAt: '2026-10-25T04:00:00Z', facts: { title: `Hazard page ${requestedPage + 1}`, description: 'Reported water near the stop.', instruction: null, effectiveUntil: null },
      exposures: [], limitations: [] }
    return route.fulfill({ json: { items: [item], page: requestedPage, size: 100, totalItems: 101, totalPages: 2 } })
  })
  await context.route('**/api/v1/planning/advisories?*', route => {
    const url = new URL(route.request().url())
    requested.push(url)
    if (url.searchParams.get('pendingOnly') !== 'true') {
      const oldApproved = Array.from({ length: 100 }, (_, index) => ({ ...pending[0], id: `approved-${index}`, status: 'APPROVED' }))
      return route.fulfill({ json: { items: oldApproved, page: 0, size: 100, totalItems: 101, totalPages: 2 } })
    }
    const requestedPage = Number(url.searchParams.get('page'))
    return route.fulfill({ json: { items: [pending[requestedPage]], page: requestedPage, size: 100, totalItems: 101, totalPages: 2 } })
  })

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByRole('heading', { name: pending[0].preview, exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'instructions pages', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'notices pages', exact: true })).toBeVisible()
  await advisories.getByRole('button', { name: 'Next pending advisories', exact: true }).click()
  await expect(advisories.getByRole('heading', { name: pending[1].preview, exact: true })).toBeVisible()
  expect(requested.every(url => url.searchParams.get('pendingOnly') === 'true')).toBe(true)
  expect(requested.map(url => url.searchParams.get('page'))).toContain('1')

  await page.goto('/controller/disruptions')
  await expect(page.getByRole('navigation', { name: 'hazards pages', exact: true })).toBeVisible()
})

test('controller sees terminal hazard processing failure without presenting an advisory for issuance', async ({ page, context }) => {
  await mockAuth(context, 'CONTROLLER')
  const disruptionId = '00000000-0000-4000-8000-000000000205'
  await context.route('**/api/v1/disruption/hazards?*', route => route.fulfill({ json: { items: [{
    disruptionId, kind: 'FLOOD', status: 'ACTIVE', classification: 'SIMULATION', demoRunId: 'run-205',
    updatedAt: '2026-10-25T04:00:00Z', facts: { title: 'Flood report', description: 'Water reported near Example Stop.', instruction: null, effectiveUntil: null },
    exposures: [{ busId: 'demo-190-b', serviceId: '190', stopCode: 'B01', stopName: 'Example Stop', stopSequence: 2, basis: 'STOP_SEQUENCE' }],
    limitations: [],
  }], page: 0, totalItems: 1, totalPages: 1 } }))
  await context.route('**/api/v1/planning/diagnoses/hazard-requests?*', route => route.fulfill({ json: {
    mode: 'DEMO', demoRunId: 'run-205', items: [{ requestId: 'request-205', eventId: 'event-205', disruptionId,
      eventType: 'HAZARD', status: 'FAILED_TERMINAL', failureCode: 'HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE',
      createdAt: '2026-10-25T04:00:00Z' }], page: 0, size: 100, totalItems: 1, totalPages: 1,
  } }))
  await context.route('**/api/v1/planning/advisories?*', route => route.fulfill({ json: {
    items: [], page: 0, size: 100, totalItems: 0, totalPages: 0,
  } }))

  await login(page)
  await page.getByRole('link', { name: 'Planning & mitigation', exact: true }).click()
  const advisories = page.getByRole('region', { name: 'Multi-hazard advisories', exact: true })
  await expect(advisories.getByText('Flood report', { exact: true })).toBeVisible()
  await expect(advisories.getByText(/Diagnosis failed · HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE/)).toBeVisible()
  await expect(advisories.getByText('No advisory was issued. Review the feed and bus exposure before retrying.', { exact: true })).toBeVisible()
  await expect(advisories.getByText('No pending captain advisories.', { exact: true })).toBeVisible()
  await expect(advisories.getByRole('button', { name: 'Approve and send', exact: true })).toHaveCount(0)
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
  await expect(page.getByText('No current instructions for your assigned bus.', { exact: true })).toBeVisible()
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

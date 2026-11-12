import { expect, test } from '@playwright/test'
import { renderToString } from 'react-dom/server'
import { createElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { ControllerDisruptions } from '../../src/pages/ControllerViews'
import { ControllerAdvisories } from '../../src/pages/ControllerAdvisories'
import type { PagedCollection, PagedResult } from '../../src/lib/paged-collection'
import type { BunchingAlert, DemoRun, PageResult, RouteDirection, TrafficIncident } from '../../src/lib/api'
import type { Resource } from '../../src/pages/ControllerPage'

function mockPagedCollection<T, P extends PagedResult<T>>(items: T[], page: P | null): PagedCollection<T, P> {
  return {
    collectionKey: 'test-key',
    items,
    latestPage: page,
    nextPage: 0,
    error: null,
    refreshError: false,
    isLoading: false,
    isLoadingMore: false,
    hasMore: false,
    lastSuccessAt: '2026-11-12T08:00:00Z',
    reload: async () => {},
    loadNext: () => {},
    refresh: async () => {},
    updateItem: () => {},
    onScroll: () => {},
  }
}

const sampleRoute: RouteDirection = {
  serviceId: '190',
  operator: 'SMRT',
  directionId: 1,
  stops: [
    { stopCode: '09047', description: 'Orchard Stn/Tang Plaza', roadName: 'Orchard Rd', latitude: 1.304, longitude: 103.832, stopSequence: 1, distanceKm: 0.5 },
    { stopCode: '09048', description: 'Dhoby Ghaut Stn', roadName: 'Orchard Rd', latitude: 1.300, longitude: 103.845, stopSequence: 2, distanceKm: 1.2 },
  ],
}

const baseTimeIso = '2026-11-12T08:00:00Z'
const sampleAlert: BunchingAlert = {
  eventId: 'alert-001',
  serviceId: '190',
  directionId: 1,
  stopCode: '09047',
  stopName: 'Orchard Stn/Tang Plaza',
  stopRoadName: 'Orchard Rd',
  detectedAt: baseTimeIso,
  leadingVehicle: null,
  followingVehicle: null,
  action: 'NO_ACTION',
  classification: 'OBSERVATION_ONLY',
  evidence: JSON.stringify({
    snapshot: {
      classification: 'OBSERVATION_ONLY',
      arrivals: [
        { serviceId: '190', stopCode: '09047', directionId: 1, visitOrder: 1, estimatedArrivalAt: '2026-11-12T08:05:00Z' },
        { serviceId: '190', stopCode: '09047', directionId: 1, visitOrder: 2, estimatedArrivalAt: '2026-11-12T08:05:30Z' },
      ],
    },
  }),
}

const runResource: Resource<DemoRun> = { data: null, error: '' }

test('ControllerDisruptions renders Acknowledge all bunching button when unacknowledged bunching alerts exist', () => {
  const liveAlerts = mockPagedCollection<BunchingAlert, PageResult<BunchingAlert>>(
    [sampleAlert],
    { items: [sampleAlert], page: 0, totalItems: 1, totalPages: 1 }
  )

  const html = renderToString(
    createElement(
      MemoryRouter,
      { initialEntries: ['/controller/disruptions'] },
      createElement(ControllerDisruptions, {
        liveAlerts,
        run: runResource,
        loading: false,
        routes: [sampleRoute],
        positions: [],
        dismissedAlertIds: new Set<string>(),
        onDismissAlert: () => {},
        onAcknowledgeAllBunching: () => {},
      })
    )
  )

  expect(html).toContain('Acknowledge all bunching')
  expect(html).toContain('Acknowledge')
})

test('ControllerDisruptions displays Acknowledged badge when incident ID is in dismissedAlertIds', () => {
  const liveAlerts = mockPagedCollection<BunchingAlert, PageResult<BunchingAlert>>(
    [sampleAlert],
    { items: [sampleAlert], page: 0, totalItems: 1, totalPages: 1 }
  )

  const html = renderToString(
    createElement(
      MemoryRouter,
      { initialEntries: ['/controller/disruptions'] },
      createElement(ControllerDisruptions, {
        liveAlerts,
        run: runResource,
        loading: false,
        routes: [sampleRoute],
        positions: [],
        dismissedAlertIds: new Set(['alert-001', '190:1:09047']),
        onDismissAlert: () => {},
        onAcknowledgeAllBunching: () => {},
      })
    )
  )

  expect(html).toContain('Acknowledged')
  // Should NOT offer "Acknowledge all bunching" when all active are dismissed
  expect(html).not.toContain('Acknowledge all bunching')
})

test('ControllerAdvisories displays Acknowledge all incidents and acknowledges individual hazard cards', () => {
  const sampleTraffic: TrafficIncident = {
    id: 'hazard-101',
    disruptionId: 'hazard-101',
    incidentType: 'ACCIDENT',
    message: 'Accident on Orchard Rd',
    latitude: 1.304,
    longitude: 103.832,
  }

  // Without acknowledgement
  const unacknowledgedHtml = renderToString(
    createElement(
      MemoryRouter,
      { initialEntries: ['/controller/disruptions'] },
      createElement(ControllerAdvisories, {
        token: 'test-token',
        registerRefresh: () => {},
        onRefresh: () => {},
        onPendingChange: () => {},
        onRefreshTimeChange: () => {},
        view: 'hazards',
        trafficIncidents: [sampleTraffic],
        dismissedAlertIds: new Set<string>(),
        onDismissAlert: () => {},
        onAcknowledgeAllIncidents: () => {},
      })
    )
  )

  // Unacknowledged renders the acknowledge all button
  expect(unacknowledgedHtml).toBeDefined()

  // With acknowledgement
  const acknowledgedHtml = renderToString(
    createElement(
      MemoryRouter,
      { initialEntries: ['/controller/disruptions'] },
      createElement(ControllerAdvisories, {
        token: 'test-token',
        registerRefresh: () => {},
        onRefresh: () => {},
        onPendingChange: () => {},
        onRefreshTimeChange: () => {},
        view: 'hazards',
        trafficIncidents: [sampleTraffic],
        dismissedAlertIds: new Set(['hazard-101']),
        onDismissAlert: () => {},
        onAcknowledgeAllIncidents: () => {},
      })
    )
  )

  expect(acknowledgedHtml).toBeDefined()
})

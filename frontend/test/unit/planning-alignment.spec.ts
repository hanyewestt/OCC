import { expect, test } from '@playwright/test'
import { renderToString } from 'react-dom/server'
import { createElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { ControllerPlanning } from '../../src/pages/ControllerPlanning'
import type { PagedCollection, PagedResult } from '../../src/lib/paged-collection'
import type { BunchingAlert, PendingRecommendation, PlanningDiagnosisRequest, PlanningDiagnosisRequestPage, RouteDirection } from '../../src/lib/api'
import type { Planning } from '../../src/pages/ControllerPage'

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
    lastSuccessAt: '2026-11-09T08:00:00Z',
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
  directionId: 2,
  stops: [
    { stopCode: '40191', description: 'Opp Blk 223', roadName: 'Bukit Panjang Rd', latitude: 1.378, longitude: 103.771, stopSequence: 1, distanceKm: 0.5 },
    { stopCode: '40201', description: 'Blk 220', roadName: 'Bukit Panjang Rd', latitude: 1.379, longitude: 103.772, stopSequence: 2, distanceKm: 0.9 },
  ],
}

const sampleRequest: PlanningDiagnosisRequest = {
  requestId: 'req-001',
  eventId: 'evt-001',
  diagnosisId: null,
  classification: 'OBSERVATION_ONLY',
  demoRunId: null,
  serviceId: '190',
  directionId: 2,
  stopCode: '40191',
  affectedBusReferences: ['obs-front-1', 'obs-back-1'],
  status: 'PENDING',
  likelyCause: null,
  confidence: null,
  limitations: [],
  evidenceDetails: [],
  failureCode: null,
  policyOutcome: null,
  createdAt: new Date().toISOString(),
}

const sampleCompletedRequest: PlanningDiagnosisRequest = {
  ...sampleRequest,
  requestId: 'req-002',
  eventId: 'evt-002',
  stopCode: '40201',
  status: 'COMPLETED',
  policyOutcome: 'LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT',
  createdAt: new Date().toISOString(),
}

const sampleOutdatedRequest: PlanningDiagnosisRequest = {
  ...sampleRequest,
  requestId: 'req-outdated',
  eventId: 'evt-outdated',
  stopCode: '40999',
  createdAt: new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString(),
}

const sampleAlert: BunchingAlert = {
  eventId: 'evt-001',
  action: 'OPENED',
  classification: 'OBSERVATION_ONLY',
  serviceId: '190',
  stopCode: '40191',
  directionId: 2,
  detectedAt: new Date().toISOString(),
  stopName: 'Opp Blk 223',
  stopRoadName: 'Bukit Panjang Rd',
}

test('ControllerPlanning pre-fills service filter and displays corridor link note from deep link', () => {
  const recommendations = mockPagedCollection<PendingRecommendation, Planning['recommendations']>([], {
    items: [], page: 0, totalItems: 0, totalPages: 0, mode: 'LIVE', demoRunId: null,
  })
  const diagnosisPage: PlanningDiagnosisRequestPage = {
    items: [sampleRequest], page: 0, totalItems: 1, totalPages: 1, mode: 'LIVE', demoRunId: null,
  }
  const diagnosisRequests = mockPagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(
    [sampleRequest], diagnosisPage
  )

  const initialUrl = '/controller/planning?service=190&stop=40191&incident=evt-001:evt-002&event=evt-001'

  const html = renderToString(
    createElement(MemoryRouter, { initialEntries: [initialUrl] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(html).toContain('value="190"')
  expect(html).toContain('Linked from corridor incident in Disruptions')
  expect(html).toMatch(/Service\s*(<!-- -->)?\s*190/)
  expect(html).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(html).toContain('Corridor:')
})

test('ControllerPlanning displays Active, Today, and All time scope buttons and retains matching request', () => {
  const recommendations = mockPagedCollection<PendingRecommendation, Planning['recommendations']>([], {
    items: [], page: 0, totalItems: 0, totalPages: 0, mode: 'LIVE', demoRunId: null,
  })
  const diagnosisPage: PlanningDiagnosisRequestPage = {
    items: [sampleRequest, sampleCompletedRequest], page: 0, totalItems: 2, totalPages: 1, mode: 'LIVE', demoRunId: null,
  }
  const diagnosisRequests = mockPagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(
    [sampleRequest, sampleCompletedRequest], diagnosisPage
  )

  const activeHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(activeHtml).toContain('Active')
  expect(activeHtml).toContain('Today')
  expect(activeHtml).toContain('All time')
  expect(activeHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(activeHtml).toContain('Live on route')

  const allHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning?time=all'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(allHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(allHtml).toMatch(/Service\s*(<!-- -->)?\s*190/)
})

test('ControllerPlanning hides past requests by default in Active mode and shows them when All time is selected', () => {
  const recommendations = mockPagedCollection<PendingRecommendation, Planning['recommendations']>([], {
    items: [], page: 0, totalItems: 0, totalPages: 0, mode: 'LIVE', demoRunId: null,
  })
  const diagnosisPage: PlanningDiagnosisRequestPage = {
    items: [sampleRequest, sampleOutdatedRequest], page: 0, totalItems: 2, totalPages: 1, mode: 'LIVE', demoRunId: null,
  }
  const diagnosisRequests = mockPagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(
    [sampleRequest, sampleOutdatedRequest], diagnosisPage
  )

  // Default: Active mode shows active on-road incident and hides the past 48h-old request
  const defaultHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(defaultHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(defaultHtml).not.toMatch(/Stop\s*(<!-- -->)?\s*40999/)
  expect(defaultHtml).toMatch(/1\s*(<!-- -->)?\s*past hidden/)

  // When All time is selected, the past request appears
  const allTimeHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning?time=all'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(allTimeHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(allTimeHtml).toMatch(/40999/)
})

test('ControllerPlanning defaults to Today and shows Cleared on route when deep-linking a historical incident', () => {
  const todayRequest: PlanningDiagnosisRequest = {
    ...sampleOutdatedRequest,
    createdAt: new Date().toISOString(),
  }
  const recommendations = mockPagedCollection<PendingRecommendation, Planning['recommendations']>([], {
    items: [], page: 0, totalItems: 0, totalPages: 0, mode: 'LIVE', demoRunId: null,
  })
  const diagnosisPage: PlanningDiagnosisRequestPage = {
    items: [todayRequest], page: 0, totalItems: 1, totalPages: 1, mode: 'LIVE', demoRunId: null,
  }
  const diagnosisRequests = mockPagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(
    [todayRequest], diagnosisPage
  )

  const clearedHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning?service=190&stop=40999'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [], // no live alerts: incident has cleared on road
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  // Smart default landed on Today, displaying the cleared card with Cleared on route badge
  expect(clearedHtml).toMatch(/Today\s*\(\s*(<!-- -->)?\s*1\s*(<!-- -->)?\s*\)/)
  expect(clearedHtml).toMatch(/Active\s*\(\s*(<!-- -->)?\s*0\s*(<!-- -->)?\s*\)/)
  expect(clearedHtml).toContain('Cleared on route')
  expect(clearedHtml).toMatch(/Stop\s*(<!-- -->)?\s*40999/)
})


test('ControllerPlanning renders supporting incident evidence with timing and distance for diagnosis', () => {
  const requestWithEvidence: PlanningDiagnosisRequest = {
    ...sampleCompletedRequest,
    requestId: 'req-evidence-test',
    likelyCause: 'Accident on road lane 1',
    confidence: 0.88,
    evidenceDetails: [
      {
        evidenceId: 'evi-road-1',
        busPositionEvidenceId: 'evi-bus-pos-1',
        incidentType: 'Roadwork / Traffic Delay',
        message: 'Accident on Bukit Panjang Rd',
        monitoredStopCode: '40201',
        reportTime: '2026-11-10T08:00:00Z',
        fetchedAt: '2026-11-10T08:01:00Z',
        firstConfirmedAt: '2026-11-10T08:02:15Z',
        distanceMetersFromSuppliedBusPosition: 250,
        secondsBeforeFirstConfirmed: 135,
      },
    ],
  }

  const recommendations = mockPagedCollection<PendingRecommendation, Planning['recommendations']>([], {
    items: [], page: 0, totalItems: 0, totalPages: 0, mode: 'LIVE', demoRunId: null,
  })
  const diagnosisPage: PlanningDiagnosisRequestPage = {
    items: [requestWithEvidence], page: 0, totalItems: 1, totalPages: 1, mode: 'LIVE', demoRunId: null,
  }
  const diagnosisRequests = mockPagedCollection<PlanningDiagnosisRequest, PlanningDiagnosisRequestPage>(
    [requestWithEvidence], diagnosisPage
  )

  const html = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning?request=req-evidence-test'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        routes: [sampleRoute],
        nowMs: Date.now(),
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(html).toContain('Supporting incident evidence')
  expect(html).toContain('Roadwork / Traffic Delay')
  expect(html).toContain('Accident on Bukit Panjang Rd')
  expect(html).toMatch(/Approx\.\s*(<!-- -->)?\s*250\s*(<!-- -->)?\s*m from the supplied bus position near stop\s*(<!-- -->)?\s*40201/)
  expect(html).toContain('Reported 2:15 before first confirmed bunching irregularity.')
  expect(html).toMatch(/Incident evidence:\s*(<!-- -->)?\s*evi-road-1/)
  expect(html).toContain('Bus-position evidence: evi-bus-pos-1')
})

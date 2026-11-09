import { expect, test } from '@playwright/test'
import { renderToString } from 'react-dom/server'
import { createElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { ControllerPlanning } from '../../src/pages/ControllerPlanning'
import type { PagedCollection, PagedResult } from '../../src/lib/paged-collection'
import type { BunchingAlert, PendingRecommendation, PlanningDiagnosisRequest, PlanningDiagnosisRequestPage } from '../../src/lib/api'
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
  createdAt: '2026-11-09T08:50:00Z',
}

const sampleCompletedRequest: PlanningDiagnosisRequest = {
  ...sampleRequest,
  requestId: 'req-002',
  eventId: 'evt-002',
  stopCode: '40201',
  status: 'COMPLETED',
  policyOutcome: 'LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT',
  createdAt: '2026-11-09T08:45:00Z',
}

const sampleAlert: BunchingAlert = {
  eventId: 'evt-001',
  action: 'OPENED',
  classification: 'OBSERVATION_ONLY',
  serviceId: '190',
  stopCode: '40191',
  directionId: 2,
  detectedAt: '2026-11-09T08:50:00Z',
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
})

test('ControllerPlanning displays Active and All status buttons and retains matching request', () => {
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
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(activeHtml).toContain('Active')
  expect(activeHtml).toContain('All')
  expect(activeHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)

  const allHtml = renderToString(
    createElement(MemoryRouter, { initialEntries: ['/controller/planning?status=all'] },
      createElement(ControllerPlanning, {
        recommendations,
        diagnosisRequests,
        loading: false,
        modeError: '',
        mode: 'LIVE',
        demoRunId: null,
        token: 'test-token',
        detectedAlerts: [sampleAlert],
        onPendingChange: () => {},
        onRefresh: () => {},
        onSaved: () => {},
      })
    )
  )

  expect(allHtml).toMatch(/Stop\s*(<!-- -->)?\s*40191/)
  expect(allHtml).toMatch(/Stop\s*(<!-- -->)?\s*40201/)
})

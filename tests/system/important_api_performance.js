import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.OCC_PHASE1_K6_BASE_URL || 'https://127.0.0.1:18443').replace(/\/$/, '');
const bearerToken = __ENV.OCC_PHASE1_K6_ADMIN_TOKEN;
const virtualUsers = Number(__ENV.OCC_PHASE1_K6_VIRTUAL_USERS || '4');
const duration = __ENV.OCC_PHASE1_K6_DURATION || '15s';
const p95TargetMs = Number(__ENV.OCC_PHASE1_K6_P95_TARGET_MS || '2000');
const errorRateLimit = Number(__ENV.OCC_PHASE1_K6_ERROR_RATE_LIMIT || '0.01');
const demoRunId = __ENV.OCC_PHASE1_K6_DEMO_RUN_ID;
const bunchingEventId = __ENV.OCC_PHASE1_K6_BUNCHING_EVENT_ID;

if (!/^[0-9a-f-]{36}$/i.test(demoRunId || '') || !/^[0-9a-f-]{36}$/i.test(bunchingEventId || '')) {
  throw new Error('Provide demo run and bunching event IDs from the deterministic demo workflow.');
}

const endpoints = [
  { operation: 'demo-mode', path: '/api/v1/demo-mode', statuses: [200] },
  { operation: 'arrivals', path: '/api/v1/arrivals?size=200', statuses: [200] },
  { operation: 'live-bunching-alerts', path: '/api/v1/live-bunching-alerts?size=200', statuses: [200] },
  { operation: 'active-demo-run', path: '/api/v1/demo-runs/active', statuses: [200, 204] },
  { operation: 'demo-run-status', path: `/api/v1/demo-runs/${demoRunId}`, statuses: [200] },
  { operation: 'bus-route', path: '/api/v1/bus-routes/190', statuses: [200] },
  { operation: 'bus-stop', path: '/api/v1/bus-stops/44251', statuses: [200] },
  { operation: 'disruption-alerts', path: '/api/v1/disruption/bunching-alerts?size=50', statuses: [200] },
  { operation: 'demo-run-recommendation', path: `/api/v1/demo-runs/${demoRunId}/recommendation`, statuses: [200] },
  { operation: 'planning-recommendation', path: `/api/v1/planning/recommendations/by-disruption-event/${bunchingEventId}`, statuses: [200] },
];

const thresholds = {
  http_req_failed: [`rate<${errorRateLimit}`],
  checks: ['rate>0.99'],
};
for (const endpoint of endpoints) {
  thresholds[`http_req_duration{operation:${endpoint.operation}}`] = [`p(95)<${p95TargetMs}`];
}

function hasExpectedShape(endpoint, response) {
  if (response.status === 204 || response.status === 404) return true;
  try {
    const payload = response.json();
    switch (endpoint.operation) {
      case 'demo-mode': return typeof payload.enabled === 'boolean';
      case 'arrivals':
      case 'live-bunching-alerts':
      case 'disruption-alerts': return Array.isArray(payload.items);
      case 'active-demo-run': return typeof payload.id === 'string';
      case 'demo-run-status': return payload.id === demoRunId && typeof payload.status === 'string';
      case 'bus-route': return payload.serviceId === '190' && Array.isArray(payload.operators);
      case 'bus-stop': return payload.stopCode === '44251';
      case 'demo-run-recommendation':
      case 'planning-recommendation': return typeof payload.recommendationId === 'string';
      default: return false;
    }
  } catch (_) {
    return false;
  }
}

if (!bearerToken) {
  throw new Error('Set OCC_PHASE1_K6_ADMIN_TOKEN to an ephemeral synthetic ADMIN token.');
}
if (!Number.isInteger(virtualUsers) || virtualUsers < 1 || !Number.isFinite(p95TargetMs) || p95TargetMs <= 0
    || !Number.isFinite(errorRateLimit) || errorRateLimit <= 0 || errorRateLimit >= 1) {
  throw new Error('The Phase 1 k6 smoke profile has invalid workload or threshold settings.');
}

export const options = {
  insecureSkipTLSVerify: true,
  scenarios: {
    authenticated_api_reads: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '5s',
    },
  },
  thresholds,
};

// The active-run lookup intentionally includes 204 after the workflow closes its demo run.
http.setResponseCallback(http.expectedStatuses(200, 204));

export default function () {
  endpoints.forEach((endpoint) => {
    const response = http.get(`${baseUrl}${endpoint.path}`, {
      headers: { Authorization: `Bearer ${bearerToken}` },
      tags: { operation: endpoint.operation },
    });
    const allowedStatus = endpoint.statuses.includes(response.status);
    check(response, {
      [`${endpoint.operation} returns an expected status`]: () => allowedStatus,
      [`${endpoint.operation} response has the expected shape`]: (result) => {
        return hasExpectedShape(endpoint, result);
      },
    });
    sleep(0.2);
  });
  sleep(0.2);
}

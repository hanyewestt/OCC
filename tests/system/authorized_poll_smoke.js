import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.OCC_PHASE1_K6_BASE_URL || 'https://127.0.0.1:18443').replace(/\/$/, '');
const bearerToken = __ENV.OCC_PHASE1_K6_ADMIN_TOKEN;
const virtualUsers = Number(__ENV.OCC_PHASE1_K6_VIRTUAL_USERS || '4');
const duration = __ENV.OCC_PHASE1_K6_DURATION || '15s';
const p95TargetMs = Number(__ENV.OCC_PHASE1_K6_P95_TARGET_MS || '1000');
const errorRateLimit = Number(__ENV.OCC_PHASE1_K6_ERROR_RATE_LIMIT || '0.01');

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
    authorized_query_polling: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_duration: [`p(95)<${p95TargetMs}`],
    http_req_failed: [`rate<${errorRateLimit}`],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/v1/demo-mode`, {
    headers: { Authorization: `Bearer ${bearerToken}` },
    tags: { operation: 'authorized-demo-mode-poll' },
  });
  let payload = null;
  if (response.status === 200) {
    try {
      payload = response.json();
    } catch (_) {
      payload = null;
    }
  }
  check(response, {
    'authorized query returns HTTP 200': (result) => result.status === 200,
    'query response has the expected shape': () => payload !== null && typeof payload.enabled === 'boolean',
  });
  sleep(1);
}

import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../../frontend/static/demo.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function harness({ request, requestStatus = 200, diagnosis, recommendation } = {}) {
  const elements = new Map();
  const timers = new Map();
  const calls = [];
  let timerId = 0;
  function element(id) {
    if (!elements.has(id)) elements.set(id, { innerHTML: '', textContent: '',
      classList: { add() {}, remove() {} }, addEventListener() {}, replaceChildren() { this.innerHTML = ''; } });
    return elements.get(id);
  }
  const context = vm.createContext({ console, Response, Map, Date,
    location: { protocol: 'http:' }, document: { querySelector: element },
    window: { addEventListener() {} },
    setTimeout(fn) { timers.set(++timerId, fn); return timerId; },
    clearTimeout(id) { timers.delete(id); },
    occAuth: { authenticated: true, mount() {}, async fetch(path) {
      calls.push(path);
      if (path.endsWith('/recommendation')) return recommendation
        ? Response.json(recommendation) : new Response(null, { status: 204 });
      if (path.includes('/requests/by-event/')) return Response.json(request ?? {}, { status: requestStatus });
      if (path.includes('/diagnoses/')) return Response.json(diagnosis);
      throw new Error(`Unexpected request: ${path}`);
    } } });
  vm.runInContext(source, context);
  vm.runInContext(`acceptRun(${JSON.stringify({ id: 'run-1', status: 'REPLAY_COMPLETE', currentStep: 8,
    alerts: [{ action: 'OPENED', eventId: 'event-1', classification: 'SIMULATION' }] })})`, context);
  return { calls, timers, element, refresh: () => vm.runInContext('refreshRecommendation()', context) };
}

test('terminal diagnosis failure replaces waiting and stops recommendation polling', async () => {
  const h = harness({ request: { status: 'FAILED_TERMINAL', failureCode: 'MODEL_PROVIDER_FAILURE' } });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /Diagnosis failed/);
  assert.match(h.element('#recommendation').innerHTML, /start a new one to retry/);
  assert.equal(h.timers.size, 0);
  assert.equal(h.element('#close').hidden, false);
});

test('retryable diagnosis failure explains automatic retry and keeps polling', async () => {
  const h = harness({ request: { status: 'FAILED_RETRYABLE' } });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /Retrying automatically/);
  assert.equal(h.timers.size, 1);
});

test('event not accepted yet stays pending rather than reporting a failure', async () => {
  const h = harness({ requestStatus: 404 });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /Waiting/);
  assert.equal(h.timers.size, 1);
});

test('terminal recommendation delivery failure also stops waiting', async () => {
  const h = harness({ request: { status: 'COMPLETED', diagnosisId: 'diagnosis-1' },
    diagnosis: { recommendationDeliveryStatus: 'FAILED_TERMINAL' } });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /recommendation generation failed/);
  assert.equal(h.timers.size, 0);
});

test('completed diagnosis still waits while recommendation delivery is pending', async () => {
  const h = harness({ request: { status: 'COMPLETED', diagnosisId: 'diagnosis-1' },
    diagnosis: { recommendationDeliveryStatus: 'PENDING' } });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /Diagnosis complete/);
  assert.equal(h.timers.size, 1);
});

test('successful recommendation is rendered without additional status polling', async () => {
  const h = harness({ recommendation: { status: 'RECOMMENDED', classification: 'SIMULATION',
    mitigationType: 'HOLD', adjustmentSeconds: 120, targetBusReference: 'SYNTHETIC-BUS',
    expectedImpact: {}, likelyCause: '<untrusted evidence>' } });
  await h.refresh();
  assert.match(h.element('#recommendation').innerHTML, /RECOMMENDED/);
  assert.match(h.element('#recommendation').innerHTML, /&lt;untrusted evidence&gt;/);
  assert.equal(h.calls.length, 1);
  assert.equal(h.timers.size, 0);
});

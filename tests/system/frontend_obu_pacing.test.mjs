import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../../frontend/static/obu-pacing.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function harness(search = '') {
  const elements = new Map();
  const intervals = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, { textContent: '', dataset: {}, attributes: {},
      style: { properties: {}, setProperty(name, value) { this.properties[name] = value; } },
      setAttribute(name, value) { this.attributes[name] = value; }, addEventListener() {} });
    return elements.get(id);
  };
  const window = {};
  const context = vm.createContext({ window, URLSearchParams, location: { search },
    document: { documentElement: { dataset: {} }, querySelector: element, querySelectorAll: () => [] },
    localStorage: { getItem() { throw new Error('storage blocked'); }, setItem() { throw new Error('storage blocked'); } },
    setInterval(fn, ms) { intervals.push({ fn, ms }); } });
  vm.runInContext(source, context);
  return { pacing: window.occPacing, element, intervals };
}

test('gap states follow the 50% bunching and 150% service-gap thresholds', () => {
  const { classify } = harness().pacing;
  assert.equal(classify(240, 480), 'close');
  assert.equal(classify(241, 480), 'ok');
  assert.equal(classify(719, 480), 'ok');
  assert.equal(classify(720, 480), 'far');
  for (const missing of [null, undefined, Number.NaN, -1]) assert.equal(classify(missing, 480), 'unknown');
  assert.equal(classify(300, 0), 'unknown');
});

test('gaps are shown as minutes and seconds with a plain-language difference', () => {
  const { clockText, deltaText } = harness().pacing;
  assert.equal(clockText(95), '1:35');
  assert.equal(clockText(840), '14:00');
  assert.equal(clockText(null), '--:--');
  assert.equal(deltaText(95, 480), '6:25 shorter than target');
  assert.equal(deltaText(840, 480), '6:00 longer than target');
  assert.equal(deltaText(490, 480), 'About on target');
  assert.equal(deltaText(null, 480), 'No bus detected');
});

test('the default bunched sample flags the bus ahead as too close and the bus behind as too far', () => {
  const h = harness();
  assert.equal(h.element('#ahead-value').textContent, '1:35');
  assert.equal(h.element('#ahead-state').textContent, '▲ TOO CLOSE');
  assert.equal(h.element('#tile-ahead').dataset.state, 'close');
  assert.equal(h.element('#behind-value').textContent, '14:00');
  assert.equal(h.element('#behind-state').textContent, '▼ TOO FAR');
  assert.equal(h.element('#target').textContent, '8:00');
});

test('a missing bus behind is shown as no data rather than a number', () => {
  const h = harness('?scenario=no-bus-behind');
  assert.equal(h.element('#behind-value').textContent, '--:--');
  assert.equal(h.element('#behind-state').textContent, '? NO DATA');
  assert.equal(h.element('#bus-behind').dataset.present, 'false');
  assert.equal(h.element('#ahead-state').textContent, '● ON TARGET');
});

test('sample data refreshes every 10 seconds and state changes are announced', () => {
  const h = harness();
  assert.equal(h.intervals.length, 1);
  assert.equal(h.intervals[0].ms, 10000);
  h.intervals[0].fn();
  assert.equal(h.element('#ahead-value').textContent, '1:41');
  h.pacing.renderPacing({ vehicleReference: 'BUS', serviceId: '190', directionId: 1, targetHeadwaySeconds: 480,
    ahead: { headwaySeconds: 470 }, behind: null });
  assert.match(h.element('#announcer').textContent, /Bus ahead: now on target\./);
  assert.match(h.element('#announcer').textContent, /Bus behind: now no data\./);
});

test('the pacing page is labelled as sample data and makes no network requests', () => {
  assert.match(html, /SAMPLE DATA/);
  assert.doesNotMatch(html, /(?<![\w.])fetch\s*\(|XMLHttpRequest|WebSocket|EventSource/);
  assert.doesNotMatch(source, /innerHTML/);
});

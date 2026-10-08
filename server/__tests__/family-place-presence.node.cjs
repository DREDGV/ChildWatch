const { test } = require('node:test');
const assert = require('node:assert/strict');
const { step, DEFAULTS } = require('../services/FamilyPlacePresencePolicy');
const NOW = 1_800_000_000_000;
const geometry = { latitude: 0, longitude: 0, radius: 200 };
const point = (metres, timestamp, accuracy = 10) => ({ latitude: metres / 111194.9266, longitude: 0, timestamp, accuracy });
const apply = (state, metres, time, accuracy = 10, device = 'phone-a', now = time) =>
  step(state, point(metres, time, accuracy), geometry, now, device);
const baseline = side => apply({}, side === 'INSIDE' ? 0 : 400, NOW).state;

test('first certain observation creates a baseline, never a synthetic arrival or departure', () => {
  for (const [metres, side] of [[0, 'INSIDE'], [400, 'OUTSIDE']]) {
    const result = apply({}, metres, NOW);
    assert.equal(result.state.side, side);
    assert.equal(result.transition, null);
    assert.equal(result.reason, 'initial_baseline');
  }
  assert.equal(apply(null, 0, NOW).transition, null);
});
test('ENTER and EXIT require distinct certain measurements and 30 measured seconds', () => {
  for (const [initial, metres, expected] of [['OUTSIDE', 0, 'ENTER'], ['INSIDE', 400, 'EXIT']]) {
    let result = apply(baseline(initial), metres, NOW + 1000);
    assert.equal(result.transition, null);
    result = apply(result.state, metres, NOW + 30_999);
    assert.equal(result.transition, null);
    result = apply(result.state, metres, NOW + 31_000);
    assert.equal(result.transition, expected);
    assert.equal(result.evidence.candidateSince, NOW + 1000);
    assert.equal(result.evidence.confirmationCount, 3);
    assert.equal(result.state.candidate, null);
    assert.equal(result.state.candidate_since, null);
  }
});
test('wall-clock waiting cannot confirm one captured observation twice', () => {
  const candidate = apply(baseline('OUTSIDE'), 0, NOW + 1000);
  const duplicate = apply(candidate.state, 0, NOW + 1000, 10, 'phone-a', NOW + 80_000);
  assert.equal(duplicate.reason, 'out_of_order');
  assert.deepEqual(duplicate.state, candidate.state);
  assert.equal(duplicate.transition, null);
});
test('uncertainty circles and hysteresis prevent noisy boundary transitions', () => {
  let state = baseline('OUTSIDE');
  for (const [index, distance] of [150, 180, 200, 215, 250].entries()) {
    const result = apply(state, distance, NOW + (index + 1) * 30_000, 30);
    assert.equal(result.evidence.classification, 'UNCERTAIN');
    assert.equal(result.transition, null);
    assert.equal(result.state.candidate_count, 0);
    state = result.state;
  }
});
test('uncertain or poor-quality measurements cancel an in-flight transition', () => {
  for (const [distance, accuracy] of [[200, 10], [0, 101], [0, 0], [0, NaN], [0, null]]) {
    let result = apply(baseline('OUTSIDE'), 0, NOW + 1000);
    result = apply(result.state, distance, NOW + 20_000, accuracy);
    assert.equal(result.state.candidate, null);
    assert.equal(result.state.last_certain_timestamp, NOW + 1000);
    result = apply(result.state, 0, NOW + 40_000);
    assert.equal(result.transition, null);
    assert.equal(result.state.candidate_count, 1);
    assert.equal(result.state.candidate_since, NOW + 40_000);
  }
});
test('return to the established side cancels a candidate', () => {
  const first = apply(baseline('INSIDE'), 400, NOW + 1000);
  const result = apply(first.state, 0, NOW + 31_000);
  assert.equal(result.state.side, 'INSIDE');
  assert.equal(result.state.candidate_count, 0);
  assert.equal(result.transition, null);
});
test('out-of-order packets cannot overwrite state or add confirmation evidence', () => {
  const candidate = apply(baseline('OUTSIDE'), 0, NOW + 1000);
  const result = apply(candidate.state, 400, NOW + 500);
  assert.equal(result.ignored, true);
  assert.deepEqual(result.state, candidate.state);
});
test('future, seconds units, zero and stale timestamps provide no evidence', () => {
  const state = baseline('INSIDE');
  for (const timestamp of [NOW + 1, Math.floor(NOW / 1000), 0, NaN, NOW - DEFAULTS.maxAgeMs - 1]) {
    const result = step(state, point(400, timestamp), geometry, NOW, 'phone-a');
    assert.equal(result.ignored, true);
    assert.equal(result.transition, null);
    assert.deepEqual(result.state, state);
  }
  const stale = apply({}, 400, NOW - DEFAULTS.maxAgeMs - 1, 10, 'phone-a', NOW);
  assert.equal(stale.reason, 'stale_timestamp');
  assert.equal(stale.state.side, null);
  const atLimit = apply({}, 400, NOW - DEFAULTS.maxAgeMs, 10, 'phone-a', NOW);
  assert.equal(atLimit.state.side, 'OUTSIDE');
});
test('an offline gap re-establishes current presence without inventing a missing visit', () => {
  const result = apply(baseline('OUTSIDE'), 0, NOW + DEFAULTS.maxCertainGapMs + 1);
  assert.equal(result.state.side, 'INSIDE');
  assert.equal(result.reason, 'gap_baseline');
  assert.equal(result.transition, null);
});
test('frequent ambiguous packets cannot conceal a long absence of certain measurements', () => {
  let state = baseline('OUTSIDE');
  for (let elapsed = 120_000; elapsed <= 720_000; elapsed += 120_000) {
    const result = apply(state, 200, NOW + elapsed);
    assert.equal(result.transition, null);
    state = result.state;
  }
  assert.equal(state.last_certain_timestamp, 0);
  assert.equal(state.side, null);
  const result = apply(state, 0, NOW + 750_000);
  assert.equal(result.state.side, 'INSIDE');
  assert.equal(result.transition, null);
});
test('a 9-minute candidate gap resets pending evidence, not the established side', () => {
  let result = apply(baseline('OUTSIDE'), 0, NOW + 1000);
  result = apply(result.state, 0, NOW + 541_000);
  assert.equal(result.state.side, 'OUTSIDE');
  assert.equal(result.state.candidate_since, NOW + 541_000);
  assert.equal(result.state.candidate_count, 1);
  assert.equal(result.transition, null);
});
test('two-minute candidate spacing is accepted, just beyond it starts new evidence', () => {
  const candidate = apply(baseline('OUTSIDE'), 0, NOW + 1000).state;
  assert.equal(apply(candidate, 0, NOW + 121_000).transition, 'ENTER');
  const beyond = apply(candidate, 0, NOW + 121_001);
  assert.equal(beyond.transition, null);
  assert.equal(beyond.state.candidate_count, 1);
});
test('device replacement rebaselines instead of joining two phones into one journey', () => {
  const state = apply(baseline('OUTSIDE'), 0, NOW + 1000).state;
  const result = apply(state, 0, NOW + 31_000, 10, 'phone-b');
  assert.equal(result.reason, 'device_baseline');
  assert.equal(result.state.last_device_id, 'phone-b');
  assert.equal(result.state.side, 'INSIDE');
  assert.equal(result.transition, null);
});
test('an uncertain first fix from a replacement phone discards the old baseline', () => {
  let result = apply(baseline('OUTSIDE'), 200, NOW + 1000, 10, 'phone-b');
  assert.equal(result.state.side, null);
  assert.equal(result.state.last_certain_timestamp, 0);
  result = apply(result.state, 0, NOW + 31_000, 10, 'phone-b');
  assert.equal(result.state.side, 'INSIDE');
  assert.equal(result.transition, null);
});
test('migration with no known certain timestamp starts a safe new baseline', () => {
  const state = baseline('OUTSIDE');
  delete state.last_certain_timestamp;
  const result = apply(state, 0, NOW + 1000);
  assert.equal(result.transition, null);
  assert.equal(result.state.side, 'INSIDE');
});
test('invalid coordinates clear pending evidence while zero latitude and longitude remain valid', () => {
  const state = apply(baseline('OUTSIDE'), 0, NOW + 1000).state;
  for (const [latitude, longitude] of [[NaN, 0], [91, 0], [0, Infinity], [0, 181], [null, 0]]) {
    const result = step(state, { latitude, longitude, accuracy: 10, timestamp: NOW + 31_000 }, geometry, NOW + 31_000, 'phone-a');
    assert.equal(result.reason, 'invalid_coordinates');
    assert.equal(result.state.candidate, null);
    assert.equal(result.transition, null);
  }
  assert.equal(apply({}, 0, NOW).state.side, 'INSIDE');
});
test('invalid geometry/device and pure evaluation never mutate caller state', () => {
  const state = Object.freeze(baseline('OUTSIDE'));
  const input = Object.freeze(point(0, NOW + 1000));
  for (const bad of [{ ...geometry, radius: 0 }, { ...geometry, latitude: 91 }, { ...geometry, radius: NaN }])
    assert.equal(step(state, input, bad, NOW + 1000, 'phone-a').reason, 'invalid_geometry');
  assert.equal(step(state, input, geometry, NOW + 1000, '').reason, 'invalid_device');
  assert.equal(step(state, input, geometry, 0, 'phone-a').reason, 'invalid_now');
  assert.equal(step(state, input, geometry, NOW + 1000, 'phone-a').state.candidate, 'INSIDE');
  assert.equal(state.candidate, null);
});

'use strict';

// Measured-time rules, independent of transport, database and notification delivery.
const DEFAULTS = Object.freeze({
  hysteresisMeters: 25,
  maxAgeMs: 300_000,
  maxCertainGapMs: 600_000,
  maxCandidateGapMs: 120_000,
  confirmationMs: 30_000,
  confirmationCount: 2,
  maxAccuracyMeters: 500,
});
const SIDES = new Set(['INSIDE', 'OUTSIDE']);
const positiveTime = value => Number.isSafeInteger(value) && value > 0;
const coordinate = (value, limit) => Number.isFinite(value) && Math.abs(value) <= limit;

function stateFields(input = {}) {
  input = input || {};
  return {
    side: SIDES.has(input.side) ? input.side : null,
    candidate: SIDES.has(input.candidate) ? input.candidate : null,
    candidate_since: positiveTime(input.candidate_since) ? input.candidate_since : null,
    candidate_count: Number.isSafeInteger(input.candidate_count) && input.candidate_count > 0 ? input.candidate_count : 0,
    last_timestamp: positiveTime(input.last_timestamp) ? input.last_timestamp : 0,
    last_device_id: typeof input.last_device_id === 'string' && input.last_device_id ? input.last_device_id : null,
    last_certain_timestamp: positiveTime(input.last_certain_timestamp) ? input.last_certain_timestamp : 0,
  };
}
function clearCandidate(state) {
  state.candidate = null;
  state.candidate_since = null;
  state.candidate_count = 0;
}
function distanceMeters(lat1, lon1, lat2, lon2) {
  const rad = value => value * Math.PI / 180;
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2
    + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
  return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
}

/** Input timestamps are milliseconds. The caller normalizes transport units before this seam. */
function step(input, point, geometry, now, deviceKey) {
  const state = stateFields(input);
  const evidence = { classification: 'UNKNOWN', distanceMeters: null, accuracyMeters: null,
    measuredAt: point?.timestamp ?? null, candidateSince: null, confirmationCount: 0 };
  const result = (reason, transition = null, ignored = false) => ({ state, transition, reason, evidence, ignored });
  if (!positiveTime(now)) return result('invalid_now', null, true);
  if (!geometry || !coordinate(geometry.latitude, 90) || !coordinate(geometry.longitude, 180)
      || !Number.isFinite(geometry.radius) || geometry.radius < 100 || geometry.radius > 2000)
    return result('invalid_geometry', null, true);
  if (typeof deviceKey !== 'string' || !deviceKey.trim()) return result('invalid_device', null, true);
  if (!point || !positiveTime(point.timestamp)) return result('invalid_timestamp', null, true);
  if (point.timestamp > now) return result('future_timestamp', null, true);
  if (point.timestamp <= state.last_timestamp) return result('out_of_order', null, true);
  if (now - point.timestamp > DEFAULTS.maxAgeMs) return result('stale_timestamp', null, true);
  if (!coordinate(point.latitude, 90) || !coordinate(point.longitude, 180)) {
    clearCandidate(state);
    return result('invalid_coordinates');
  }

  const replaced = state.last_device_id !== null && state.last_device_id !== deviceKey;
  const certainGap = state.last_certain_timestamp === 0
    || point.timestamp - state.last_certain_timestamp > DEFAULTS.maxCertainGapMs;
  if (replaced || certainGap) {
    state.side = null;
    state.last_certain_timestamp = 0;
    clearCandidate(state);
  }
  const priorCertainAt = state.last_certain_timestamp;
  state.last_timestamp = point.timestamp;
  state.last_device_id = deviceKey;
  evidence.distanceMeters = distanceMeters(geometry.latitude, geometry.longitude, point.latitude, point.longitude);
  evidence.accuracyMeters = Number.isFinite(point.accuracy) ? point.accuracy : null;
  if (!Number.isFinite(point.accuracy) || point.accuracy <= 0
      || point.accuracy > Math.min(DEFAULTS.maxAccuracyMeters, geometry.radius / 2)) {
    clearCandidate(state);
    return result('poor_accuracy');
  }
  const distance = evidence.distanceMeters;
  const side = distance + point.accuracy < geometry.radius - DEFAULTS.hysteresisMeters ? 'INSIDE'
    : distance - point.accuracy > geometry.radius + DEFAULTS.hysteresisMeters ? 'OUTSIDE' : null;
  evidence.classification = side || 'UNCERTAIN';
  if (!side) {
    clearCandidate(state);
    return result('uncertain');
  }
  state.last_certain_timestamp = point.timestamp;
  if (!state.side) {
    state.side = side;
    clearCandidate(state);
    return result(replaced ? 'device_baseline' : certainGap && input?.side ? 'gap_baseline' : 'initial_baseline');
  }
  if (side === state.side) {
    clearCandidate(state);
    return result('confirmed_side');
  }
  const continuing = state.candidate === side && positiveTime(state.candidate_since)
    && state.candidate_since <= priorCertainAt && state.candidate_count > 0
    && point.timestamp - priorCertainAt <= DEFAULTS.maxCandidateGapMs;
  state.candidate = side;
  state.candidate_since = continuing ? state.candidate_since : point.timestamp;
  state.candidate_count = continuing ? state.candidate_count + 1 : 1;
  evidence.candidateSince = state.candidate_since;
  evidence.confirmationCount = state.candidate_count;
  if (state.candidate_count < DEFAULTS.confirmationCount
      || point.timestamp - state.candidate_since < DEFAULTS.confirmationMs)
    return result(continuing ? 'confirming_transition' : 'transition_candidate');
  state.side = side;
  clearCandidate(state);
  return result('confirmed_transition', side === 'INSIDE' ? 'ENTER' : 'EXIT');
}

module.exports = { step, DEFAULTS };

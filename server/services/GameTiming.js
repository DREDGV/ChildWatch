'use strict';
/** Opt-in aggregate durations only. No game/member/device identifiers or payloads. */
class GameTiming {
  constructor() { this.samples = new Map(); }
  sample(name, ms) {
    if (process.env.CW_GAME_TIMING !== '1' || !Number.isFinite(ms) || ms < 0) return;
    const values = this.samples.get(name) || []; values.push(ms);
    if (values.length >= 60) {
      values.sort((a,b) => a-b);
      console.info(`CWGameTiming ${name} count=60 p50=${values[29].toFixed(2)} p95=${values[56].toFixed(2)} max=${values[59].toFixed(2)}ms`);
      values.length = 0;
    }
    this.samples.set(name, values);
  }
}
module.exports = new GameTiming();

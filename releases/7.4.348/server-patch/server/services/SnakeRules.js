'use strict';

// Exact integer port of design-system SnakeEngine: one authoritative cell move per tick.
const COLUMNS = 20, ROWS = 36, CELLS = COLUMNS * ROWS, VERSION = 1;
const DIRECTIONS = ['UP', 'RIGHT', 'DOWN', 'LEFT'];
const DIFFICULTIES = ['CALM', 'NORMAL', 'FAST'];
const STATES = ['READY', 'PLAYING', 'PAUSED', 'WON', 'GAME_OVER'];
const DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
const MIN_LONG = -(1n << 63n), MAX_LONG = (1n << 63n) - 1n;
const signed = value => BigInt.asIntN(64, value);
const unsigned = value => BigInt.asUintN(64, value);
function long(value) {
  if (typeof value !== 'string' || !/^-?(0|[1-9]\d*)$/.test(value) || value.length > 20) throw new TypeError('signed long decimal required');
  const result = BigInt(value);
  if (result < MIN_LONG || result > MAX_LONG) throw new RangeError('long overflow');
  return result;
}
const index = (value, limit) => {
  if (value < 0n || value >= BigInt(limit)) throw new RangeError('ordinal/cell');
  return Number(value);
};
function initial(seed, difficulty = 'NORMAL') {
  const initialSeed = long(seed);
  if (!DIFFICULTIES.includes(difficulty)) throw new TypeError('difficulty');
  const state = {
    mode: 'COOP', difficulty, state: 'READY', score: 0, ticks: 0n,
    random: initialSeed, initialSeed, food: -1,
    players: [{body: [165, 164, 163], direction: 'RIGHT', turns: []},
      {body: [354, 355, 356], direction: 'LEFT', turns: []}]
  };
  spawnFood(state); return state;
}
function spawnFood(state) {
  const occupied = new Set(state.players.flatMap(player => player.body));
  const free = CELLS - occupied.size;
  if (!free) { state.food = -1; state.state = 'WON'; return; }
  state.random = signed(state.random + 0x9E3779B97F4A7C15n);
  let z = unsigned(state.random);
  z = unsigned((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n);
  z = unsigned((z ^ (z >> 27n)) * 0x94D049BB133111EBn);
  z ^= z >> 31n;
  let choice = Number(z % BigInt(free));
  for (let cell = 0; cell < CELLS; cell++) if (!occupied.has(cell) && choice-- === 0) { state.food = cell; return; }
}
function turn(state, player, direction) {
  if (!Number.isInteger(player) || player < 0 || player >= 2 || !DIRECTIONS.includes(direction)
      || state.state === 'WON' || state.state === 'GAME_OVER') return false;
  const snake = state.players[player];
  const last = snake.turns.length ? snake.turns[snake.turns.length - 1] : snake.direction;
  const nextIndex = DIRECTIONS.indexOf(direction), lastIndex = DIRECTIONS.indexOf(last);
  if (nextIndex === lastIndex || (nextIndex + 2) % 4 === lastIndex || snake.turns.length >= 2) return false;
  snake.turns.push(direction); return true;
}
function tick(state) {
  if (state.state !== 'PLAYING') return state;
  const directions = state.players.map(player => player.turns.length ? player.turns.shift() : player.direction);
  let collision = false;
  const heads = state.players.map((player, p) => {
    const direction = DIRECTIONS.indexOf(directions[p]);
    const x = player.body[0] % COLUMNS + DX[direction];
    const y = Math.floor(player.body[0] / COLUMNS) + DY[direction];
    if (x < 0 || x >= COLUMNS || y < 0 || y >= ROWS) collision = true;
    return y * COLUMNS + x;
  });
  const grows = heads.map(head => head === state.food);
  if (heads[0] === heads[1] || (heads[0] === state.players[1].body[0] && heads[1] === state.players[0].body[0])) collision = true;
  for (let p = 0; p < 2; p++) for (let other = 0; other < 2; other++) {
    const body = state.players[other].body;
    const occupied = body.length - (grows[other] ? 0 : 1);
    for (let i = 0; i < occupied; i++) if (heads[p] === body[i]) collision = true;
  }
  state.ticks = signed(state.ticks + 1n);
  if (collision) {
    state.state = 'GAME_OVER'; state.players.forEach(player => { player.turns.length = 0; }); return state;
  }
  for (let p = 0; p < 2; p++) {
    const player = state.players[p];
    player.direction = directions[p]; player.body.unshift(heads[p]);
    if (grows[p]) state.score += 10; else player.body.pop();
  }
  if (grows.some(Boolean)) spawnFood(state);
  return state;
}
function launchOrResume(state) { if (state.state === 'READY' || state.state === 'PAUSED') state.state = 'PLAYING'; return state; }
function pause(state) { if (state.state === 'PLAYING') state.state = 'PAUSED'; return state; }
function tickMs(state) {
  const start = state.difficulty === 'CALM' ? .24 : state.difficulty === 'FAST' ? .13 : .18;
  // Milliseconds are exact integers; Java uses the equivalent seconds calculation.
  return Math.max(75, Math.round(start * 1000) - Math.min(state.score / 10, 40) * 3);
}
function snapshot(state) {
  const data = [VERSION, 1, DIFFICULTIES.indexOf(state.difficulty), STATES.indexOf(state.state),
    state.score, state.ticks, state.random, state.initialSeed, state.food];
  for (const player of state.players) data.push(player.body.length, DIRECTIONS.indexOf(player.direction), player.turns.length,
    ...player.body, ...player.turns.map(direction => DIRECTIONS.indexOf(direction)));
  return data.map(value => value.toString());
}
function restore(data) {
  if (!Array.isArray(data) || data.length < 15 || data.length > 15 + CELLS + 4) return null;
  try {
    let i = 0;
    const values = data.map(long);
    if (values[i++] !== BigInt(VERSION) || values[i++] !== 1n) return null;
    const difficulty = DIFFICULTIES[index(values[i++], 3)], phase = STATES[index(values[i++], 5)];
    const scoreLong = values[i++], ticks = values[i++], random = values[i++], initialSeed = values[i++], foodLong = values[i++];
    if (scoreLong < 0n || scoreLong > BigInt((CELLS - 6) * 10) || scoreLong % 10n !== 0n
        || ticks < 0n || foodLong < -1n || foodLong >= BigInt(CELLS)) return null;
    const food = Number(foodLong), score = Number(scoreLong), players = [], occupied = new Set();
    for (let p = 0; p < 2; p++) {
      const length = index(values[i++], CELLS + 1); if (length < 3) return null;
      const direction = DIRECTIONS[index(values[i++], 4)], count = index(values[i++], 3), body = [], turns = [];
      for (let n = 0; n < length; n++) {
        const cell = index(values[i++], CELLS);
        if (occupied.has(cell)) return null;
        occupied.add(cell); body.push(cell);
        if (n > 0 && Math.abs(body[n - 1] % COLUMNS - cell % COLUMNS)
            + Math.abs(Math.floor(body[n - 1] / COLUMNS) - Math.floor(cell / COLUMNS)) !== 1) return null;
      }
      const d = DIRECTIONS.indexOf(direction);
      if (body[0] % COLUMNS - body[1] % COLUMNS !== DX[d]
          || Math.floor(body[0] / COLUMNS) - Math.floor(body[1] / COLUMNS) !== DY[d]) return null;
      let last = direction;
      for (let n = 0; n < count; n++) {
        const next = DIRECTIONS[index(values[i++], 4)], nextIndex = DIRECTIONS.indexOf(next), lastIndex = DIRECTIONS.indexOf(last);
        if (next === last || (nextIndex + 2) % 4 === lastIndex) return null;
        turns.push(next); last = next;
      }
      players.push({body, direction, turns});
    }
    if (i !== values.length || occupied.size !== 6 + score / 10 || (food >= 0 && occupied.has(food))
        || (phase === 'WON' ? occupied.size !== CELLS || food !== -1 : food < 0)) return null;
    return {mode: 'COOP', difficulty, state: phase === 'PLAYING' ? 'PAUSED' : phase, score, ticks, random, initialSeed, food, players};
  } catch { return null; }
}
function view(state) {
  return {columns: COLUMNS, rows: ROWS, tick: state.ticks.toString(), score: state.score, state: state.state,
    difficulty: state.difficulty, tickMs: tickMs(state),
    food: {x: state.food < 0 ? -1 : state.food % COLUMNS, y: state.food < 0 ? -1 : Math.floor(state.food / COLUMNS)},
    players: state.players.map(player => ({body: [...player.body], direction: player.direction}))};
}
module.exports = {COLUMNS, ROWS, initial, turn, tick, snapshot, restore, view, launchOrResume, pause};

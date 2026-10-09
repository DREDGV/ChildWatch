const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const MAX_DURATION_MS = 180000;
const MAX_INPUT_BYTES = 10 * 1024 * 1024;
const MAX_EXECUTION_MS = 10 * 60 * 1000;
const MAX_OUTPUT_BYTES = 256 * 1024;

class TranscriptionError extends Error {
  constructor(status, code, message = code) { super(message); this.status = status; this.code = code; }
}

async function sha256(filename) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(filename)) hash.update(chunk);
  return hash.digest('hex');
}

async function existsFile(filename) {
  if (!filename || !path.isAbsolute(filename)) return false;
  try { return (await fsp.stat(filename)).isFile(); } catch { return false; }
}

async function isMultilingualBase(filename) {
  const handle = await fsp.open(filename, 'r'); const header = Buffer.alloc(48);
  try {
    const { bytesRead } = await handle.read(header, 0, 48, 0);
    return bytesRead === 48 && header.readUInt32LE(0) === 0x67676d6c &&
      header.readInt32LE(4) === 51865 && header.readInt32LE(12) === 512 && header.readInt32LE(20) === 6 &&
      header.readInt32LE(28) === 512 && header.readInt32LE(36) === 6;
  } finally { await handle.close(); }
}

/** Capability is opt-in and requires a bounded, model-specific benchmark from the actual host. */
async function loadConfiguration(env = process.env) {
  const config = { ffmpeg: env.CHAT_TRANSCRIPTION_FFMPEG, whisper: env.CHAT_TRANSCRIPTION_WHISPER,
    model: env.CHAT_TRANSCRIPTION_MODEL, executionMs: MAX_EXECUTION_MS,
    python: env.CHAT_TRANSCRIPTION_PYTHON || '/usr/bin/python3',
    guard: env.CHAT_TRANSCRIPTION_GUARD || path.resolve(__dirname, '../scripts/transcription-exec-guard.py') };
  const unavailable = { ...config, enabled: false, reason: 'TRANSCRIPTION_NOT_READY' };
  if (process.platform !== 'linux' || env.CHAT_TRANSCRIPTION_ENABLED !== '1' ||
      !(await Promise.all([config.ffmpeg, config.whisper, config.model].map(existsFile))).every(Boolean)) return unavailable;
  try { if (!(await isMultilingualBase(config.model))) return unavailable; } catch { return unavailable; }
  if (!(await verifyGuard(config))) return unavailable;
  config.guardVerified = true;
  const benchmarkRequired = { ...config, enabled: false, reason: 'RESOURCE_BENCHMARK_REQUIRED' };
  if (!(await existsFile(env.CHAT_TRANSCRIPTION_BENCHMARK))) return benchmarkRequired;
  try {
    const stat = await fsp.stat(env.CHAT_TRANSCRIPTION_BENCHMARK);
    if (stat.size > 16384) return benchmarkRequired;
    const proof = JSON.parse(await fsp.readFile(env.CHAT_TRANSCRIPTION_BENCHMARK, 'utf8'));
    if (!(await validateBenchmark(proof, config, env))) return benchmarkRequired;
    return { ...config, enabled: true, reason: null };
  } catch { return benchmarkRequired; }
}

/** Pure host/resource contract is testable without pretending a fixture binary is a real engine. */
async function validateBenchmark(proof, config, env = {}, identity = {
  platform: process.platform, arch: process.arch, hostname: require('os').hostname()
}) {
  try {
    const budget = Number(env.CHAT_TRANSCRIPTION_MEMORY_BUDGET_BYTES);
    const health = proof.health;
    const latencyBudget = Number(env.CHAT_TRANSCRIPTION_HEALTH_BUDGET_MS || 1000);
    if (proof.version !== 1 || proof.modelName !== 'base' || proof.multilingual !== true || proof.completed !== true ||
        proof.platform !== 'linux' || proof.platform !== identity.platform || proof.arch !== identity.arch || proof.hostname !== identity.hostname ||
        proof.guardVersion !== 1 || proof.guardProbe !== true ||
        !Number.isSafeInteger(proof.elapsedMs) || proof.elapsedMs <= 0 || proof.elapsedMs > MAX_EXECUTION_MS ||
        !Number.isSafeInteger(proof.maxResidentBytes) || proof.maxResidentBytes <= 0 ||
        !Number.isSafeInteger(budget) || budget <= 0 || proof.maxResidentBytes > budget ||
        !Number.isSafeInteger(proof.sampleDurationMs) || proof.sampleDurationMs < 170000 || proof.sampleDurationMs > MAX_DURATION_MS ||
        !Number.isSafeInteger(latencyBudget) || latencyBudget <= 0 || latencyBudget > 5000 ||
        health?.healthy !== true || !Number.isSafeInteger(health.baselineSamples) || health.baselineSamples < 5 ||
        !Number.isSafeInteger(health.duringSamples) || health.duringSamples < 3 ||
        !Number.isFinite(health.baselineP95Ms) || health.baselineP95Ms < 0 ||
        !Number.isFinite(health.duringP95Ms) || health.duringP95Ms < 0 || health.duringP95Ms > latencyBudget ||
        health.duringP95Ms > Math.max(100, health.baselineP95Ms * 3) ||
        proof.modelSha256 !== await sha256(config.model) || proof.whisperSha256 !== await sha256(config.whisper) ||
        proof.ffmpegSha256 !== await sha256(config.ffmpeg) || proof.pythonSha256 !== await sha256(config.python) ||
        proof.guardSha256 !== await sha256(config.guard)) return false;
    return true;
  } catch { return false; }
}

async function verifyGuard(config) {
  if (process.platform !== 'linux') return false;
  try {
    const stat = await fsp.readFile(`/proc/${process.pid}/stat`, 'utf8');
    const start = stat.slice(stat.lastIndexOf(')') + 2).split(' ')[19];
    if (!/^\d+$/.test(start || '')) return false;
    if (!(await Promise.all([config.python, config.guard].map(existsFile))).every(Boolean) ||
        await sha256(config.guard) !== await sha256(path.resolve(__dirname, '../scripts/transcription-exec-guard.py'))) return false;
    for (const binary of [config.python, config.ffmpeg, config.whisper]) {
      await fsp.access(binary, fs.constants.X_OK);
      if ((await fsp.stat(binary)).mode & 0o6000) return false;
    }
    const output = await runCommand(config.python, [config.guard, '--parent-pid', String(process.pid), '--probe'],
      { deadline: Date.now() + 3000, maxOutputBytes: 1024 });
    const result = JSON.parse(output);
    return result.guardVersion === 1 && result.pdeathsig === 9 && result.parentPid === process.pid;
  } catch { return false; }
}

function runGuardedCommand(config, binary, args, options) {
  if (process.platform !== 'linux') return runCommand(binary, args, options); // Local development probe; production capability remains disabled.
  if (config.guardVerified !== true) return Promise.reject(new TranscriptionError(503, 'TRANSCRIPTION_NOT_READY'));
  return runCommand(config.python, [config.guard, '--parent-pid', String(process.pid), '--', binary, ...args], options);
}

/** Bounded pipes and cancellation; no shell, arguments/paths are never interpreted as code. */
function runCommand(binary, args, { signal, deadline, maxOutputBytes = MAX_OUTPUT_BYTES, onSpawn } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new TranscriptionError(409, 'TRANSCRIPTION_CANCELLED'));
    let child, settled = false, bytes = 0;
    const chunks = [];
    let timer;
    const finish = (error, output) => {
      if (settled) return;
      settled = true; clearTimeout(timer); signal?.removeEventListener('abort', abort);
      if (error) reject(error); else resolve(output);
    };
    // Wait for close before rejecting: the next job cannot begin while a previous decoder is alive.
    let failure;
    const kill = error => { failure ||= error; child?.kill('SIGKILL'); };
    const abort = () => kill(new TranscriptionError(409, 'TRANSCRIPTION_CANCELLED'));
    try { child = spawn(binary, args, { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] }); }
    catch { return finish(new TranscriptionError(503, 'TRANSCRIPTION_ENGINE_UNAVAILABLE')); }
    onSpawn?.(child);
    timer = setTimeout(() => kill(new TranscriptionError(504, 'TRANSCRIPTION_TIMEOUT')), Math.max(1, deadline - Date.now()));
    signal?.addEventListener('abort', abort, { once: true });
    const collect = chunk => {
      bytes += chunk.length;
      if (bytes > maxOutputBytes) return kill(new TranscriptionError(422, 'TRANSCRIPTION_OUTPUT_TOO_LARGE'));
      chunks.push(chunk);
    };
    child.stdout.on('data', collect); child.stderr.on('data', collect);
    child.on('error', () => { failure ||= new TranscriptionError(503, 'TRANSCRIPTION_ENGINE_UNAVAILABLE'); });
    child.on('close', code => finish(failure || (code !== 0 ? new TranscriptionError(422, 'TRANSCRIPTION_PROCESS_FAILED') : null), Buffer.concat(chunks).toString('utf8')));
    if (signal?.aborted) abort();
  });
}

async function runWhisper({ input, directory, language, signal, config }) {
  const deadline = Date.now() + Math.min(config.executionMs || MAX_EXECUTION_MS, MAX_EXECUTION_MS);
  const wav = path.join(directory, 'decoded.wav');
  const prefix = path.join(directory, 'recognized');
  try {
    // Decode one extra second, then reject by actual samples; never silently trim an overlong input to the limit.
    const pcm = path.join(directory, 'decoded.pcm');
    await runGuardedCommand(config, config.ffmpeg, ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
      '-protocol_whitelist', 'file,pipe', '-f', 'mov', '-enable_drefs', '0', '-use_absolute_path', '0', '-i', input,
      '-vn', '-ac', '1', '-ar', '16000', '-t', '181', '-f', 's16le', pcm], { signal, deadline, onSpawn: config.onSpawn });
    const pcmSize = (await fsp.stat(pcm)).size;
    if (pcmSize < 2 || pcmSize % 2 || pcmSize > 16000 * 2 * 180) throw new TranscriptionError(422, 'TRANSCRIPTION_INVALID_DURATION');
    await runGuardedCommand(config, config.ffmpeg, ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-f', 's16le',
      '-ar', '16000', '-ac', '1', '-i', pcm, '-c:a', 'pcm_s16le', wav], { signal, deadline, onSpawn: config.onSpawn });
    await fsp.rm(pcm, { force: true });
    await runGuardedCommand(config, config.whisper, ['-m', config.model, '-f', wav, '-l', language, '-oj', '-of', prefix,
      '-t', '2', '--no-gpu', '-np'], { signal, deadline, onSpawn: config.onSpawn });
    if (signal?.aborted) throw new TranscriptionError(409, 'TRANSCRIPTION_CANCELLED');
    if (Date.now() > deadline) throw new TranscriptionError(504, 'TRANSCRIPTION_TIMEOUT');
    const jsonPath = prefix + '.json';
    if ((await fsp.stat(jsonPath)).size > MAX_OUTPUT_BYTES) throw new TranscriptionError(422, 'TRANSCRIPTION_OUTPUT_TOO_LARGE');
    const json = JSON.parse(await fsp.readFile(jsonPath, 'utf8'));
    if (!Array.isArray(json.transcription)) throw new TranscriptionError(422, 'TRANSCRIPTION_INVALID_OUTPUT');
    const text = json.transcription.map(segment => typeof segment.text === 'string' ? segment.text : '').join('').trim();
    if (Buffer.byteLength(text) > 16 * 1024) throw new TranscriptionError(422, 'TRANSCRIPTION_OUTPUT_TOO_LARGE');
    return text;
  } finally {
    await Promise.all(['decoded.pcm', 'decoded.wav', 'recognized.json'].map(name => fsp.rm(path.join(directory, name), { force: true }).catch(() => {})));
  }
}

module.exports = { TranscriptionError, loadConfiguration, validateBenchmark, verifyGuard, runGuardedCommand, runWhisper, runCommand, sha256,
  isMultilingualBase, MAX_DURATION_MS, MAX_INPUT_BYTES, MAX_EXECUTION_MS, MAX_OUTPUT_BYTES };

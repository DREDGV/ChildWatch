/** Run on the actual Linux server with an owner-provided, neutral 170–180-second M4A sample.
 * Does not enable the feature, download anything, or change a running server.
 * CHAT_TRANSCRIPTION_{FFMPEG,WHISPER,MODEL,MEMORY_BUDGET_BYTES,BENCHMARK} are required.
 * node scripts/benchmark-chat-transcription.js /absolute/path/sample.m4a
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const Engine = require('../services/ChatTranscriptionRunner');
async function sampleHealth(url) {
  const start = performance.now();
  return new Promise((resolve, reject) => {
    const request = http.get(url, response => {
      let size = 0;
      response.on('data', chunk => { size += chunk.length; if (size > 8192) request.destroy(new Error('Health response too large')); });
      response.on('end', () => response.statusCode === 200 ? resolve(performance.now() - start) : reject(new Error('Health endpoint unavailable')));
    });
    request.setTimeout(2000, () => request.destroy(new Error('Health request timed out')));
    request.on('error', reject);
  });
}
function p95(values) { return [...values].sort((a, b) => a - b)[Math.ceil(values.length * .95) - 1]; }
async function main() {
  const sample = process.argv[2];
  if (!sample || !path.isAbsolute(sample) || process.platform !== 'linux') throw new Error('Use an absolute neutral sample path on the actual Linux host');
  const config = { ffmpeg: process.env.CHAT_TRANSCRIPTION_FFMPEG, whisper: process.env.CHAT_TRANSCRIPTION_WHISPER,
    model: process.env.CHAT_TRANSCRIPTION_MODEL, executionMs: Engine.MAX_EXECUTION_MS,
    python: process.env.CHAT_TRANSCRIPTION_PYTHON || '/usr/bin/python3',
    guard: process.env.CHAT_TRANSCRIPTION_GUARD || path.resolve(__dirname, 'transcription-exec-guard.py') };
  const budget = Number(process.env.CHAT_TRANSCRIPTION_MEMORY_BUDGET_BYTES);
  const proofPath = process.env.CHAT_TRANSCRIPTION_BENCHMARK;
  if (![config.ffmpeg, config.whisper, config.model, proofPath].every(p => p && path.isAbsolute(p)) ||
      !Number.isSafeInteger(budget) || budget <= 0 || !(await Engine.isMultilingualBase(config.model))) throw new Error('Specify absolute binary/base model/proof paths and a measured available memory budget');
  if (!(await Engine.verifyGuard(config))) throw new Error('Verified Python/Linux parent-death guard is unavailable');
  config.guardVerified = true;
  if (process.argv[3] === '--child') {
    const directory = process.argv[4];
    const start = Date.now();
    const text = await Engine.runWhisper({ input: sample, directory, language: 'ru', config, signal: new AbortController().signal });
    if (!text.trim()) throw new Error('Benchmark sample produced no text');
    fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify({ elapsedMs: Date.now() - start,
      controllerMaxResidentBytes: process.resourceUsage().maxRSS * 1024 }), { mode: 0o600 });
    return;
  }
  const directory = fs.mkdtempSync(path.join(path.dirname(proofPath), '.transcription-benchmark-'));
  try {
    const decoded = path.join(directory, 'sample.pcm');
    await Engine.runGuardedCommand(config, config.ffmpeg, ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
      '-protocol_whitelist', 'file,pipe', '-f', 'mov', '-enable_drefs', '0', '-use_absolute_path', '0', '-i', sample,
      '-vn', '-ac', '1', '-ar', '16000', '-t', '181', '-f', 's16le', decoded], { deadline: Date.now() + 60000 });
    const sampleDurationMs = Math.round(fs.statSync(decoded).size / 32);
    fs.unlinkSync(decoded);
    if (sampleDurationMs < 170000 || sampleDurationMs > 180000) throw new Error('Use a representative 170–180-second sample, not a short snippet');
    const healthUrl = new URL(process.env.CHAT_TRANSCRIPTION_HEALTH_URL || 'http://127.0.0.1:3000/api/health');
    if (healthUrl.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(healthUrl.hostname) ||
        healthUrl.pathname !== '/api/health' || healthUrl.username || healthUrl.password || healthUrl.search) throw new Error('Health probe must be the local running server /api/health');
    const baseline = [];
    for (let i = 0; i < 5; i++) baseline.push(await sampleHealth(healthUrl));
    const during = []; let probing = false, probeFailure = null;
    const probe = async () => {
      if (probing) return;
      probing = true;
      try { during.push(await sampleHealth(healthUrl)); } catch (error) { probeFailure ||= error; }
      finally { probing = false; }
    };
    const memoryPath = path.join(directory, 'max-rss-kib.txt');
    const probeTimer = setInterval(probe, 250);
    try { await Engine.runCommand('/usr/bin/time', ['-f', '%M', '-o', memoryPath, process.execPath, __filename, sample, '--child', directory],
      { deadline: Date.now() + Engine.MAX_EXECUTION_MS + 10000 }); }
    finally { clearInterval(probeTimer); }
    // A probe already in flight still belongs to the measured processing window.
    while (probing) await new Promise(resolve => setTimeout(resolve, 20));
    if (probeFailure || during.length < 3) throw new Error('Running server health unavailable or not sufficiently sampled during actual engine benchmark');
    const health = { healthy: true, baselineSamples: baseline.length, duringSamples: during.length,
      baselineP95Ms: p95(baseline), duringP95Ms: p95(during) };
    const latencyBudget = Number(process.env.CHAT_TRANSCRIPTION_HEALTH_BUDGET_MS || 1000);
    if (!Number.isSafeInteger(latencyBudget) || latencyBudget <= 0 || latencyBudget > 5000 ||
        health.duringP95Ms > latencyBudget || health.duringP95Ms > Math.max(100, health.baselineP95Ms * 3)) throw new Error('Transcription degrades the running server health latency beyond configured limits');
    const result = JSON.parse(fs.readFileSync(path.join(directory, 'result.json'), 'utf8'));
    // GNU time reports the largest process, not the sum. Add the controller peak conservatively;
    // ffmpeg and whisper run sequentially and never contribute concurrent peaks to each other.
    const maxResidentBytes = Number(fs.readFileSync(memoryPath, 'utf8').trim()) * 1024 + result.controllerMaxResidentBytes;
    if (!Number.isSafeInteger(maxResidentBytes) || maxResidentBytes <= 0 || maxResidentBytes > budget) throw new Error(`Measured peak memory ${maxResidentBytes} exceeds configured available budget ${budget}`);
    const proof = { version: 1, modelName: 'base', multilingual: true, completed: true, measuredAt: Date.now(),
      hostname: os.hostname(), platform: process.platform, arch: process.arch, sampleDurationMs,
      elapsedMs: result.elapsedMs, maxResidentBytes, health, guardVersion: 1, guardProbe: true,
      modelSha256: await Engine.sha256(config.model), ffmpegSha256: await Engine.sha256(config.ffmpeg),
      whisperSha256: await Engine.sha256(config.whisper), pythonSha256: await Engine.sha256(config.python), guardSha256: await Engine.sha256(config.guard) };
    const temp = proofPath + '.' + require('crypto').randomUUID() + '.tmp';
    fs.writeFileSync(temp, JSON.stringify(proof, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    fs.renameSync(temp, proofPath);
    console.log(JSON.stringify({ proofPath, sampleDurationMs, elapsedMs: proof.elapsedMs, maxResidentBytes,
      message: 'Proof written. Check server load and memory headroom before explicitly enabling and restarting.' }));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

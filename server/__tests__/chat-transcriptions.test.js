const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const crypto = require('crypto');
const DatabaseManager = require('../database/DatabaseManager');
const ChatService = require('../services/ChatConversationService');
const Transcription = require('../services/ChatTranscriptionService');
const Engine = require('../services/ChatTranscriptionRunner');
const routes = require('../routes/chat-v2');
const { spawn } = require('child_process');
jest.setTimeout(30000);

function request(server, uri, device, { method = 'GET', body = null, type } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: server.address().port, path: uri, method,
      headers: { 'x-test-device-id': device, ...(body ? { 'content-type': type, 'content-length': body.length } : {}) } }, res => {
      const chunks = []; res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString() || '{}'), headers: res.headers }));
    });
    req.on('error', reject); req.end(body);
  });
}
async function family(db, suffix) {
  const parent = 'transcription-parent-' + suffix, child = 'transcription-child-' + suffix;
  for (const device of [parent, child]) await db.registerDevice(device, { device_name: device, device_type: 'android' });
  await db.upsertDeviceLink({ parentDeviceId: parent, childDeviceId: child, parentDisplayName: parent,
    childDisplayName: child, createdBy: 'transcription-fixture' });
  const [family] = await db.getFamiliesForDevice(parent);
  return { parent, child, family, membership: await db.getFamilyDeviceMembership(family.id, parent) };
}
const m4a = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from('ftypM4A '), Buffer.alloc(48)]);
describe('private transcription jobs: real SQLite/HTTP, injectable runner only for isolated fixtures', () => {
  let root, db, chat, primary, secondary, conversation, server, spies;
  const config = { enabled: true, reason: null };
  beforeEach(async () => {
    spies = ['log', 'warn', 'error'].map(key => jest.spyOn(console, key).mockImplementation(() => {}));
    root = fs.mkdtempSync(path.join(__dirname, '../../.runtime/chat-transcription-test-'));
    db = new DatabaseManager(path.join(root, 'test.db')); await db.initialize();
    primary = await family(db, 'main'); secondary = await family(db, 'other');
    chat = new ChatService(db, { attachments: { root: path.join(root, 'attachments') }, transcriptions: {
      root: path.join(root, 'transcriptions'), configuration: config, autoStart: false,
      runner: async () => 'Тестовый распознанный текст' } });
    conversation = (await chat.listConversations(primary.parent)).conversations.find(c => c.type === 'FAMILY').conversationId;
    const app = express(); app.use(express.json()); app.use((req, res, next) => { req.deviceId = req.headers['x-test-device-id']; next(); });
    app.use('/api/chat/v2', routes(db, chat)); server = http.createServer(app);
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  });
  afterEach(async () => {
    chat.transcriptions.stop();
    await new Promise(resolve => server.close(resolve)); await db.close();
    fs.rmSync(root, { recursive: true, force: true }); spies.forEach(s => s.mockRestore());
  });
  function url(id = 'voice-draft') { return `/api/chat/v2/conversations/${conversation}/transcriptions/by-client/${encodeURIComponent(id)}`; }
  async function upload({ id = 'voice-draft', bytes = m4a, device = primary.parent, language = 'ru', duration = 1000 } = {}) {
    const boundary = 'transcription-boundary';
    const fields = { clientRequestId: id, language, durationMs: duration };
    const text = Object.entries(fields).map(([k, v]) => `--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`).join('');
    const body = Buffer.concat([Buffer.from(text + `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="voice.m4a"\r\nContent-Type: audio/mp4\r\n\r\n`), bytes, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    return request(server, `/api/chat/v2/conversations/${conversation}/transcriptions`, device,
      { method: 'POST', body, type: 'multipart/form-data; boundary=' + boundary });
  }
  test('default disabled gate does not enqueue, fixture capability and idempotent result preserve one job', async () => {
    expect((await request(server, '/api/chat/v2/capabilities', primary.parent)).body).toMatchObject({ transcription: true, transcriptionMaxDurationMs: 180000 });
    const first = await upload(); expect(first.status).toBe(201); expect(first.body.job.state).toBe('QUEUED');
    const duplicate = await upload(); expect(duplicate.status).toBe(200); expect(duplicate.body.job.jobId).toBe(first.body.job.jobId);
    expect((await db.get('SELECT COUNT(*) n FROM chat_transcription_jobs')).n).toBe(1);
    await chat.transcriptions.tick();
    const result = await request(server, url(), primary.parent);
    expect(result.body.job).toMatchObject({ state: 'SUCCEEDED', text: 'Тестовый распознанный текст' });
    expect(result.headers['cache-control']).toBe('private, no-store');
    expect((await upload()).body.job.state).toBe('SUCCEEDED');
    chat.transcriptions.configuration = Promise.resolve({ enabled: false, reason: 'RESOURCE_BENCHMARK_REQUIRED' });
    expect((await upload({ id: 'blocked' })).status).toBe(503);
    expect((await db.get('SELECT COUNT(*) n FROM chat_transcription_jobs')).n).toBe(1);
    expect((await Engine.loadConfiguration({})).enabled).toBe(false);
  });
  test('different bytes/language conflict; overlong metadata/invalid container rejected', async () => {
    await upload();
    expect((await upload({ bytes: Buffer.concat([m4a, Buffer.from('changed')]) })).status).toBe(409);
    expect((await upload({ duration: 2000 })).status).toBe(409);
    expect((await upload({ language: 'en' })).status).toBe(400);
    expect((await upload({ id: 'long', duration: 180001 })).status).toBe(422);
    expect((await upload({ id: 'invalid', bytes: Buffer.alloc(16) })).status).toBe(415);
    expect((await upload({ id: 'badlang', language: 'ru;touch /tmp/oops' })).status).toBe(400);
  });
  test('other family and another participant cannot read or cancel private draft', async () => {
    await upload();
    expect((await request(server, url(), primary.child)).status).toBe(404);
    expect((await request(server, url(), primary.child, { method: 'DELETE' })).status).toBe(404);
    expect((await request(server, url(), secondary.parent)).status).toBe(403);
    expect((await upload({ device: secondary.parent })).status).toBe(403);
  });
  test('revocation before execution and after success hides result', async () => {
    await upload(); const runner = jest.fn(async () => 'secret'); chat.transcriptions.runner = runner;
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', [primary.parent]);
    await chat.transcriptions.tick(); expect(runner).not.toHaveBeenCalled();
    expect((await db.get('SELECT state,text FROM chat_transcription_jobs'))).toMatchObject({ state: 'FAILED', text: null });
    expect((await request(server, url(), primary.parent)).status).toBe(403);
  });
  test('revocation while recognizing fences a late result and completed result is private after revocation', async () => {
    await upload(); let release, started; const gate = new Promise(resolve => { started = resolve; });
    chat.transcriptions.runner = () => new Promise(resolve => { release = resolve; started(); });
    const running = chat.transcriptions.tick(); await gate;
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', [primary.parent]);
    release('must remain hidden'); await running;
    expect((await db.get('SELECT state,text FROM chat_transcription_jobs'))).toMatchObject({ state: 'FAILED', text: null });
    await db.run('UPDATE family_devices SET is_active=1 WHERE device_id=?', [primary.parent]);
    chat.transcriptions.runner = async () => 'completed private result'; await upload({ id: 'completed' }); await chat.transcriptions.tick();
    expect((await request(server, url('completed'), primary.parent)).body.job.state).toBe('SUCCEEDED');
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', [primary.parent]);
    expect((await request(server, url('completed'), primary.parent)).status).toBe(403);
  });
  test('jobs and exact owner survive real database close/reopen', async () => {
    const first = await upload();
    chat.transcriptions.stop(); await new Promise(resolve => server.close(resolve)); await db.close();
    db = new DatabaseManager(path.join(root, 'test.db')); await db.initialize();
    chat = new ChatService(db, { attachments: { root: path.join(root, 'attachments') }, transcriptions: {
      root: path.join(root, 'transcriptions'), configuration: config, autoStart: false, runner: async () => 'after actual reopen' } });
    const app = express(); app.use(express.json()); app.use((req, res, next) => { req.deviceId = req.headers['x-test-device-id']; next(); });
    app.use('/api/chat/v2', routes(db, chat)); server = http.createServer(app); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    expect((await request(server, url(), primary.parent)).body.job).toMatchObject({ jobId: first.body.job.jobId, state: 'QUEUED' });
    await chat.transcriptions.tick();
    expect((await request(server, url(), primary.parent)).body.job).toMatchObject({ jobId: first.body.job.jobId, state: 'SUCCEEDED', text: 'after actual reopen' });
  });
  test('cancel running work aborts runner, does not publish late text, keeps audio TTL', async () => {
    const first = await upload(); let started;
    const gate = new Promise(resolve => { started = resolve; });
    chat.transcriptions.runner = ({ signal }) => new Promise(resolve => { signal.addEventListener('abort', () => resolve('late private text')); started(); });
    const running = chat.transcriptions.tick(); await gate;
    const cancelled = await request(server, url(), primary.parent, { method: 'DELETE' });
    expect(cancelled.body.job).toMatchObject({ state: 'CANCELLED', text: null }); await running;
    expect((await request(server, url(), primary.parent)).body.job.text).toBeNull();
    expect(fs.existsSync(chat.transcriptions.input(first.body.job.jobId))).toBe(true);
  });
  test('restart recovery waits for live lease; expired lease recovered; simultaneous ticks do not duplicate', async () => {
    const first = await upload();
    await chat.transcriptions.ensure();
    await db.run("UPDATE chat_transcription_jobs SET state='RUNNING',lease_owner='crashed' WHERE id=?", [first.body.job.jobId]);
    await db.run('INSERT INTO chat_transcription_worker_lease(id,owner,expires_at) VALUES(1,?,?)', ['crashed', Date.now() + 30000]);
    const runner = jest.fn(async () => 'recovered');
    chat.transcriptions.runner = runner;
    await chat.transcriptions.tick(); expect(runner).not.toHaveBeenCalled();
    await db.run('UPDATE chat_transcription_worker_lease SET expires_at=0 WHERE id=1');
    await Promise.all([chat.transcriptions.tick(), chat.transcriptions.tick()]);
    expect(runner).toHaveBeenCalledTimes(1);
    expect((await request(server, url(), primary.parent)).body.job.state).toBe('SUCCEEDED');
  });
  test('second service shares global lease and one runner; stale worker cannot commit', async () => {
    await upload(); const second = new Transcription(chat, { root: chat.transcriptions.root, configuration: config, autoStart: false });
    let release, started; const gate = new Promise(resolve => { started = resolve; });
    chat.transcriptions.runner = () => new Promise(resolve => { release = resolve; started(); });
    second.runner = jest.fn(async () => 'other worker');
    const firstRun = chat.transcriptions.tick(); await gate;
    await second.tick(); expect(second.runner).not.toHaveBeenCalled();
    await db.run('UPDATE chat_transcription_worker_lease SET owner=?,expires_at=? WHERE id=1', [second.owner, Date.now() + 30000]);
    release('stale result'); await firstRun;
    expect((await db.get('SELECT state,text FROM chat_transcription_jobs'))).toMatchObject({ state: 'RUNNING', text: null });
    second.stop();
  });
  test('audio is removed after terminal TTL, sent voice attachment unaffected, idempotent result survives', async () => {
    const first = await upload(); await chat.transcriptions.tick();
    const independent = path.join(chat.attachments.root, crypto.randomUUID() + '.blob'); fs.writeFileSync(independent, m4a);
    await chat.transcriptions.gc(Date.now() + Transcription.TEMP_TTL_MS + 1000);
    expect(fs.existsSync(chat.transcriptions.input(first.body.job.jobId))).toBe(false);
    expect(fs.existsSync(independent)).toBe(true);
    expect((await upload()).body.job.jobId).toBe(first.body.job.jobId);
    expect((await request(server, url(), primary.parent)).body.job.state).toBe('SUCCEEDED');
  });
  test('pending queue is bounded but retry of an existing job does not consume another slot', async () => {
    for (let i = 0; i < 3; i++) expect((await upload({ id: 'q' + i })).status).toBe(201);
    expect((await upload({ id: 'extra' })).body.code).toBe('TRANSCRIPTION_QUEUE_FULL');
    expect((await upload({ id: 'q0' })).status).toBe(200);
  });
  (process.platform === 'linux' ? test : test.skip)('expired lease of a live controller cannot start an overlapping worker', async () => {
    await upload();
    await chat.transcriptions.claim();
    await db.run('UPDATE chat_transcription_worker_lease SET expires_at=0 WHERE id=1');
    const second = new Transcription(chat, { root: chat.transcriptions.root, configuration: config, autoStart: false });
    try {
      expect(await second.claim()).toBeNull();
      chat.transcriptions.stop(); // Idle controller, no subprocess remains; same-process replacement can recover.
      expect((await second.claim()).state).toBe('RUNNING');
    } finally { second.stop(); }
  });
});

describe('bounded subprocess runner', () => {
  const testLinux = process.platform === 'linux' ? test : test.skip;
  testLinux('actual Linux guard probes kernel and rejects a disappeared/wrong parent', async () => {
    const config = { python: '/usr/bin/python3', guard: path.resolve(__dirname, '../scripts/transcription-exec-guard.py'),
      ffmpeg: process.execPath, whisper: process.execPath };
    expect(await Engine.verifyGuard(config)).toBe(true);
    await expect(Engine.runCommand(config.python, [config.guard, '--parent-pid', String(process.pid + 1), '--probe'],
      { deadline: Date.now() + 3000 })).rejects.toMatchObject({ code: 'TRANSCRIPTION_PROCESS_FAILED' });
  });
  testLinux('guarded child exits after its Node controller is forcibly terminated', async () => {
    const helper = path.resolve(__dirname, '../scripts/transcription-exec-guard.py');
    const marker = 'guard-fixture-' + crypto.randomUUID();
    const controllerCode = `const {spawn}=require('child_process');const child=spawn('/usr/bin/python3',
      [${JSON.stringify(helper)},'--parent-pid',String(process.pid),'--',process.execPath,'-e',
      ${JSON.stringify(`console.log(process.pid);setInterval(()=>{},1000);/*${marker}*/`)}],{stdio:['ignore','pipe','pipe']});
      child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);setInterval(()=>{},1000);`;
    const controller = spawn(process.execPath, ['-e', controllerCode], { stdio: ['ignore', 'pipe', 'pipe'], shell: false });
    let childPid;
    try {
      childPid = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Guard fixture failed to start')), 3000);
        let output = '';
        controller.stdout.on('data', chunk => { output += chunk; if (output.includes('\n')) { clearTimeout(timer); resolve(Number(output.trim().split('\n')[0])); } });
        controller.on('error', error => { clearTimeout(timer); reject(error); });
      });
      expect(Number.isSafeInteger(childPid) && childPid > 1).toBe(true);
      const exited = new Promise(resolve => controller.once('exit', resolve)); controller.kill('SIGKILL'); await exited;
      let gone = false;
      for (let i = 0; i < 100; i++) {
        try { const stat = fs.readFileSync(`/proc/${childPid}/stat`, 'utf8'); gone = /\) Z /.test(stat); }
        catch (error) { if (error.code === 'ENOENT') gone = true; else throw error; }
        if (gone) break;
        await new Promise(resolve => setTimeout(resolve, 20));
      }
      expect(gone).toBe(true);
    } finally {
      controller.kill('SIGKILL');
      // On a failed regression, clean up only the exact fixture child with its unique command marker.
      if (childPid) try { if (fs.readFileSync(`/proc/${childPid}/cmdline`, 'utf8').includes(marker)) process.kill(childPid, 'SIGKILL'); } catch {}
    }
  });
  test('capability requires base model identity, matching binaries, this host, memory and live health proof', async () => {
    const root = fs.mkdtempSync(path.join(__dirname, '../../.runtime/transcription-gate-'));
    try {
      const model = path.join(root, 'model.bin'), binary = path.join(root, 'fixture-binary'), proofPath = path.join(root, 'proof.json');
      const header = Buffer.alloc(48); for (const [offset, value] of [[0, 0x67676d6c], [4, 51865], [12, 512], [20, 6], [28, 512], [36, 6]]) header.writeInt32LE(value, offset);
      fs.writeFileSync(model, header); fs.writeFileSync(binary, 'configuration fixture, not executable engine');
      const env = { CHAT_TRANSCRIPTION_ENABLED: '1', CHAT_TRANSCRIPTION_FFMPEG: binary, CHAT_TRANSCRIPTION_WHISPER: binary,
        CHAT_TRANSCRIPTION_MODEL: model, CHAT_TRANSCRIPTION_BENCHMARK: proofPath, CHAT_TRANSCRIPTION_MEMORY_BUDGET_BYTES: '1000000000',
        CHAT_TRANSCRIPTION_PYTHON: binary };
      // Pure proof fixture deliberately is not executable; loadConfiguration must never enable it.
      expect((await Engine.loadConfiguration(env)).reason).toBe('TRANSCRIPTION_NOT_READY');
      const config = { ffmpeg: binary, whisper: binary, model, python: binary,
        guard: path.resolve(__dirname, '../scripts/transcription-exec-guard.py') };
      const identity = { platform: 'linux', arch: process.arch, hostname: require('os').hostname() };
      const proof = { version: 1, modelName: 'base', multilingual: true, completed: true, platform: 'linux', arch: process.arch,
        hostname: require('os').hostname(), elapsedMs: 10000, maxResidentBytes: 200000000, sampleDurationMs: 175000,
        modelSha256: await Engine.sha256(model), ffmpegSha256: await Engine.sha256(binary), whisperSha256: await Engine.sha256(binary),
        guardVersion: 1, guardProbe: true, pythonSha256: await Engine.sha256(binary), guardSha256: await Engine.sha256(config.guard),
        health: { healthy: true, baselineSamples: 5, duringSamples: 10, baselineP95Ms: 20, duringP95Ms: 50 } };
      fs.writeFileSync(proofPath, JSON.stringify(proof)); expect(await Engine.validateBenchmark(proof, config, env, identity)).toBe(true);
      for (const mutate of [p => { p.hostname = 'another-host'; }, p => { p.maxResidentBytes = 2000000000; },
        p => { p.health.duringP95Ms = 1500; }, p => { p.ffmpegSha256 = 'changed'; }, p => { p.health.healthy = false; },
        p => { delete p.health.baselineSamples; }, p => { delete p.health.duringSamples; },
        p => { p.health.baselineSamples = 'five'; }, p => { p.health.duringSamples = 'three'; },
        p => { p.health.baselineSamples = 5.5; }, p => { p.health.duringSamples = 3.5; },
        p => { delete p.guardProbe; }, p => { p.guardSha256 = 'wrong-helper'; }, p => { p.pythonSha256 = 'wrong-python'; }]) {
        const changed = JSON.parse(JSON.stringify(proof)); mutate(changed); fs.writeFileSync(proofPath, JSON.stringify(changed));
        expect(await Engine.validateBenchmark(changed, config, env, identity)).toBe(false);
      }
      header.writeInt32LE(51864, 4); fs.writeFileSync(model, header); fs.writeFileSync(proofPath, JSON.stringify(proof));
      expect((await Engine.loadConfiguration(env)).reason).toBe('TRANSCRIPTION_NOT_READY');
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  });
  test('deadline kills actual process and waits for exit', async () => {
    await expect(Engine.runCommand(process.execPath, ['-e', 'setInterval(()=>{},1000)'],
      { deadline: Date.now() + 100 })).rejects.toMatchObject({ code: 'TRANSCRIPTION_TIMEOUT' });
  });
  test('cancel kills actual process', async () => {
    const abort = new AbortController();
    const result = Engine.runCommand(process.execPath, ['-e', 'setInterval(()=>{},1000)'],
      { deadline: Date.now() + 10000, signal: abort.signal, onSpawn: () => setTimeout(() => abort.abort(), 50) });
    await expect(result).rejects.toMatchObject({ code: 'TRANSCRIPTION_CANCELLED' });
  });
  test('bounded output kills process without collecting unbounded stdout', async () => {
    await expect(Engine.runCommand(process.execPath, ['-e', 'process.stdout.write("x".repeat(65536));setInterval(()=>{},1000)'],
      { deadline: Date.now() + 10000, maxOutputBytes: 100 })).rejects.toMatchObject({ code: 'TRANSCRIPTION_OUTPUT_TOO_LARGE' });
  });
});

'use strict';

// Read-only cross-language proof. Never regenerates or changes the committed golden vectors.
// node scripts/verify-snake-java-parity.cjs [--java-home "C:\\Program Files\\Android\\Android Studio\\jbr"]
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const Snake = require('../server/services/SnakeRules');
const root = path.resolve(__dirname, '..');
const output = path.join(root, '.runtime', 'snake-java-parity');
const engine = path.join(root, 'design-system/src/main/java/ru/example/childwatch/designsystem/SnakeEngine.java');
const runner = path.join(root, 'server/__tests__/fixtures/SnakeGoldenRunner.java');
const goldenFile = path.join(root, 'server/__tests__/snake-rules.node.cjs');
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--java-home')) {
  throw new Error('Usage: node scripts/verify-snake-java-parity.cjs [--java-home JDK_DIRECTORY]');
}
const javaHome = args[1] || process.env.JAVA_HOME;
const executable = name => javaHome
  ? path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
function run(name, arguments_, input) {
  const result = spawnSync(executable(name), arguments_, {
    cwd: root, input, encoding: 'utf8', timeout: 60000, maxBuffer: 2 * 1024 * 1024
  });
  if (result.error || result.status !== 0) {
    throw new Error(`${name} failed: ${result.error ? result.error.message : result.stderr}`);
  }
  return result.stdout;
}
function apply(state, input) {
  const parts = input.split(' ');
  switch (parts[0]) {
    case 'launch': Snake.launchOrResume(state); break;
    case 'pause': Snake.pause(state); break;
    case 'tick': Snake.tick(state); break;
    case 'turn': Snake.turn(state, Number(parts[1]), parts[2]); break;
    case 'restore': state = Snake.restore(parts[1].split(','));
      if (!state) throw new Error('invalid fixture'); break;
    default: throw new Error('unknown command');
  }
  return state;
}
const source = fs.readFileSync(goldenFile, 'utf8');
const block = source.match(/const GOLDEN\s*=\s*([\s\S]+?)\r?\n;\s*\r?\nfunction command/);
if (!block) throw new Error('Golden fixture block missing; inspect committed test format');
const golden = JSON.parse(block[1]);
fs.mkdirSync(output, {recursive: true});
run('javac', ['-encoding', 'UTF-8', '-d', output, engine, runner]);
let snapshots = 0;
const cases = [];
for (const fixture of [...golden.fixtures, ...golden.forced]) {
  const commands = fixture.commands || golden.operations;
  const javaValues = run('java', ['-cp', output, 'SnakeGoldenRunner', fixture.seed, fixture.difficulty],
    commands.join('\n') + '\n').trim().split(/\r?\n/).map(line => JSON.parse(line));
  let state = Snake.initial(fixture.seed, fixture.difficulty);
  const nodeValues = [Snake.snapshot(state)];
  for (const command of commands) { state = apply(state, command); nodeValues.push(Snake.snapshot(state)); }
  if (javaValues.length !== nodeValues.length) throw new Error('snapshot count mismatch');
  for (let i = 0; i < nodeValues.length; i++) {
    if (JSON.stringify(nodeValues[i]) !== JSON.stringify(javaValues[i])) {
      throw new Error(`Parity failed ${fixture.seed}/${fixture.difficulty} step${i} ${commands[i - 1] || 'initial'}`);
    }
  }
  const digest = hash(javaValues.map(value => JSON.stringify(value)).join('\n') + '\n');
  if (digest !== fixture.digest) throw new Error(`Golden changed ${fixture.seed}/${fixture.difficulty}; review rules explicitly`);
  snapshots += javaValues.length;
  cases.push({seed: fixture.seed, difficulty: fixture.difficulty, growth: !!fixture.commands, digest});
}
const report = {javaEngineSha256: hash(fs.readFileSync(engine)), javaRunnerSha256: hash(fs.readFileSync(runner)),
  nodeRulesSha256: hash(fs.readFileSync(path.join(root, 'server/services/SnakeRules.js'))),
  cases: cases.length, snapshots, result: 'PASS', vectors: cases};
fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
console.log(`Java/Node/golden parity PASS: ${cases.length} cases, ${snapshots} snapshots`);
console.log(path.join(output, 'verification.json'));

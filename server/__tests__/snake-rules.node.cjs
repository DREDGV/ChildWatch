'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const Snake = require('../services/SnakeRules');

// Recorded from javac/java against the production20x36 WRAP SnakeEngine on2026-10-13.
// SHA256 of Java source: 1a2f37577f35c4ad80e2862cf0ea36c3cb9209ab7651c102564a528c94b4653d.
// 31 independent Java runs,3388 complete snapshots; no Java dependency for portable tests.
const GOLDEN =
{
  "operations": [
    "launch",
    "turn 0 RIGHT",
    "turn 1 LEFT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 DOWN",
    "turn 1 UP",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 LEFT",
    "turn 1 RIGHT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 UP",
    "turn 1 DOWN",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 RIGHT",
    "turn 1 LEFT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 DOWN",
    "turn 1 UP",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 LEFT",
    "turn 1 RIGHT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 UP",
    "turn 1 DOWN",
    "tick",
    "tick",
    "tick",
    "tick",
    "pause",
    "launch",
    "turn 0 RIGHT",
    "turn 1 LEFT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 DOWN",
    "turn 1 UP",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 LEFT",
    "turn 1 RIGHT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 UP",
    "turn 1 DOWN",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 RIGHT",
    "turn 1 LEFT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 DOWN",
    "turn 1 UP",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 LEFT",
    "turn 1 RIGHT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 UP",
    "turn 1 DOWN",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 RIGHT",
    "turn 1 LEFT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 DOWN",
    "turn 1 UP",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 LEFT",
    "turn 1 RIGHT",
    "tick",
    "tick",
    "tick",
    "tick",
    "turn 0 UP",
    "turn 1 DOWN",
    "tick",
    "tick",
    "tick",
    "tick"
  ],
  "fixtures": [
    {
      "seed": "0",
      "difficulty": "CALM",
      "digest": "b4aef8d0e17547f057d0650dc5b58f5f5f95cb1bc833e3e7f2375f9cdb40d15d"
    },
    {
      "seed": "0",
      "difficulty": "NORMAL",
      "digest": "5eb74fca358b6ccc18b384ed28098b14c3134c8b1a2ab4370f25d4c105b27d6c"
    },
    {
      "seed": "0",
      "difficulty": "FAST",
      "digest": "ae7eb6464f99fc8d7e714473e08451dc1c67b907a487db5df17b9f1a4666ce71"
    },
    {
      "seed": "1",
      "difficulty": "CALM",
      "digest": "165a4074e33bc691fb7c232060c46f6129f10fe3c31b1414b48a3b7bb5f5ec63"
    },
    {
      "seed": "1",
      "difficulty": "NORMAL",
      "digest": "fe7412cf44de214266ece5379eac86aee42ec41de5a9c94b9bbf82076d981eab"
    },
    {
      "seed": "1",
      "difficulty": "FAST",
      "digest": "7b0ea3884a9143dc3ae3c9d763c6b8b9efdd65bc0c83fb2c3bc0acafc505d0b2"
    },
    {
      "seed": "-1",
      "difficulty": "CALM",
      "digest": "65b1f826a2372c6e055809d59d44c17c3203ba76ee1083ddf6b758555fe49976"
    },
    {
      "seed": "-1",
      "difficulty": "NORMAL",
      "digest": "fd2ce36fcfa7fdeee37e794eae5955607988ddffe6bdee5519294a343ed6051c"
    },
    {
      "seed": "-1",
      "difficulty": "FAST",
      "digest": "5900466f53d8133bbcb611e8a4ee65a0ba31f7cce1293ab20b2f0b47e5ca7206"
    },
    {
      "seed": "42",
      "difficulty": "CALM",
      "digest": "46fe13d30380243f6b51066a6f6fe62f706be766ee529386bb0f65ba4cf5c080"
    },
    {
      "seed": "42",
      "difficulty": "NORMAL",
      "digest": "b16919cdb265399bbd09708b6b12244992909182e6edb76f0c1816ecd2531b11"
    },
    {
      "seed": "42",
      "difficulty": "FAST",
      "digest": "5408b62824fad197df1264225917ff9b6ecea11f41f8fac860f310bdeaac276f"
    },
    {
      "seed": "-42",
      "difficulty": "CALM",
      "digest": "46f4ef7f34ee4f01002e7a99016344a4fe0e8d9e85225222666bcf0dd6b89457"
    },
    {
      "seed": "-42",
      "difficulty": "NORMAL",
      "digest": "ae579abd532c434dd5192fe5536d0fa0d7898bd091dfc9ed8b68d976ff9aafd9"
    },
    {
      "seed": "-42",
      "difficulty": "FAST",
      "digest": "38f329a3c7112cba5bb5097ff8099779d29cbf849b81648d11b57d15faa66648"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "CALM",
      "digest": "fa3d7fa09b10c610210a0e94ab2d69157ee7d69e92dcd1a7937e59e31afa268f"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "NORMAL",
      "digest": "23cbbec5518c5e8312a64ee99519b80f1f25f5199b74d9bfa9b9037f985f3ab1"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "FAST",
      "digest": "9a2f0c6e775375d8a33430c07712a58d8af34c895680de80ba17c3a8c12f00cd"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "CALM",
      "digest": "33c64e6e61944bc2c6022017474f5ce48358ae7dd2db5ff1b57c990dc47b4974"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "NORMAL",
      "digest": "91fe52c7f6babac0642ae0865f8d2143b7f05c40abb60f5f390e148cb8e03713"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "FAST",
      "digest": "2c6c398e83b0b5d7cd262e7183376943b294d9f7eadeb7e18d4ba587f5ceb152"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "CALM",
      "digest": "4b9f067ad98a5bded7adf71c521f3e3a3861bfc0d038f56da68c787415b30ac3"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "NORMAL",
      "digest": "2b3a98698f3b8e98b52ad01d46e9c28dd7365aa86f91f82e7c91efcf30b2cea1"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "FAST",
      "digest": "4d668a60d1c37ecec2c865d1e31dd80b3e96bca8c830cea35c22ad44e5c49cc6"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "CALM",
      "digest": "44c59d907cbff909121a341c7d137721251bfe9e4c5e9925d2a98282d8e33901"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "NORMAL",
      "digest": "0aa077edb4c1da240f7b68dde0456817d2d513b487e391ba3718e1cc67a2f8b9"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "FAST",
      "digest": "b5090ffa7728f674157ccc18909c16956621e05929aed7c1f92aaea83ee0a3c6"
    }
  ],
  "forced": [
    {
      "seed": "0",
      "difficulty": "NORMAL",
      "digest": "e4eeecdd3cce653743b1546868243ce3b860aec6d12c0e970b478173e62ff2a5",
      "commands": [
        "restore 1,1,1,0,0,0,-7046029254386353131,0,166,3,1,0,165,164,163,3,3,0,354,355,356",
        "launch",
        "tick",
        "turn 0 DOWN",
        "turn 1 UP",
        "tick",
        "pause",
        "launch",
        "tick"
      ]
    },
    {
      "seed": "-1",
      "difficulty": "NORMAL",
      "digest": "8a6c55b08127f64bffe5421836c4cf46c9631d2bacd4c720b7e7cddc9b0320a0",
      "commands": [
        "restore 1,1,1,0,0,0,-7046029254386353132,-1,166,3,1,0,165,164,163,3,3,0,354,355,356",
        "launch",
        "tick",
        "turn 0 DOWN",
        "turn 1 UP",
        "tick",
        "pause",
        "launch",
        "tick"
      ]
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "NORMAL",
      "digest": "b18f9c36b728428b62636d5a9649b8a64cadcdb1e49f5f2011fe08d6c6908411",
      "commands": [
        "restore 1,1,1,0,0,0,2177342782468422676,9223372036854775807,166,3,1,0,165,164,163,3,3,0,354,355,356",
        "launch",
        "tick",
        "turn 0 DOWN",
        "turn 1 UP",
        "tick",
        "pause",
        "launch",
        "tick"
      ]
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "NORMAL",
      "digest": "930a0038aba446ba65b5c1b15de75ee0a24630678cdf223466036fd402970557",
      "commands": [
        "restore 1,1,1,0,0,0,2177342782468422677,-9223372036854775808,166,3,1,0,165,164,163,3,3,0,354,355,356",
        "launch",
        "tick",
        "turn 0 DOWN",
        "turn 1 UP",
        "tick",
        "pause",
        "launch",
        "tick"
      ]
    }
  ]
}
;
function command(state, input) {
  const parts = input.split(' ');
  switch(parts[0]) {
    case 'launch': Snake.launchOrResume(state); break;
    case 'pause': Snake.pause(state); break;
    case 'tick': Snake.tick(state); break;
    case 'turn': Snake.turn(state,Number(parts[1]),parts[2]); break;
    case 'restore': state = Snake.restore(parts[1].split(',')); assert.ok(state); break;
  }
  return state;
}
for(const fixture of [...GOLDEN.fixtures,...GOLDEN.forced]) {
  test(`Java golden parity ${fixture.seed}/${fixture.difficulty}${fixture.commands?' growth':''}`,()=>{
    let state=Snake.initial(fixture.seed,fixture.difficulty);
    const values=[Snake.snapshot(state)];
    for(const input of fixture.commands || GOLDEN.operations) {state=command(state,input); values.push(Snake.snapshot(state));}
    const digest=crypto.createHash('sha256').update(values.map(value=>JSON.stringify(value)).join('\n')+'\n').digest('hex');
    assert.equal(digest,fixture.digest);
  });
}
function fixture(one,d1,two,d2,food) {
  const data=['1','1','1','0',String((one.length+two.length-6)*10),'0','42','42',String(food),
    String(one.length),String(['UP','RIGHT','DOWN','LEFT'].indexOf(d1)),'0',...one.map(String),
    String(two.length),String(['UP','RIGHT','DOWN','LEFT'].indexOf(d2)),'0',...two.map(String)];
  const state=Snake.restore(data);assert.ok(state);Snake.launchOrResume(state);return state;
}
test('same-cell and crossing heads end both players without moving either',()=>{
  for(const other of [[167,168,169],[166,167,168]]) {
    const state=fixture([165,164,163],'RIGHT',other,'LEFT',300), before=state.players.map(p=>[...p.body]);
    Snake.tick(state);assert.equal(state.state,'GAME_OVER');assert.equal(state.ticks,1n);
    assert.deepEqual(state.players.map(p=>p.body),before);assert.ok(Snake.restore(Snake.snapshot(state)));
  }
});
test('all four joined edges wrap and the wrapped save remains valid',()=>{
  for(const [body,direction,expected] of [
    [[19,18,17],'RIGHT',0],[[0,1,2],'LEFT',19],[[5,25,45],'UP',705],[[705,685,665],'DOWN',5]
  ]){
    const state=fixture(body,direction,[354,355,356],'LEFT',300);Snake.tick(state);
    assert.equal(state.state,'PLAYING');assert.equal(state.players[0].body[0],expected);
    assert.ok(Snake.restore(Snake.snapshot(state)));
  }
});
test('joined-edge head swap still ends both snakes atomically',()=>{
  const state=fixture([19,18,17],'RIGHT',[0,1,2],'LEFT',300),before=state.players.map(p=>[...p.body]);
  Snake.tick(state);assert.equal(state.state,'GAME_OVER');assert.deepEqual(state.players.map(p=>p.body),before);
});
test('vacated partner tail is allowed only when partner does not grow',()=>{
  const free=fixture([165,164,163],'RIGHT',[167,166,186,185],'RIGHT',300);
  const grows=fixture([165,164,163],'RIGHT',[167,166,186,185],'RIGHT',168);
  for(const state of [free,grows]) {assert.equal(Snake.turn(state,0,'DOWN'),true);Snake.tick(state);}
  assert.equal(free.state,'PLAYING');assert.equal(grows.state,'GAME_OVER');assert.equal(grows.score,10);
});
test('turn queue is bounded, disallows reversal and applies once per tick',()=>{
  const state=Snake.initial('5');
  assert.equal(Snake.turn(state,0,'LEFT'),false);
  assert.equal(Snake.turn(state,0,'UP'),true);assert.equal(Snake.turn(state,0,'DOWN'),false);
  assert.equal(Snake.turn(state,0,'LEFT'),true);assert.equal(Snake.turn(state,0,'DOWN'),false);
  assert.equal(Snake.turn(state,'0','UP'),false);assert.equal(Snake.turn(state,2,'UP'),false);
  Snake.launchOrResume(state);Snake.tick(state);assert.equal(state.players[0].direction,'UP');
  Snake.tick(state);assert.equal(state.players[0].direction,'LEFT');
});
test('restore validates all fields and fails without mutating input',()=>{
  const saved=Snake.snapshot(Snake.initial('-9223372036854775808'));
  for(const index of [0,1,2,3,4,8,9,10,11,12]) {
    const bad=[...saved];bad[index]='9223372036854775807';const before=[...bad];
    assert.equal(Snake.restore(bad),null,`index ${index}`);assert.deepEqual(bad,before);
  }
  const time=[...saved];time[5]='-1';assert.equal(Snake.restore(time),null);
  const unsafe=[...saved];unsafe[6]=Number(unsafe[6]);assert.equal(Snake.restore(unsafe),null);
  const overlap=[...saved];overlap[13]=overlap[12];assert.equal(Snake.restore(overlap),null);
  for(const bad of [null,[],saved.slice(0,-1),[...saved,'1'],saved.map(Number)])assert.equal(Snake.restore(bad),null);
});
test('signed64 seeds and snapshots cannot silently pass through floating point',()=>{
  for(const seed of [1,1n,'9223372036854775808','-9223372036854775809','1.0','1e2',''])assert.throws(()=>Snake.initial(seed));
  const state=Snake.initial('9007199254740993');assert.equal(Snake.snapshot(state)[7],'9007199254740993');
  const restored=Snake.restore(Snake.snapshot(state));assert.ok(restored);assert.deepEqual(Snake.snapshot(restored),Snake.snapshot(state));
});
test('active restore pauses and retains buffered directions',()=>{
  const state=Snake.initial('13');Snake.launchOrResume(state);Snake.turn(state,0,'UP');
  const copy=Snake.restore(Snake.snapshot(state));assert.equal(copy.state,'PAUSED');
  const saved=Snake.snapshot(copy);Snake.tick(copy);assert.deepEqual(Snake.snapshot(copy),saved);
  Snake.launchOrResume(copy);Snake.tick(copy);assert.equal(copy.players[0].direction,'UP');
});
test('view is JSON-safe, copies bodies and separates score from food count',()=>{
  const state=fixture([165,164,163],'RIGHT',[354,355,356],'LEFT',166);Snake.tick(state);
  const view=Snake.view(state);assert.equal(view.columns,20);assert.equal(view.rows,36);
  assert.equal(view.score,10);assert.equal(view.tick,'1');assert.equal(view.tickMs,177);
  assert.doesNotThrow(()=>JSON.stringify(view));view.players[0].body[0]=0;
  assert.equal(state.players[0].body[0],166);
});

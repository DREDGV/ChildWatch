'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const Snake = require('../services/SnakeRules');

// Recorded from javac/java against the production20x36 SnakeEngine on2026-10-11.
// SHA256 of Java source: eccd2b8db82406039d89ba98b733b743c36003dc0989ad4ccc6d3ba4c83bcddb.
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
      "digest": "09d2369607df0c0b0489b9860ed339a1ea9e42bd906348f1093b3e67466e1a3a"
    },
    {
      "seed": "0",
      "difficulty": "NORMAL",
      "digest": "9be74379e9621031176d3555433939924d9a351f0ad029110a8a914f4f5149ce"
    },
    {
      "seed": "0",
      "difficulty": "FAST",
      "digest": "16fc1ef4aad9fe6b754e1829740f07f3b189d3641110928c9eeee2857cc6bf5f"
    },
    {
      "seed": "1",
      "difficulty": "CALM",
      "digest": "1db31a0c1c6ac2341030a20814249a83973b38391d12220aed10495aecdfc347"
    },
    {
      "seed": "1",
      "difficulty": "NORMAL",
      "digest": "3ae29241d474e66f9e81d2dd091ad66a94455785e42016ffb437c17f8996840e"
    },
    {
      "seed": "1",
      "difficulty": "FAST",
      "digest": "d854d4088a2cbbdb094b9f50243753a8291671932a53e391ce7d674238836635"
    },
    {
      "seed": "-1",
      "difficulty": "CALM",
      "digest": "b874641e07705e91382aef41bd01c4ca38520cbe23d75445c3b525e69f4037ee"
    },
    {
      "seed": "-1",
      "difficulty": "NORMAL",
      "digest": "50093bbd794423ce3fef511dd26d9252df01af6fa33a6c10ad9883f5a4aefb1a"
    },
    {
      "seed": "-1",
      "difficulty": "FAST",
      "digest": "aefc74e29120fbe68ff37e681a9cb6a77258ee5b26a0cf7e90c1c65bc80bc57d"
    },
    {
      "seed": "42",
      "difficulty": "CALM",
      "digest": "2114024dab50a0ddf3c5d10255f53941f4a53dbf5662c3779d261060353de0df"
    },
    {
      "seed": "42",
      "difficulty": "NORMAL",
      "digest": "dc72597069b49f7f545fff30921122c67f6112cf8cbeb6fcab339cf623bbac81"
    },
    {
      "seed": "42",
      "difficulty": "FAST",
      "digest": "9f187392d4bd6e4e40245eb53e744411c1fc53c29fa794f7048cbb145b4fddb2"
    },
    {
      "seed": "-42",
      "difficulty": "CALM",
      "digest": "03f9880e9875e6d97beb07de04a4310190b3d83424a0ac7c1aac663f996c1a3e"
    },
    {
      "seed": "-42",
      "difficulty": "NORMAL",
      "digest": "b57cf7ed51396b1687febe40be988590eb584338f0bdb9f3e03ff9b70f110806"
    },
    {
      "seed": "-42",
      "difficulty": "FAST",
      "digest": "abd71e9477bed4207e75f9072b55b71fdea24aa444266830b0c2e488e1b195d6"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "CALM",
      "digest": "c36192084abf32ed50480355bcd3d8cbfc350508b1e3f89d11b5aa4c45170f9e"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "NORMAL",
      "digest": "2996b73400b24002ef2ae1902837e22cdac31ca05cbd40d0ee872abaf353ed5c"
    },
    {
      "seed": "9007199254740993",
      "difficulty": "FAST",
      "digest": "3557bdaa094ad84dd20b8dec6df32e510837188b63846f618f605a36eb5f7a80"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "CALM",
      "digest": "52b5caa25f03d6aeba8b3ffa09e59ffad4fb95fdc35fd0bd5a01a6bf9a77f54d"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "NORMAL",
      "digest": "d52b19521b12a085699e594215159343471aa7b7c3280a6b67bf5e1028b34081"
    },
    {
      "seed": "-9007199254740993",
      "difficulty": "FAST",
      "digest": "bcb95d8d0ae521fb2e39f2ec910b908991db8ab929d405466e97497154f8da42"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "CALM",
      "digest": "309277fab0b5a4a24ef6315d656ea0da91d4ebd4b5d3d7f14f758206de7728b6"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "NORMAL",
      "digest": "f5ac58bdb33e0bae828be5828ccaa49d1fc5e3628f4328e87adce737b8e5ef1a"
    },
    {
      "seed": "9223372036854775807",
      "difficulty": "FAST",
      "digest": "b111032fa45c0501f2f2961e5cb82e9715d70ed2d3edb87d2a6d4fe291270ac0"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "CALM",
      "digest": "8d1918e5d4decc762582076299d02448f6bb6105a50ddb8cc3a846fa8c3e23c8"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "NORMAL",
      "digest": "b6d321ded7eeec373c0e3c72e920181021dfc103af309fce99b3f7bf150400c0"
    },
    {
      "seed": "-9223372036854775808",
      "difficulty": "FAST",
      "digest": "0fc7f0b750ed03a2c896ed73828a90c23a7e65be72d569876d1ae4dfacb5a857"
    }
  ],
  "forced": [
    {
      "seed": "0",
      "difficulty": "NORMAL",
      "digest": "c2da9ec4e05560e581255bb481cb03729a38a9a1f8dbae9bdedb4c9a23e705c9",
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
      "digest": "193632936c2686b4152522ac841f7a98aca1f9d5cee563fbfd84859001598703",
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
      "digest": "94676719f70d0116e8de27f6853509a2d6f00687f702f2161ad4fd305f466c57",
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
      "digest": "eb321f15b8c7d33997c807a0fe4731f35eae94dd89137d097408e393f387194a",
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

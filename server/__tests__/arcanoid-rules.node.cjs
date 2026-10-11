'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const Rules=require('../services/ArcanoidRules');
const playing=()=>{const engine=Rules.initial();Rules.launchOrResume(engine);return engine;};
function force(engine,x,y,vx,vy){Object.assign(engine,{x,y,vx,vy,state:'PLAYING'});}

test('cooperative initial field and immutable JSON projection use fixed geometry',()=>{
  const engine=Rules.initial(),view=Rules.view(engine);
  assert.equal(view.width,1000);assert.equal(view.height,1400);assert.equal(view.radius,20);
  assert.deepEqual(view.paddleY,[1250,150]);assert.deepEqual(view.paddles,[500,500]);
  assert.equal(view.state,'READY');assert.equal(view.level,1);assert.equal(view.levelCount,6);
  assert.equal(view.bricks,1073741823);assert.equal(view.lives,3);assert.equal(view.score,0);
  view.paddles[0]=0;view.ball.x=0;assert.equal(Rules.view(engine).ball.x,500);
  assert.doesNotThrow(()=>JSON.stringify(Rules.snapshot(engine)));
});
test('fixed substeps are frame-rate independent and lost foreground time is bounded',()=>{
  const a=playing(),b=playing();for(let i=0;i<120;i++)Rules.update(a,1/120);for(let i=0;i<30;i++)Rules.update(b,1/30);
  const first=Rules.snapshot(a),second=Rules.snapshot(b);first.forEach((value,i)=>assert.ok(Math.abs(value-second[i])<1e-7));
  const c=playing(),before=c.y;Rules.update(c,30);assert.ok(Math.abs(c.y-before)<71);assert.equal(c.lives,3);
});
test('both paddles reflect and wall collision stays finite',()=>{
  const engine=playing();force(engine,500,1227,0,700);Rules.update(engine,.02);assert.ok(engine.vy<0);
  force(engine,500,193,0,-700);Rules.update(engine,.02);assert.ok(engine.vy>0);assert.equal(engine.lives,3);
  force(engine,21,900,-500,0);Rules.update(engine,.02);assert.ok(engine.vx>0);assert.ok(engine.x>=20);
  force(engine,979,900,500,0);Rules.update(engine,.02);assert.ok(engine.vx<0);assert.ok(engine.x<=980);
  assert.ok(Math.hypot(engine.vx,engine.vy)<=700);
});
test('brick face corner and penetration collisions score once without NaN',()=>{
  for(const values of [[116,400,0,700],[50,404,400,400],[110,430,170,-480]]){
    const engine=playing();force(engine,...values);Rules.update(engine,.04);
    assert.equal(engine.bricks&1,0);assert.equal(engine.score,10);
    Rules.update(engine,.04);assert.equal(engine.score,10);
    assert.ok(Rules.snapshot(engine).every(Number.isFinite));
  }
});
test('misses on either edge consume one shared life then require explicit launch',()=>{
  const engine=playing();
  for(let left=2;left>=0;left--){force(engine,20,left%2? -19:1419,0,left%2?-600:600);Rules.update(engine,.02);
    assert.equal(engine.lives,left);assert.equal(engine.state,left===0?'GAME_OVER':'READY');
    assert.equal(engine.vx,0);assert.equal(engine.vy,0);assert.equal(engine.y,1228);
    Rules.update(engine,1);assert.equal(engine.lives,left);Rules.launchOrResume(engine);
  }
  assert.equal(engine.state,'GAME_OVER');
});
test('six authored levels preserve score lives and pause between levels',()=>{
  const engine=Rules.initial(),masks=new Set();let score=0;
  for(let level=1;level<=6;level++){
    const view=Rules.view(engine);assert.equal(view.level,level);assert.equal(engine.state,'READY');assert.equal(Rules.nextLevel(engine),false);
    masks.add(engine.bricks);let bits=0;for(let i=0;i<30;i++)if(engine.bricks&(1<<i))bits++;score+=bits*10;
    let last=0;while(!(engine.bricks&(1<<last)))last++;
    engine.bricks=1<<last;engine.score=score-10;force(engine,66+last%6*146+50,420+Math.floor(last/6)*72-20,0,700);
    Rules.update(engine,.02);assert.equal(engine.state,'WON');assert.equal(engine.score,score);assert.equal(engine.lives,3);
    assert.ok(Rules.restore(Rules.snapshot(engine)));
    if(level<6){assert.equal(Rules.campaignComplete(engine),false);assert.equal(Rules.nextLevel(engine),true);assert.equal(engine.vy,0);}
    else {assert.equal(Rules.campaignComplete(engine),true);assert.equal(Rules.nextLevel(engine),false);}
  }
  assert.equal(masks.size,6);
});
test('canonical cooperative snapshots restore paused and reject impossible data',()=>{
  const engine=playing();Rules.update(engine,.04);const saved=Rules.snapshot(engine),copy=Rules.restore(saved);
  assert.equal(copy.state,'PAUSED');Rules.update(copy,10);assert.equal(copy.x,saved[1]);Rules.launchOrResume(copy);Rules.update(copy,.02);assert.notEqual(copy.x,saved[1]);
  for(const [index,value] of [[0,2],[1,1001],[2,-21],[3,711],[5,94],[6,1.5],[7,10],[8,1073741824],[9,5],[10,4000],[11,6],[12,0],[13,906]]){
    const bad=saved.slice();bad[index]=value;assert.equal(Rules.restore(bad),null,`field${index}`);
  }
  for(const bad of [null,{},saved.slice(0,11),saved.map(value=>String(value)),saved.map((value,index)=>index===1?NaN:value)])assert.equal(Rules.restore(bad),null);
  const impossible=saved.slice();impossible[9]=3;assert.equal(Rules.restore(impossible),null);
  impossible[9]=4;assert.equal(Rules.restore(impossible),null);
});
test('paddle inputs clamp safely and ready ball follows only the bottom player',()=>{
  const engine=Rules.initial();Rules.movePaddle(engine,0,-100);assert.equal(engine.paddle,95);assert.equal(engine.x,95);
  Rules.movePaddle(engine,1,9999);assert.equal(engine.secondPaddle,905);assert.equal(engine.x,95);
  assert.equal(Rules.movePaddle(engine,2,500),false);assert.equal(Rules.movePaddle(engine,0,NaN),false);assert.equal(engine.paddle,95);
});


const golden=require('./fixtures/arcanoid-java-golden.json');
for(const fixture of golden.fixtures)test(`recorded production Java trajectory: ${fixture.name}`,()=>{
  let engine=Rules.initial(),saved;
  for(const command of fixture.commands){
    const parts=command.split(' ');
    switch(parts[0]){
      case 'launch':Rules.launchOrResume(engine);break;
      case 'pause':Rules.pause(engine);break;
      case 'update':Rules.update(engine,Number(parts[1]));break;
      case 'move':Rules.movePaddle(engine,Number(parts[1]),Number(parts[2]));break;
      case 'force':force(engine,...parts.slice(1).map(Number));break;
      case 'last':{
        let bits=0,last=0;for(let i=0;i<30;i++)if(engine.bricks&(1<<i))bits++;
        while(!(engine.bricks&(1<<last)))last++;
        engine.score+=(bits-1)*10;engine.bricks=1<<last;
        force(engine,66+last%6*146+50,420+Math.floor(last/6)*72-20,0,700);break;
      }
      case 'next':Rules.nextLevel(engine);break;
      case 'save':saved=Rules.snapshot(engine);break;
      case 'restore':engine=Rules.restore(saved);assert.ok(engine);break;
      default:assert.fail('Unknown golden operation');
    }
  }
  const snapshot=Rules.snapshot(engine);
  snapshot.forEach((value,index)=>assert.ok(Math.abs(value-fixture.finalSnapshot[index])<=golden.tolerance,
    `${fixture.name} field${index}: actual${value} golden${fixture.finalSnapshot[index]}`));
});

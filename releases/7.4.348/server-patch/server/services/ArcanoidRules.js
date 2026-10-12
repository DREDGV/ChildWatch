"use strict";

// Fixed canonical cooperative field. Port of the existing Java engine; only server advances it.
const WIDTH = 1000, HEIGHT = 1400, RADIUS = 20;
const PADDLE_WIDTH = 190, PADDLE_HEIGHT = 22, PADDLE_Y = [1250, 150];
const COLUMNS = 6, BRICKS = 30, BRICK_WIDTH = 136, BRICK_HEIGHT = 56;
const STATES = ['READY','PLAYING','PAUSED','WON','GAME_OVER'];
const PATTERNS = [
  '111111111111111111111111111111', '011110111111110011111111011110',
  '100001110011111111011110001100', '101010010101101010010101111111',
  '001100011110111111011110001100', '111111100001101101100001111111'
];
const clamp = (value,min,max) => Math.max(min,Math.min(max,value));
const pattern = level => { let mask = 0; for (let i=0;i<BRICKS;i++) if(PATTERNS[level][i]==='1') mask |= 1 << i; return mask; };
const count = mask => { let bits=0; for(let i=0;i<BRICKS;i++) if(mask & (1 << i)) bits++; return bits; };
const completedScore = before => { let total=0; for(let i=0;i<before;i++)total+=count(pattern(i))*10; return total; };
const brickX = index => 66 + index % COLUMNS * 146;
const brickY = index => 420 + Math.floor(index / COLUMNS) * 72;
function attachBall(engine) {engine.x=engine.paddle;engine.y=PADDLE_Y[0]-RADIUS-2;engine.vx=engine.vy=0;}
function initial() {
  const engine={x:500,y:1228,vx:0,vy:0,paddle:500,secondPaddle:500,lives:3,score:0,
    bricks:pattern(0),levelIndex:0,state:'READY',accumulator:0};
  return engine;
}
function campaignComplete(engine) {return engine.state==='WON' && engine.levelIndex===PATTERNS.length-1;}
function nextLevel(engine) {
  if(engine.state!=='WON'||campaignComplete(engine))return false;
  engine.levelIndex++;engine.bricks=pattern(engine.levelIndex);engine.state='READY';engine.accumulator=0;attachBall(engine);return true;
}
function movePaddle(engine,player,x) {
  if(!Number.isFinite(x)||(player!==0&&player!==1))return false;
  const value=clamp(x,PADDLE_WIDTH/2,WIDTH-PADDLE_WIDTH/2);
  if(player===0){engine.paddle=value;if(engine.state==='READY')attachBall(engine);}else engine.secondPaddle=value;
  return true;
}
function launchOrResume(engine) {
  if(engine.state==='READY') {
    engine.vx=170;engine.vy=-(480+engine.levelIndex*25);
    const speed=Math.hypot(engine.vx,engine.vy);if(speed>700){engine.vx*=700/speed;engine.vy*=700/speed;}
    engine.state='PLAYING';
  } else if(engine.state==='PAUSED')engine.state='PLAYING';
  return engine;
}
function pause(engine) {if(engine.state==='PLAYING')engine.state='PAUSED';engine.accumulator=0;return engine;}
function collide(engine,left,top,width,height) {
  let dx=engine.x-clamp(engine.x,left,left+width),dy=engine.y-clamp(engine.y,top,top+height);
  const d2=dx*dx+dy*dy;if(d2>RADIUS*RADIUS)return false;
  if(d2<1e-9) {
    const distances=[engine.x-left,left+width-engine.x,engine.y-top,top+height-engine.y];
    let side=0;for(let i=1;i<4;i++)if(distances[i]<distances[side])side=i;
    dx=side===0?-1:side===1?1:0;dy=side===2?-1:side===3?1:0;
    if(side===0)engine.x=left-RADIUS;if(side===1)engine.x=left+width+RADIUS;
    if(side===2)engine.y=top-RADIUS;if(side===3)engine.y=top+height+RADIUS;
  } else {
    const distance=Math.sqrt(d2);dx/=distance;dy/=distance;
    engine.x+=dx*(RADIUS-distance);engine.y+=dy*(RADIUS-distance);
  }
  const dot=engine.vx*dx+engine.vy*dy;if(dot<0){engine.vx-=2*dot*dx;engine.vy-=2*dot*dy;}return true;
}
function step(engine,dt) {
  const previousY=engine.y;
  engine.x+=engine.vx*dt;engine.y+=engine.vy*dt;
  if(engine.x<RADIUS){engine.x=RADIUS;engine.vx=Math.abs(engine.vx);}
  if(engine.x>WIDTH-RADIUS){engine.x=WIDTH-RADIUS;engine.vx=-Math.abs(engine.vx);}
  if(engine.vy<0&&previousY-RADIUS>=PADDLE_Y[1]+PADDLE_HEIGHT
      &&engine.y-RADIUS<=PADDLE_Y[1]+PADDLE_HEIGHT
      &&engine.x>=engine.secondPaddle-PADDLE_WIDTH/2-RADIUS&&engine.x<=engine.secondPaddle+PADDLE_WIDTH/2+RADIUS) {
    const angle=clamp((engine.x-engine.secondPaddle)/(PADDLE_WIDTH/2),-1,1)*Math.PI/3;
    const speed=Math.min(700,Math.hypot(engine.vx,engine.vy)+4);
    engine.vx=Math.sin(angle)*speed;engine.vy=Math.cos(angle)*speed;engine.y=PADDLE_Y[1]+PADDLE_HEIGHT+RADIUS;
  }
  if(engine.vy>0&&previousY+RADIUS<=PADDLE_Y[0]&&engine.y+RADIUS>=PADDLE_Y[0]
      &&engine.x>=engine.paddle-PADDLE_WIDTH/2-RADIUS&&engine.x<=engine.paddle+PADDLE_WIDTH/2+RADIUS) {
    const angle=clamp((engine.x-engine.paddle)/(PADDLE_WIDTH/2),-1,1)*Math.PI/3;
    const speed=Math.min(700,Math.hypot(engine.vx,engine.vy)+4);
    engine.vx=Math.sin(angle)*speed;engine.vy=-Math.cos(angle)*speed;engine.y=PADDLE_Y[0]-RADIUS;
  }
  for(let i=0;i<BRICKS;i++)if((engine.bricks&(1<<i))&&collide(engine,brickX(i),brickY(i),BRICK_WIDTH,BRICK_HEIGHT)) {
    engine.bricks&=~(1<<i);engine.score+=10;if(engine.bricks===0)engine.state='WON';break;
  }
  if(engine.y-RADIUS>HEIGHT||engine.y+RADIUS<0) {
    engine.lives--;engine.state=engine.lives===0?'GAME_OVER':'READY';attachBall(engine);engine.accumulator=0;
  }
}
function update(engine,seconds) {
  if(engine.state!=='PLAYING'||!Number.isFinite(seconds)||seconds<=0)return engine;
  engine.accumulator+=Math.min(seconds,.1);
  while(engine.accumulator>=1/240&&engine.state==='PLAYING'){engine.accumulator-=1/240;step(engine,1/240);}
  return engine;
}
function snapshot(engine) {
  return [3,engine.x,engine.y,engine.vx,engine.vy,engine.paddle,engine.lives,engine.score,
    engine.bricks,STATES.indexOf(engine.state),HEIGHT,engine.levelIndex,1,engine.secondPaddle];
}
function restore(saved) {
  if(!Array.isArray(saved)||saved.length!==14||saved.some(value=>!Number.isFinite(value))
      ||saved[0]!==3||saved[10]!==HEIGHT||saved[12]!==1
      ||!Number.isInteger(saved[11])||saved[11]<0||saved[11]>=PATTERNS.length
      ||!Number.isInteger(saved[9])||saved[9]<0||saved[9]>=STATES.length
      ||saved[1]<0||saved[1]>WIDTH||saved[2]<-RADIUS||saved[2]>HEIGHT+RADIUS
      ||Math.hypot(saved[3],saved[4])>710
      ||saved[5]<PADDLE_WIDTH/2||saved[5]>WIDTH-PADDLE_WIDTH/2
      ||saved[13]<PADDLE_WIDTH/2||saved[13]>WIDTH-PADDLE_WIDTH/2
      ||!Number.isInteger(saved[6])||saved[6]<0||saved[6]>3
      ||!Number.isInteger(saved[8])||saved[8]<0||saved[8]>(1<<BRICKS)-1)return null;
  const original=pattern(saved[11]),state=STATES[saved[9]];
  if((saved[8]&~original)!==0||saved[7]!==completedScore(saved[11])+(count(original)-count(saved[8]))*10
      ||(state==='WON')!==(saved[8]===0)||(state==='GAME_OVER')!==(saved[6]===0))return null;
  const engine={x:saved[1],y:saved[2],vx:saved[3],vy:saved[4],paddle:saved[5],lives:saved[6],score:saved[7],
    bricks:saved[8],levelIndex:saved[11],secondPaddle:saved[13],state:state==='PLAYING'?'PAUSED':state,accumulator:0};
  if(engine.state==='READY')attachBall(engine);return engine;
}
function view(engine) {
  return {width:WIDTH,height:HEIGHT,radius:RADIUS,paddleWidth:PADDLE_WIDTH,paddleHeight:PADDLE_HEIGHT,
    paddles:[engine.paddle,engine.secondPaddle],paddleY:[...PADDLE_Y],ball:{x:engine.x,y:engine.y,vx:engine.vx,vy:engine.vy},
    bricks:engine.bricks,level:engine.levelIndex+1,levelCount:PATTERNS.length,lives:engine.lives,score:engine.score,state:engine.state};
}
module.exports={initial,restore,snapshot,view,launchOrResume,pause,movePaddle,update,nextLevel,campaignComplete};

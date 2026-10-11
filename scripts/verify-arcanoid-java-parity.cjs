"use strict";
// Actual production Java vs canonical server physics; no Gradle or Android installation.
// Run: node scripts/verify-arcanoid-java-parity.cjs [--java-home JDK_DIRECTORY]
// Initial fixture authoring only: --record-golden (review resulting committed vectors).
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const Rules=require('../server/services/ArcanoidRules');
const root=path.resolve(__dirname,'..'),out=path.join(root,'.runtime','arcanoid-java-parity');
const engine=path.join(root,'design-system/src/main/java/ru/example/childwatch/designsystem/ArcanoidEngine.java');
const runner=path.join(root,'server/__tests__/fixtures/ArcanoidGoldenRunner.java');
const goldenFile=path.join(root,'server/__tests__/fixtures/arcanoid-java-golden.json');
const args=process.argv.slice(2);let javaHome=process.env.JAVA_HOME,record=false;
for(let i=0;i<args.length;i++)if(args[i]==='--java-home'&&args[i+1])javaHome=args[++i];
else if(args[i]==='--record-golden')record=true;else throw new Error('Unknown argument');
const executable=name=>javaHome?path.join(javaHome,'bin',name+(process.platform==='win32'?'.exe':'')):name;
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function run(name,args,input){
  const result=spawnSync(executable(name),args,{cwd:root,input,encoding:'utf8',timeout:60000,maxBuffer:4*1024*1024});
  if(result.error||result.status!==0)throw new Error(`${name}: ${result.error?.message||result.stderr}`);
  return result.stdout;
}
function apply(engine,input,context){
  const fields=input.split(' ');
  switch(fields[0]){
    case 'launch':Rules.launchOrResume(engine);break;
    case 'pause':Rules.pause(engine);break;
    case 'update':Rules.update(engine,Number(fields[1]));break;
    case 'move':Rules.movePaddle(engine,Number(fields[1]),Number(fields[2]));break;
    case 'force':Object.assign(engine,{x:Number(fields[1]),y:Number(fields[2]),vx:Number(fields[3]),vy:Number(fields[4]),state:'PLAYING'});break;
    case 'last':{
      let last=0,bits=0;for(let i=0;i<30;i++)if(engine.bricks&(1<<i))bits++;
      while(!(engine.bricks&(1<<last)))last++;
      engine.score+=(bits-1)*10;engine.bricks=1<<last;
      Object.assign(engine,{x:66+last%6*146+50,y:420+Math.floor(last/6)*72-20,vx:0,vy:700,state:'PLAYING'});break;
    }
    case 'next':Rules.nextLevel(engine);break;
    case 'save':context.saved=Rules.snapshot(engine);break;
    case 'restore':engine=Rules.restore(context.saved);if(!engine)throw new Error('Invalid restore fixture');break;
    default:throw new Error('Unknown command');
  }
  return engine;
}
const fixtures=[
  {name:'bottom-center',commands:['launch','force 500 1227 0 700','update 0.02','update 0.04']},
  {name:'top-center',commands:['launch','force 500 193 0 -700','update 0.02','update 0.04']},
  {name:'bottom-edge-angle',commands:['move 0 500','launch','force 585 1227 0 690','update 0.03','update 0.08']},
  {name:'top-edge-angle',commands:['move 1 500','launch','force 415 193 0 -690','update 0.03','update 0.08']},
  {name:'left-right-walls',commands:['launch','force 21 900 -500 0','update 0.02','force 979 900 500 0','update 0.02']},
  {name:'brick-face',commands:['launch','force 116 400 0 700','update 0.04','update 0.04']},
  {name:'brick-corner',commands:['launch','force 50 404 400 400','update 0.04','update 0.04']},
  {name:'penetrated-brick',commands:['launch','force 110 430 170 -480','update 0.04','update 0.04']},
  {name:'three-shared-losses',commands:['launch','force 20 1419 0 600','update 0.02','launch','force 20 -19 0 -600','update 0.02','launch','force 20 1419 0 600','update 0.02','launch']},
  {name:'clamps-pause-restore-gap',commands:['move 0 -1000','move 1 5000','move 0 NaN','launch','update 0.031','pause','update 20','launch','update 0.019','save','restore','update 10','launch','update 30']},
  {name:'six-level-campaign',commands:Array.from({length:6},()=>['last','update 0.02','save','restore','next']).flat()},
  {name:'240Hz-substeps',commands:['launch',...Array(480).fill('update 0.004166666666666667')]},
  {name:'60Hz-moving-paddles',commands:['launch',...Array.from({length:900},(_,i)=>[
    `move 0 ${500+Math.sin(i*.035)*330}`,`move 1 ${500+Math.cos(i*.027)*330}`,'update 0.016666666666666666']).flat()]}
];
fs.mkdirSync(out,{recursive:true});run('javac',['-encoding','UTF-8','-d',out,engine,runner]);
let snapshots=0,maxDifference=0;
const results=[];
for(const fixture of fixtures){
  const actual=run('java',['-cp',out,'ru.example.childwatch.designsystem.ArcanoidGoldenRunner'],fixture.commands.join('\n')+'\n')
    .trim().split(/\r?\n/).map(line=>JSON.parse(line));
  let state=Rules.initial();const context={},expected=[Rules.snapshot(state)];
  for(const command of fixture.commands){state=apply(state,command,context);expected.push(Rules.snapshot(state));}
  if(actual.length!==expected.length)throw new Error('Snapshot count mismatch');
  for(let i=0;i<actual.length;i++)for(let j=0;j<14;j++){
    const difference=Math.abs(actual[i][j]-expected[i][j]);maxDifference=Math.max(maxDifference,difference);
    if(difference>1e-7)throw new Error(`Parity ${fixture.name} step${i} field${j}: Java${actual[i][j]} Node${expected[i][j]}`);
  }
  snapshots+=actual.length;results.push({...fixture,finalSnapshot:actual.at(-1),javaDigest:hash(actual.map(value=>JSON.stringify(value)).join('\n'))});
}
const golden={tolerance:1e-7,javaEngineSha256:hash(fs.readFileSync(engine)),fixtures:results};
if(record)fs.writeFileSync(goldenFile,JSON.stringify(golden,null,2)+'\n');
else{
  const stored=JSON.parse(fs.readFileSync(goldenFile,'utf8'));
  if(stored.javaEngineSha256!==golden.javaEngineSha256)throw new Error('Java source changed: review and record updated fixtures explicitly');
  for(let i=0;i<results.length;i++)if(results[i].javaDigest!==stored.fixtures[i]?.javaDigest)throw new Error(`Java golden changed: ${results[i].name}`);
}
const report={result:'PASS',cases:fixtures.length,snapshots,maxDifference,tolerance:1e-7,
  javaEngineSha256:golden.javaEngineSha256,javaRunnerSha256:hash(fs.readFileSync(runner)),
  nodeRulesSha256:hash(fs.readFileSync(path.join(root,'server/services/ArcanoidRules.js')))};
fs.writeFileSync(path.join(out,'verification.json'),JSON.stringify(report,null,2)+'\n');
console.log(`Arcanoid Java/Node parity PASS ${fixtures.length} cases/${snapshots} snapshots, max error ${maxDifference}`);
console.log(path.join(out,'verification.json'));

const SIZE = 10;
const LENGTHS = [4,3,3,2,2,2,1,1,1,1];
function fleet(input) {
  if (!Array.isArray(input) || input.length !== 10) return null;
  const result = input.map(ship => Array.isArray(ship) ? [...ship].sort((a,b)=>a-b) : []);
  if (result.map(s=>s.length).sort((a,b)=>b-a).join() !== LENGTHS.join()) return null;
  const owners = new Map();
  for (let i=0;i<result.length;i++) {
    const ship=result[i];
    if (ship.some(c=>!Number.isInteger(c)||c<0||c>=100)) return null;
    const horizontal=ship.every(c=>Math.floor(c/10)===Math.floor(ship[0]/10));
    if (!ship.every((c,j)=>c===ship[0]+j*(horizontal?1:10))) return null;
    for(const c of ship) { if(owners.has(c)) return null; owners.set(c,i); }
  }
  for (const [cell,owner] of owners) for(let dr=-1;dr<=1;dr++) for(let dc=-1;dc<=1;dc++) {
    const r=Math.floor(cell/10)+dr,c=cell%10+dc;
    if(r>=0&&r<10&&c>=0&&c<10&&owners.has(r*10+c)&&owners.get(r*10+c)!==owner) return null;
  }
  return result.sort((a,b)=>b.length-a.length||a[0]-b[0]);
}
const initial=()=>({fleets:{X:null,O:null},shots:{X:[],O:[]}});
function fire(state,mark,cell) {
  const other=mark==='X'?'O':'X';
  const ship=state.fleets[other].find(s=>s.includes(cell));
  const shots=state.shots[mark];
  const sunk=ship&&ship.every(c=>c===cell||shots.some(s=>s.cell===c));
  const result=ship?(sunk?'SUNK':'HIT'):'MISS';
  shots.push({cell,result});
  return {result,won:state.fleets[other].every(s=>s.every(c=>shots.some(shot=>shot.cell===c)))};
}
function view(state,mark,terminal,nextMemberId) {
  const other=mark==='X'?'O':'X';
  const sunk=(ships,shots)=>(ships||[]).filter(ship=>ship.every(c=>shots.some(s=>s.cell===c)));
  return {size:SIZE,myShips:state.fleets[mark],opponentShips:terminal?state.fleets[other]:null,
    myReady:!!state.fleets[mark],opponentReady:!!state.fleets[other],
    myShots:state.shots[mark],opponentShots:state.shots[other],
    mySunkShips:sunk(state.fleets[mark],state.shots[other]),
    opponentSunkShips:sunk(state.fleets[other],state.shots[mark]),nextMemberId};
}
module.exports={fleet,initial,fire,view};

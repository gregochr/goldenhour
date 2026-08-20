/* PhotoCast Plan tab v2 — the six pictures ARE the plan.
   The verdict pills sit on the thumbnails, so the six-row list they used to label is gone.
   Everything that was inside a row body (map, region pills, tide, locations) is now one
   popup, opened by the picture it belongs to. Origin still sets the frame of reference. */
const {REGIONS,HOME,WINS,CONF,RUNAGE,DELTA,NARR,WHY,SPOTS,SETUP,TOPICS,WTOPICS}=PLAN;
/* A window's topics, resolved to glyph and copy, rarest first, and filtered to what the
   CURRENT scope can honestly see: a king tide needs a coast in scope, an inversion needs
   valleys. Plan from the Lake District and the tide topic drops out on its own. */
const topicsOf=i=>(WTOPICS[WINS[i].id]||[]).map(x=>{
  const rids=PLAN.topicRids(x.t).filter(r=>scopeRids().includes(r));
  return{...TOPICS[x.t],...x,rids};
 }).filter(x=>x.rids.length).sort((a,b)=>a.w-b.w);
const RNAME=Object.fromEntries(REGIONS.map(r=>[r.id,r.n]));
const clamp=HeatField.clamp,ramp=HeatField.ramp,rgb=HeatField.rgb;
const REACH={'45':45,'90':90,'150':150,'any':1e9};
const RLBL={'45':'45 min','90':'1h 30min','150':'2h 30min','any':'Any'};
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');

/* win: which window's popup is open (null = closed). No `open` row state any more. */
const S={win:null,reg:null,reach:'150',rate:'4',origin:HOME,spot:null,
 searching:false,q:'',sel:0,exp:new Set()};

/* ── origin-derived basis ─────────────────────────────────────────────────── */
const isHome=o=>!!o.home;
const {GLANCE,beyondRids}=PLAN;
let _area=null,_rids=null,_spots=null,_rank=null;
function bumpCache(){_area=_rids=_spots=_rank=null}
const areaRids=()=>_area||(_area=PLAN.areaRids());
const scopeRids=()=>_rids||(_rids=S.origin.all?areaRids():[S.origin.id]);
const scopeSpots=()=>_spots||(_spots=PLAN.spotsIn(scopeRids()));
const inScope=s=>scopeRids().includes(s.rid);
const driveOf=s=>isHome(S.origin)?s.min:s.lmin;
const inReach=s=>driveOf(s)<=REACH[S.reach];
const meetsRate=(s,i)=>S.rate==='any'||s.r[i]>=+S.rate;
const rateLbl=()=>S.rate==='any'?'any rating':S.rate+'★+';
const activeFilters=()=>[S.reg&&!S.origin.all?null:S.reg?RNAME[S.reg]:null,rateLbl(),
 S.reach==='any'?'any drive':'within '+RLBL[S.reach].toLowerCase()].filter(Boolean);
const poolFor=i=>scopeSpots().filter(s=>s.named&&meetsRate(s,i)&&inReach(s)&&(!S.reg||s.rid===S.reg));

const vCls=s=>s>=3.7?'g':s>=2.8?'m':'p';
const vWord=s=>s>=3.7?'Worth it':s>=2.8?'Maybe':'Poor';
function rScore(rid,win){const rs=SPOTS.filter(s=>s.rid===rid).map(s=>s.r[win]);
 return{avg:d3.mean(rs)||0,best:d3.max(rs)||0,n:rs.length,good:rs.filter(v=>v>=4).length}}
const railRids=()=>scopeRids();
const topAvg=win=>d3.max(railRids().map(r=>rScore(r,win).avg))||0;
const topRegion=win=>railRids().reduce((a,b)=>rScore(b,win).avg>rScore(a,win).avg?b:a);
/* measured over the named locations in scope you could reach, and deliberately NOT filtered
   by the rating floor — an average of things that passed a 4★ filter always reads 4-something */
const reachPool=i=>scopeSpots().filter(s=>s.named&&inReach(s));
const wMax=i=>d3.max(reachPool(i),s=>s.r[i])||0;
const wAvg=i=>d3.mean(reachPool(i),s=>s.r[i])||0;
/* One row instead of two label/value rows: the shape of the night. Bars are 1★→5★ counts,
   so a lone spike on the right reads "one good spot, drive to it" and a right-weighted block
   reads "the whole area is on" — which two averaged digits could never say. The average was
   the verdict word again in numerals; the ceiling stays, because it is what you would chase. */
const histOf=i=>{const p=reachPool(i),b=[0,0,0,0,0];
 p.forEach(s=>{b[clamp(Math.round(s.r[i]),1,5)-1]++});
 const mx=d3.max(b)||1;
 return{n:p.length,
  html:b.map((c,k)=>`<i style="height:${c?Math.max(2,Math.round(c/mx*13)):1}px;background:${c?rgb(ramp(k+1),.92):'rgba(242,231,211,.13)'}"></i>`).join(''),
  title:`${p.length} locations within reach — `+b.map((c,k)=>`${c} at ${k+1}★`).reverse().join(', ')};
};
const wDelta=win=>DELTA[topRegion(win)][win];
/* exactly one best bet and exactly one runner-up: a recommendation that fires on three of
   six windows is not a recommendation. Memoised with the other derived caches. */
const rank=()=>_rank||(_rank=WINS.map((w,i)=>i).sort((a,b)=>topAvg(b)-topAvg(a)||a-b));
const bestWin=()=>rank()[0];
const alsoWin=i=>i===rank()[1]&&vCls(topAvg(i))!=='p';

const hm2m=t=>{const[a,b]=t.split(':').map(Number);return a*60+b};
const m2hm=m=>{m=((m%1440)+1440)%1440;return String(Math.floor(m/60)).padStart(2,'0')+':'+String(m%60).padStart(2,'0')};
const leaveBy=(w,s)=>m2hm(hm2m(w.time)-driveOf(s)-SETUP);
const fmtDrive=m=>{const hh=Math.floor(m/60),mm=m%60;return hh?`${hh}h ${mm}min`:`${mm} min`};
/* Sunrise or sunset, said in words. An arrow carried two meanings in one glyph — a down arrow
   for sunset reads as a falling forecast — so the row states which end of the day it is. */
const sunMark=w=>`<span class="ar">${/sunrise/i.test(w.when)?'SUNRISE':'SUNSET'}</span>`;
const dChip=d=>!d?'<span class="dl same">—</span>'
 :`<span class="dl ${d>0?'up':'dn'}">${d>0?'▲':'▼'}${Math.abs(d).toFixed(1)}</span>`;
const fitOf=()=>HeatField.bbox(scopeSpots(),S.origin.all?0.10:0.13);
const frameAspect=()=>HeatField.aspect(fitOf());

/* ── search: places, regions and days in one box ──────────────────────────── */
const norm=s=>s.toLowerCase().replace(/['’`]/g,'').replace(/&/g,' and ').replace(/\bsaint\b/g,'st').replace(/[^a-z0-9]+/g,' ').trim();
const squash=s=>norm(s).replace(/ /g,'');
function score(o,q){
 const nq=norm(q);if(!nq)return -1;
 const hay=norm(o.n),toks=hay.split(' '),qt=nq.split(' '),sq=squash(o.n);
 let sc=0,missed=0;
 for(const t of qt){
  if(hay.startsWith(t)){sc+=60;continue}
  const ti=toks.findIndex(x=>x.startsWith(t));if(ti>-1){sc+=40-ti*2;continue}
  if(hay.includes(t)){sc+=18;continue}
  if(sq.includes(t)){sc+=26;continue}
  if((o.al||[]).find(x=>norm(x).split(' ').some(y=>y.startsWith(t))||squash(x).includes(t))){sc+=30;continue}
  missed++;
 }
 if(!sc||missed>1||missed>=qt.length)return -1;
 return sc-missed*20;
}
const DAYW=[['tonight','today','tue','tuesday','18'],['tomorrow','wed','wednesday','19'],
 ['tomorrow','wed','wednesday','19'],['thu','thursday','20'],['thu','thursday','20'],['fri','friday','21']];
const WINW={Sunrise:['sunrise','dawn','morning','am','first light'],Sunset:['sunset','dusk','evening','pm','golden hour']};
function scoreW(i,q){
 const ev=/sunrise/i.test(WINS[i].when)?'Sunrise':'Sunset',qt=norm(q).split(' ');
 let day=0,win=0,missed=0;
 for(const t of qt){
  if(DAYW[i].some(x=>x.startsWith(t)&&t.length>1)){day=1;continue}
  if(WINW[ev].some(x=>x.startsWith(t)&&t.length>2)){win=1;continue}
  missed++;
 }
 if((!day&&!win)||missed)return -1;
 return 40+day*30+win*30+(day&&win?20:0);
}
function hl(name,q){
 const nq=norm(q).split(' ')[0];if(!nq)return esc(name);
 let map=[],nn='';
 for(let i=0;i<name.length;i++){const c=norm(name[i]);if(c){nn+=c;map.push(i)}}
 let at=nn.indexOf(nq),s,e;
 if(at<0){const sn=nn.replace(/ /g,''),sm=map.filter((_,i)=>nn[i]!==' '),k=sn.indexOf(nq);
  if(k<0)return esc(name);s=sm[k];e=sm[k+nq.length-1]}
 else{s=map[at];e=map[at+nq.length-1]}
 return esc(name.slice(0,s))+'<mark>'+esc(name.slice(s,e+1))+'</mark>'+esc(name.slice(e+1));
}
function items(){
 if(!S.q.trim())return null;
 const ws=WINS.map((_,i)=>({k:'w',o:i,sc:scoreW(i,S.q)})).filter(x=>x.sc>=0).sort((a,b)=>b.sc-a.sc);
 const rs=REGIONS.map(o=>({k:'r',o,sc:score(o,S.q)})).filter(x=>x.sc>=0).sort((a,b)=>b.sc-a.sc);
 const ss=SPOTS.filter(s=>s.named).map(o=>({k:'s',o,sc:score(o,S.q)})).filter(x=>x.sc>=0)
  .sort((a,b)=>b.sc-a.sc).slice(0,5);
 return[...ws,...rs,...ss];
}
const bestIdx=sp=>sp.r.indexOf(d3.max(sp.r));
function rowS(sp,i){
 const bi=bestIdx(sp),w=WINS[bi],out=!inScope(sp);
 return `<button class="res ${i===S.sel?'sel':''}" data-pick="${esc(sp.n)}"><span class="gl">◇</span><span><span class="nm">${hl(sp.n,S.q)}</span>
  <span class="sub"><span>${esc(RNAME[sp.rid])}</span><span>${out?sp.min+' min from home':fmtDrive(driveOf(sp))}</span>${out?'<span class="out">outside your plan</span>':''}</span></span>
  <span class="bst"><b>${sp.r[bi]}.0★</b>${(w.lbl||w.when).toLowerCase()}</span><span class="act">4 days</span></button>`;
}
function rowR(r,i){
 const bars=WINS.map((_,j)=>rScore(r.id,j).best),bi=bars.indexOf(d3.max(bars));
 const n=SPOTS.filter(s=>s.rid===r.id&&s.named).length,cur=S.origin.id===r.id;
 return `<button class="res reg ${i===S.sel?'sel':''}" data-rpick="${r.id}"><span class="gl">◎</span><span><span class="nm">${hl(r.n,S.q)}</span>
  <span class="sub"><span>${n} locations</span>${r.home?`<span>your region · from ${esc(r.base)}</span>`:`<span class="aw">away · from ${esc(r.base)}</span>`}</span></span>
  <span class="bst"><b>${bars[bi]}.0★</b>${(WINS[bi].lbl||WINS[bi].when).toLowerCase()}</span><span class="act">${cur?'Planning now':'Plan from here'}</span></button>`;
}
function rowW(i,sel){
 const w=WINS[i],pool=scopeSpots().filter(s=>s.named&&inReach(s));
 const top=pool.slice().sort((x,y)=>y.r[i]-x.r[i]||driveOf(x)-driveOf(y))[0];
 return `<button class="res ${sel===S.sel?'sel':''}" data-wpick="${i}"><span class="gl" style="color:var(--tide)">◷</span><span><span class="nm">${w.lbl||w.when}</span>
  <span class="sub"><span>${w.time}</span><span>${pool.length} in reach</span></span></span>
  <span class="bst">${top?`<b>${top.r[i]}.0★</b>${esc(top.n)}`:'<b>—</b>nothing in reach'}</span><span class="act" style="color:#9CCBD1;border-color:rgba(111,168,176,.35)">Open window</span></button>`;
}
const rowOf=(x,i)=>x.k==='w'?rowW(x.o,i):x.k==='r'?rowR(x.o,i):rowS(x.o,i);

function renderDrop(){
 const drop=document.getElementById('drop'),xs=items();
 if(xs===null){
  const rec=["St Mary’s Lighthouse",'Bamburgh Castle','Simonside'].map(n=>SPOTS.find(s=>s.n===n)).filter(Boolean);
  drop.innerHTML=`<div class="dtitle">Windows <span class="hint">or type a day — “thursday sunset”</span></div>
  <div class="dlist">${[0,1,2].map(i=>rowW(i,-1)).join('')}</div>
  <div class="dtitle brk">Recent locations</div>
  <div class="dlist">${rec.map(s=>rowS(s,-1)).join('')}</div>
  <div class="dfoot"><span><span class="k2">↑↓</span> move</span><span><span class="k2">enter</span> open</span><span>Regions live on the map — or type one</span></div>`;
  return;
 }
 if(!xs.length){
  drop.innerHTML=`<div class="dtitle">No match</div><div class="dnone"><b>Nothing called “${esc(S.q)}”.</b><br>Try a region, or a day like <span class="k2">thursday sunset</span>:</div>
  <div class="dlist">${[SPOTS.find(s=>s.n==='Bamburgh Castle'),SPOTS.find(s=>s.n==='Buttermere')].filter(Boolean).map(s=>rowS(s,-1)).join('')}</div>`;
  return;
 }
 const ws=xs.filter(x=>x.k==='w'),rs=xs.filter(x=>x.k==='r'),ss=xs.filter(x=>x.k==='s');
 drop.innerHTML=(ws.length?`<div class="dtitle">Window${ws.length>1?'s':''} <span class="hint">opens it</span></div><div class="dlist">${ws.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +(rs.length?`<div class="dtitle${ws.length?' brk':''}">Region${rs.length>1?'s':''} <span class="hint">re-points the plan and the heat</span></div><div class="dlist">${rs.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +(ss.length?`<div class="dtitle${ws.length||rs.length?' brk':''}">Location${ss.length>1?'s':''} <span class="hint">four-day view</span></div><div class="dlist">${ss.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +`<div class="dfoot"><span><span class="k2">↑↓</span> move</span><span><span class="k2">enter</span> open</span></div>`;
}

/* ── chrome ───────────────────────────────────────────────────────────────── */
const HOMESVG='<svg class="pinsvg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 10.6 12 4l8.5 6.6"></path><path d="M5.6 12.2V20h12.8v-7.8"></path></svg>';
const AWAYSVG='<svg class="pinsvg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21c4.2-5 6.3-8.3 6.3-11a6.3 6.3 0 1 0-12.6 0c0 2.7 2.1 6 6.3 11z"></path><circle cx="12" cy="9.9" r="2.3"></circle></svg>';
const isMob=()=>document.getElementById('wrap').classList.contains('mob');
/* today's light, stated once, in the masthead rule — golden from the data, blue derived */
function renderTicks(){
 const set=hm2m(WINS[0].time),rise=hm2m(WINS[1].time)-2;
 const t=[[rise-36,'blue'],[rise,'golden',1],[set,'golden',1],[set+41,'blue']];
 document.getElementById('ctimes').innerHTML=(isMob()?t.slice(1,3):t)
  .map(([m,k,hot])=>hot?`<span><b>${m2hm(m)} ${k}</b></span>`:`<span>${m2hm(m)} ${k}</span>`).join('');
}
/* the ONE statement of where you are planning from — the old breadcrumb repeated it */
function renderOrigin(){
 const o=S.origin,b=document.getElementById('orig'),mob=isMob();
 b.className='oloc'+(isHome(o)?'':' away');
 const label=o.all?(mob?'Home · DH3 4NG':'Home · DH3 4NG')
  :(isHome(o)?`${o.n} · from DH3 4NG`:`${o.n} · from ${o.base}`);
 b.innerHTML=`${isHome(o)?HOMESVG:AWAYSVG}<span class="nm">${esc(label)}</span><span class="sep"></span>
 <span class="find" title="Search a location, region or day"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="10.6" cy="10.6" r="6.4"></circle><path d="m15.4 15.4 4.2 4.2"></path></svg>${mob?'':'<span class="kbd">/</span>'}</span>`;
 document.getElementById('gohome').classList.toggle('on',!o.all);
 document.getElementById('rk').textContent=isHome(o)?'How far to travel':`Drive from ${o.base}`;
 document.getElementById('rk2').textContent=isHome(o)?'Drive':`From ${o.base}`;
 document.getElementById('sumk').textContent=o.all?'The days ahead':`The days ahead · ${o.n}`;
 renderTicks();
}
function syncLens(){
 const rc=[['45','45 min'],['90','1h 30'],['150','2h 30'],['any','Any']];
 const rt=[['any','Any'],['3','3★+'],['4','4★+']];
 const fill=(id,opts,key)=>{const el=document.getElementById(id);if(el)el.innerHTML=opts.map(([v,l])=>
  `<button class="${S[key]===v?'on':''}" data-set="${key}" data-v="${v}">${l}</button>`).join('')};
 fill('reach',rc,'reach');fill('reach2',rc,'reach');
 fill('rate',rt,'rate');fill('rate2',rt,'rate');
 const named=scopeSpots().filter(s=>s.named),reach=named.filter(inReach);
 const bey=beyondRids();
 document.getElementById('sumn').textContent=`${scopeSpots().length} rated locations · ${named.length} named`;
 document.getElementById('beyond').innerHTML=(S.origin.all&&bey.length)
  ?`Beyond ${GLANCE/60}h and not in the field: ${bey.map(r=>esc(RNAME[r])).join(' · ')} — <button data-openq="${esc(RNAME[bey[0]].split(' ')[0].toLowerCase())}">search to plan from one →</button>`:'';
 document.getElementById('cnt').innerHTML=`<b>${reach.length}</b> of ${named.length} locations within reach · ${rateLbl()}`;
 const def=S.reach==='45';
 for(const id of ['stick','rst'])document.getElementById(id).style.visibility=def?'hidden':'visible';
}

/* ── the six pictures: the plan itself, carrying their own verdicts ────────── */
/* The week as a matrix: a column per day, sunrise on the top row and sunset below it.
   Reading down a column is one day; reading across a row compares the same light day to day.
   Two cells are empty by definition — this morning has gone, the last evening is past the end
   of the forecast — and saying so is worth more than closing the gap. */
function renderStrip(){
 const el=document.getElementById('hstrip');el.innerHTML='';
 const days=[];
 WINS.forEach((w,i)=>{const k=w.dow+w.dn;let d=days.find(x=>x.k===k);
  if(!d)days.push(d={k,dow:w.dow,dn:w.dn});
  d[/sunrise/i.test(w.when)?'am':'pm']=i});
 el.style.setProperty('--dc',days.length);
 days.forEach((d,c)=>{
  const solo=(d.am==null)!==(d.pm==null);
  const hd=document.createElement('div');hd.className='dh'+(c===0?' tdy':'');hd.style.cssText=`--c:${c+1};--r:1`;
  hd.innerHTML=`<div class="cal"><span class="cd">${d.dow}</span><span class="cn">${d.dn}</span></div><i class="cr"></i>`;
  el.appendChild(hd);
  [['am',2],['pm',3]].forEach(([slot,r])=>{
   const i=d[slot];
   if(i==null){const g=document.createElement('div');g.className='hg';g.style.cssText=`--c:${c+1};--r:${r}`;
    g.innerHTML=`<span>${slot==='am'?'this morning has gone':'past the end of the forecast'}</span>`;
    el.appendChild(g);return}
   const b=card(i);b.style.cssText=`--c:${c+1};--r:${r}`;if(solo)b.classList.add('solo');el.appendChild(b)});
 });
 drawThumbs();
}
function card(i){
  const w=WINS[i];
  const t=topAvg(i),cl=vCls(t),b=document.createElement('button'),tps=topicsOf(i);
  const best=i===bestWin(),also=alsoWin(i);
  /* the pick rides the card's own border, like a legend on a fieldset: it stops competing
     with the verdict word for the same slot, and every card keeps its verdict */
  const legend=best?'<span class="lg wb">Best bet</span>':also?'<span class="lg wa">Also good</span>':'';
  b.className='hc v'+cl+(S.win===i?' on':'')+(best?' best':also?' also':'');
  b.setAttribute('data-screen-label',w.lbl||w.when);
  /* every topic is named on the card — a night with three of them is the most interesting
     night of the week, so hiding two behind a hover count had it exactly backwards */
  const twords=tps.map(x=>`<span class="tw" style="--tc:${x.c}" title="${esc(x.n)} — ${esc(x.d)}${x.needs?' — '+esc(x.scope)+' only':''}"><span class="ic">${x.ic}</span>${x.sh}</span>`).join('');
  const hs=histOf(i);
  /* the card names a place: the best spot you could actually reach, with the spread beside
     it as context. "best 5★" told you a 5★ existed somewhere; this tells you where to go. */
  const bs=reachPool(i).slice().sort((a,b)=>b.r[i]-a.r[i]||driveOf(a)-driveOf(b))[0];
  const bsc=bs?rgb(ramp(bs.r[i])):'';
  /* the rating takes the label slot — it needs no caption, and that hands the whole value
     column to the name, which is what actually needs the width */
  const bsHtml=bs?`<span class="rt" style="color:${bsc}">${bs.r[i]}★</span><span class="pv2"><span class="st1" title="${esc(bs.n)} · ${esc(RNAME[bs.rid])} · ${fmtDrive(driveOf(bs))} · leave ${leaveBy(w,bs)}">${esc(bs.n)}</span></span>`
   :`<span class="k2">Best</span><span class="pv2"><span class="st1 none">nothing in reach</span></span>`;
  b.innerHTML=`<div class="top">${sunMark(w)}</div>
  <canvas></canvas>
  <div class="pls"><span class="tt">${w.time}</span><span class="pv"><span class="vw v${cl}">${vWord(t)}</span></span>
  <span class="k2">Spread</span><span class="pv"><span class="hist" title="${esc(hs.title)}">${hs.html}</span></span>
  ${bsHtml}
  <span class="tps2">${twords}</span></div>${legend}`;
  b.onclick=()=>openWin(i);
  b.querySelector('canvas').dataset.w=i;
  return b;
}
function drawThumbs(tries){
 tries=tries||0;
 const el=document.getElementById('hstrip'),cvs=el.querySelectorAll('canvas');
 if(!(el.clientWidth>40)){if(tries<30)requestAnimationFrame(()=>drawThumbs(tries+1));return}
 /* every card is stacked, so the field takes its own card's width — measured per card, because
    a day with one window spans the whole row on the phone and its map should too */
 const fit=fitOf(),ar=clamp(frameAspect(),0.78,1.0),sp=scopeSpots();
 cvs.forEach(cv=>{const i=+cv.dataset.w;
  const cw=Math.max(120,Math.floor(cv.parentElement.clientWidth)-12);
  HeatField.drawGeo(cv,cw,Math.round(cw*ar),sp,i,
   {grid:4,radius:Math.max(10,cw*0.155),blur:2.4,line:0.5,conf:CONF[i],fit})});
}
function renderChange(){
 const el=document.getElementById('chgline');
 const moves=WINS.map((w,i)=>({w,i,d:wDelta(i)})).filter(x=>x.d);
 if(!moves.length){el.innerHTML=`Nothing moved since your last look ${RUNAGE} ago.`;return}
 moves.sort((a,b)=>Math.abs(b.d)-Math.abs(a.d));
 const say=x=>`<b>${x.w.lbl||x.w.when}</b> ${x.d>0?'▲':'▼'}${Math.abs(x.d).toFixed(1)} in ${RNAME[topRegion(x.i)]}`;
 el.innerHTML=`Since your last look ${RUNAGE} ago · ${say(moves[0])}${moves[1]?` · ${say(moves[1])}`:''}`;
}
/* lens/origin conflicts are about the whole plan, so they sit above the pictures */
function renderMsgs(){
 const o=S.origin,named=scopeSpots().filter(s=>s.named),reach=named.filter(inReach);
 const reachBest=WINS.map((_,i)=>d3.max(reach,s=>s.r[i])||0);
 const ceiling=d3.max(reachBest)||0,shut=S.rate!=='any'&&ceiling<+S.rate;
 const bw=reachBest.indexOf(ceiling);
 const bs=reach.slice().sort((x,y)=>y.r[bw]-x.r[bw]||driveOf(x)-driveOf(y))[0];
 const nearest=named.slice().sort((x,y)=>driveOf(x)-driveOf(y))[0];
 const nextReach={'45':'90','90':'150','150':'any','any':'any'}[S.reach];
 let clash='';
 if(!reach.length&&nearest)clash=`<div class="clash"><b>Nothing within ${RLBL[S.reach].toLowerCase()} of ${esc(o.base)}</b>
  <span>${named.length} locations in ${esc(o.all?'your regions':o.n)}, and the closest is ${esc(nearest.n)} at ${fmtDrive(driveOf(nearest))}.</span>
  <span class="acts"><button data-set="reach" data-v="${nextReach}">Widen to ${RLBL[nextReach].toLowerCase()} →</button></span></div>`;
 else if(shut)clash=`<div class="clash"><b>Nothing at ${rateLbl()} anywhere in ${esc(o.all?'your regions':o.n)} this week</b>
  <span>Your rating floor came from planning at home. The best on offer within ${RLBL[S.reach].toLowerCase()} is ${ceiling}.0★ — ${esc(bs?bs.n:'')}, ${(WINS[bw].lbl||WINS[bw].when).toLowerCase()}.</span>
  <span class="acts"><button data-set="rate" data-v="any">Show the week as it is →</button><button data-set="rate" data-v="${ceiling}">Or drop the floor to ${ceiling}★+ →</button></span></div>`;
 document.getElementById('msgs').innerHTML=clash+((!o.all&&o.lead)?`<div class="rlead">${o.lead}</div>`:'');
}

/* ── the popup: everything the row body used to hold ───────────────────────── */
function tideRow(w){
 if(!w.tide)return '';
 return `<div class="rows"><div class="frow"><span class="k3">≈ Tide</span>
 <span class="mini"><svg viewBox="0 0 104 24" preserveAspectRatio="none" aria-hidden="true"><path d="${w.tide.path}" fill="none" stroke="#6FA8B0" stroke-width="1.5"></path><line x1="${w.tide.mx}" y1="-1" x2="${w.tide.mx}" y2="24" stroke="#E0A542" stroke-width="1" stroke-dasharray="2 2"></line><circle cx="${w.tide.mx}" cy="${w.tide.my}" r="2.4" fill="#E0A542"></circle></svg></span>
 <span class="f">${w.tide.f.split('|').map(x=>`<span>${x}</span>`).join('')}</span></div></div>`;
}
function regionRail(i){
 const rids=railRids();
 if(rids.length<2)return '';
 const rows=rids.map(r=>({r,...rScore(r,i)})).sort((a,b)=>b.avg-a.avg);
 const allN=scopeSpots().filter(s=>s.named&&meetsRate(s,i)&&inReach(s)).length;
 const allCell=`<button class="rr all ${vCls(topAvg(i))}${S.reg?'':' on'}" data-reg-all>
  <span class="rn">All ${rids.length} regions</span><span class="rv">${S.reg?'show everything':'showing everything'}</span>
  <span class="rm">best ${d3.max(scopeSpots(),s=>s.r[i])||0}★ · ${allN} in reach</span></button>`;
 return `<div class="rlab">This window by region · ranked<span class="tail"> · tap to filter the locations below</span></div>
 <div class="rrail">${allCell}${rows.map(x=>{
  const named=SPOTS.filter(s=>s.named&&s.rid===x.r);
  const n=named.filter(s=>meetsRate(s,i)&&inReach(s)).length;
  const near=d3.min(named,s=>s.min);
  return `<button class="rr ${vCls(x.avg)}${S.reg===x.r?' on':''}" data-reg="${x.r}">
   <span class="rn">${esc(RNAME[x.r])}</span><span class="rv">${vWord(x.avg)}</span>
   <span class="rm">best ${x.best.toFixed(0)}★ · ${n?n+' in reach':`<span class="far">${fmtDrive(near)} away</span>`}</span></button>`}).join('')}</div>`;
}
/* The prose slot is ALWAYS rendered, at the same size, whether or not a region is picked.
   It used to be a panel that appeared on selection, which pushed tide and locations down
   the popup — the picking gesture is meant to swap words and repaint the field, not move
   furniture. Everything else the old panel carried is already stated: the counts live on
   the pill, the filters in the footer, and the All-regions pill is the clear. */
function narrSlot(i){
 const w=WINS[i],rids=railRids();
 const rid=S.reg||(rids.length<2?rids[0]:null);
 if(!rid)return `<div class="nsl all"><div class="nh"><span class="ttl">All ${rids.length} regions</span><span class="k">the window as a whole</span></div><p>${w.lead}</p></div>`;
 const sc=rScore(rid,i),cl=vCls(sc.avg),narr=NARR[rid]&&NARR[rid][i];
 const avgs=WINS.map((_,k)=>rScore(rid,k).avg),bi=avgs.indexOf(d3.max(avgs));
 const nReach=SPOTS.filter(s=>s.named&&s.rid===rid&&meetsRate(s,i)&&inReach(s)).length;
 return `<div class="nsl ${cl}">
 <div class="nh"><span class="ttl">${esc(RNAME[rid])}</span><span class="k">${dChip(DELTA[rid][i])} since ${RUNAGE} ago</span><span class="k">${nReach} of its locations below</span></div>
 <p>${narr||`No narrative generated for this window.${bi!==i?` This region’s own best is <b>${(WINS[bi].lbl||WINS[bi].when).toLowerCase()} ${WINS[bi].time}</b>.`:''}`}</p></div>`;
}
function spotCards(w,i){
 const pool=poolFor(i).slice().sort((a,b)=>b.r[i]-a.r[i]||driveOf(a)-driveOf(b));
 if(!pool.length)return `<div class="quiet">Nothing at ${rateLbl()} within ${RLBL[S.reach].toLowerCase()}${S.reg?' in '+RNAME[S.reg]:''} for this window.</div>`;
 const cards=pool.slice(0,8).map(s=>{const c=ramp(s.r[i]);
  return `<button class="spot" data-pick="${esc(s.n)}"><span class="top"><span class="nm">${esc(s.n)}</span><span class="st" style="background:${rgb(c,.17)};color:${rgb(c)};box-shadow:inset 0 0 0 1px ${rgb(c,.4)}">${s.r[i]}★</span></span>
  <span class="rg">${esc(RNAME[s.rid])}</span><span class="mt">🚗 ${fmtDrive(driveOf(s))} · ${isHome(S.origin)?s.mi+' mi':'local'}</span>
  <span class="lv">↰ leave <b>${leaveBy(w,s)}</b></span><span class="op">◉ Four days here →</span></button>`}).join('');
 return `<div class="strip"><div class="spots">${cards}</div></div>`;
}
function renderWinSheet(){
 const i=S.win;if(i==null)return;
 const w=WINS[i],t=topAvg(i),cl=vCls(t),rids=railRids(),mob=isMob(),tps=topicsOf(i);
 const badge=cl==='p'?'<span class="bdg poor">Poor</span>':`<span class="bdg good">◎ ${vWord(t)}</span>`;
 const tpills=tps.map(x=>`<span class="bdg tpb" style="--tc:${x.c}">${x.ic} ${esc(mob?x.sh:x.n)}</span>`).join('');
 /* the topic block is only drawn when the night actually has one — but it sits above the
    tide row, which is itself conditional, so nothing below it can be pushed by a click */
 const tprows=tps.length?`<div class="rows tpr">${tps.map(x=>`<div class="trow" style="--tc:${x.c}">
  <span class="ic">${x.ic}</span><span class="lb">${esc(x.n)}</span><span class="i" title="${esc(x.sci)}">i</span>
  <span class="dt">${esc(x.d)}</span><span class="rg" title="${esc(x.rids.map(r=>RNAME[r]).join(' · '))}">${x.needs?esc(x.scope)+' · ':''}${x.rids.length} in scope</span></div>`).join('')}</div>`:'';
 const l2=mob?`<span>best ${wMax(i)}★ · avg ${wAvg(i).toFixed(1)}★</span><span>◐ ${Math.round(CONF[i]*100)}%</span><span>${dChip(wDelta(i))} in ${RUNAGE}</span>`
  :`<span>best ${wMax(i)}★ within reach</span><span>average ${wAvg(i).toFixed(1)}★ across ${reachPool(i).length} locations</span><span>◐ ${Math.round(CONF[i]*100)}% confidence</span><span>${dChip(wDelta(i))} since ${RUNAGE} ago</span>`;
 document.getElementById('wcard').innerHTML=`<div class="wsh">
 <div class="dbox"><span class="dow2">${w.dow}</span><span class="dn">${w.dn}</span></div>
 <div class="wst"><div class="l1"><h3>${esc(w.lbl?w.lbl+' · '+w.when.toLowerCase():w.when)}</h3><span class="t2">${w.time}</span>${badge}${i===bestWin()?'<span class="bdg pickb">◎ Best bet</span>':alsoWin(i)?'<span class="bdg good">○ Also good</span>':''}${tpills}</div>
  <div class="l2">${l2}</div></div>
 <div class="wnav"><button data-step="-1" aria-label="Previous window">‹</button><span class="of">${i+1}/${WINS.length}</span><button data-step="1" aria-label="Next window">›</button><button class="x2" data-act="closewin">esc</button></div></div>
 <div class="wsb">
  <div class="wgrid"><div class="mapbox"><canvas id="wcv"></canvas><div class="mlab" id="wml"></div><span class="mhint">${rids.length<2?'one region in scope':S.reg?'tap the region again to clear':'tap a region'}</span></div>
  <div class="wside">${regionRail(i)}${narrSlot(i)}</div></div>
  ${tprows}${tideRow(w)}${spotCards(w,i)}
 </div>
 <div class="wsf"><span>Ranked by rating, then drive time.</span><span class="fx">${activeFilters().join(' · ')}</span><span class="go">See all ${poolFor(i).length} →</span></div>`;
 const c=document.getElementById('wcard');
 c.querySelectorAll('[data-step]').forEach(b=>b.onclick=()=>openWin((S.win+ +b.dataset.step+WINS.length)%WINS.length));
 c.querySelectorAll('[data-reg]').forEach(b=>b.onclick=()=>{S.reg=S.reg===b.dataset.reg?null:b.dataset.reg;renderWinSheet()});
 c.querySelectorAll('[data-reg-all]').forEach(b=>b.onclick=()=>{S.reg=null;renderWinSheet()});
 drawBig();
}
function drawBig(tries){
 tries=tries||0;
 const cv=document.getElementById('wcv');if(!cv||S.win==null)return;
 const bw=cv.parentElement.clientWidth;
 if(!(bw>20)){if(tries<30)requestAnimationFrame(()=>drawBig(tries+1));return}
 const i=S.win,mob=isMob();
 /* two columns on desktop and iPad, so the field is drawn portrait beside the pills;
    stacked on phone, where a wide-ish field costs less than a column would */
 const bh=Math.round(bw*clamp(frameAspect(),mob?0.5:0.88,mob?0.95:1.34));
 const rids=railRids(),focus=S.reg||(rids.length<2?rids[0]:null);
 const proj=HeatField.drawGeo(cv,bw,bh,scopeSpots(),i,{grid:6,radius:Math.max(20,bw*0.072),blur:3.6,line:0.85,
  focus,conf:CONF[i],fit:fitOf()});
 if(!proj)return;
 const gl=document.getElementById('wml');gl.innerHTML='';
 /* one greedy placement pass over both label layers: regions claim their space first, then
    the strongest locations take whatever is left. A label that cannot fit is dropped rather
    than overlapped \u2014 an unreadable name is worse than a missing one. */
 const boxes=[{x:0,y:bh-24,w:118,h:24}];   // the tap hint owns the bottom-left corner
 const fits=b=>b.x>=2&&b.y>=2&&b.x+b.w<=bw-2&&b.y+b.h<=bh-2&&
  !boxes.some(o=>b.x+b.w>o.x-3&&b.x<o.x+o.w+3&&b.y+b.h>o.y-3&&b.y<o.y+o.h+3);
 const measure=el=>{el.style.left='-9999px';el.style.top='0';gl.appendChild(el);
  return{w:el.offsetWidth,h:el.offsetHeight}};
 rids.forEach(rid=>{if(S.reg===rid)return;   // the pill and the prose already name the focus
  const c=HeatField.centroid(scopeSpots(),rid,s=>proj([s.lng,s.lat]));if(!c)return;
  const el=document.createElement('span');el.className='rg';
  el.textContent=RNAME[rid];
  const m=measure(el),b={x:c[0]-m.w/2,y:c[1]-m.h/2,w:m.w,h:m.h};
  if(fits(b)){boxes.push(b);el.style.left=b.x+'px';el.style.top=b.y+'px'}else el.remove()});
 /* the same pool the cards below are ranked from, so the map cannot name a spot the list
    has filtered out \u2014 focused region first, then rating */
 const cand=poolFor(i).slice().sort((a,b)=>(focus?(b.rid===focus)-(a.rid===focus):0)||b.r[i]-a.r[i]||driveOf(a)-driveOf(b));
 let placed=0;
 for(const s of cand){
  if(placed>=(mob?6:8))break;
  const p=proj([s.lng,s.lat]);if(!p)continue;
  const c2=ramp(s.r[i]);
  const el=document.createElement('span');el.className='loc';
  el.innerHTML=`<i></i><b>${esc(s.n)}</b><em style="color:${rgb(c2)}">${s.r[i]}\u2605</em>`;
  el.title=`${s.n} \u00b7 ${fmtDrive(driveOf(s))} \u00b7 leave ${leaveBy(WINS[i],s)}`;
  el.onclick=ev=>{ev.stopPropagation();openSpot(s)};
  const m=measure(el);
  let b={x:p[0]-5.5,y:p[1]-m.h/2,w:m.w,h:m.h},flip=false;
  if(!fits(b)){b={x:p[0]+5.5-m.w,y:p[1]-m.h/2,w:m.w,h:m.h};flip=true}
  if(!fits(b)){el.remove();continue}
  if(flip)el.classList.add('flip');
  boxes.push(b);el.style.left=b.x+'px';el.style.top=b.y+'px';placed++;
 }
 cv.onclick=e=>{
  if(rids.length<2)return;
  const r=cv.getBoundingClientRect(),x=e.clientX-r.left,y=e.clientY-r.top;
  let best=null,bd=1e9;
  rids.forEach(rid=>{const c=HeatField.centroid(scopeSpots(),rid,s=>proj([s.lng,s.lat]));if(!c)return;
   const d=Math.hypot(c[0]-x,c[1]-y);if(d<bd){bd=d;best=rid}});
  S.reg=(bd<r.width*0.26)?(S.reg===best?null:best):null;
  renderWinSheet();
 };
}

/* ── the four-day location view ───────────────────────────────────────────── */
function renderSpot(){
 const sp=S.spot;if(!sp)return;
 const bi=bestIdx(sp),out=!inScope(sp);
 const evs=sp.r.map((v,i)=>{
  const w=WINS[i],op=S.exp.has(i),c=ramp(v),why=WHY[sp.n]&&WHY[sp.n][i];
  return `<div class="ev ${i===bi?'top':''} ${v<=2?'weak':''}" data-row="${i}"><div class="dbox"><span class="dow2">${w.dow}</span><span class="dn">${w.dn}</span></div>
  <div><div class="ttl"><span class="w">${/sunrise/i.test(w.when)?'Sunrise':'Sunset'}</span><span class="t2">${w.time}</span>${i===bi?'<span class="tag">◎ best</span>':''}
   <span class="st" style="background:${rgb(c,.17)};color:${rgb(c)};box-shadow:inset 0 0 0 1px ${rgb(c,.4)}">${v}★</span><span class="car">${op?'▲':'▾'}</span></div>
  <span class="lv2">↰ leave <b>${leaveBy(w,sp)}</b> · ${fmtDrive(driveOf(sp))} · ◐ ${Math.round(CONF[i]*100)}%</span>
  ${op?`<div class="why">${why||w.lead}</div>`:''}</div></div>`}).join('');
 const reg=REGIONS.find(r=>r.id===sp.rid);
 document.getElementById('card').innerHTML=`<div class="sh"><button class="bk" data-act="closespot">←</button><div><h3>${esc(sp.n)}</h3>
  <div class="meta"><span>${esc(RNAME[sp.rid])}</span><span>${fmtDrive(driveOf(sp))} from ${esc(S.origin.base)}</span>${out?'<span class="bdg out">outside your plan</span>':''}</div></div>
  <button class="x2" data-act="closespot">esc</button></div>
  <div class="lead2"><span class="kk">The next four days here · ${sp.r.filter(v=>v>=4).length} of 6 windows at 4★+</span>
  <p>${WHY[sp.n]&&WHY[sp.n][bi]?WHY[sp.n][bi]:WINS[bi].lead}</p></div>
  <div class="tl">${evs}</div>
  <div class="ft">${reg&&reg.id!==S.origin.id?`<button data-rpick="${reg.id}">◎ Plan from ${esc(reg.n)} →</button>`:'<span>Planning from here</span>'}<button>◍ Show on map →</button></div>`;
}

/* ── state transitions ────────────────────────────────────────────────────── */
function setOrigin(o){
 S.origin=o;S.reg=null;S.spot=null;S.searching=false;S.q='';
 document.getElementById('inp').value='';
 S.reach=isHome(o)?'150':'90';
 render();
}
function openWin(i){
 S.win=i;S.reg=null;S.spot=null;S.searching=false;S.q='';
 document.getElementById('inp').value='';
 render();
}
function closeWin(){S.win=null;S.reg=null;render()}
function openSpot(sp){S.spot=sp;S.exp=new Set([bestIdx(sp)]);S.searching=false;S.q='';
 document.getElementById('inp').value='';render()}
function render(){
 bumpCache();
 document.getElementById('mast').classList.toggle('on',S.searching);
 document.getElementById('wsheet').classList.toggle('on',S.win!=null);
 document.getElementById('sheet').classList.toggle('on',!!S.spot);
 renderOrigin();syncLens();renderMsgs();renderStrip();renderChange();
 if(S.win!=null)renderWinSheet();
 if(S.searching)renderDrop();
 if(S.spot)renderSpot();
}

document.addEventListener('click',e=>{
 const b=e.target.closest('[data-act],[data-set],[data-pick],[data-rpick],[data-wpick],[data-row],[data-openq],#orig,#gohome,#rst');
 if(!b)return;
 if(b.dataset.openq){S.searching=true;S.q=b.dataset.openq;render();
  const i2=document.getElementById('inp');i2.value=S.q;i2.focus();renderDrop();return}
 if(b.id==='orig'){S.searching=true;render();document.getElementById('inp').focus();return}
 if(b.id==='gohome'){setOrigin(HOME);return}
 if(b.id==='rst'){S.reach='45';render();return}
 const a=b.dataset.act;
 if(a==='close'){S.searching=false;S.q='';document.getElementById('inp').value='';render();return}
 if(a==='closespot'){S.spot=null;render();return}
 if(a==='closewin'){closeWin();return}
 if(b.dataset.rpick){setOrigin(REGIONS.find(r=>r.id===b.dataset.rpick));return}
 if(b.dataset.wpick!==undefined){openWin(+b.dataset.wpick);return}
 if(b.dataset.pick){const sp=SPOTS.find(s=>s.n===b.dataset.pick);if(sp)openSpot(sp);return}
 if(b.dataset.row!==undefined){const i=+b.dataset.row;S.exp.has(i)?S.exp.delete(i):S.exp.add(i);renderSpot();return}
 if(b.dataset.set){S[b.dataset.set]=b.dataset.v;render()}
});
const inp=document.getElementById('inp');
inp.addEventListener('input',()=>{S.q=inp.value;S.sel=0;renderDrop()});
inp.addEventListener('keydown',e=>{
 const xs=items()||[];
 if(e.key==='ArrowDown'){S.sel=Math.min(S.sel+1,Math.max(0,xs.length-1));renderDrop();e.preventDefault()}
 else if(e.key==='ArrowUp'){S.sel=Math.max(0,S.sel-1);renderDrop();e.preventDefault()}
 else if(e.key==='Enter'&&xs[S.sel]){const x=xs[S.sel];
  x.k==='w'?openWin(x.o):x.k==='r'?setOrigin(x.o):openSpot(x.o)}
 else if(e.key==='Escape'){S.searching=false;S.q='';inp.value='';render()}
});
document.addEventListener('keydown',e=>{
 if(e.key==='/'&&document.activeElement.tagName!=='INPUT'){e.preventDefault();S.searching=true;render();inp.focus();return}
 if(e.key==='Escape'){if(S.spot){S.spot=null;render()}else if(S.win!=null)closeWin();return}
 if(S.win!=null&&!S.spot&&!S.searching){
  if(e.key==='ArrowRight'){openWin((S.win+1)%WINS.length);e.preventDefault()}
  else if(e.key==='ArrowLeft'){openWin((S.win+WINS.length-1)%WINS.length);e.preventDefault()}
 }
});

const VKEY='photocast.heat.viewport';
function setView(v){
 const w=document.getElementById('wrap');
 w.classList.toggle('pad',v==='pad');w.classList.toggle('mob',v==='mob');
 ['bD','bP','bM'].forEach(id=>document.getElementById(id).classList.toggle('on',
  (id==='bD'&&v==='desk')||(id==='bP'&&v==='pad')||(id==='bM'&&v==='mob')));
 try{localStorage.setItem(VKEY,v)}catch(e){}
 /* .wrap animates its max-width, so an immediate redraw measures the OLD width; and the
    cards themselves are viewport-dependent (how many topic names fit), so re-render them */
 requestAnimationFrame(redraw);setTimeout(()=>{render();redraw()},270);
}
function redraw(){measureMast();drawThumbs();drawBig()}
function measureMast(){const m=document.getElementById('mast');
 document.getElementById('scroll').style.setProperty('--mastH',m.offsetHeight+'px')}
document.getElementById('bD').onclick=()=>setView('desk');
document.getElementById('bP').onclick=()=>setView('pad');
document.getElementById('bM').onclick=()=>setView('mob');
document.getElementById('wrap').addEventListener('transitionend',e=>{if(e.propertyName==='max-width')redraw()});

HeatField.load().then(()=>{
 document.getElementById('loading').style.display='none';
 let v0='desk';try{v0=localStorage.getItem(VKEY)||'desk'}catch(e){}
 const w0=document.getElementById('wrap');
 w0.classList.toggle('pad',v0==='pad');w0.classList.toggle('mob',v0==='mob');
 ['bD','bP','bM'].forEach(id=>document.getElementById(id).classList.toggle('on',
  (id==='bD'&&v0==='desk')||(id==='bP'&&v0==='pad')||(id==='bM'&&v0==='mob')));
 render();measureMast();
 new IntersectionObserver(([e])=>{document.getElementById('lens').classList.toggle('stuck',!e.isIntersecting)},
  {root:document.getElementById('scroll'),threshold:1}).observe(document.getElementById('sent'));
 let to;window.addEventListener('resize',()=>{clearTimeout(to);to=setTimeout(redraw,170)});
 if(document.fonts&&document.fonts.ready)document.fonts.ready.then(redraw);
}).catch(e=>{document.getElementById('loading').textContent='could not load coastline: '+e.message});

/* PhotoCast Plan tab — heat summary + movable origin.
   Origin is the frame of reference: it decides the pool, the drive times, the lens label
   and what the heat is fitted to. A region is Plan with the origin moved. */
const {REGIONS,HOME,WINS,CONF,RUNAGE,DELTA,NARR,WHY,SPOTS,SETUP}=PLAN;
const RNAME=Object.fromEntries(REGIONS.map(r=>[r.id,r.n]));
const clamp=HeatField.clamp,ramp=HeatField.ramp,rgb=HeatField.rgb;
const REACH={'45':45,'90':90,'150':150,'any':1e9};
const RLBL={'45':'45 min','90':'1h 30min','150':'2h 30min','any':'Any'};
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');

const S={open:'w5',reg:null,reach:'150',rate:'4',order:'when',origin:HOME,spot:null,
 searching:false,q:'',sel:0,flash:null,exp:new Set()};

/* ── origin-derived basis ─────────────────────────────────────────────────── */
const isHome=o=>!!o.home;
const homeRids=()=>REGIONS.filter(r=>r.home).map(r=>r.id);
const nearestMin=rid=>d3.min(SPOTS.filter(s=>s.named&&s.rid===rid),s=>s.min);
/* GLANCE is what makes the summary survive a growing catalogue: a region is in the field if
   you could plausibly drive to it, not because it is on the list. Northern England, the
   Borders and the Peak all clear it; Skye does not, so it cannot flatten the glance to three
   green pixels — you reach it by moving the origin, exactly like any away region. */
/* GLANCE and the planning-area rule live in plan-data, so this tab and the Map tab cannot
   disagree. Memoised per render pass: areaRids() rescans every location, and it sits beneath
   scopeSpots/topAvg/bestWin, which are called dozens of times while building the strip and
   the six rows. Unmemoised this was seconds of work per repaint. */
const {GLANCE,beyondRids}=PLAN;
let _mk=0,_area=null,_rids=null,_spots=null;
function bumpCache(){_mk++;_area=_rids=_spots=null}
const areaRids=()=>_area||(_area=PLAN.areaRids());
const scopeRids=()=>_rids||(_rids=S.origin.all?areaRids():[S.origin.id]);
const scopeSpots=()=>_spots||(_spots=PLAN.spotsIn(scopeRids()));
const inScope=s=>scopeRids().includes(s.rid);
/* drive time depends where you are: from DH3, or local minutes inside an away region */
const driveOf=s=>isHome(S.origin)?s.min:s.lmin;

const inReach=s=>driveOf(s)<=REACH[S.reach];
const meetsRate=(s,i)=>S.rate==='any'||s.r[i]>=+S.rate;
const rateLbl=()=>S.rate==='any'?'any rating':S.rate+'★+';
const activeFilters=()=>[S.reg&&!S.origin.all?null:S.reg?RNAME[S.reg]:null,rateLbl(),
 S.reach==='any'?'any drive':'within '+RLBL[S.reach].toLowerCase()].filter(Boolean);
/* the window's card pool: scope, rating floor, travel time, and the per-window region pick */
const poolFor=i=>scopeSpots().filter(s=>s.named&&meetsRate(s,i)&&inReach(s)&&(!S.reg||s.rid===S.reg));

const vCls=s=>s>=3.7?'g':s>=2.8?'m':'p';
const vWord=s=>s>=3.7?'Worth it':s>=2.8?'Maybe':'Poor';
function rScore(rid,win){const rs=SPOTS.filter(s=>s.rid===rid).map(s=>s.r[win]);
 return{avg:d3.mean(rs)||0,best:d3.max(rs)||0,n:rs.length,good:rs.filter(v=>v>=4).length}}
const railRids=()=>scopeRids();
const topAvg=win=>d3.max(railRids().map(r=>rScore(r,win).avg))||0;
const topRegion=win=>railRids().reduce((a,b)=>rScore(b,win).avg>rScore(a,win).avg?b:a);
const wBest=win=>d3.max(scopeSpots(),s=>s.r[win])||0;
const wDelta=win=>DELTA[topRegion(win)][win];
/* best bet is derived, so moving the origin moves the recommendation with it */
const bestWin=()=>WINS.map((w,i)=>i).sort((a,b)=>topAvg(b)-topAvg(a)||a-b)[0];

const hm2m=t=>{const[a,b]=t.split(':').map(Number);return a*60+b};
const m2hm=m=>{m=((m%1440)+1440)%1440;return String(Math.floor(m/60)).padStart(2,'0')+':'+String(m%60).padStart(2,'0')};
const leaveBy=(w,s)=>m2hm(hm2m(w.time)-driveOf(s)-SETUP);
const fmtDrive=m=>{const hh=Math.floor(m/60),mm=m%60;return hh?`${hh}h ${mm}min`:`${mm} min`};
const dChip=d=>!d?'<span class="dl same">—</span>'
 :`<span class="dl ${d>0?'up':'dn'}">${d>0?'▲':'▼'}${Math.abs(d).toFixed(1)}</span>`;
/* the heat is fitted to whatever is in scope, so the frame tightens as you narrow and
   grows only as far as your planning area — never to the whole catalogue */
const fitOf=()=>HeatField.bbox(scopeSpots(),S.origin.all?0.10:0.13);
/* thumbnails and the big map take their aspect from the frame, so a taller planning area
   does not letterbox itself into uselessness */
const frameAspect=()=>HeatField.aspect(fitOf());
const winIdx=id=>WINS.findIndex(w=>w.id===id);

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
  <span class="bst">${top?`<b>${top.r[i]}.0★</b>${esc(top.n)}`:'<b>—</b>nothing in reach'}</span><span class="act" style="color:#9CCBD1;border-color:rgba(111,168,176,.35)">Go to window</span></button>`;
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
 drop.innerHTML=(ws.length?`<div class="dtitle">Window${ws.length>1?'s':''} <span class="hint">jumps your plan to it</span></div><div class="dlist">${ws.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +(rs.length?`<div class="dtitle${ws.length?' brk':''}">Region${rs.length>1?'s':''} <span class="hint">re-points the plan and the heat</span></div><div class="dlist">${rs.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +(ss.length?`<div class="dtitle${ws.length||rs.length?' brk':''}">Location${ss.length>1?'s':''} <span class="hint">four-day view</span></div><div class="dlist">${ss.map(x=>rowOf(x,xs.indexOf(x))).join('')}</div>`:'')
 +`<div class="dfoot"><span><span class="k2">↑↓</span> move</span><span><span class="k2">enter</span> open</span></div>`;
}

/* ── chrome ───────────────────────────────────────────────────────────────── */
const HOMESVG='<svg class="pinsvg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 10.6 12 4l8.5 6.6"></path><path d="M5.6 12.2V20h12.8v-7.8"></path></svg>';
const AWAYSVG='<svg class="pinsvg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21c4.2-5 6.3-8.3 6.3-11a6.3 6.3 0 1 0-12.6 0c0 2.7 2.1 6 6.3 11z"></path><circle cx="12" cy="9.9" r="2.3"></circle></svg>';
function renderOrigin(){
 const o=S.origin,b=document.getElementById('orig'),mob=isMob();
 b.className='orig'+(isHome(o)?'':' away');
 b.innerHTML=`${isHome(o)?HOMESVG:AWAYSVG}<span class="nm">${esc(o.all?'DH3 4NG':o.n)}</span><span class="sep"></span>
 <span class="find" title="Search a location, region or day"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="10.6" cy="10.6" r="6.4"></circle><path d="m15.4 15.4 4.2 4.2"></path></svg>${mob?'':'<span class="kbd">/</span>'}</span>`;
 document.getElementById('gohome').classList.toggle('on',!o.all);
 const lbl=isHome(o)?'How far to travel':`Drive from ${o.base}`;
 document.getElementById('rk').textContent=lbl;
 document.getElementById('rk2').textContent=isHome(o)?'Drive':`From ${o.base}`;
 document.getElementById('sumk').textContent=o.all?'Next four days':`Next four days · ${o.n}`;
}
const isMob=()=>document.getElementById('wrap').classList.contains('mob');
function syncLens(){
 const rc=[['45','45 min'],['90','1h 30'],['150','2h 30'],['any','Any']];
 const rt=[['any','Any'],['3','3★+'],['4','4★+']];
 const or=[['when','When'],['best','Best']];
 const fill=(id,opts,key)=>{const el=document.getElementById(id);if(el)el.innerHTML=opts.map(([v,l])=>
  `<button class="${S[key]===v?'on':''}" data-set="${key}" data-v="${v}">${l}</button>`).join('')};
 fill('reach',rc,'reach');fill('reach2',rc,'reach');
 fill('rate',rt,'rate');fill('rate2',rt,'rate');
 fill('order',or,'order');fill('order2',or,'order');
 const named=scopeSpots().filter(s=>s.named),reach=named.filter(inReach);
 const bey=beyondRids();
 document.getElementById('sumn').textContent=`${scopeSpots().length} rated locations · ${named.length} named`;
 const bel=document.getElementById('beyond');
 bel.innerHTML=(S.origin.all&&bey.length)
  ?`Beyond ${GLANCE/60}h and not in the field: ${bey.map(r=>esc(RNAME[r])).join(' · ')} — <button data-openq="${esc(RNAME[bey[0]].split(' ')[0].toLowerCase())}">search to plan from one →</button>`:'';
 document.getElementById('cnt').innerHTML=`<b>${reach.length}</b> of ${named.length} locations within reach · ${rateLbl()}`;
 /* visibility, not display: the desktop group is hidden wholesale by CSS on phone */
 const def=S.reach==='45';
 for(const id of ['stick','rst'])document.getElementById(id).style.visibility=def?'hidden':'visible';
}

/* ── thumbnails: the shape of the week, always chronological ───────────────── */
function renderStrip(){
 const el=document.getElementById('hstrip');el.innerHTML='';
 WINS.forEach((w,i)=>{
  const t=topAvg(i),b=document.createElement('button');
  b.className='hc'+(S.open===w.id?' on':'');
  b.innerHTML=`<div class="top"><span class="dow">${w.dow}</span>${dChip(wDelta(i))}<span class="ar">${/sunrise/i.test(w.when)?'↑':'↓'}</span></div>
  <canvas></canvas><div class="bot"><span class="t">${w.time}</span><span class="vd ${vCls(t)}">${vWord(t)}</span></div>
  ${i===bestWin()?'<span class="flag">BEST BET</span>':''}`;
  b.onclick=()=>{S.open=S.open===w.id?null:w.id;S.reg=null;renderStrip();renderWins();
   if(S.open){const n=document.getElementById('row-'+w.id),sc=document.getElementById('scroll');
    if(n)sc.scrollTo({top:n.offsetTop-96,behavior:'smooth'})}};
  el.appendChild(b);
 });
 drawThumbs();
}
function drawThumbs(tries){
 tries=tries||0;
 const el=document.getElementById('hstrip'),cvs=el.querySelectorAll('canvas');
 const cols=getComputedStyle(el).gridTemplateColumns.split(' ').length;
 const gap=parseFloat(getComputedStyle(el).gap)||6;
 const cell=Math.floor((el.clientWidth-gap*(cols-1))/cols)-12;
 if(!(cell>20)){if(tries<30)requestAnimationFrame(()=>drawThumbs(tries+1));return}
 const fit=fitOf();
 /* capped tighter than the true frame: the six must stay comparable AND compact, and a
    slight letterbox costs less than a 200px-tall summary strip */
 const ar=clamp(frameAspect(),0.85,1.22);
 const sp=scopeSpots();
 cvs.forEach((cv,i)=>HeatField.drawGeo(cv,cell,Math.round(cell*ar),sp,i,
  {grid:4,radius:Math.max(10,cell*0.155),blur:2.4,line:0.5,conf:CONF[i],fit}));
}
function renderChange(){
 const el=document.getElementById('chgline');
 const moves=WINS.map((w,i)=>({w,i,d:wDelta(i)})).filter(x=>x.d);
 if(!moves.length){el.innerHTML=`Nothing moved since your last look ${RUNAGE} ago.`;return}
 moves.sort((a,b)=>Math.abs(b.d)-Math.abs(a.d));
 const say=x=>`<b>${x.w.lbl||x.w.when}</b> ${x.d>0?'▲':'▼'}${Math.abs(x.d).toFixed(1)} in ${RNAME[topRegion(x.i)]}`;
 el.innerHTML=`Since your last look ${RUNAGE} ago · ${say(moves[0])}${moves[1]?` · ${say(moves[1])}`:''}`;
}

/* ── window rows ──────────────────────────────────────────────────────────── */
function tideRow(w){
 if(!w.tide)return '';
 return `<div class="rows"><div class="frow"><span class="k3">≈ Tide</span>
 <span class="mini"><svg viewBox="0 0 104 24" preserveAspectRatio="none" aria-hidden="true"><path d="${w.tide.path}" fill="none" stroke="#6FA8B0" stroke-width="1.5"></path><line x1="${w.tide.mx}" y1="-1" x2="${w.tide.mx}" y2="24" stroke="#E0A542" stroke-width="1" stroke-dasharray="2 2"></line><circle cx="${w.tide.mx}" cy="${w.tide.my}" r="2.4" fill="#E0A542"></circle></svg></span>
 <span class="f">${w.tide.f.split('|').map(x=>`<span>${x}</span>`).join('')}</span></div></div>`;
}
function regionRail(i){
 const rids=railRids();
 if(rids.length<2)return '';   // origin is already one region — nothing left to choose
 const rows=rids.map(r=>({r,...rScore(r,i)})).sort((a,b)=>b.avg-a.avg);
 const allN=scopeSpots().filter(s=>s.named&&meetsRate(s,i)&&inReach(s)).length;
 const allCell=`<button class="rr all ${vCls(topAvg(i))}${S.reg?'':' on'}" data-reg-all>
  <span class="rn">All ${rids.length} regions</span><span class="rv">${S.reg?'show everything':'showing everything'}</span>
  <span class="rm">best ${wBest(i)}★ · ${allN} in reach</span></button>`;
 return `<div class="rlab">This window by region · ranked<span class="tail"> · tap to filter the locations below</span></div>
 <div class="rrail">${allCell}${rows.map(x=>{
  const named=SPOTS.filter(s=>s.named&&s.rid===x.r);
  const n=named.filter(s=>meetsRate(s,i)&&inReach(s)).length;
  const reg=REGIONS.find(r=>r.id===x.r),near=d3.min(named,s=>s.min);
  return `<button class="rr ${vCls(x.avg)}${S.reg===x.r?' on':''}" data-reg="${x.r}">
   <span class="rn">${esc(RNAME[x.r])}</span><span class="rv">${vWord(x.avg)}</span>
   <span class="rm">best ${x.best.toFixed(0)}★ · ${n?n+' in reach':`<span class="far">${fmtDrive(near)} away</span>`}</span></button>`}).join('')}</div>`;
}
function regionPanel(i){
 const rid=S.reg||(railRids().length<2?railRids()[0]:null);
 if(!rid)return '';
 const sc=rScore(rid,i),cl=vCls(sc.avg),narr=NARR[rid]&&NARR[rid][i];
 const avgs=WINS.map((_,k)=>rScore(rid,k).avg),bi=avgs.indexOf(d3.max(avgs));
 const named=SPOTS.filter(s=>s.named&&s.rid===rid);
 const nTot=named.length,nRate=named.filter(s=>meetsRate(s,i)).length,
  nReach=named.filter(s=>meetsRate(s,i)&&inReach(s)).length;
 return `<div class="rp ${cl}">
 <div class="hdr"><span class="nm">${esc(RNAME[rid])}</span><span class="k">${dChip(DELTA[rid][i])} since ${RUNAGE} ago</span>
 <span class="k">locations below · ${activeFilters().join(' · ')}</span>${S.reg?'<button class="x" data-clear>Show all regions ×</button>':''}</div>
 ${narr?`<div class="narr">${narr}</div>`:`<div class="none">No narrative generated for this window.${bi!==i?` This region’s own best is <b>${(WINS[bi].lbl||WINS[bi].when).toLowerCase()} ${WINS[bi].time}</b>.`:''}</div>`}
 <div class="figs"><div class="fig">best in field<b>${sc.best.toFixed(1)}★</b></div><div class="fig">at ${rateLbl()}<b>${nRate} of ${nTot}</b></div><div class="fig">within ${RLBL[S.reach].toLowerCase()}<b>${nReach}</b></div></div>
 <div class="right"><div class="lab">Its six windows</div>
 <div class="dots">${WINS.map((x,k)=>{const c=ramp(avgs[k]);
  return `<button class="dot${k===i?' on':''}" data-jump="${x.id}"><span class="d">${x.dow}${/sunrise/i.test(x.when)?'↑':'↓'}</span><span class="sw" style="background:${rgb(c)}"></span>${k===bi?'<span class="pk">◎</span>':''}</button>`}).join('')}</div></div>
 </div>`;
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
function renderWins(){
 const el=document.getElementById('wins'),o=S.origin;
 const named=scopeSpots().filter(s=>s.named),reach=named.filter(inReach);
 /* the two ways a lens set at home stops making sense once you have moved */
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
 const lead=(!o.all&&o.lead)?`<div class="rlead">${o.lead}</div>`:'';
 const order=S.order==='best'
  ? WINS.map((w,i)=>i).sort((a,b)=>topAvg(b)-topAvg(a)||a-b)
  : WINS.map((w,i)=>i);
 el.innerHTML=clash+lead+order.map((i,rank)=>{
  const w=WINS[i],open=S.open===w.id,t=topAvg(i),poor=vCls(t)==='p';
  const badges=`${poor?'<span class="bdg poor">Poor</span>':`<span class="bdg good">◎ ${vWord(t)}</span>`}${i===bestWin()?'<span class="bdg pickb">◎ Best bet</span>':''}`;
  return `<div class="win${open?' lead':' closed'}${S.flash===w.id?' flash':''}" id="row-${w.id}" data-screen-label="${w.lbl||w.when}">
  <div class="wh" data-t="${w.id}">${S.order==='best'?`<span class="ord">${rank+1}</span>`:''}${w.lbl?`<span class="lbl">${w.lbl}</span>`:''}
   <span class="when">${w.when}</span><span class="time">${w.time}</span>
   ${poor?'':`<span class="best">best ${wBest(i)}★</span>`}<span class="cf" title="forecast confidence at this lead time">◐ ${Math.round(CONF[i]*100)}%</span>
   <span class="rule"></span>${badges}<button class="exp">${open?'Collapse ▴':'Open ▾'}</button></div>
  <div class="wbody">
   <div class="wmap"><div class="mapbox"><canvas data-m="${w.id}"></canvas><div class="mlab" data-ml="${w.id}"></div><span class="mhint">${railRids().length<2?'one region in scope':S.reg?'tap the region again to clear':'tap a region'}</span></div></div>
   ${regionRail(i)}
   ${regionPanel(i)}
   ${tideRow(w)}
   ${spotCards(w,i)}
   <div class="wfoot"><span>Ranked by rating, then drive time.</span><span class="fx">${activeFilters().join(' · ')}</span><span class="go">See all ${poolFor(i).length} →</span></div>
  </div></div>`;
 }).join('');
 el.querySelectorAll('.wh').forEach(h=>h.onclick=()=>{const id=h.dataset.t;
  S.open=S.open===id?null:id;S.reg=null;renderStrip();renderWins()});
 el.querySelectorAll('[data-reg]').forEach(b=>b.onclick=e=>{e.stopPropagation();
  S.reg=S.reg===b.dataset.reg?null:b.dataset.reg;renderWins()});
 el.querySelectorAll('[data-reg-all]').forEach(b=>b.onclick=e=>{e.stopPropagation();S.reg=null;renderWins()});
 const cl2=el.querySelector('[data-clear]');if(cl2)cl2.onclick=e=>{e.stopPropagation();S.reg=null;renderWins()};
 el.querySelectorAll('[data-jump]').forEach(b=>b.onclick=e=>{e.stopPropagation();S.open=b.dataset.jump;renderStrip();renderWins()});
 drawBig();
}
function drawBig(tries){
 tries=tries||0;
 const cv=document.querySelector(`canvas[data-m="${S.open}"]`);if(!cv)return;
 const bw=cv.parentElement.clientWidth;
 if(!(bw>20)){if(tries<30)requestAnimationFrame(()=>drawBig(tries+1));return}
 const i=winIdx(S.open),cls=document.getElementById('wrap').classList;
 const bh=Math.round(bw*clamp(frameAspect(),cls.contains('mob')?0.5:0.36,cls.contains('mob')?0.95:0.62));
 const rids=railRids(),focus=S.reg||(rids.length<2?rids[0]:null);
 const proj=HeatField.drawGeo(cv,bw,bh,scopeSpots(),i,{grid:6,radius:Math.max(20,bw*0.072),blur:3.6,line:0.85,
  focus,conf:CONF[i],fit:fitOf()});
 if(!proj)return;
 const gl=document.querySelector(`[data-ml="${S.open}"]`);gl.innerHTML='';
 rids.forEach(rid=>{const c=HeatField.centroid(scopeSpots(),rid,s=>proj([s.lng,s.lat]));if(!c)return;
  const sp=document.createElement('span');sp.className=S.reg===rid?'hot':'';
  sp.style.left=c[0]+'px';sp.style.top=c[1]+'px';sp.textContent=RNAME[rid];gl.appendChild(sp)});
 cv.onclick=e=>{
  if(rids.length<2)return;
  const r=cv.getBoundingClientRect(),x=e.clientX-r.left,y=e.clientY-r.top;
  let best=null,bd=1e9;
  rids.forEach(rid=>{const c=HeatField.centroid(scopeSpots(),rid,s=>proj([s.lng,s.lat]));if(!c)return;
   const d=Math.hypot(c[0]-x,c[1]-y);if(d<bd){bd=d;best=rid}});
  S.reg=(bd<r.width*0.26)?(S.reg===best?null:best):null;
  renderWins();
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
 if(!WINS.some(w=>w.id===S.open))S.open='w5';
 render();
}
function goWindow(i){
 S.searching=false;S.q='';S.spot=null;document.getElementById('inp').value='';
 S.open=WINS[i].id;S.flash=WINS[i].id;render();
 const n=document.getElementById('row-'+WINS[i].id),sc=document.getElementById('scroll');
 if(n)sc.scrollTo({top:n.offsetTop-96,behavior:'smooth'});
 setTimeout(()=>{S.flash=null;renderWins()},1500);
}
function openSpot(sp){S.spot=sp;S.exp=new Set([bestIdx(sp)]);S.searching=false;S.q='';
 document.getElementById('inp').value='';render()}
function render(){
 bumpCache();   // state has changed; derived scope and score caches are stale
 document.getElementById('mast').classList.toggle('on',S.searching);
 document.getElementById('sheet').classList.toggle('on',!!S.spot);
 renderOrigin();syncLens();renderStrip();renderChange();renderWins();
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
 if(b.dataset.rpick){setOrigin(REGIONS.find(r=>r.id===b.dataset.rpick));return}
 if(b.dataset.wpick!==undefined){goWindow(+b.dataset.wpick);return}
 if(b.dataset.pick){const sp=SPOTS.find(s=>s.n===b.dataset.pick);if(sp)openSpot(sp);return}
 if(b.dataset.row!==undefined){const i=+b.dataset.row;S.exp.has(i)?S.exp.delete(i):S.exp.add(i);renderSpot();return}
 if(b.dataset.set){S[b.dataset.set]=b.dataset.v;
  /* order is a real preference, not a demo toggle — worth living with for a week to see
     which one you reach for, so it has to survive a reload */
  if(b.dataset.set==='order'){try{localStorage.setItem(OKEY,S.order)}catch(e){}}
  render()}
});
const inp=document.getElementById('inp');
inp.addEventListener('input',()=>{S.q=inp.value;S.sel=0;renderDrop()});
inp.addEventListener('keydown',e=>{
 const xs=items()||[];
 if(e.key==='ArrowDown'){S.sel=Math.min(S.sel+1,Math.max(0,xs.length-1));renderDrop();e.preventDefault()}
 else if(e.key==='ArrowUp'){S.sel=Math.max(0,S.sel-1);renderDrop();e.preventDefault()}
 else if(e.key==='Enter'&&xs[S.sel]){const x=xs[S.sel];
  x.k==='w'?goWindow(x.o):x.k==='r'?setOrigin(x.o):openSpot(x.o)}
 else if(e.key==='Escape'){S.searching=false;S.q='';inp.value='';render()}
});
document.addEventListener('keydown',e=>{
 if(e.key==='/'&&document.activeElement.tagName!=='INPUT'){e.preventDefault();S.searching=true;render();inp.focus()}
});

const VKEY='photocast.heat.viewport',OKEY='photocast.heat.order';
function setView(v){
 const w=document.getElementById('wrap');
 w.classList.toggle('pad',v==='pad');w.classList.toggle('mob',v==='mob');
 ['bD','bP','bM'].forEach(id=>document.getElementById(id).classList.toggle('on',
  (id==='bD'&&v==='desk')||(id==='bP'&&v==='pad')||(id==='bM'&&v==='mob')));
 try{localStorage.setItem(VKEY,v)}catch(e){}   // survives a reload, so a viewport can be shared
 /* .wrap animates its max-width, so an immediate redraw measures the OLD width */
 requestAnimationFrame(redraw);setTimeout(()=>{renderOrigin();redraw()},270);
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
 try{const o0=localStorage.getItem(OKEY);if(o0==='when'||o0==='best')S.order=o0}catch(e){}
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

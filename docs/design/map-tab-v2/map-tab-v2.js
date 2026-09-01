/* PhotoCast Map tab v2 — the tab that answers WHERE, on one screen with no scroll.

   Four things changed from v1, three of them things the Plan tab already settled:

   1. ONE window control. The date strip and the Sunrise/Sunset/Astro/Aurora pills said the
      same thing twice, and the in-map "Tonight Sunset 19:58" select said it a third time.
      Now there is one chronological list of EVENTS — every solar window plus each night's
      astro and aurora — so picking a day and picking an event is one act.
   2. The filter block is off the page. It was ~380px of chrome above a 500px map; it is now
      a popover with a count on its chip, and the map has the whole frame.
   3. The field is clipped to land. A gaussian centred on a coastal location spreads both
      ways, and with no land mask the visible half was the half over water — which is why
      the heat read as "a lot of locations in the sea". Same kernel as the Plan thumbnails,
      same coastline clip, so the two surfaces cannot disagree.
   4. Selecting a location is answered ON the map: the point is ringed and a callout tails
      into it. A popup covers the thing you just asked about.

   There is deliberately NO search box here. On a map, panning is the search — a text field
   in the masthead belongs to the Plan tab, where you are choosing an origin. What this tab
   needs is a jump list, and there are only ever a handful of regions. */
const {WINS,CONF,SPOTS,REGIONS,HOME,TOPICS,WTOPICS,GLANCE}=PLAN;
const clamp=HeatField.clamp,ramp=HeatField.ramp,rgb=HeatField.rgb;
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');
const lum=c=>0.2126*c[0]+0.7152*c[1]+0.0722*c[2];
const HOMEPT=[54.855,-1.573];                       // DH3 4NG
const SHORT={ntw:'NORTHUMBERLAND',penn:'NORTH PENNINES',nymc:'NORTH YORK MOORS',lakes:'LAKE DISTRICT',dales:'YORKSHIRE DALES',borders:'BORDERS',peak:'PEAK DISTRICT',highlands:'HIGHLANDS & SKYE'};
const TINY={ntw:'NORTHUMB.',penn:'PENNINES',nymc:'N Y MOORS',lakes:'LAKES',dales:'DALES',borders:'BORDERS',peak:'PEAK',highlands:'HIGHLANDS'};
const $=id=>document.getElementById(id);

/* ── subject tags ───────────────────────────────────────────────────────────
   The shipped app filters by Landscape / Wildlife / Seascape / Woodland / Waterfall. The
   catalogue here carries `coast` and `lake` honestly; the rest is derived once, so the chip
   row behaves like the real one. In the port these come off the location record. */
const SUBJ=[['sea','Seascape','🌊'],['land','Landscape','🏔️'],['wood','Woodland','🌳'],['fall','Waterfall','💦'],['wild','Wildlife','🐾']];
const SUBJN=Object.fromEntries(SUBJ.map(s=>[s[0],s[1]]));
for(const s of SPOTS){
 const t=[],h=Math.abs(Math.round(s.j*997))%10;
 if(s.coast)t.push('sea');
 if(s.lake)t.push('wood');
 if(/falls|force|cove/i.test(s.n))t.push('fall');
 if(h>=8)t.push('wild');
 if(!t.length||(!s.coast&&!s.lake&&h<7))t.push('land');
 s.tags=t;
}

/* ── the event list: one chronological spine for every kind of light ─────────
   A night event sits after that day's sunset, which is where it happens. Aurora only appears
   on a night the forecast actually flags — an always-present Aurora tab that is empty six
   nights in seven trains people to ignore it. */
const NIGHT_CLAR=[4.2,4.6,2.4];                     // Tue, Wed, Thu nights — clarity 1–5
const NIGHT_TIME=['22:54','22:50','22:46'];
const EV=[];
let nightN=0;
WINS.forEach((w,i)=>{
 const am=/sunrise/i.test(w.when);
 EV.push({k:'solar',wi:i,dow:w.dow,dn:w.dn,name:am?'Sunrise':'Sunset',am,
  time:w.time,conf:CONF[i],day:w.dow+' '+w.dn,lead:w.lead});
 if(!am&&nightN<NIGHT_CLAR.length){
  const clar=NIGHT_CLAR[nightN];
  EV.push({k:'astro',dow:w.dow,dn:w.dn,name:'Astro',time:NIGHT_TIME[nightN],clar,conf:CONF[i]-0.04,day:w.dow+' '+w.dn,
   lead:clar>=4?'Clear and dark once twilight ends — darkness decides this, not the sky colour.'
    :'Cloud through most of the night. Not an astro night wherever you stand.'});
  if((WTOPICS[w.id]||[]).some(x=>x.t==='aur'))
   EV.push({k:'aur',dow:w.dow,dn:w.dn,name:'Aurora',time:'23:30',kp:5,conf:CONF[i]-0.1,day:w.dow+' '+w.dn,
    lead:'Kp 5 forecast. Anything with a clear northern horizon is in range; latitude decides the rest.'});
  nightN++;
 }
});
const evLabel=e=>e.k==='solar'?(WINS[e.wi].lbl||WINS[e.wi].when)
 :(e.dn===WINS[0].dn?'Tonight':e.dow.charAt(0)+e.dow.slice(1).toLowerCase()+' night');
const evKind=e=>e.k==='solar'?(e.am?'am':'pm'):e.k;

/* score for a location under an event. Solar comes off the forecast; the night events are
   derived from darkness, clarity and — for aurora — latitude, because at Kp 5 the southern
   edge of the band is the whole story and a dark sky in Derbyshire cannot fix being too far
   south. Neither night model is the shipped one; both are plausible and stated as such. */
function scoreOf(s,e){
 if(e.k==='solar')return s.r[e.wi];
 if(e.k==='astro')return clamp(Math.round(s.bortle*0.72+e.clar*0.5-1.0+s.j*0.2),1,5);
 return clamp(Math.round(1.4+(s.lat-53.0)*1.1+(s.bortle-2)*0.45+(e.kp-4)*0.6+s.j*0.2),1,5);
}
const vWord=v=>v>=3.7?'Worth it':v>=2.8?'Maybe':'Poor';

/* ── state. No `q` and no search state: this tab has no text entry. ────────── */
const S={ei:0,view:'heat',area:true,rate:'any',reach:'any',subj:new Set(),dark:false,
 rings:true,spot:null,menu:null,strip:false};
const ev=()=>EV[S.ei];
const areaRids=()=>PLAN.areaRids();
const basePool=()=>S.area?PLAN.spotsIn(areaRids()):SPOTS;
function pool(){
 const e=ev();let sp=basePool();
 if(S.dark)sp=sp.filter(s=>s.dark);
 if(S.subj.size)sp=sp.filter(s=>s.tags.some(t=>S.subj.has(t)));
 if(S.reach!=='any')sp=sp.filter(s=>s.min<=+S.reach);
 if(S.rate!=='any')sp=sp.filter(s=>scoreOf(s,e)>=+S.rate);
 return sp;
}
const nFilters=()=>(S.rate!=='any'?1:0)+(S.reach!=='any'?1:0)+S.subj.size+(S.dark?1:0);
const fmtDrive=m=>{const h=Math.floor(m/60),mm=m%60;return h?`${h}h ${mm}min`:`${mm} min`};
const hm2m=t=>{const[a,b]=t.split(':').map(Number);return a*60+b};
const m2hm=m=>{m=((m%1440)+1440)%1440;return String(Math.floor(m/60)).padStart(2,'0')+':'+String(m%60).padStart(2,'0')};

/* ── map ────────────────────────────────────────────────────────────────────── */
const map=L.map('map',{zoomControl:false,attributionControl:true,preferCanvas:true,zoomSnap:0});
/* Esri Dark Gray Canvas, no place names: our chips are the only labels on the map, so two
   label systems stop competing for the same pixels — and the one carrying a rating wins.
   The warm filter pulls the tiles' neutral blue-grey into the palette at no per-tile cost.
   maxNativeZoom because the canvas service stops at 16: upscaling beats blank tiles. */
L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
 {maxZoom:19,maxNativeZoom:16,className:'basewarm',
  attribution:'Tiles © Esri — Esri, DeLorme, HERE'}).addTo(map);
/* Place names come back only past the zoom where our own chips thin out — at that point the
   village you are driving through is useful context, and at the glance it was competition. */
const refLayer=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}',
 {maxZoom:19,maxNativeZoom:16,className:'baseref',opacity:0.6});
function syncRef(){
 const on=map.getZoom()>=11.8;
 if(on&&!map.hasLayer(refLayer))refLayer.addTo(map);
 else if(!on&&map.hasLayer(refLayer))map.removeLayer(refLayer);
}
const canvas=$('heat'),labs=$('labs'),tip=$('tip'),cal=$('cal'),selmk=$('selmk');
const fitArea=animate=>map.fitBounds(HeatField.latLngBounds(basePool(),0.12),
 {padding:[34,34],animate:!!animate});
/* An unconditional valid view first. Fitting bounds against a box that has not been laid out
   yet clamps to max zoom and there is no event afterwards that would ever correct it, so the
   real frame is set from the observer below — once the box has a size. */
map.setView([54.9,-2.0],8);

/* ── the land mask ──────────────────────────────────────────────────────────
   Built once per zoom in absolute pixel coordinates and slid by the pixel origin, so panning
   costs a translate rather than a re-projection of the whole coastline. */
let LC={z:null,p:null};
function landPath(){
 if(!HeatField.LAND||!window.Path2D)return null;
 const z=map.getZoom();
 if(LC.z!==z){
  const t=d3.geoTransform({point(x,y){const p=map.project([y,x],z);this.stream.point(p.x,p.y)}});
  LC={z,p:new Path2D(d3.geoPath().projection(t)(HeatField.LAND))};
 }
 return LC.p;
}

/* heat is the base layer; named locations come forward as you zoom, because past a county
   the question stops being WHERE and becomes WHICH, and a smear cannot answer which */
const FADE={a:10.4,b:12.0},FLOOR=0.12;
const handover=()=>({t:clamp((map.getZoom()-FADE.a)/(FADE.b-FADE.a),0,1),
 heat:1-(1-FLOOR)*clamp((map.getZoom()-FADE.a)/(FADE.b-FADE.a),0,1)});

/* A rated location must never paint as empty ground. Natural Earth 1:50m carries 2–5km of
   coastline error — more than a coastal spot's distance from the shore — so clipping to it
   raw removed the gaussian core at Marsden Bay, Whitby, Robin Hood's Bay and five others,
   turning a 5★ into blank sea: worse than the bug it fixed.

   The mask is therefore GROWN SEAWARD by roughly the coastline's own error (see clipGrow),
   which follows the coast rather than inventing a shape. The first attempt unioned a disc at
   each location instead, and that drew visible circles offshore — the error is geographic, so
   the disc had to grow with zoom until it was the most conspicuous thing on the map. A
   uniform band is also honest: a photographer standing at Marsden Bay is shooting out to sea.
   For production, swap countries-50m for a real UK coastline and clipGrow can drop to ~0. */
const COAST_ERR=4200;                               // metres of slack given to the coastline

function drawHeat(alpha){
 const sz=map.getSize(),ctx=HeatField.fit(canvas,sz.x,sz.y);
 ctx.clearRect(0,0,sz.x,sz.y);
 if(alpha<=0.01){drawCoast(ctx);return}
 const o=map.getPixelBounds().min,e=ev(),sp=pool();
 /* The land mask comes off above zoom 11.5: a 1:50m coastline is accurate enough to keep the
    field off the sea at the scales where the field is the subject, but at street scale its
    error shows as a false coast. By then the field is down to its floor, so an unclipped
    wash over water is less wrong than a hard edge in the wrong place. */
 HeatField.drawTiles(canvas,map,sp,0,{
  radius:HeatField.radiusFor(map,7200,30,190),grid:6,blur:4,conf:e.conf,
  opacity:0.92*alpha,score:s=>scoreOf(s,e),bloom:1,
  clipPath:map.getZoom()<11.5?landPath():null,clipSoft:4,
  clipGrow:HeatField.radiusFor(map,COAST_ERR,3,120),clipDx:-o.x,clipDy:-o.y});
 drawCoast(ctx);
 if(S.rings&&map.getZoom()<10.6)drawRings(ctx);
}
/* The coast, stroked from the same geometry the field is clipped to — so the two can never
   disagree, and the ground keeps its shape on a basemap quiet enough to let the field lead.
   Faded out by zoom 11, where a 1:50m coastline would start being visibly wrong and the
   basemap's own detail has taken over. */
function drawCoast(ctx){
 const a=clamp((11-map.getZoom())/1.6,0,1)*0.5;
 const lp=a>0.02&&landPath();
 if(!lp)return;
 const o=map.getPixelBounds().min;
 ctx.save();ctx.translate(-o.x,-o.y);
 ctx.strokeStyle=`rgba(242,231,211,${a.toFixed(3)})`;ctx.lineWidth=0.8;ctx.stroke(lp);
 ctx.restore();
}
/* reach rings: drive minutes at ~0.8 km/min, so the field says whether tonight's warmth is
   reachable tonight rather than merely warm */
const pxPerKm=()=>{const c=map.getCenter();
 return 1000/(156543.03392*Math.cos(c.lat*Math.PI/180)/Math.pow(2,map.getZoom()))};
function drawRings(ctx){
 const k=pxPerKm(),h=map.latLngToContainerPoint(HOMEPT);
 ctx.save();ctx.setLineDash([3,4]);ctx.lineWidth=1;ctx.strokeStyle='rgba(201,162,75,.42)';
 [36,72].forEach(km=>{ctx.beginPath();ctx.arc(h.x,h.y,km*k,0,Math.PI*2);ctx.stroke()});
 ctx.restore();
}

/* ── labels: one greedy pass in priority order. A name that cannot find clear air is
   dropped, never stacked — an unreadable label is worse than a missing one. ──── */
const mk=(cls,html)=>{const d=document.createElement('span');d.className=cls;d.innerHTML=html;return d};
function place(items,w,h,boxes){
 boxes=boxes||[];
 const hit=(a,b)=>a.x<b.x+b.w+3&&b.x<a.x+a.w+3&&a.y<b.y+b.h+2&&b.y<a.y+a.h+2;
 for(const it of items){
  const el=it.el;el.style.left='-9999px';el.style.top='0';labs.appendChild(el);
  const bw=el.offsetWidth,bh=el.offsetHeight;let ok=false;
  const dxs=[0,-Math.round(bw/2)-9,Math.round(bw/2)+9];
  search:
  for(const dy of [0,-14,14,-26,26,-38,38])for(const dx of dxs){
   const b={x:it.x-bw/2+dx,y:it.y-bh/2+dy,w:bw,h:bh};
   if(b.x<2||b.y<2||b.x+b.w>w-2||b.y+b.h>h-2)continue;
   if(boxes.some(o=>hit(b,o)))continue;
   boxes.push(b);el.style.left=b.x+'px';el.style.top=b.y+'px';ok=true;
   break search;
  }
  if(!ok)el.remove();
 }
 return boxes;
}

/* the overlay controls are obstacles too: a name placed under the window pill or behind the
   callout has been dropped, it just took the pixels with it */
function chromeBoxes(){
 const r0=$('mapwrap').getBoundingClientRect(),out=[];
 document.querySelectorAll('#gwin,#gnav,#lchip,#mapwrap .foot,#mapwrap .zoomg,#cal.on,.menu.on')
  .forEach(el=>{const r=el.getBoundingClientRect();if(!r.width)return;
   out.push({x:r.left-r0.left-5,y:r.top-r0.top-5,w:r.width+10,h:r.height+10})});
 return out;
}

function drawLabels(){
 labs.innerHTML='';
 const sz=map.getSize(),w=sz.x,h=sz.y,z=map.getZoom(),e=ev(),sp=pool(),boxes=chromeBoxes();
 /* home first: every drive time and leave-by on this screen is measured from it */
 if(z<13){const p=map.latLngToContainerPoint(HOMEPT);
  place([{el:mk('hm','<i class="mk"></i><span class="lb">HOME</span>'),x:p.x,y:p.y}],w,h,boxes)}
 if(S.rings&&z<10.6){
  const k=pxPerKm(),hp=map.latLngToContainerPoint(HOMEPT);
  /* ring labels go through the same pass as everything else: a "45 min" sitting on top of
     NORTHUMBERLAND is two unreadable labels, so one of them has to lose */
  place([[36,'45 min'],[72,'1h 30']].map(([km,lbl])=>({el:mk('ringlb',lbl),x:hp.x+26,y:hp.y-km*k}))
   .filter(it=>it.y>10&&it.y<h-10),w,h,boxes);
 }
 /* region names while the field is the subject: the ground gets named, hottest brightest */
 if(z<11.2&&sp.length){
  const rids=[...new Set(sp.map(s=>s.rid))];
  const avg=r=>d3.mean(sp.filter(s=>s.rid===r),s=>scoreOf(s,e))||0;
  const hot=rids.reduce((a,b)=>avg(b)>avg(a)?b:a);
  const tiny=w<430,items=[];
  rids.forEach(rid=>{
   const c=HeatField.centroid(sp,rid,s=>{const p=map.latLngToContainerPoint([s.lat,s.lng]);return[p.x,p.y]});
   if(!c)return;
   items.push({el:mk('rg2'+(rid===hot?' hot':''),(tiny?TINY[rid]:SHORT[rid]).replace(/ /g,'&nbsp;')),x:c[0],y:c[1]});
  });
  place(items,w,h,boxes);
 }
 /* then the locations themselves — a square on the point, the name, and this event's rating.
    Best first, so when space runs out it is the weak ones that go. The selected location
    always gets its chip, because it is the thing being answered. */
 const named=sp.filter(s=>s.named).sort((a,b)=>scoreOf(b,e)-scoreOf(a,e)||a.min-b.min);
 /* Density ramps with zoom over what is actually in view, rather than stepping from "one per
    region" straight to "all of them": thirty names over a field is a legend, but two names
    across a county is a map that has stopped answering. The best location in each region is
    always a candidate, so a named region always has a named destination in it, and the
    collision pass does the final arbitration — best first, so the weak ones are what go. */
 const b=map.getBounds(),inView=named.filter(s=>b.contains([s.lat,s.lng]));
 const budget=Math.round(clamp(6+(z-8.6)*11,6,60));
 const first={};
 named.forEach(s=>{if(!first[s.rid])first[s.rid]=s});
 const shown=[...new Set([...Object.values(first),...inView.slice(0,budget)])];
 const sel=S.spot&&named.find(s=>s.n===S.spot);
 if(sel&&!shown.includes(sel))shown.unshift(sel);
 else if(sel)shown.splice(shown.indexOf(sel),1),shown.unshift(sel);
 place(shown.map(s=>{
  const p=map.latLngToContainerPoint([s.lat,s.lng]),v=scoreOf(s,e),c=ramp(v);
  const el=mk('loc'+(S.spot===s.n?' on':''),
   `<i style="background:${rgb(c)}"></i><b>${esc(s.n)}</b><em>${v}★</em>`);
  el.addEventListener('click',ee=>{ee.stopPropagation();openSpot(s)});
  bindTip(el,s.n,`${esc(evLabel(e))} · <b>${v}★</b> ${vWord(v)}<br>${esc(PLAN.name[s.rid])} · ${fmtDrive(s.min)} · sky ${s.bortle.toFixed(1)}`);
  return{el,x:p.x,y:p.y-1};
 }),w,h,boxes);
}

/* the pins view, kept as the honest comparison: one dot per location, no field. At anything
   wider than a county it is why heat became the default — clusters average their gems away. */
function drawPins(){
 labs.innerHTML='';
 const sz=map.getSize(),e=ev(),sp=pool().slice().sort((a,b)=>scoreOf(a,e)-scoreOf(b,e));
 for(const s of sp){
  const p=map.latLngToContainerPoint([s.lat,s.lng]),v=scoreOf(s,e),c=ramp(v);
  const size=s.named?26:13,d=document.createElement('div');d.className='pin';
  d.style.left=p.x+'px';d.style.top=p.y+'px';d.style.width=d.style.height=size+'px';
  d.style.background=rgb(c);d.style.color=lum(c)>150?'#1a130d':'#fff';
  d.style.boxShadow=`0 2px 0 -1px ${rgb([c[0]*0.5|0,c[1]*0.5|0,c[2]*0.5|0])},0 5px 12px rgba(0,0,0,.5)`;
  if(s.named){
   d.innerHTML=`${v}<span class="st">★</span>`;
   d.addEventListener('click',ee=>{ee.stopPropagation();openSpot(s)});
   bindTip(d,s.n,`${esc(evLabel(e))} · <b>${v}★</b><br>${esc(PLAN.name[s.rid])} · ${fmtDrive(s.min)}`);
  }
  labs.appendChild(d);
 }
 const p=map.latLngToContainerPoint(HOMEPT);
 place([{el:mk('hm','<i class="mk"></i><span class="lb">HOME</span>'),x:p.x,y:p.y}],sz.x,sz.y,[]);
}

function bindTip(el,name,sub){
 el.addEventListener('mouseenter',()=>{tip.style.display='block';
  tip.innerHTML=`<div class="n">${esc(name)}</div><div class="s">${sub}</div>`});
 el.addEventListener('mousemove',e=>{const r=canvas.getBoundingClientRect();
  tip.style.left=Math.min(e.clientX-r.left+13,r.width-tip.offsetWidth-8)+'px';
  tip.style.top=Math.max(6,e.clientY-r.top-10)+'px'});
 el.addEventListener('mouseleave',()=>{tip.style.display='none'});
}

function paint(){
 tip.style.display='none';
 syncRef();
 if(S.view==='heat'){const m=handover();drawHeat(m.heat);drawLabels();setHand(m)}
 else{const sz=map.getSize(),ctx=HeatField.fit(canvas,sz.x,sz.y);ctx.clearRect(0,0,sz.x,sz.y);
  drawCoast(ctx);drawPins()}
 anchorCal();
 syncFoot();
}
let raf=0;
const render=()=>{if(!raf)raf=requestAnimationFrame(()=>{raf=0;paint()})};
const renderNow=()=>{if(raf){cancelAnimationFrame(raf);raf=0}paint()};
map.on('move zoom viewreset resize',render);
map.on('moveend zoomend',renderNow);
map.on('click',()=>{closeMenus();if(S.spot)clearSpot()});

/* ── the selected location, rendered on the map ───────────────────────────────
   The point gets a ring and the callout tails into it, so the thing you selected stays the
   thing you are looking at: the field, the region names and the neighbouring locations all
   stay readable around it. A popup would cover exactly the ground you asked about. */
function openSpot(s){
 S.spot=s.n;buildCal(s);
 map.panInside(L.latLng(s.lat,s.lng),{padding:[70,150],animate:true});
 renderNow();
}
function clearSpot(){S.spot=null;S.strip=false;cal.classList.remove('on');selmk.classList.remove('on');renderNow()}
function buildCal(s){
 s=s||SPOTS.find(x=>x.n===S.spot);if(!s)return;
 const e=ev(),v=scoreOf(s,e),c=ramp(v);
 const why=e.k==='solar'?(PLAN.WHY[s.n]||{})[e.wi]||e.lead:e.lead;
 const leave=m2hm(hm2m(e.time)-s.min-PLAN.SETUP);
 const tps=(e.k==='solar'?(WTOPICS[WINS[e.wi].id]||[]):[])
  .map(x=>({...TOPICS[x.t],...x})).filter(x=>!x.needs||s[x.needs]);
 const strip=EV.map((x,i)=>{const vv=scoreOf(s,x),cc=ramp(vv);
  return `<button class="sr${i===S.ei?' on':''}" data-ei="${i}" title="${esc(evLabel(x))} · ${x.time}">`
   +`<span class="sk ${evKind(x)}">${x.name.slice(0,3).toUpperCase()}</span>`
   +`<span class="sd">${esc(x.dow)} ${x.dn}</span>`
   +`<span class="sv"><i style="background:${rgb(cc)}"></i>${vv}★</span></button>`}).join('');
 cal.innerHTML='<div class="tail"></div>'
  +`<div class="ch"><div class="cn"><b>${esc(s.n)}</b>`
  +`<span>${esc(PLAN.name[s.rid])} · ${s.tags.map(t=>SUBJN[t]).join(' · ')}</span></div>`
  +`<button class="cx" data-act="close" aria-label="Close">✕</button></div>`
  +`<div class="cb">`
  +`<div class="cv2" style="border-color:${rgb(c,.5)};background:${rgb(c,.1)}">`
  +`<span class="cvk ${evKind(e)}">${e.name}</span><span class="cvl">${esc(evLabel(e))} · ${e.time}</span>`
  +`<span class="cvs" style="background:${rgb(c)};color:${HeatField.ink(c)}">${v}★ ${vWord(v)}</span></div>`
  +(why?`<p class="cw">${why}</p>`:'')
  +`<div class="cfacts"><span><i>Drive</i>${fmtDrive(s.min)} · ${s.mi} mi</span>`
  +`<span><i>Leave by</i>${leave}</span><span><i>Dark sky</i>${s.bortle.toFixed(1)}${s.dark?' · dark':''}</span></div>`
  +(tps.length?`<div class="ctp">${tps.map(t=>`<span style="--tc:${t.c}" title="${esc(t.d)}">${t.ic} ${t.n}</span>`).join('')}</div>`:'')
  +`<button class="ck" id="calmore" aria-expanded="${S.strip}">This location, every window <span class="cv">${S.strip?'▴':'▾'}</span></button>`
  +`<div class="cstrip"${S.strip?'':' hidden'}>${strip}</div>`
  +`<div class="cacts"><button data-act="zoom">Zoom to it</button><button data-act="plan">Open in Plan</button></div>`
  +`</div>`;
 cal.classList.add('on');
 cal.querySelector('#calmore').onclick=()=>{S.strip=!S.strip;buildCal(s);anchorCal()};
 cal.querySelectorAll('[data-ei]').forEach(b=>b.onclick=()=>setEv(+b.dataset.ei));
 cal.querySelector('[data-act="close"]').onclick=clearSpot;
 cal.querySelector('[data-act="zoom"]').onclick=()=>map.flyTo([s.lat,s.lng],Math.max(map.getZoom(),12.6));
 cal.querySelector('[data-act="plan"]').onclick=()=>{};
}
/* re-anchored every paint, so the callout travels with its point through pan and zoom.
   It is clamped to the band left clear by the overlay chrome, NOT to the map box — clamping
   to the box let the card land under the bottom control bar on a phone, where Regions,
   Heat/Pins and Filters live. Same rect source drawLabels() uses for its obstacle list. */
function calBand(){
 const r0=$('mapwrap').getBoundingClientRect();
 let top=8,bot=r0.height-8;
 document.querySelectorAll('#gwin,#gnav,#lchip,#mapwrap .zoomg,#mapwrap .foot').forEach(el=>{
  const r=el.getBoundingClientRect();
  if(!r.width||!r.height)return;
  const t=r.top-r0.top,b=r.bottom-r0.top;
  /* only bars that span enough width to actually block a 286px card count as a floor/ceiling */
  if(r.width<r0.width*0.5)return;
  if(b<r0.height*0.5)top=Math.max(top,b+8);
  else bot=Math.min(bot,t-8);
 });
 return{top,bot:Math.max(bot,top+90)};
}
function anchorCal(){
 if(!S.spot){cal.classList.remove('on');selmk.classList.remove('on');return}
 const s=SPOTS.find(x=>x.n===S.spot);if(!s)return;
 const p=map.latLngToContainerPoint([s.lat,s.lng]),sz=map.getSize();
 selmk.style.left=p.x+'px';selmk.style.top=p.y+'px';selmk.classList.add('on');
 cal.classList.add('on');
 const cw=cal.offsetWidth,chh=cal.offsetHeight,gap=22,band=calBand();
 let below=p.y+gap+chh<=band.bot;
 let top=below?p.y+gap:p.y-gap-chh;
 if(!below&&top<band.top){below=true;top=p.y+gap}
 top=clamp(top,band.top,Math.max(band.top,band.bot-chh));
 const left=clamp(p.x-cw/2,8,Math.max(8,sz.x-cw-8));
 cal.style.left=left+'px';cal.style.top=top+'px';
 const tail=cal.querySelector('.tail');
 tail.style.left=clamp(p.x-left-5.5,13,Math.max(13,cw-24))+'px';
 tail.style.top=below?'-6px':(chh-6)+'px';
 tail.style.transform=below?'rotate(225deg)':'rotate(45deg)';
}

/* ── chrome ─────────────────────────────────────────────────────────────────── */
function setHand(m){
 $('handbar').style.width=Math.round(100-m.t*80)+'%';
 $('handtxt').innerHTML=m.t<0.05?'<b>Field</b>the regional glance'
  :m.t>0.92?'<b>Locations</b>field kept as a faint wash':'<b>Handing over</b>field → locations';
}
function syncFoot(){
 const sp=pool(),base=basePool(),bey=PLAN.beyondRids();
 $('count').innerHTML=`<b>${sp.filter(s=>s.named).length}</b> named · ${sp.length} rated of ${base.length}`
  +(nFilters()?' <span class="fx">filtered</span>':'');
 $('areanote').innerHTML=S.area&&bey.length
  ?`Beyond ${GLANCE/60}h: ${bey.map(r=>esc(PLAN.name[r])).join(' · ')}`
  :S.area?'':'Whole catalogue — including regions you would not drive to tonight';
 $('fcount').textContent=nFilters()?` (${nFilters()})`:'';
 $('fchip').classList.toggle('act',nFilters()>0);
}

/* ── the one window control ──────────────────────────────────────────────────
   Every kind of light on one chronological spine. Stepping with ‹ › or the arrow keys walks
   the week; the menu states each event's best score so choosing is informed. */
function syncWin(){
 const e=ev();
 $('wnow').innerHTML=`<span class="wk ${evKind(e)}">${e.name}</span>`
  +`<span class="wl">${esc(evLabel(e))}</span><span class="wt">${e.time}</span><span class="cv">▾</span>`;
 $('wprev').disabled=S.ei===0;$('wnext').disabled=S.ei===EV.length-1;
}
function buildMenu(){
 const sp=basePool();let day='',h='';
 EV.forEach((e,i)=>{
  if(e.day!==day){day=e.day;h+=`<div class="wday">${esc(e.dow)} ${e.dn}</div>`}
  const vs=sp.map(s=>scoreOf(s,e)),best=d3.max(vs)||0,c=ramp(d3.mean(vs)||0);
  const tps=e.k==='solar'?(WTOPICS[WINS[e.wi].id]||[]):[];
  h+=`<button class="wr${i===S.ei?' on':''}" data-ei="${i}">`
   +`<span class="wk ${evKind(e)}">${e.name}</span>`
   +`<span class="wm"><b>${esc(evLabel(e))}</b><span class="wt2">${e.time}</span></span>`
   +`<span class="wsc"><i style="background:${rgb(c)}"></i>${best}★ best</span>`
   +`<span class="wtp">${tps.map(t=>TOPICS[t.t].ic).join('')}</span></button>`;
 });
 $('wmenu').innerHTML=h;
}
function setEv(i){
 S.ei=clamp(i,0,EV.length-1);
 syncWin();buildMenu();if(S.spot)buildCal();renderNow();
}

/* ── region jump: the map's answer to "find somewhere", without a text field ── */
function buildJump(){
 const e=ev(),sp=basePool(),rids=[...new Set(SPOTS.map(s=>s.rid))];
 const rows=rids.map(rid=>{
  const set=sp.filter(s=>s.rid===rid),all=SPOTS.filter(s=>s.rid===rid);
  const vs=(set.length?set:all).map(s=>scoreOf(s,e));
  return{rid,best:d3.max(vs)||0,c:ramp(d3.mean(vs)||0),
   near:d3.min(all.filter(s=>s.named),s=>s.min),out:!set.length};
 }).sort((a,b)=>a.near-b.near);
 $('jmenu').innerHTML=rows.map(r=>`<button class="jr${r.out?' out':''}" data-rid="${r.rid}">`
  +`<span class="jn">${esc(PLAN.name[r.rid])}</span>`
  +`<span class="jd">${fmtDrive(r.near)}${r.near>GLANCE?' · beyond your area':''}</span>`
  +`<span class="jv"><i style="background:${rgb(r.c)}"></i>${r.best}★</span></button>`).join('');
}
/* jumping somewhere outside your area switches scope for you rather than refusing */
function jumpTo(rid){
 if(S.area&&!areaRids().includes(rid)){S.area=false;buildFilters()}
 map.fitBounds(HeatField.latLngBounds(SPOTS.filter(s=>s.rid===rid),0.06),{padding:[40,40]});
 buildJump();closeMenus();renderNow();
}

/* ── filters ─────────────────────────────────────────────────────────────────── */
function buildFilters(){
 const rate=[['any','Any'],['2','2★+'],['3','3★+'],['4','4★+'],['5','5★']];
 const reach=[['any','Any'],['45','45 min'],['90','1h 30'],['150','2h 30']];
 $('fpanel').innerHTML=
  `<div class="frow"><span class="fk">Minimum rating</span><div class="fseg" data-g="rate">`
  +rate.map(([v,l])=>`<button data-v="${v}"${S.rate===v?' class="on"':''}>${l}</button>`).join('')+`</div></div>`
  +`<div class="frow"><span class="fk">Subject</span><div class="fchips" data-g="subj">`
  +SUBJ.map(([k,l,ic])=>`<button data-v="${k}"${S.subj.has(k)?' class="on"':''}>${ic} ${l}</button>`).join('')+`</div></div>`
  +`<div class="frow"><span class="fk">Drive from DH3 4NG</span><div class="fseg" data-g="reach">`
  +reach.map(([v,l])=>`<button data-v="${v}"${S.reach===v?' class="on"':''}>${l}</button>`).join('')+`</div></div>`
  +`<div class="frow"><span class="fk">Sky</span><div class="fchips" data-g="dark">`
  +`<button data-v="1"${S.dark?' class="on"':''}>🔭 Dark sky only</button></div></div>`
  +`<div class="frow"><span class="fk">Scope</span><div class="fseg" data-g="area">`
  +`<button data-v="1"${S.area?' class="on"':''}>◎ My area</button>`
  +`<button data-v="0"${!S.area?' class="on"':''}>Whole catalogue</button></div></div>`
  +`<div class="ffoot"><span><b>${pool().length}</b> of ${basePool().length} shown</span>`
  +`<button class="fclr" data-g="clear">Clear all</button></div>`;
}

/* ── menus ───────────────────────────────────────────────────────────────────── */
function closeMenus(){
 S.menu=null;
 ['wmenu','jmenu','fpanel','lpanel'].forEach(id=>$(id).classList.remove('on'));
 ['wnow','jchip','fchip','lchip'].forEach(id=>$(id).classList.remove('open'));
}
function toggleMenu(name,el,chip){
 const open=S.menu===name;closeMenus();
 if(!open){S.menu=name;$(el).classList.add('on');$(chip).classList.add('open')}
}
$('wnow').onclick=e=>{e.stopPropagation();buildMenu();toggleMenu('win','wmenu','wnow')};
$('jchip').onclick=e=>{e.stopPropagation();buildJump();toggleMenu('jump','jmenu','jchip')};
$('fchip').onclick=e=>{e.stopPropagation();buildFilters();toggleMenu('filt','fpanel','fchip')};
$('lchip').onclick=e=>{e.stopPropagation();toggleMenu('leg','lpanel','lchip')};
$('wprev').onclick=()=>setEv(S.ei-1);
$('wnext').onclick=()=>setEv(S.ei+1);
$('wmenu').onclick=e=>{const b=e.target.closest('[data-ei]');if(b){setEv(+b.dataset.ei);closeMenus()}};
$('jmenu').onclick=e=>{const b=e.target.closest('[data-rid]');if(b)jumpTo(b.dataset.rid)};
$('fpanel').onclick=e=>{
 const b=e.target.closest('button[data-v],button[data-g="clear"]');if(!b)return;
 const g=(b.closest('[data-g]')||b).dataset.g,v=b.dataset.v;
 if(g==='clear'){S.rate='any';S.reach='any';S.subj=new Set();S.dark=false}
 else if(g==='rate')S.rate=v;
 else if(g==='reach')S.reach=v;
 else if(g==='subj')S.subj.has(v)?S.subj.delete(v):S.subj.add(v);
 else if(g==='dark')S.dark=!S.dark;
 else if(g==='area'){
  S.area=v==='1';buildJump();
  /* animate:false is deliberate: a heavy field paint in the same frame forces layout mid
     zoom-transition and strands Leaflet at the old view. A jump is honest; a no-op is not. */
  fitArea(false);
 }
 buildFilters();renderNow();
};
document.querySelectorAll('#viewseg button').forEach(b=>b.onclick=()=>{
 S.view=b.dataset.view;
 document.querySelectorAll('#viewseg button').forEach(x=>x.classList.toggle('on',x===b));
 $('lchip').classList.toggle('hide',S.view!=='heat');
 if(S.view!=='heat')closeMenus();
 renderNow();
});
$('ringtog').onclick=function(){S.rings=!S.rings;this.classList.toggle('on',S.rings);renderNow()};
$('zin').onclick=()=>map.zoomIn();
$('zout').onclick=()=>map.zoomOut();
$('zhome').onclick=()=>{S.area=true;buildFilters();buildJump();fitArea(true)};
document.addEventListener('keydown',e=>{
 if(e.key==='ArrowLeft'){setEv(S.ei-1);closeMenus()}
 else if(e.key==='ArrowRight'){setEv(S.ei+1);closeMenus()}
 else if(e.key==='Escape'){closeMenus();if(S.spot)clearSpot()}
});

/* ── viewport switch ─────────────────────────────────────────────────────────── */
[['bD',''],['bP','pad'],['bM','mob']].forEach(([id,cls])=>{
 $(id).onclick=()=>{
  $('wrap').className='wrap'+(cls?' '+cls:'');
  document.querySelectorAll('.demobar button').forEach(b=>b.classList.toggle('on',b.id===id));
  closeMenus();S.strip=false;if(S.spot)clearSpot();
 }});

/* The map's box is not sized at script time in every host, and Leaflet will not recompute a
   stale zero size on its own. So the box itself is the only sizing trigger: the first callback
   with a real size sets the opening frame, and every later one keeps the map honest through
   the viewport transition without guessing at timeouts. */
let started=false;
new ResizeObserver(()=>{
 const el=$('mapwrap');
 if(!el.clientWidth||!el.clientHeight)return;
 map.invalidateSize({animate:false});
 LC={z:null,p:null};
 if(!started){started=true;fitArea(false)}
 renderNow();
}).observe($('mapwrap'));

/* ── walkthrough ─────────────────────────────────────────────────────────────── */
document.querySelectorAll('[data-do]').forEach(b=>b.onclick=()=>{
 const[k,v]=b.dataset.do.split(':');
 if(k==='ev')setEv(+v);
 else if(k==='view')document.querySelector(`#viewseg [data-view="${v}"]`).click();
 else if(k==='go'){const p=v.split(',').map(Number);map.setView([p[0],p[1]],p[2])}
 else if(k==='jump')jumpTo(v);
 else if(k==='filt'){S.rate='4';S.subj=new Set(['sea']);S.dark=false;buildFilters();renderNow()}
 else if(k==='clr'){S.rate='any';S.reach='any';S.subj=new Set();S.dark=false;buildFilters();renderNow()}
});

/* the light rule's times, stated once */
$('ctimes').innerHTML=['05:30 blue','<b>06:08 golden</b>','<b>20:05 golden</b>','20:43 blue']
 .map(t=>`<span>${t}</span>`).join('');

syncWin();buildMenu();buildJump();buildFilters();
HeatField.load().then(()=>{LC={z:null,p:null};renderNow()});

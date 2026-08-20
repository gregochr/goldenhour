/* PhotoCast Map tab — the same field as the Plan tab, hosted on a tile map.
   Data comes from plan-data.js and the field from heat-field.js, so the two surfaces cannot
   drift in what a colour means. This tab's job is SPACE: pan anywhere, see the field, and
   let locations take over as you zoom. The Plan tab's job is TIME. */
const {WINS,CONF,SPOTS,GLANCE}=PLAN;
const clamp=HeatField.clamp,ramp=HeatField.ramp,rgb=HeatField.rgb;
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');
const lum=c=>0.2126*c[0]+0.7152*c[1]+0.0722*c[2];

const S={view:'heat',win:0,dark:false,area:true};
/* the same planning-area rule the Plan tab frames itself with — the whole catalogue is
   pannable, but you do not OPEN on the whole of Britain */
const areaSpots=()=>PLAN.spotsIn(PLAN.areaRids());
const visible=()=>{
 let sp=S.area?areaSpots():SPOTS;
 return S.dark?sp.filter(s=>s.dark):sp;
};

const map=L.map('map',{zoomControl:true,attributionControl:true,preferCanvas:true,zoomSnap:0});
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
 {maxZoom:19,subdomains:'abcd',attribution:'© CARTO · © OpenStreetMap'}).addTo(map);
map.fitBounds(HeatField.latLngBounds(areaSpots(),0.12),{padding:[28,28]});

const canvas=document.getElementById('heat'),medsEl=document.getElementById('meds'),tip=document.getElementById('tip');

/* heat is the base layer; locations come forward as you zoom, because past this point the
   question changes from WHERE to WHICH and a smear cannot answer which */
const FADE={a:10.6,b:12.2},FLOOR=0.17;
const blend=()=>{const t=clamp((map.getZoom()-FADE.a)/(FADE.b-FADE.a),0,1);
 return{t,heat:1-(1-FLOOR)*t,pin:t}};

function drawHeat(hA){
 const sz=map.getSize();
 const ctx=HeatField.fit(canvas,sz.x,sz.y);
 ctx.clearRect(0,0,sz.x,sz.y);
 if(hA<=0.01)return;
 HeatField.drawTiles(canvas,map,visible(),S.win,
  {radius:HeatField.radiusFor(map,8500,34,240),grid:6,blur:4,conf:CONF[S.win],opacity:0.9*hA});
}
function drawPins(pA){
 const named=pA>0.55,sp=visible().slice().sort((a,b)=>a.r[S.win]-b.r[S.win]);
 for(const s of sp){
  const p=map.latLngToContainerPoint([s.lat,s.lng]),v=s.r[S.win],c=ramp(v),size=s.named?30:18;
  const d=document.createElement('div');d.className='med';
  d.style.left=p.x+'px';d.style.top=p.y+'px';d.style.width=d.style.height=size+'px';
  d.style.fontSize='12px';d.style.opacity=pA;d.style.background=rgb(c);
  const dk=[c[0]*0.55|0,c[1]*0.55|0,c[2]*0.55|0];
  d.style.boxShadow=`0 3px 0 -2px ${rgb(dk)},0 6px 14px rgba(0,0,0,.5)`;
  d.style.color=lum(c)>150?'#1a130d':'#fff';
  d.innerHTML=s.named?`${v}<span class="st">★</span>`:'';
  bindTip(d,s.n||'Location · not yet named',`${WINS[S.win].lbl||WINS[S.win].when} · <b>${v}.0★</b> · ${PLAN.name[s.rid]}<br>sky <b>${s.bortle.toFixed(1)}</b>${s.dark?' · dark':''}`);
  medsEl.appendChild(d);
  if(named&&s.named){const l=document.createElement('div');l.className='hlbl';
   l.style.left=p.x+'px';l.style.top=(p.y+size/2+5)+'px';l.style.opacity=(pA-0.55)/0.45;
   l.textContent=s.n;medsEl.appendChild(l)}
 }
}
/* the medallion view, kept for comparison: one circle per cluster, count and average score.
   It is the honest "before" — at anything wider than a county it averages the gems away. */
function drawMeds(){
 const sp=visible(),cell=64,cells={};
 for(const s of sp){const p=map.latLngToContainerPoint([s.lat,s.lng]);
  const k=Math.floor(p.x/cell)+','+Math.floor(p.y/cell);
  const c=cells[k]=cells[k]||{x:0,y:0,n:0,ss:0,named:0,one:null,best:0};
  c.n++;c.x+=p.x;c.y+=p.y;c.ss+=s.r[S.win];c.best=Math.max(c.best,s.r[S.win]);
  if(s.named)c.named++;c.one=s}
 for(const k in cells){const c=cells[k],avg=c.ss/c.n,single=c.n===1;
  const v=single?c.one.r[S.win]:avg,col=ramp(v);
  const size=clamp(34+Math.log2(c.n+1)*7,36,62);
  const d=document.createElement('div');d.className='med';
  d.style.left=(c.x/c.n)+'px';d.style.top=(c.y/c.n)+'px';
  d.style.width=d.style.height=size+'px';d.style.fontSize=clamp(size*0.38,14,22)+'px';
  d.style.background=rgb(col);
  const dk=[col[0]*0.55|0,col[1]*0.55|0,col[2]*0.55|0];
  d.style.boxShadow=`0 4px 0 -2px ${rgb(dk)},0 8px 16px rgba(0,0,0,.5)`;
  d.style.color=lum(col)>150?'#1a130d':'#fff';
  d.innerHTML=`${c.n}${c.named&&c.n<=4?'<span class="st">★</span>':''}`;
  bindTip(d,single?(c.one.n||'1 location'):`${c.n} locations`,
   single?`<b>${v}.0★</b>`:`avg <b>${avg.toFixed(1)}★</b> · best ${c.best}.0★`);
  medsEl.appendChild(d)}
}
function bindTip(el,name,sub){
 el.addEventListener('mouseenter',()=>{tip.style.display='block';
  tip.innerHTML=`<div class="n">${esc(name)}</div><div class="s">${sub}</div>`});
 el.addEventListener('mousemove',e=>{const r=canvas.getBoundingClientRect();
  tip.style.left=(e.clientX-r.left+12)+'px';tip.style.top=(e.clientY-r.top-8)+'px'});
 el.addEventListener('mouseleave',()=>{tip.style.display='none'});
}

function paint(){
 medsEl.innerHTML='';tip.style.display='none';
 if(S.view==='heat'){const m=blend();drawHeat(m.heat);if(m.pin>0.02)drawPins(m.pin);setStat(m)}
 else{const ctx=HeatField.fit(canvas,map.getSize().x,map.getSize().y);
  ctx.clearRect(0,0,canvas.width,canvas.height);drawMeds()}
}
let raf=0;
function render(){if(raf)return;raf=requestAnimationFrame(()=>{raf=0;paint()})}   // throttle
function renderNow(){if(raf){cancelAnimationFrame(raf);raf=0}paint()}             // settle
map.on('move zoom viewreset resize',render);
map.on('moveend zoomend',renderNow);

function setStat(m){
 const bar=document.getElementById('mixbar'),tx=document.getElementById('statxt');
 bar.style.width=Math.round(100-m.pin*82)+'%';
 const st=m.pin<0.05?['Field','the regional glance']
  :m.pin>0.92?['Locations','field kept as a faint wash']:['Handing over','field → locations'];
 tx.innerHTML=`<b>${st[0]}</b>${st[1]}`;
}
function setView(v){
 S.view=v;
 document.querySelectorAll('#viewseg button').forEach(b=>b.classList.toggle('on',b.dataset.view===v));
 document.getElementById('hpanel').classList.toggle('on',v==='heat');
 document.getElementById('medcap').classList.toggle('off',v!=='med');
 renderNow();
}
function setArea(on){
 S.area=on;
 document.querySelectorAll('#areaseg button').forEach(b=>b.classList.toggle('on',(b.dataset.area==='1')===on));
 syncCount();
 /* animate:false is deliberate. A heavy field paint in the same frame forces layout mid
    zoom-transition and strands Leaflet at the old view, so the labels would update while
    the map never moved. A jump is honest; a silent no-op is not. */
 map.fitBounds(HeatField.latLngBounds(S.area?areaSpots():SPOTS,0.12),{padding:[28,28],animate:false});
 renderNow();
}
function syncCount(){
 const sp=visible(),bey=PLAN.beyondRids();
 const pool=S.area?areaSpots():SPOTS;
 document.getElementById('count').innerHTML=S.dark
  ? `<b>${sp.length}</b> of ${pool.length} · dark sky only (3.8+)`
  : `<b>${sp.length}</b> of ${SPOTS.length} rated locations shown`;
 document.getElementById('areanote').innerHTML=S.area&&bey.length
  ? `Beyond ${GLANCE/60}h: ${bey.map(r=>esc(PLAN.name[r])).join(' · ')}`
  : S.area?'':'Whole catalogue — including regions you would not drive to tonight';
}

const winSel=document.getElementById('win');
winSel.innerHTML=WINS.map((w,i)=>`<option value="${i}">${esc(w.lbl||w.when)} · ${w.time}</option>`).join('');
winSel.addEventListener('change',()=>{S.win=+winSel.value;renderNow()});
document.getElementById('viewseg').addEventListener('click',e=>{
 const b=e.target.closest('[data-view]');if(b)setView(b.dataset.view)});
document.getElementById('areaseg').addEventListener('click',e=>{
 const b=e.target.closest('[data-area]');if(b)setArea(b.dataset.area==='1')});
document.getElementById('dark').addEventListener('click',function(){
 S.dark=!S.dark;this.classList.toggle('on',S.dark);syncCount();renderNow()});
document.querySelectorAll('[data-do]').forEach(b=>b.onclick=()=>{
 const[k,v]=b.dataset.do.split(':');
 if(k==='view')setView(v);
 else if(k==='win'){setView('heat');S.win=+v;winSel.value=v;renderNow()}
 else if(k==='area')setArea(v==='1');
 else if(k==='go'){setView('heat');const p=v.split(',').map(Number);map.setView([p[0],p[1]],p[2])}
});

setView('heat');setArea(true);
map.whenReady(renderNow);
setTimeout(renderNow,400);

/* PhotoCast Map tab — aurora mode on the shared heat kernel.
   Same field, same cull/bucketing, same coverage clamp. Two things change for aurora:
   the SCORE (derived per location from Kp, latitude, cloud and darkness — not a solar
   window) and the RAMP (night → aurora green, so the mode is legible at a glance and
   cannot be mistaken for a sunset field). */
const{SPOTS}=PLAN;
const HF=HeatField,clamp=HF.clamp;
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');

/* night → teal → aurora green. Luminance rises monotonically with score, so on dark tiles
   brightness alone ranks the field — the opposite of the temperature ramp, whose hot end is
   its darkest colour and needs the bloom pass to read. 1★ stays a visible slate so "poor"
   is still distinguishable from "no data". */
const ASTOPS=[[1,[56,66,98]],[2,[44,84,112]],[3,[34,134,126]],[4,[58,200,132]],[5,[118,255,160]]];
function auroraRamp(s){s=clamp(s,1,5);
 for(let i=0;i<ASTOPS.length-1;i++){const[a,ca]=ASTOPS[i],[b,cb]=ASTOPS[i+1];
  if(s<=b){const f=(s-a)/(b-a);return[0,1,2].map(k=>Math.round(ca[k]+(cb[k]-ca[k])*f))}}
 return ASTOPS[ASTOPS.length-1][1]}

/* mock scenarios. Real scores come from getAuroraForecastResults(date).stars */
const NIGHTS=[
 {lbl:'Tonight · Kp 6',kp:6,view:54.6,conf:.9,cloud:{ntw:.15,penn:.3,lakes:.75,nymc:.35,dales:.5,borders:.2,peak:.6,highlands:.4}},
 {lbl:'Tomorrow · Kp 5',kp:5,view:55.3,conf:.7,cloud:{ntw:.5,penn:.25,lakes:.2,nymc:.65,dales:.3,borders:.15,peak:.4,highlands:.1}},
 {lbl:'Sat · Kp 7 (G3)',kp:7,view:53.6,conf:.45,cloud:{ntw:.3,penn:.3,lakes:.4,nymc:.3,dales:.35,borders:.3,peak:.35,highlands:.5}}];
function score(s,n){
 const lat=clamp((s.lat-n.view+0.9)/1.6,0,1);            // north of the viewline → visible
 const clear=1-(n.cloud[s.rid]==null?.4:n.cloud[s.rid]);
 const dark=clamp((s.bortle-1)/4,0,1);
 const north=s.coast?1:.85;                                  // open northern horizon
 return Math.round(clamp(1+4*lat*clear*(.45+.55*dark)*north+(s.j*.25),1,5)*10)/10;
}

const S={night:0,ramp:'aurora',area:true};
const areaSpots=()=>PLAN.spotsIn(PLAN.areaRids());
const visible=()=>S.area?areaSpots():SPOTS;

const map=L.map('map',{zoomControl:true,attributionControl:true,preferCanvas:true,zoomSnap:0});
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
 {maxZoom:19,subdomains:'abcd',attribution:'© CARTO · © OpenStreetMap'}).addTo(map);
map.fitBounds(HF.latLngBounds(areaSpots(),0.12),{padding:[28,28]});
const vline=L.polyline([],{color:'#E0A542',weight:2,dashArray:'8 7',opacity:.85,interactive:false}).addTo(map);
const vlab=L.marker([0,0],{interactive:false,icon:L.divIcon({className:'vlab',html:'',iconSize:null})}).addTo(map);

const canvas=document.getElementById('heat'),pinsEl=document.getElementById('pins'),tip=document.getElementById('tip');
const FADE={a:10.6,b:12.2},FLOOR=0.17;
const blend=()=>{const t=clamp((map.getZoom()-FADE.a)/(FADE.b-FADE.a),0,1);return{heat:1-(1-FLOOR)*t,pin:t}};
const rampFn=()=>S.ramp==='aurora'?auroraRamp:HF.ramp;

function drawHeat(hA){
 const sz=map.getSize(),ctx=HF.fit(canvas,sz.x,sz.y);ctx.clearRect(0,0,sz.x,sz.y);
 if(hA<=0.01)return;
 const n=NIGHTS[S.night],au=S.ramp==='aurora';
 HF.drawTiles(canvas,map,visible(),0,{score:s=>score(s,n),ramp:rampFn(),
  radius:HF.radiusFor(map,8500,34,240),grid:6,blur:4,conf:n.conf,opacity:0.9*hA,
  bloom:true,bloomFrom:au?3.4:3,bloomRGB:au?[120,255,170]:null,bloomA:au?150:190});
}
function drawPins(pA){
 const n=NIGHTS[S.night],r=rampFn();
 const sp=visible().map(s=>({s,v:score(s,n)})).sort((a,b)=>a.v-b.v);
 for(const{s,v}of sp){
  const p=map.latLngToContainerPoint([s.lat,s.lng]),c=r(v),size=s.named?30:16;
  const d=document.createElement('div');d.className='pin';
  Object.assign(d.style,{left:p.x+'px',top:p.y+'px',width:size+'px',height:size+'px',opacity:pA,
   background:HF.rgb(c),color:HF.ink(c)});
  if(S.ramp==='aurora'&&v>=4)d.style.boxShadow=`0 0 14px ${HF.rgb(c,.7)}`;
  d.textContent=s.named?Math.round(v):'';
  bindTip(d,s.n||'Location · not yet named',`${esc(n.lbl)} · <b>${v.toFixed(1)}★</b><br>${esc(PLAN.name[s.rid])} · sky ${s.bortle.toFixed(1)}`);
  pinsEl.appendChild(d);
  if(pA>0.55&&s.named){const l=document.createElement('div');l.className='hlbl';
   l.style.left=p.x+'px';l.style.top=(p.y+size/2+5)+'px';l.style.opacity=(pA-0.55)/0.45;
   l.textContent=s.n;pinsEl.appendChild(l)}
 }
}
function bindTip(el,name,sub){
 el.onmouseenter=()=>{tip.style.display='block';tip.innerHTML=`<div class="n">${esc(name)}</div><div class="s">${sub}</div>`};
 el.onmousemove=e=>{const r=canvas.getBoundingClientRect();tip.style.left=(e.clientX-r.left+12)+'px';tip.style.top=(e.clientY-r.top-8)+'px'};
 el.onmouseleave=()=>{tip.style.display='none'};
}
function drawViewline(){
 const n=NIGHTS[S.night],b=map.getBounds();
 vline.setLatLngs([[n.view,b.getWest()-2],[n.view,b.getEast()+2]]);
 vlab.setLatLng([n.view,b.getWest()+(b.getEast()-b.getWest())*.5]);
 vlab.setIcon(L.divIcon({className:'vlab',html:`<span>Kp ${n.kp} viewline · visible north of here</span>`,iconSize:null}));
}
function paint(){
 pinsEl.innerHTML='';tip.style.display='none';
 const m=blend();drawHeat(m.heat);if(m.pin>0.02)drawPins(m.pin);drawViewline();syncBest();
}
let raf=0;
function render(){if(raf)return;raf=requestAnimationFrame(()=>{raf=0;paint()})}
function renderNow(){if(raf){cancelAnimationFrame(raf);raf=0}paint()}
map.on('move zoom viewreset resize',render);map.on('moveend zoomend',renderNow);

function syncBest(){
 const n=NIGHTS[S.night],named=visible().filter(s=>s.named);
 const best=named.reduce((b,s)=>{const v=score(s,n);return!b||v>b.v?{s,v}:b},null);
 const go=named.filter(s=>score(s,n)>=4).length;
 document.getElementById('best').innerHTML=best
  ?`<b>${esc(best.s.n)}</b> · ${best.v.toFixed(1)}★ · ${go} named location${go===1?'':'s'} at 4★+`:'—';
}
function setRamp(r){
 S.ramp=r;
 document.querySelectorAll('#rampseg button').forEach(b=>b.classList.toggle('on',b.dataset.ramp===r));
 document.getElementById('legbar').className='legbar '+r;
 document.getElementById('leg5').textContent=r==='aurora'?'5★ aurora green':'5★';
 document.getElementById('leg1').textContent=r==='aurora'?'1★ dark sky, no show':'1★';
 renderNow();
}
function setArea(on){
 S.area=on;
 document.querySelectorAll('#areaseg button').forEach(b=>b.classList.toggle('on',(b.dataset.area==='1')===on));
 map.fitBounds(HF.latLngBounds(visible(),0.12),{padding:[28,28],animate:false});renderNow();
}
const sel=document.getElementById('night');
sel.innerHTML=NIGHTS.map((n,i)=>`<option value="${i}">${esc(n.lbl)} · ◐ ${Math.round(n.conf*100)}%</option>`).join('');
sel.onchange=()=>{S.night=+sel.value;renderNow()};
document.getElementById('rampseg').onclick=e=>{const b=e.target.closest('[data-ramp]');if(b)setRamp(b.dataset.ramp)};
document.getElementById('areaseg').onclick=e=>{const b=e.target.closest('[data-area]');if(b)setArea(b.dataset.area==='1')};
document.querySelectorAll('[data-do]').forEach(b=>b.onclick=()=>{
 const[k,v]=b.dataset.do.split(':');
 if(k==='night'){S.night=+v;sel.value=v;renderNow()}
 else if(k==='ramp')setRamp(v);
 else if(k==='area')setArea(v==='1');
 else if(k==='go'){const p=v.split(',').map(Number);map.setView([p[0],p[1]],p[2])}
});
setRamp('aurora');setArea(true);
map.whenReady(renderNow);setTimeout(renderNow,400);

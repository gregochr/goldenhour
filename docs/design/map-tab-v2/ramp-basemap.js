/* Light or dark, judged on the scale the app actually uses.

   My earlier verdict was computed on the RAG ramp, and it does not survive the temperature
   scale. Luminance of the two ramps at whole stars:

     RAG   1★ 82   3★ 170  5★ 162     good end is LIGHT
     TEMP  1★ 86   3★ 152  5★  73     good end is DARK

   Both ramps peak in the middle. That means the ground decides which END separates from it
   (ground luminances: dark #2A2724 = 39, light #E9E6E0 = 230):

     RAG  on dark  — 5★ separates by 123, 1★ by  43  → hot pops.  Correct.
     RAG  on light — 5★ separates by  68, 1★ by 148  → poor pops. Wrong.
     TEMP on dark  — 5★ separates by  34, 3★ by 113  → gold pops. WRONG.
     TEMP on light — 5★ separates by 157, 3★ by  78  → hot pops.  Correct.

   So switching to cold-to-hot inverts the basemap polarity. On today's dark map the deep red
   5★ is the closest colour on the ramp to the basemap itself, and the gold 3★ is the thing
   your eye lands on — the map is loudest where the night is merely average.

   A blend mode does NOT fix this. 'screen' and 'lighter' are monotonic in source luminance,
   so they lift the whole field and leave the ranking where it was — a dark red cannot outrank
   a light gold under either. What fixes it is a second layer whose alpha is keyed to the
   SCORE: the bloom pass in heat-field.js. Read which blob you look at first. */
const clamp=HeatField.clamp;
const POOL=PLAN.spotsIn(PLAN.areaRids()),WIN=0;
const HOMEPT=[54.855,-1.573];
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');
const C='https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/';
const DARK=C+'World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}';
const LIGHT=C+'World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}';

const CELLS=[
 {id:'a',n:'Dark, as it is now',t:'ordering inverted',map:DARK,
  note:'What the Map tab does today. The gold middle is the brightest thing on the screen and the deep red 5★ sinks toward the basemap, so the map shouts about average nights and whispers about good ones. This is the problem, not the baseline.'},
 {id:'b',n:'Dark + heat bloom',t:'ordering restored',map:DARK,bloom:1,pick:1,
  note:'Same tiles, same frozen stops, plus a second emissive layer gated from 3★ up whose strength follows the SCORE rather than the colour\u2019s own luminance. Hot areas glow brightest and the climb from 3★ to 5★ is monotonic again — measured 152 \u00b7 153 \u00b7 165 \u00b7 189.',v:'Best on dark'},
 {id:'c',n:'Light',t:'ordering correct, no trick',map:LIGHT,lgt:1,
  note:'The honest fix. Deep red on pale grey is the highest-contrast thing on the map and the gold middle recedes — correct ordering with no compositing trick. Costs the night-instrument feel, and needs an inverted label style.',v:'Best overall ordering'},
 {id:'d',n:'Light, warmed',t:'ordering correct, in palette',map:LIGHT,lgt:1,warm:1,
  note:'The same, pulled towards the palette so a light map still belongs to a warm app. Worth seeing before rejecting light on the grounds that it clashes.'},
];

const grid=document.getElementById('grid'),maps=[];
let syncing=false;
const rgbOf=c=>`rgb(${c[0]},${c[1]},${c[2]})`;

CELLS.forEach(o=>{
 const card=document.createElement('div');
 card.className='opt'+(o.pick?' pick':'');
 const bar=[1,1.5,2,2.5,3,3.5,4,4.5,5].map(v=>rgbOf(HeatField.ramp(v))).join(',');
 card.innerHTML=`<div class="oh"><span class="n">${esc(o.n)}</span><span class="t">${esc(o.t)}</span></div>`
  +`<div class="mb${o.lgt?' lgt':''}"><div class="lf" id="lf-${o.id}"></div>`
  +`<canvas class="hf"></canvas><div class="lb"></div></div>`
  +`<div class="sw"><span class="rb" style="background:linear-gradient(90deg,${bar})"></span>`
  +`<span class="ends"><i>1★ cold</i><i>3★</i><i>5★ hot</i></span></div>`
  +`<div class="on">${o.note}`+(o.v?`<span class="v">▲ ${o.v}</span>`:'')+`</div>`;
 grid.appendChild(card);

 const m=L.map('lf-'+o.id,{zoomControl:false,attributionControl:false,zoomSnap:0,
  preferCanvas:true,fadeAnimation:false});
 L.tileLayer(o.map,{maxZoom:19,maxNativeZoom:16,
  className:o.warm?'basewarmlight':o.lgt?'baseplain':'basewarm'}).addTo(m);
 m.setView([54.95,-2.1],7.4);
 const rec={o,m,cv:card.querySelector('canvas.hf'),lb:card.querySelector('.lb'),LC:{z:null,p:null}};
 maps.push(rec);
 m.on('move zoom',()=>{draw(rec);
  if(syncing)return;syncing=true;
  const c=m.getCenter(),z=m.getZoom();
  maps.forEach(x=>{if(x!==rec)x.m.setView(c,z,{animate:false})});
  syncing=false});
});

function landPath(rec){
 if(!HeatField.LAND||!window.Path2D)return null;
 const z=rec.m.getZoom();
 if(rec.LC.z!==z){
  const t=d3.geoTransform({point(x,y){const p=rec.m.project([y,x],z);this.stream.point(p.x,p.y)}});
  rec.LC={z,p:new Path2D(d3.geoPath().projection(t)(HeatField.LAND))};
 }
 return rec.LC.p;
}

function label(rec){
 const {m,lb}=rec,sz=m.getSize(),w=sz.x,h=sz.y,z=m.getZoom(),boxes=[];
 lb.innerHTML='';
 const hit=(a,b)=>a.x<b.x+b.w+3&&b.x<a.x+a.w+3&&a.y<b.y+b.h+2&&b.y<a.y+a.h+2;
 const put=(el,x,y)=>{el.style.left='-9999px';el.style.top='0';lb.appendChild(el);
  const bw=el.offsetWidth,bh=el.offsetHeight;
  for(const dy of [0,-12,12,-22,22]){
   const b={x:x-bw/2,y:y-bh/2+dy,w:bw,h:bh};
   if(b.x<2||b.y<2||b.x+b.w>w-2||b.y+b.h>h-2)continue;
   if(boxes.some(o=>hit(b,o)))continue;
   boxes.push(b);el.style.left=b.x+'px';el.style.top=b.y+'px';return}
  el.remove()};
 const mk=(cls,html)=>{const d=document.createElement('span');d.className=cls;d.innerHTML=html;return d};
 const hp=m.latLngToContainerPoint(HOMEPT);
 put(mk('hm','<i class="mk"></i><span class="lb2">HOME</span>'),hp.x,hp.y);
 const named=POOL.filter(s=>s.named).sort((a,b)=>b.r[WIN]-a.r[WIN]||a.min-b.min);
 const bd=m.getBounds(),inView=named.filter(s=>bd.contains([s.lat,s.lng]));
 const budget=Math.round(clamp(4+(z-8.6)*7,4,40)),first={};
 named.forEach(s=>{if(!first[s.rid])first[s.rid]=s});
 [...new Set([...Object.values(first),...inView.slice(0,budget)])].forEach(s=>{
  /* whole stars only: label-bearing fills never sample the ramp's interior */
  const p=m.latLngToContainerPoint([s.lat,s.lng]),v=s.r[WIN],c=HeatField.ramp(v);
  put(mk('loc',`<i style="background:${rgbOf(c)}"></i><b>${esc(s.n)}</b>`
   +`<em>${v}★</em>`),p.x,p.y)});
}

function draw(rec){
 const {m,cv,o}=rec,sz=m.getSize();
 if(!sz.x||!sz.y)return;
 const ctx=HeatField.fit(cv,sz.x,sz.y);ctx.clearRect(0,0,sz.x,sz.y);
 const t=clamp((m.getZoom()-10.4)/1.6,0,1),po=m.getPixelBounds().min;
 HeatField.drawTiles(cv,m,POOL,WIN,{
  radius:HeatField.radiusFor(m,7200,30,190),grid:6,blur:4,conf:PLAN.CONF[WIN],
  opacity:0.92*(1-0.84*t),bloom:o.bloom,
  clipPath:m.getZoom()<11.5?landPath(rec):null,clipDx:-po.x,clipDy:-po.y});
 label(rec);
}
const drawAll=()=>maps.forEach(draw);

document.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>{
 document.querySelectorAll('[data-go]').forEach(x=>x.classList.toggle('on',x===b));
 const[lat,lng,z]=b.dataset.go.split(',').map(Number);
 syncing=true;maps.forEach(x=>x.m.setView([lat,lng],z,{animate:false}));syncing=false;drawAll()});

let started=false;
new ResizeObserver(()=>{
 if(!grid.clientWidth)return;
 maps.forEach(x=>{x.m.invalidateSize({animate:false});x.LC={z:null,p:null}});
 if(!started){started=true;syncing=true;
  maps.forEach(x=>x.m.setView([54.95,-2.1],7.4,{animate:false}));syncing=false}
 drawAll()}).observe(grid);

HeatField.load().then(()=>{maps.forEach(x=>x.LC={z:null,p:null});
 document.getElementById('load').textContent='';drawAll()});

/* Basemap comparison for the Map tab's field. Six synced Leaflet maps, one field kernel, one
   catalogue — so the only variable is the ground. Judge which basemap DISAPPEARS. */
const {SPOTS,WINS,CONF}=PLAN;
const clamp=HeatField.clamp,ramp=HeatField.ramp,rgb=HeatField.rgb;
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');
const HOMEPT=[54.855,-1.573];
const TINY={ntw:'NORTHUMB.',penn:'PENNINES',nymc:'N Y MOORS',lakes:'LAKES',dales:'DALES',borders:'BORDERS',peak:'PEAK'};
const WIN=0;                                            // tonight's sunset, as in the app
const POOL=PLAN.spotsIn(PLAN.areaRids());

const ESRI='https://server.arcgisonline.com/ArcGIS/rest/services/';
const OPTS=[
 {id:'dark',n:'CARTO Dark + labels',t:'today',
  url:'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',sub:'abcd',
  note:'What ships now. The basemap sets its own place names in the same weight and colour as our location chips, so two label systems compete and the one carrying a rating is not the one that wins.'},
 {id:'nolab',n:'Esri Dark Gray Canvas',t:'change 1',
  url:ESRI+'Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
  note:'No place names, so our chips are the only names on the map and the field has the whole surface. Flatter than CARTO with less road detail, which the field benefits from — and the same provider the shipped app already attributes.',v:['up','The single biggest legibility win']},
 {id:'warm',n:'Dark Gray, warmed to palette',t:'change 2',cls:'on2',pick:1,
  url:ESRI+'Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
  note:'The same tiles under a CSS filter that pulls the neutral blue-grey towards the palette. Costs nothing per tile, and the map stops looking like a third-party component embedded in a warm brown app.',v:['up','Recommended']},
 {id:'ref',n:'Dark Gray + reference layer',t:'labels, if wanted',cls:'on2',
  url:ESRI+'Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
  over:ESRI+'Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}',
  note:'If place names turn out to be load-bearing for orientation, they come back as a layer we control — shown only below the zoom where our own chips take over, rather than baked into the tile at every scale.'},
 {id:'hill',n:'Esri hillshade, dark',t:'terrain',cls:'on3',
  url:ESRI+'Elevation/World_Hillshade_Dark/MapServer/tile/{z}/{y}/{x}',
  note:'Relief instead of roads: aspect, valleys and the shape of the coast — the things you are actually reading a map for. Costs contrast against the field and carries no roads, so it is a real trade, not a free win.'},
 {id:'light',n:'Esri Light Gray Canvas',t:'control',
  url:ESRI+'Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}',lgt:1,
  note:'The control. The low end of the ramp — poor — nearly vanishes, so the field stops being a scale and becomes green blobs and elsewhere. Labels need inverting too, which means two label styles to maintain.',v:['dn','Breaks the ramp']},
];

const grid=document.getElementById('grid'),maps=[];
let syncing=false;

OPTS.forEach(o=>{
 const card=document.createElement('div');
 card.className='opt'+(o.pick?' pick':'');
 card.innerHTML=`<div class="oh"><span class="n">${esc(o.n)}</span><span class="t">${esc(o.t)}</span></div>`
  +`<div class="mb${o.lgt?' lgt':''}"><div class="lf" id="lf-${o.id}"></div>`
  +`<canvas class="hf"></canvas><div class="lb"></div></div>`
  +`<div class="on">${o.note}`
  +(o.v?`<span class="v ${o.v[0]}">${o.v[0]==='up'?'▲':'▼'} ${o.v[1]}</span>`:'')+`</div>`;
 grid.appendChild(card);

 const m=L.map('lf-'+o.id,{zoomControl:false,attributionControl:false,zoomSnap:0,
  preferCanvas:true,fadeAnimation:false});
 const tl=L.tileLayer(o.url,{maxZoom:19,subdomains:o.sub||'abc',className:o.cls||''}).addTo(m);
 if(o.over)L.tileLayer(o.over,{maxZoom:19,className:o.cls||''}).addTo(m);
 m.setView([54.95,-2.1],7.4);
 const rec={o,m,cv:card.querySelector('canvas.hf'),lb:card.querySelector('.lb'),LC:{z:null,p:null}};
 maps.push(rec);
 m.on('move zoom',()=>{draw(rec);
  if(syncing)return;syncing=true;
  const c=m.getCenter(),z=m.getZoom();
  maps.forEach(x=>{if(x!==rec)x.m.setView(c,z,{animate:false})});
  syncing=false;
 });
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

/* one greedy pass, region names then locations, best first — the Map tab's rule, scaled down */
function label(rec){
 const {m,lb}=rec,sz=m.getSize(),w=sz.x,h=sz.y,z=m.getZoom(),boxes=[];
 lb.innerHTML='';
 const hit=(a,b)=>a.x<b.x+b.w+3&&b.x<a.x+a.w+3&&a.y<b.y+b.h+2&&b.y<a.y+a.h+2;
 const put=(el,x,y)=>{
  el.style.left='-9999px';el.style.top='0';lb.appendChild(el);
  const bw=el.offsetWidth,bh=el.offsetHeight;
  for(const dy of [0,-12,12,-22,22]){
   const b={x:x-bw/2,y:y-bh/2+dy,w:bw,h:bh};
   if(b.x<2||b.y<2||b.x+b.w>w-2||b.y+b.h>h-2)continue;
   if(boxes.some(o=>hit(b,o)))continue;
   boxes.push(b);el.style.left=b.x+'px';el.style.top=b.y+'px';return true;
  }
  el.remove();return false;
 };
 const mk=(cls,html)=>{const d=document.createElement('span');d.className=cls;d.innerHTML=html;return d};
 const hp=m.latLngToContainerPoint(HOMEPT);
 if(z<13)put(mk('hm','<i class="mk"></i><span class="lb2">HOME</span>'),hp.x,hp.y);
 if(z<11.2){
  const rids=[...new Set(POOL.map(s=>s.rid))];
  rids.forEach(rid=>{
   const c=HeatField.centroid(POOL,rid,s=>{const p=m.latLngToContainerPoint([s.lat,s.lng]);return[p.x,p.y]});
   if(c)put(mk('rg2',(TINY[rid]||rid).replace(/ /g,'&nbsp;')),c[0],c[1]);
  });
 }
 const named=POOL.filter(s=>s.named).sort((a,b)=>b.r[WIN]-a.r[WIN]||a.min-b.min);
 const b=m.getBounds(),inView=named.filter(s=>b.contains([s.lat,s.lng]));
 const budget=Math.round(clamp(4+(z-8.6)*7,4,40));
 const first={};named.forEach(s=>{if(!first[s.rid])first[s.rid]=s});
 [...new Set([...Object.values(first),...inView.slice(0,budget)])].forEach(s=>{
  const p=m.latLngToContainerPoint([s.lat,s.lng]),v=s.r[WIN],c=ramp(v);
  put(mk('loc',`<i style="background:${rgb(c)}"></i><b>${esc(s.n)}</b><em style="color:${rgb(c)}">${v}★</em>`),p.x,p.y);
 });
}

function draw(rec){
 const {m,cv}=rec,sz=m.getSize();
 if(!sz.x||!sz.y)return;
 const ctx=HeatField.fit(cv,sz.x,sz.y);
 ctx.clearRect(0,0,sz.x,sz.y);
 const t=clamp((m.getZoom()-10.4)/1.6,0,1),alpha=1-0.84*t;
 const o=m.getPixelBounds().min;
 HeatField.drawTiles(cv,m,POOL,WIN,{
  radius:HeatField.radiusFor(m,7200,30,190),grid:6,blur:4,conf:CONF[WIN],
  opacity:0.92*alpha,clipPath:landPath(rec),clipDx:-o.x,clipDy:-o.y});
 label(rec);
}
const drawAll=()=>maps.forEach(draw);

document.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>{
 document.querySelectorAll('[data-go]').forEach(x=>x.classList.toggle('on',x===b));
 const[lat,lng,z]=b.dataset.go.split(',').map(Number);
 syncing=true;maps.forEach(x=>x.m.setView([lat,lng],z,{animate:false}));syncing=false;
 drawAll();
});

/* the boxes are laid out by the grid, so the box is the trigger — never fit or paint against
   a container that has not been measured yet */
let started=false;
new ResizeObserver(()=>{
 if(!grid.clientWidth)return;
 maps.forEach(x=>{x.m.invalidateSize({animate:false});x.LC={z:null,p:null}});
 if(!started){started=true;syncing=true;
  maps.forEach(x=>x.m.setView([54.95,-2.1],7.4,{animate:false}));syncing=false}
 drawAll();
}).observe(grid);

HeatField.load().then(()=>{
 maps.forEach(x=>x.LC={z:null,p:null});
 document.getElementById('load').textContent='';
 drawAll();
});

/* PhotoCast heat field — ONE field kernel, two hosts.

   The kernel (field/paint) knows nothing about maps: give it screen-space points and it
   returns the blended score field. The geo host (drawGeo) projects with d3 and clips to
   real coastline for the static Plan thumbnails; the tile host (Plan tab's Map, or any
   Leaflet map) projects with the map itself and paints over the basemap. Both get the same
   colour ramp, the same coverage clamp, the same confidence haze and the same cull, so the
   two surfaces can never drift in what they mean. */
(function(){
const BBOX={type:'MultiPoint',coordinates:[[-3.85,53.80],[-0.28,53.80],[-0.28,55.88],[-3.85,55.88]]};
/* TWO ramps, one kernel. 'temp' is the default: cold slate at 1 star, red at 5, so the field
   reads the way every other heat map on earth reads — more heat means more of the thing.
   'verdict' is the legacy scale (red=bad, green=go) kept behind a user toggle so anyone who
   learned the old map is not stranded. Both surfaces read MODE from here, so Plan and Map can
   never disagree about what a colour means. */
const STOPS_VERDICT=[[1,[176,58,42]],[2,[200,69,47]],[3,[224,165,66]],[4,[176,190,116]],[5,[138,174,114]]];
/* Stops are NOT evenly spaced, on purpose. Regional means in a real week occupy roughly
   1.9-4.6, and 65% of location scores sit between 2.5 and 4.5 — so an evenly spaced ramp
   spends its blue end and its red end on values that almost never survive the blur, and
   renders every night the same orange. The spacing below puts the cool half above 2.8 and
   the crossover at 3, so a 2.5 night reads cold and a 4.6 night reads hot.
   The 2.2 stop is held dark on purpose, and it helps twice: a cold night reads cold, and the
   app's white marker ink clears 4.5:1 against a dark fill. Do not lighten it toward mid-tone —
   mid-luminance is where neither #0F172A nor #FFFFFF passes. The worst stop on that count is
   4.3 (#D63A26): dark ink reaches 3.82:1, white only 4.67:1. It is safe only because every
   label-bearing surface samples at whole stars; if one ever goes continuous, fix 4.3 first. */
const STOPS_TEMP=[[1,[58,92,112]],[2.2,[80,104,120]],[2.8,[146,140,128]],[3,[196,148,64]],[3.2,[201,146,48]],[3.9,[223,107,42]],[4.3,[214,58,38]],[5,[242,96,52]]];
let MODE='temp';
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
function rampOn(STOPS,s){s=clamp(s,1,5);
 for(let i=0;i<STOPS.length-1;i++){const[a,ca]=STOPS[i],[b,cb]=STOPS[i+1];
  if(s<=b){const f=(s-a)/(b-a);return[0,1,2].map(k=>Math.round(ca[k]+(cb[k]-ca[k])*f))}}
 return STOPS[4][1]}
function ramp(s){return rampOn(MODE==='verdict'?STOPS_VERDICT:STOPS_TEMP,s)}

const HF={
 LAND:null,ramp,clamp,
 rgb:(c,a)=>`rgba(${c[0]},${c[1]},${c[2]},${a==null?1:a})`,

 /* ── the kernel ─────────────────────────────────────────────────────────────
    pts  : [{x,y,sc,rid}] in CSS px of the target surface
    opts : {radius, grid, conf, focus, alpha}
    Returns {canvas, cols, rows, gp, n} or null when nothing can contribute.
    conf 0–1 is forecast confidence: lower desaturates and thins, so a day-4 guess cannot
    look as authoritative as tonight. focus fades every other region almost to nothing. */
 field(pts,w,h,opts){
  opts=opts||{};
  const conf=opts.conf==null?1:clamp(opts.conf,0,1),unc=1-conf;
  const R=opts.radius||Math.max(14,w*0.085),R2=R*R,gp=opts.grid||4;
  /* cull to the frame plus the kernel's reach (the sum cuts off at d2>6R2, i.e. ~2.45R).
     A location that cannot touch a single cell must not cost one iteration per cell. */
  const CUT=2.45*R,m=CUT+gp,keep=[];
  for(const p of pts){if(p.x<-m||p.x>w+m||p.y<-m||p.y>h+m)continue;keep.push(p)}
  if(!keep.length)return null;
  /* bucket by the cutoff distance so each cell sums only its 3x3 neighbourhood instead of
     the whole catalogue. This is what turns O(cells x locations) into O(cells x local
     density) — without it 200 locations already stalled a pan, and 1000 would be hopeless. */
  const B=Math.max(CUT,1),bx0=-m,by0=-m;
  const bw=Math.ceil((w+2*m)/B)+1,bh=Math.ceil((h+2*m)/B)+1;
  const buckets=new Array(bw*bh);
  for(const p of keep){
   const bi=Math.floor((p.x-bx0)/B),bj=Math.floor((p.y-by0)/B);
   if(bi<0||bj<0||bi>=bw||bj>=bh)continue;
   const k=bj*bw+bi;(buckets[k]||(buckets[k]=[])).push(p);
  }
  const cols=Math.ceil(w/gp)+1,rows=Math.ceil(h/gp)+1;
  const off=document.createElement('canvas');off.width=cols;off.height=rows;
  const octx=off.getContext('2d'),img=octx.createImageData(cols,rows);
  const aMax=(opts.alpha==null?206:opts.alpha)*(1-0.34*unc);
  for(let j=0;j<rows;j++)for(let i=0;i<cols;i++){
   const px=i*gp,py=j*gp;let sw=0,sws=0;
   const bi=Math.floor((px-bx0)/B),bj=Math.floor((py-by0)/B);
   for(let dj=-1;dj<=1;dj++)for(let di=-1;di<=1;di++){
    const bjj=bj+dj,bii=bi+di;
    if(bii<0||bjj<0||bii>=bw||bjj>=bh)continue;
    const list=buckets[bjj*bw+bii];if(!list)continue;
    for(const p of list){const dx=px-p.x,dy=py-p.y,d2=dx*dx+dy*dy;if(d2>R2*6)continue;
     let wt=Math.exp(-d2/R2);if(opts.focus&&p.rid!==opts.focus)wt*=1e-4;sw+=wt;sws+=wt*p.sc}
   }
   const k=(j*cols+i)*4;
   if(sw<0.02){img.data[k+3]=0;continue}
   let c=ramp(sws/sw);
   /* coverage clamp: warmth only where locations actually are, so empty ground stays empty
      instead of being coloured in by interpolation from thirty miles away */
   const cov=1-Math.exp(-sw/1.15);
   if(unc>0){const g=(c[0]+c[1]+c[2])/3,d=0.6*unc;c=[c[0]+(g-c[0])*d,c[1]+(g-c[1])*d,c[2]+(g-c[2])*d]}
   img.data[k]=c[0];img.data[k+1]=c[1];img.data[k+2]=c[2];
   img.data[k+3]=Math.round(clamp(cov,0,1)*aMax);
  }
  octx.putImageData(img,0,0);
  return{canvas:off,cols,rows,gp,n:keep.length,unc};
 },

 /* paint a field into any 2d context. clip is an optional callback that sets a path. */
 paint(ctx,w,h,pts,opts){
  opts=opts||{};
  const f=HF.field(pts,w,h,opts);if(!f)return null;
  ctx.save();
  if(opts.clip){ctx.beginPath();opts.clip(ctx);ctx.clip()}
  ctx.imageSmoothingEnabled=true;
  ctx.filter=`blur(${(opts.blur||3)+f.unc*2.6}px)`;
  ctx.globalAlpha=opts.opacity==null?0.92:opts.opacity;
  ctx.drawImage(f.canvas,0,0,f.cols,f.rows,0,0,f.cols*f.gp,f.rows*f.gp);
  ctx.restore();
  return f;
 },

 /* size a canvas for the display's pixel ratio and return its 2d context */
 fit(cv,w,h){
  const dpr=Math.min(window.devicePixelRatio||1,2);
  cv.width=Math.round(w*dpr);cv.height=Math.round(h*dpr);
  cv.style.width=w+'px';cv.style.height=h+'px';
  const ctx=cv.getContext('2d');ctx.setTransform(dpr,0,0,dpr,0,0);
  return ctx;
 },

 /* ── geo helpers ──────────────────────────────────────────────────────────── */
 load(){
  if(HF.LAND)return Promise.resolve(HF.LAND);
  return d3.json('https://cdn.jsdelivr.net/npm/world-atlas@2.0.2/countries-50m.json').then(topo=>{
   const f=topojson.feature(topo,topo.objects.countries).features;
   const uk=f.filter(x=>x.id==='826'||(x.properties&&/^united kingdom$/i.test(x.properties.name||'')));
   HF.LAND={type:'FeatureCollection',features:uk.length?uk:f};
   return HF.LAND});
 },
 /* corner MultiPoint, never a ring: a polygon's winding can be read as the whole globe,
    which silently fits the projection to the world instead of the area you asked for */
 proj(w,h,fit){return d3.geoMercator().fitExtent([[-2,-2],[w+2,h+2]],fit||BBOX)},
 bbox(spots,padDeg){
  if(!spots||!spots.length)return BBOX;
  const p=padDeg==null?0.16:padDeg;
  const la=spots.map(s=>s.lat),ln=spots.map(s=>s.lng);
  const a=Math.min(...la)-p,b=Math.max(...la)+p,c=Math.min(...ln)-p*1.7,d=Math.max(...ln)+p*1.7;
  return{type:'MultiPoint',coordinates:[[c,a],[d,a],[d,b],[c,b]]};
 },
 /* [[south,west],[north,east]] for Leaflet's fitBounds */
 latLngBounds(spots,padDeg){
  const c=HF.bbox(spots,padDeg).coordinates,la=c.map(x=>x[1]),ln=c.map(x=>x[0]);
  return[[Math.min(...la),Math.min(...ln)],[Math.max(...la),Math.max(...ln)]];
 },
 /* aspect of a frame (height/width) so a surface can size itself to its geography */
 aspect(fit){
  const cs=(fit||BBOX).coordinates,la=cs.map(c=>c[1]),ln=cs.map(c=>c[0]);
  const dLat=Math.max(...la)-Math.min(...la);
  const dLng=(Math.max(...ln)-Math.min(...ln))*Math.cos(d3.mean(la)*Math.PI/180);
  return dLng>0?dLat/dLng:1;
 },
 centroid(spots,rid,project){
  const ps=spots.filter(s=>s.rid===rid).map(s=>project(s));
  return ps.length?[d3.mean(ps,p=>p[0]),d3.mean(ps,p=>p[1])]:null},

 /* ── host A: static canvas, d3 projection, clipped to real coastline ──────── */
 drawGeo(cv,w,h,spots,win,opts){
  opts=opts||{};
  if(!cv||!(w>20)||!(h>20)||!HF.LAND)return null;   // a zero measure throws on cv.width
  const ctx=HF.fit(cv,w,h);
  const proj=HF.proj(w,h,opts.fit),path=d3.geoPath(proj,ctx);
  ctx.fillStyle=opts.sea||'#13100e';ctx.fillRect(0,0,w,h);
  ctx.beginPath();path(HF.LAND);ctx.fillStyle=opts.plate||'#241d18';ctx.fill();
  const pts=spots.map(s=>{const p=proj([s.lng,s.lat]);return{x:p[0],y:p[1],sc:s.r[win],rid:s.rid}});
  HF.paint(ctx,w,h,pts,Object.assign({},opts,{alpha:opts.focus?238:206,clip:c=>path(HF.LAND)}));
  ctx.beginPath();path(HF.LAND);ctx.strokeStyle=opts.stroke||'rgba(242,231,211,.30)';
  ctx.lineWidth=opts.line||0.7;ctx.stroke();
  return proj;
 },

 /* ── host B: a Leaflet map, painted over the basemap (tiles carry the geography) ── */
 drawTiles(cv,map,spots,win,opts){
  opts=opts||{};
  const sz=map.getSize(),w=sz.x,h=sz.y;
  if(!(w>20)||!(h>20))return null;
  const ctx=HF.fit(cv,w,h);
  ctx.clearRect(0,0,w,h);
  const pts=spots.map(s=>{const p=map.latLngToContainerPoint([s.lat,s.lng]);
   return{x:p.x,y:p.y,sc:s.r[win],rid:s.rid}});
  return HF.paint(ctx,w,h,pts,opts);
 },
 /* metres-per-pixel at the map's centre, so a radius can be set in real distance */
 radiusFor(map,metres,lo,hi){
  const c=map.getCenter();
  const mpp=156543.03392*Math.cos(c.lat*Math.PI/180)/Math.pow(2,map.getZoom());
  return clamp(metres/mpp,lo==null?34:lo,hi==null?240:hi);
 }
};
/* Map a 0-100 metric onto the 1-5 ramp. lo/hi are the metric's own working range:
   assuming 0-100 spans the whole ramp is what makes every bar read gold, because real
   Fiery Sky and Golden Hour scores do not use the ends. Measure, then set lo/hi. */
HF.rampPct=(v,lo,hi)=>{lo=lo==null?0:lo;hi=hi==null?100:hi;
 return ramp(1+clamp((v-lo)/(hi-lo),0,1)*4)};
HF.STOPS_TEMP=STOPS_TEMP;HF.STOPS_VERDICT=STOPS_VERDICT;HF.rampOn=rampOn;
HF.setMode=m=>{MODE=(m==='verdict'?'verdict':'temp')};
HF.getMode=()=>MODE;
HF.stops=()=>MODE==='verdict'?STOPS_VERDICT:STOPS_TEMP;
window.HeatField=HF;
})();

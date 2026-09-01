/* PhotoCast heat field — ONE field kernel, two hosts.

   The kernel (field/paint) knows nothing about maps: give it screen-space points and it
   returns the blended score field. The geo host (drawGeo) projects with d3 and clips to
   real coastline for the static Plan thumbnails; the tile host (Plan tab's Map, or any
   Leaflet map) projects with the map itself and paints over the basemap. Both get the same
   colour ramp, the same coverage clamp, the same confidence haze and the same cull, so the
   two surfaces can never drift in what they mean. */
(function(){
const BBOX={type:'MultiPoint',coordinates:[[-3.85,53.80],[-0.28,53.80],[-0.28,55.88],[-3.85,55.88]]};
/* The shipped temperature scale: cold slate blue at 1★ through gold to deep red at 5★.
   Stops are deliberately UNEVEN — regional means occupy roughly 1.9–4.6, so evenly spaced
   stops spend the blue and the red on values that never survive the blur. The hot leg
   descends in luminance on purpose, so 4.3★ cannot read hotter than 5★. Do not "fix"
   either property; both are load-bearing. Source: design_temp_scale/heat-field.js. */
const STOPS=[[1,[58,92,112]],[2.2,[80,104,120]],[2.8,[146,140,128]],[3,[196,148,64]],[3.2,[201,146,48]],[3.9,[223,107,42]],[4.3,[222,72,38]],[5,[200,40,32]]];
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
function ramp(s){s=clamp(s,1,5);
 for(let i=0;i<STOPS.length-1;i++){const[a,ca]=STOPS[i],[b,cb]=STOPS[i+1];
  if(s<=b){const f=(s-a)/(b-a);return[0,1,2].map(k=>Math.round(ca[k]+(cb[k]-ca[k])*f))}}
 return STOPS[STOPS.length-1][1]}

const HF={
 LAND:null,ramp,clamp,
 rgb:(c,a)=>`rgba(${c[0]},${c[1]},${c[2]},${a==null?1:a})`,

 /* Ink for a ramp FILL, chosen per fill. This is the distinction design_temp_scale draws and
    the reason "no ramp colour as text, anywhere": as a fill the ramp colour has ink placed on
    top of it and the ink can be picked to pass; as text the ramp colour IS the ink and cannot.
    The mid-peaked luminance makes the text case unfixable by a floor — 1★ and 5★ fail from
    opposite ends of the ramp. Pair is the app's shipped ink pair. */
 ink(c){
  const lin=v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4)};
  const L=0.2126*lin(c[0])+0.7152*lin(c[1])+0.0722*lin(c[2]);
  const cr=o=>{const hi=Math.max(L,o)+0.05,lo=Math.min(L,o)+0.05;return hi/lo};
  return cr(1)>=cr(0.0138)?'#FFFFFF':'#0F172A';   // 0.0138 = luminance of #0F172A
 },

 /* ── the kernel ─────────────────────────────────────────────────────────────
    pts  : [{x,y,sc,rid}] in CSS px of the target surface
    opts : {radius, grid, conf, focus, alpha}
    Returns {canvas, cols, rows, gp, n} or null when nothing can contribute.
    conf 0–1 is forecast confidence: lower desaturates and thins, so a day-4 guess cannot
    look as authoritative as tonight. focus fades every other region almost to nothing. */
 field(pts,w,h,opts){
  opts=opts||{};
  /* opts.ramp lets a host substitute the colour scale without touching the kernel — used by
     the ramp comparison so every option paints through an identical field */
  const ramp=opts.ramp||HF.ramp;
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
  /* Optional emissive layer, keyed to SCORE rather than to the colour's own luminance.
     The temperature ramp peaks in luminance at the gold 3★ and its hot end is its darkest
     colour, so on a dark ground luminance contrast ranks the middle highest and the ordering
     inverts. No blend mode fixes that — screen and lighter are both monotonic in source
     luminance, so a dark red can never outrank a light gold under them. A separate layer
     whose alpha rises with the blended score can, and it leaves the frozen stops alone. */
  const bl=opts.bloom?octx.createImageData(cols,rows):null;
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
   const s0=sws/sw;
   let c=ramp(s0);
   /* coverage clamp: warmth only where locations actually are, so empty ground stays empty
      instead of being coloured in by interpolation from thirty miles away */
   const cov=1-Math.exp(-sw/1.15);
   if(unc>0){const g=(c[0]+c[1]+c[2])/3,d=0.6*unc;c=[c[0]+(g-c[0])*d,c[1]+(g-c[1])*d,c[2]+(g-c[2])*d]}
   img.data[k]=c[0];img.data[k+1]=c[1];img.data[k+2]=c[2];
   img.data[k+3]=Math.round(clamp(cov,0,1)*aMax);
   if(bl){
    /* The gate must stay at 3★ — where the ramp's own luminance peaks. Any higher leaves a
       DEAD BAND between 3★ and the gate in which the ramp is already darkening and the bloom
       contributes nothing, which reintroduces the exact inversion the bloom exists to remove.
       Measured: gate 3.7 on the thumbnails put 3★ and 5★ 0.2 luminance apart.
       To stop a small surface washing out, cut bloomBlur (keeps the glow on the hot cores)
       rather than raising the gate or dropping the strength. Exponent 1.2 starts the climb
       gently so a 3.2★ does not glow. */
    const g=opts.bloomFrom==null?3:opts.bloomFrom;
    const t=Math.pow(clamp((s0-g)/(5-g),0,1),1.2);
    bl.data[k]=255;bl.data[k+1]=138;bl.data[k+2]=66;
    bl.data[k+3]=Math.round(t*clamp(cov,0,1)*(opts.bloomA==null?190:opts.bloomA)*conf);
   }
  }
  octx.putImageData(img,0,0);
  let bloom=null;
  if(bl){bloom=document.createElement('canvas');bloom.width=cols;bloom.height=rows;
   bloom.getContext('2d').putImageData(bl,0,0)}
  return{canvas:off,bloom,cols,rows,gp,n:keep.length,unc};
 },

 /* paint a field into any 2d context. clip is an optional callback that sets a path. */
 paint(ctx,w,h,pts,opts){
  opts=opts||{};
  const f=HF.field(pts,w,h,opts);if(!f)return null;
  /* A HARD clip gives a crisp edge against a blurred field, which reads as an artifact the
     moment the mask contains anything the eye knows should not be a sharp line — a perfect
     circle, or a coastline at a zoom where 1:50m error is visible. clipSoft renders the field
     to a temp surface and masks it with a BLURRED fill of the same path, so the boundary
     feathers at the same rate the field does. The thumbnails keep the hard clip: there the
     crisp coast is the point. */
  if(opts.clipPath&&opts.clipSoft){
   const t=document.createElement('canvas');
   t.width=ctx.canvas.width;t.height=ctx.canvas.height;
   const tc=t.getContext('2d'),sx=t.width/w;
   tc.setTransform(sx,0,0,sx,0,0);
   HF._blit(tc,f,opts);
   /* The mask is composed on its OWN surface and applied in a single destination-in. Doing
      fill() then stroke() straight onto the field with destination-in already set makes the
      two INTERSECT rather than union — which leaves only the dilation band and erases the
      land it was meant to extend. */
   const mk=document.createElement('canvas');
   mk.width=t.width;mk.height=t.height;
   const mc=mk.getContext('2d');
   mc.setTransform(sx,0,0,sx,0,0);
   mc.filter=`blur(${opts.clipSoft}px)`;
   mc.translate(opts.clipDx||0,opts.clipDy||0);
   mc.fillStyle='#fff';mc.strokeStyle='#fff';
   /* clipGrow DILATES the mask by stroking the same path — a uniform band that follows the
      coastline, which absorbs a coarse coastline's 2–5km error without inventing a shape.
      Unioning discs at each location did the same job but drew visible circles offshore,
      because the error is geographic and so the disc had to grow with zoom. */
   if(opts.clipGrow>0){
    mc.lineWidth=opts.clipGrow*2;mc.lineJoin='round';mc.lineCap='round';
    mc.stroke(opts.clipPath);
   }
   mc.fill(opts.clipPath);
   tc.globalCompositeOperation='destination-in';
   tc.globalAlpha=1;tc.filter='none';
   tc.setTransform(1,0,0,1,0,0);
   tc.drawImage(mk,0,0);
   ctx.save();ctx.globalAlpha=1;ctx.filter='none';
   ctx.globalCompositeOperation='source-over';
   ctx.drawImage(t,0,0,w,h);
   ctx.restore();
   return f;
  }
  ctx.save();
  /* clipPath is a Path2D in some other coordinate space, offset by clipDx/clipDy — the tile
     host builds one land mask per zoom in absolute pixel coords and slides it, so the coast
     is a clip rather than a redraw. clip() is a callback that sets a path in this space. */
  if(opts.clipPath){const dx=opts.clipDx||0,dy=opts.clipDy||0;
   ctx.translate(dx,dy);ctx.clip(opts.clipPath);ctx.translate(-dx,-dy)}
  else if(opts.clip){ctx.beginPath();opts.clip(ctx);ctx.clip()}
  HF._blit(ctx,f,opts);
  ctx.restore();
  return f;
 },

 /* the field, then its emissive pass — shared by the clipped and soft-masked routes */
 _blit(ctx,f,opts){
  ctx.imageSmoothingEnabled=true;
  ctx.filter=`blur(${(opts.blur||3)+f.unc*2.6}px)`;
  ctx.globalAlpha=opts.opacity==null?0.92:opts.opacity;
  ctx.drawImage(f.canvas,0,0,f.cols,f.rows,0,0,f.cols*f.gp,f.rows*f.gp);
  /* additive, and INSIDE this surface — it composites against the field just drawn, which is
     why it works where a blend against the tiles could not: the overlay canvas is a DOM
     sibling of the tile pane and clearRect leaves nothing for a canvas-level blend to hit */
  if(f.bloom){
   ctx.globalCompositeOperation='lighter';
   ctx.filter=`blur(${(opts.blur||3)*(opts.bloomBlur||2.4)+f.unc*3}px)`;
   ctx.drawImage(f.bloom,0,0,f.cols,f.rows,0,0,f.cols*f.gp,f.rows*f.gp);
  }
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
  /* opts.score lets a host score a location by something other than a solar window index —
     the Map tab's night events (astro, aurora) are derived, not in s.r */
  const sc=opts.score||(s=>s.r[win]);
  const pts=spots.map(s=>{const p=map.latLngToContainerPoint([s.lat,s.lng]);
   return{x:p.x,y:p.y,sc:sc(s),rid:s.rid}});
  return HF.paint(ctx,w,h,pts,opts);
 },
 /* metres-per-pixel at the map's centre, so a radius can be set in real distance */
 radiusFor(map,metres,lo,hi){
  const c=map.getCenter();
  const mpp=156543.03392*Math.cos(c.lat*Math.PI/180)/Math.pow(2,map.getZoom());
  return clamp(metres/mpp,lo==null?34:lo,hi==null?240:hi);
 }
};
window.HeatField=HF;
})();

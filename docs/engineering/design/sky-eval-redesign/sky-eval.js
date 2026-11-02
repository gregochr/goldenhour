(()=>{
const DAY=864e5,NOW=new Date(2026,10,2,9,26).getTime();
const sod=t=>{const d=new Date(t);d.setHours(0,0,0,0);return d.getTime()};
const p2=n=>String(n).padStart(2,"0");
const hm=t=>{const d=new Date(t);return p2(d.getHours())+":"+p2(d.getMinutes())};
const MO=["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"],WD=["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
const ds2=t=>{const d=new Date(t);return d.getDate()+" "+MO[d.getMonth()]};
const dl=t=>WD[new Date(t).getDay()]+" "+ds2(t);
const ago=t=>{const m=(NOW-t)/6e4;return m<1?"just now":m<60?Math.round(m)+"m ago":m<1440?Math.round(m/60)+"h ago":Math.round(m/1440)+"d ago"};
const mmss=s=>s<60?Math.round(s)+"s":Math.floor(s/60)+"m "+p2(Math.round(s%60))+"s";
const gbp=c=>"£"+c.toFixed(2);
const sum=a=>a.reduce((x,y)=>x+y,0);
const P=x=>x==null?"—":(x>=.9995?"100":(x*100).toFixed(1).replace(/\.0$/,""))+"%";
const pts=d=>(d>=0?"+":"−")+Math.abs(d*100).toFixed(1).replace(/\.0$/,"")+" pts";
let sd=7;const R=()=>{sd=sd+0x6D2B79F5|0;let t=Math.imul(sd^sd>>>15,1|sd);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296};

const M={s55:{l:"Sonnet 5.5",c:"oklch(.74 .14 340)",cost:.28,dur:11,base:.012},h45:{l:"Haiku 4.5",c:"oklch(.72 .11 250)",cost:.085,dur:6,base:.05},h55:{l:"Haiku 5.5",c:"oklch(.8 .1 200)",cost:.012,dur:5,base:.035},s46:{l:"Sonnet 4.6",c:"oklch(.72 .12 300)",cost:.36,dur:14,base:.055},opus:{l:"Opus",c:"oklch(.82 .12 85)",cost:.72,dur:24,base:.015}};
const MK=Object.keys(M);
const LANES=[{m:"s55",l:"T and T+1 forecasts",w:"Must be as accurate as possible"},{m:"h45",l:"T+2 and T+3 look-ahead",w:"Cheaper, needs to be accurate enough to plan with"}];
const PROD=Object.fromEntries(LANES.map(l=>[l.m,l]));
const at15=d=>sod(NOW)-d*DAY+15*36e5;
const FIX=[["copt-hill-11mar-false-positive",1,2,"up",1.5,999],["copt-hill-15mar-overcast",1,2,"up",1.3,999],["copt-hill-16mar-horizon-strip",3,4,"mix",2.2,999],["copt-hill-5mar-washout",1,2,"up",1,999],["middling-hazy-mixed",2,3,"mix",.6,999],["st-marys-10mar-moderate",4,4,"mix",.8,999],["st-marys-7apr-clear-sky-cap",1,3,"up",1.8,999],["far-corridor-blanket-canvas",1,2,"up",.7,63],["seaham-28sep-low-sun-cirrus",3,5,"down",1.4,35],["durham-2oct-sea-fret",2,3,"mix",1,35]].map(([id,lo,hi,b,d,s])=>({id,lo,hi,b,d,since:at15(s)}));
const band=f=>f.lo===f.hi?""+f.lo:f.lo+"–"+f.hi;
const COM=[["b7e01aa",126,"Baseline calibration set"],["3d9c2f1",98,"Prompt: weigh horizon clearance"],["5a1e8c0",70,"Prompt: clarify overcast wording"],["8f22b19",63,"Add far-corridor fixture"],["e07d4a2",35,"Prompt: cap clear-sky scores; add 2 fixtures"],["9b3f6d7",28,"Weekly eval: add 5.5 models"],["c41e2d8",9,"Scorer: per-fixture band tolerance"],["a91b0e2",-1,"Prompt: stricter cirrus penalty"]].map(([id,d,m])=>({id,m,t:d<0?NOW-95*6e4:at15(d)}));
const HEAD=COM.length-1;
const ci=t=>{let i=0;COM.forEach((c,j)=>{if(c.t<=t)i=j});return i};

function prob(m,f,c){let p=M[m].base*f.d,pd=f.b==="up"?p*.15:f.b==="down"?p*.85:p*.5,pu=p-pd;
 if(f.id.includes("horizon")&&c<1){pd*=1.8;pu*=1.8}
 if(f.id.includes("clear-sky")&&c>=4)pu*=.3;
 if(m==="s46"&&f.id.includes("false-positive")&&c>=2)pu+=.55;
 if(c>=7){if(m==="h55"&&(f.id.includes("horizon")||f.id.includes("cirrus")))pd+=.42;if(m==="h45"&&f.id.includes("cirrus"))pd+=.1}
 return[pd,pu]}
let RID=1;const RUNS=[];
function gen(m,t,trig,rpf=8,only){const c=ci(t),fx=FIX.filter(f=>f.since<=t&&(!only||only.includes(f.id))),res={};let inb=0,dn=0,up=0;
 fx.forEach(f=>{const[pd,pu]=prob(m,f,c),o={n:rpf,inb:0,dn:0,up:0,rs:[]};for(let i=0;i<rpf;i++){const u=R();let r;if(u<pd){o.dn++;r=f.lo-.6-R()*.9}else if(u<pd+pu){o.up++;r=f.hi+.6+R()*.9}else{o.inb++;r=f.lo-.45+R()*(f.hi-f.lo+.9)}o.rs.push(Math.max(.3,Math.min(5.7,r)))}
  o.mean=sum(o.rs)/rpf;o.min=Math.min(...o.rs);o.max=Math.max(...o.rs);res[f.id]=o;inb+=o.inb;dn+=o.dn;up+=o.up});
 const k=fx.length/10*rpf/8;return{id:RID++,m,t,trig,c,rpf,nfix:fx.length,res,inb,dn,up,n:inb+dn+up,pass:inb/(inb+dn+up),cost:M[m].cost*k*(.9+R()*.2),dur:M[m].dur*60*k*(.8+R()*.4),st:"done"}}
for(let d=120;d>=1;d-=7){const t=sod(NOW)-d*DAY+4*36e5,ms=d>=29?["h45","s46","opus"]:["s55","h45","h55"];
 ms.forEach((m,i)=>{const tt=t+i*9e5;if(d===113)RUNS.push({id:RID++,m,t:tt,trig:"weekly",c:ci(tt),st:"fail",note:"Batch API timed out",nfix:8,rpf:8,cost:0,dur:0});else RUNS.push(gen(m,tt,"weekly"))})}
RUNS.push(gen("s55",NOW-21*36e5,"manual"));
[["h55",50],["h55",47],["h55",44],["s55",41],["s55",37],["h45",31],["h45",27]].forEach(([m,a])=>RUNS.push(gen(m,NOW-a*6e4,"manual")));
let SESS=[];
function group(){SESS=[];RUNS.slice().sort((a,b)=>a.t-b.t).forEach(r=>{const s=SESS[SESS.length-1];if(s&&s.trig===r.trig&&r.t-s.last<40*6e4&&s.c===r.c){s.runs.push(r);s.last=r.t}else SESS.push({id:"s"+r.id,trig:r.trig,t:r.t,last:r.t,c:r.c,runs:[r]})})}
group();

const ok=r=>r.st==="done";
const mRuns=m=>RUNS.filter(r=>r.m===m&&ok(r)).sort((a,b)=>a.t-b.t);
const avg=rs=>rs.length?sum(rs.map(r=>r.inb))/sum(rs.map(r=>r.n)):null;
const w4=m=>mRuns(m).filter(r=>r.trig==="weekly").slice(-4);
const onC=(m,c)=>mRuns(m).filter(r=>r.c===c);
const prevC=(m,c)=>{for(let i=c-1;i>=0;i--){const rs=onC(m,i);if(rs.length)return{c:i,rs}}return null};
const lastRun=m=>mRuns(m).slice(-1)[0];
const fresh=m=>{const l=lastRun(m);return l&&l.t>NOW-21*DAY};
const sw=m=>`<i class="sw" style="background:${M[m].c}"></i>`;
const cmTag=c=>`<span class="cm"${tip(`<div class="th">${COM[c].id}</div>${COM[c].m}<br><span style="color:var(--ink-3)">${dl(COM[c].t)} ${hm(COM[c].t)}</span>`)}>${COM[c].id}</span>`;

const LS="photocast-sky-eval";
const S=Object.assign({tab:"models",fm:"s55",fo:null,cc:HEAD,open:null,hl:null,chk:false,cm:["s55","h45"],rpf:3,fs:"all",more:false},JSON.parse(localStorage.getItem(LS)||"{}"));
const save=()=>localStorage.setItem(LS,JSON.stringify({tab:S.tab,fm:S.fm,cc:S.cc,rpf:S.rpf}));
let TIPS=[];function tip(h){TIPS.push(h);return` data-t="${TIPS.length-1}"`}
const tipEl=document.getElementById("tip");
document.addEventListener("mousemove",e=>{const t=e.target.closest&&e.target.closest("[data-t]");if(!t){tipEl.style.display="none";return}tipEl.innerHTML=TIPS[+t.dataset.t]||"";tipEl.style.display="block";const w=tipEl.offsetWidth,h=tipEl.offsetHeight;let x=e.clientX+14,y=e.clientY+14;if(x+w>innerWidth-8)x=e.clientX-w-14;if(y+h>innerHeight-8)y=e.clientY-h-14;tipEl.style.left=x+"px";tipEl.style.top=y+"px"});
const ln=(a,b,c,d,s,x="")=>`<line x1="${a}" y1="${b}" x2="${c}" y2="${d}" stroke="${s}" ${x}/>`;
const tx=(x,y,s,c="ax",e="")=>`<text x="${x}" y="${y}" class="${c}" ${e}>${s}</text>`;

/* ---------- run panel ---------- */
function lastMissed(){const s=SESS.filter(s=>s.runs.some(ok)).slice(-1)[0];const ids=new Set();s&&s.runs.filter(ok).forEach(r=>Object.entries(r.res).forEach(([k,o])=>{if(o.dn+o.up)ids.add(k)}));return[...ids]}
function panel(){const lm=lastMissed(),nf=S.fs==="all"?FIX.length:lm.length,k=nf/10*S.rpf/8,cost=sum(S.cm.map(m=>M[m].cost*k)),dur=Math.max(0,...S.cm.map(m=>M[m].dur*60*k));
 return`<div class="card rp"><div class="ch"><h3>Run a check</h3><span class="c">results land in Runs and update every chart</span><span class="r"><button class="lnk" data-a="chk" style="color:var(--ink-2)">Close</button></span></div>
<div class="rpg"><label>Models</label><div class="chips">${MK.map(m=>`<button class="chip${S.cm.includes(m)?" on":""}" data-a="cmt" data-k="${m}">${sw(m)}${M[m].l}${PROD[m]?' <span class="kd p">prod</span>':""}</button>`).join("")}<button class="lnk" data-a="cmp" style="font-size:12px">Production pair</button></div>
<label>Runs per fixture</label><div class="chips"><span class="segm">${[1,3,8].map(n=>`<button data-a="rpf" data-k="${n}" class="${S.rpf===n?"on":""}">${n}${n===3?" · quick":n===8?" · same as weekly":""}</button>`).join("")}</span></div>
<label>Fixtures</label><div class="chips"><span class="segm"><button data-a="fs" data-k="all" class="${S.fs==="all"?"on":""}">All ${FIX.length}</button><button data-a="fs" data-k="miss" class="${S.fs==="miss"?"on":""}">Missed last time · ${lm.length}</button></span></div>
<label>Version</label><div style="font-size:13px;display:flex;gap:8px;align-items:baseline;flex-wrap:wrap">${cmTag(HEAD)}<span>${COM[HEAD].m}</span><span class="mut" style="font-size:12px">current, pushed ${ago(COM[HEAD].t)}</span></div></div>
<div class="rpf"><span class="est">Estimate <b>~${gbp(cost)}</b> · <b>~${mmss(dur)}</b> · ${S.cm.length*nf*S.rpf} ratings</span><span class="sp"></span><button class="btn bs" data-a="chk">Cancel</button><button class="btn bp" data-a="go"${S.cm.length&&nf?"":" disabled"}>Run check</button></div></div>`}

/* ---------- models tab ---------- */
function laneCard(L){const m=L.m,h=onC(m,HEAD),hp=avg(h),pv=prevC(m,HEAD),pp=pv?avg(pv.rs.slice(-2)):null,w=w4(m),wp=avg(w),wd=sum(w.map(r=>r.dn)),wu=sum(w.map(r=>r.up)),cst=sum(w.map(r=>r.cost))/w.length;
 let st;if(!h.length)st=`<div class="lst wait"><span>Not checked on ${cmTag(HEAD)} yet.</span><button class="lnk" data-a="chkm" data-k="${m}">Run a check</button></div>`;
 else if(hp-pp<=-.03)st=`<div class="lst bad"><span>Dropped ${pts(hp-pp)} after ${cmTag(HEAD)} (${P(pp)} → ${P(hp)}).</span><button class="lnk" data-a="tab" data-k="fixtures" data-m="${m}">See which fixtures</button></div>`;
 else st=`<div class="lst ok"><span>Held after ${cmTag(HEAD)}: ${P(pp)} → ${P(hp)} over ${h.length} run${h.length>1?"s":""}.</span></div>`;
 const alts=MK.filter(x=>x!==m&&fresh(x)).map(x=>{const xp=avg(w4(x)),xc=sum(w4(x).map(r=>r.cost))/w4(x).length,xh=avg(onC(x,HEAD)),xv=prevC(x,HEAD),xpp=xv?avg(xv.rs.slice(-2)):null,d=xp-wp;
  return`<div class="ar"><span>${sw(x)}${M[x].l}${PROD[x]?' <span class="kd p">prod</span>':""}</span><span class="n">${P(xp)}</span><span class="n ${d>.005?"dn":d<-.005?"up":""}">${pts(d)}</span><span class="n">${(xc/cst).toFixed(xc/cst<1?2:1)}× cost</span>${xh!=null&&xpp!=null&&xh-xpp<=-.03?`<span class="fl">Fell to ${P(xh)} on ${COM[HEAD].id}</span>`:""}</div>`}).join("");
 const stale=MK.filter(x=>x!==m&&!fresh(x));
 return`<div class="card"><div class="lh"><span class="lt">${L.l}</span><span class="lm">${sw(m)}${M[m].l}</span><span class="lw">${L.w}</span></div>
<div class="kv"><div><span>Today's version</span><b>${P(hp)}</b><span>${h.length?h.length+" run"+(h.length>1?"s":"")+" · "+COM[HEAD].id:"not run yet"}</span></div><div><span>Last 4 weekly</span><b>${P(wp)}</b><span>in band</span></div><div><span>Misses, last 4</span><b>${wd}<small class="mut" style="font-size:11px"> below</small> ${wu}<small class="mut" style="font-size:11px"> above</small></b><span>of ${sum(w.map(r=>r.n))} ratings</span></div><div><span>Per eval run</span><b>${gbp(cst)}</b><span>weekly, ${w[0]?w[0].nfix:10} fixtures × 8</span></div></div>
${st}<div class="alts"><span class="ah">Compared with, last 4 weekly</span>${alts}</div>${stale.length?`<p class="note">${stale.map(x=>M[x].l).join(" and ")} last tested ${ago(lastRun(stale[0]).t)} on an older version. <button class="lnk" data-a="chkm" data-k="${stale.join(",")}" style="font-size:12px">Check them on ${COM[HEAD].id}</button></p>`:""}</div>`}
function verdict(){const ms=LANES.map(L=>{const h=onC(L.m,HEAD),pv=prevC(L.m,HEAD);return{m:L.m,h:avg(h),p:pv?avg(pv.rs.slice(-2)):null,n:h.length}});
 const bad=ms.filter(x=>x.n&&x.h-x.p<=-.03),unk=ms.filter(x=>!x.n);
 const others=MK.filter(m=>!PROD[m]).map(m=>{const h=onC(m,HEAD),pv=prevC(m,HEAD);return{m,h:avg(h),p:pv?avg(pv.rs.slice(-2)):null,n:h.length}}).filter(x=>x.n&&x.h-x.p<=-.03);
 const h2=bad.length?`${COM[HEAD].id} made ${bad.map(x=>M[x.m].l).join(" and ")} worse.`:unk.length?`${COM[HEAD].id} hasn't been checked on ${unk.map(x=>M[x.m].l).join(" and ")} yet.`:`Both production models held up on today's change.`;
 let p=`<b>${COM[HEAD].m}</b> (${COM[HEAD].id}, ${ago(COM[HEAD].t)}). `+ms.filter(x=>x.n).map(x=>`${M[x.m].l} ${P(x.p)} → <b>${P(x.h)}</b>`).join(", ")+".";
 if(others.length)p+=` ${others.map(x=>`${M[x.m].l} dropped from ${P(x.p)} to <b>${P(x.h)}</b>`).join("; ")}, but it isn't used in production.`;
 return`<div class="card verdict"><h2>${h2}</h2><p>${p}</p></div>`}
function change(){const c=S.cc,cands=[];for(let i=HEAD;i>=1&&cands.length<5;i--)if(RUNS.some(r=>r.c===i&&ok(r))&&MK.some(m=>onC(m,i).length&&prevC(m,i)))cands.push(i);
 const rows=MK.map(m=>{const a=onC(m,c),pv=prevC(m,c);if(!a.length||!pv)return{m,nr:1};const b=pv.rs.slice(-2),ap=avg(a),bp=avg(b);
  const fx=FIX.map(f=>{const A=a.filter(r=>r.res[f.id]),B=b.filter(r=>r.res[f.id]);if(!A.length||!B.length)return null;const ag=k=>rs=>sum(rs.map(r=>r.res[f.id][k]))/sum(rs.map(r=>r.res[f.id].n));const ai=ag("inb")(A),bi=ag("inb")(B);return{f,ai,bi,d:ai-bi,dn:ag("dn")(A),up:ag("up")(A)}}).filter(x=>x&&Math.abs(x.d)>=.2).sort((x,y)=>x.d-y.d);
  return{m,ap,bp,d:ap-bp,fx,pc:pv.c,na:a.length}}).sort((x,y)=>(x.nr||0)-(y.nr||0)||(PROD[y.m]?1:0)-(PROD[x.m]?1:0));
 return`<div class="card"><div class="ch"><h3>Did a change make ratings worse?</h3><span class="c">runs on a version vs the last runs before it</span><span class="r chips">${cands.map(i=>`<button class="chip${c===i?" on":""}" data-a="cc" data-k="${i}">${COM[i].id} <span style="opacity:.6">${ago(COM[i].t)}</span></button>`).join("")}</span></div>
<div style="font-size:13px;color:var(--ink-2)">${cmTag(c)} ${COM[c].m} <span class="mut">· ${dl(COM[c].t)}</span></div>
<div class="ct"><div class="cr th"><span>Model</span><span class="n x">Before</span><span class="x"></span><span class="n">After</span><span class="n">Change</span><span>Fixtures that moved</span></div>
${rows.map(r=>r.nr?`<div class="cr nr"><span class="ty">${sw(r.m)}${M[r.m].l}</span><span class="x"></span><span class="x"></span><span class="n">—</span><span></span><span style="font-size:12px">Not run on this version</span></div>`:`<div class="cr"><span class="ty">${sw(r.m)}${M[r.m].l}${PROD[r.m]?' <span class="kd p">prod</span>':""}</span><span class="n x">${P(r.bp)}</span><span class="x mut">→</span><span class="n">${P(r.ap)}</span><span class="n ${r.d<=-.03?"bad":r.d>=.03?"dn":""}">${pts(r.d)}</span><span class="fx">${r.fx.length?r.fx.map(x=>`<span><b style="font-weight:500;color:${x.d<0?"var(--coral)":"var(--go)"}">${x.d<0?"worse":"better"}</b>${x.f.id} <span class="mut">${Math.round(x.bi*100)}% → ${Math.round(x.ai*100)}% in band${x.d<0?(x.dn>x.up?" · rated too low":" · rated too high"):""}</span></span>`).join(""):'<span class="mut">No fixture moved by 20 pts or more</span>'}</span></div>`).join("")}</div></div>`}
function board(){const rows=MK.map(m=>{const w=w4(m),l=lastRun(m);return{m,w,wp:avg(w),hp:avg(onC(m,HEAD)),dn:sum(w.map(r=>r.dn)),up:sum(w.map(r=>r.up)),cost:sum(w.map(r=>r.cost))/w.length,l,fr:fresh(m)}}).sort((a,b)=>(b.fr-a.fr)||(b.wp-a.wp));
 const bar=v=>v==null?'<span class="mut">—</span>':`<span class="pb">${P(v)}<i><b style="width:${Math.max(0,(v-.7)/.3*100)}%;background:${v>=.97?"var(--go)":v>=.92?"var(--marginal)":"var(--coral)"}"></b></i></span>`;
 return`<div class="card"><div class="ch"><h3>All models</h3><span class="c">click a model to highlight it in the chart</span></div><div class="lb"><div class="lr th"><span>Model</span><span class="x">Used for</span><span>Last 4 weekly</span><span class="n">On ${COM[HEAD].id}</span><span class="n x">Misses ↓/↑</span><span class="n">£/run</span><span class="n x">Last tested</span></div>
${rows.map(r=>`<div class="lr${r.fr?"":" st"}" data-a="hl" data-k="${r.m}" style="cursor:pointer${S.hl===r.m?";background:rgba(242,231,211,.05)":""}"><span class="ty">${sw(r.m)}${M[r.m].l}</span><span class="x">${PROD[r.m]?`<span class="kd p">${PROD[r.m].l.split(" ").slice(0,3).join(" ").replace(" forecasts","")}</span>`:'<span class="mut" style="font-size:12px">eval only</span>'}</span>${bar(r.wp)}<span class="n">${P(r.hp)}</span><span class="n x">${r.dn} / ${r.up}</span><span class="n">${gbp(r.cost)}</span><span class="n x">${ago(r.l.t)}${r.fr?"":'<br><small style="font-size:10.5px">older version</small>'}</span></div>`).join("")}</div></div>`}
function vModels(){return`${verdict()}<div class="lanes">${LANES.map(laneCard).join("")}</div>
<div class="card"><div class="ch"><h3>Accuracy over time</h3><span class="c">% of fixture ratings in band · production models in colour · gold lines are versions</span></div><div class="chips">${MK.map(m=>`<button class="chip${S.hl===m?" on":""}" data-a="hl" data-k="${m}">${sw(m)}${M[m].l}</button>`).join("")}</div><div class="chart" id="acc"></div></div>
${change()}${board()}`}
function drawAcc(el){const W=el.clientWidth,H=250,L=40,Rp=12,T=30,B=22,iw=W-L-Rp,ih=H-T-B,t0=NOW-124*DAY,x=t=>L+(t-t0)/(NOW-t0)*iw,lo=.7,y=v=>T+ih-(Math.max(lo,v)-lo)/(1-lo)*ih;let s="";
 [.7,.8,.9,1].forEach(v=>s+=ln(L,y(v),L+iw,y(v),"rgba(242,231,211,.07)")+tx(L-6,y(v)+3,Math.round(v*100)+"%","axs",'text-anchor="end"'));
 COM.forEach((c,i)=>{if(c.t<t0)return;const X=x(c.t);s+=ln(X,T-14,X,T+ih,"rgba(235,217,168,.35)",'stroke-dasharray="2 3"')+`<text x="${X}" y="${T-18-(i%2)*11}" class="axs" text-anchor="middle" style="fill:var(--gold)"${tip(`<div class="th">${c.id}</div>${c.m}<br>${dl(c.t)}`)}>${c.id.slice(0,4)}</text>`});
 const emph=m=>S.hl?S.hl===m:!!PROD[m];
 [...MK].sort((a,b)=>emph(a)-emph(b)).forEach(m=>{const pts=SESS.map(se=>{const rs=se.runs.filter(r=>r.m===m&&ok(r));return rs.length?{t:se.t,v:avg(rs),n:rs.length,tr:se.trig,c:se.c}:null}).filter(Boolean),e=emph(m),col=e?M[m].c:"rgba(242,231,211,.3)";
  if(!pts.length)return;s+=`<polyline fill="none" stroke="${col}" stroke-width="${e?2:1}" opacity="${e?1:.6}" points="${pts.map(p=>x(p.t)+","+y(p.v)).join(" ")}"/>`;
  pts.forEach(p=>s+=`<circle cx="${x(p.t)}" cy="${y(p.v)}" r="${e?3.5:2.5}" fill="${p.tr==="manual"?"var(--surface)":col}" stroke="${col}" stroke-width="1.5"${tip(`<div class="th">${M[m].l} · ${P(p.v)}</div>${dl(p.t)} · ${p.tr}${p.n>1?" · "+p.n+" runs":""}<br>version ${COM[p.c].id}`)}/>`)});
 for(let i=0;i<=5;i++){const t=t0+(NOW-t0)*i/5;s+=tx(x(t),H-5,ds2(t),"axs",`text-anchor="${i===0?"start":i===5?"end":"middle"}"`)}
 s+=tx(L+iw,T+ih+-6,"hollow = manual","axs",'text-anchor="end"');
 el.innerHTML=`<svg width="${W}" height="${H}">${s}</svg>`}

/* ---------- fixtures tab ---------- */
function vFix(){const m=S.fm,cols=mRuns(m).slice(-13),l4=mRuns(m).slice(-4);
 const rows=FIX.map(f=>{const r4=l4.filter(r=>r.res[f.id]),miss=sum(r4.map(r=>r.res[f.id].dn+r.res[f.id].up)),n=sum(r4.map(r=>r.res[f.id].n));return{f,miss,p:n?1-miss/n:null}}).sort((a,b)=>b.miss-a.miss);
 const cell=(r,f,i)=>{const cb=i>0&&cols[i-1].c!==r.c?" cbl":"",o=r.res[f.id];if(!o)return`<td class="c na${cb}">·</td>`;const ms=o.dn+o.up;if(!ms)return`<td class="c g${cb}"${tip(`${f.id}<br>${dl(r.t)} · ${COM[r.c].id}<br>${o.n}/${o.n} in band`)}></td>`;
  const dir=o.dn>=o.up,a=.18+.6*ms/o.n,bg=dir?`rgba(111,168,176,${a})`:`rgba(232,89,63,${a})`;return`<td class="c${cb}" style="background:${bg}"${tip(`${f.id}<br>${dl(r.t)} · ${COM[r.c].id}<br>${o.inb}/${o.n} in band${o.dn?` · ${o.dn} below`:""}${o.up?` · ${o.up} above`:""}<br>mean rating ${o.mean.toFixed(1)} · band ${band(f)}`)}>${o.dn?"↓"+o.dn:""}${o.up?"↑"+o.up:""}</td>`};
 const head=`<tr><th></th><th></th>${cols.map((r,i)=>`<th class="d${i>0&&cols[i-1].c!==r.c?" cb":""}">${i>0&&cols[i-1].c!==r.c?`<em>${COM[r.c].id.slice(0,4)}</em>`:""}<span>${ds2(r.t)}${r.trig==="manual"?" ·m":""}</span></th>`).join("")}<th style="text-align:right;padding-left:10px">Last 4</th></tr>`;
 const body=rows.map(({f,p})=>`<tr class="fr${S.fo===f.id?" on":""}" data-a="fo" data-k="${f.id}"><td class="fn">${f.id}</td><td class="bd">band ${band(f)}</td>${cols.map((r,i)=>cell(r,f,i)).join("")}<td class="sm ${p!=null&&p<.9?"bad":""}">${P(p)}</td></tr>${S.fo===f.id?`<tr class="dr"><td colspan="${cols.length+3}"><div class="chart" id="drift"></div></td></tr>`:""}`).join("");
 return`<div class="card"><div class="ch"><h3>Fixture by fixture</h3><span class="c">last ${cols.length} runs of ${M[m].l} · worst fixtures first · click a fixture for its rating history</span></div>
<div class="chips"><span class="segm">${MK.map(k=>`<button data-a="fm" data-k="${k}" class="${S.fm===k?"on":""}">${M[k].l}</button>`).join("")}</span></div>
<div class="lg"><span><i style="background:rgba(138,174,114,.2)"></i>all ratings in band</span><span><i style="background:rgba(111,168,176,.6)"></i>↓ rated below band</span><span><i style="background:rgba(232,89,63,.6)"></i>↑ rated above band</span><span>numbers = misses out of 8 runs</span><span><i style="background:none;box-shadow:inset 2px 0 0 var(--gold)"></i>new version</span></div>
<div class="mxw"><table class="mx">${head}${body}</table></div></div>`}
function drawDrift(el){const f=FIX.find(x=>x.id===S.fo),m=S.fm,rs=mRuns(m).filter(r=>r.res[f.id]);const W=el.clientWidth,H=170,L=34,Rp=10,T=18,B=22,iw=W-L-Rp,ih=H-T-B,t0=Math.min(rs[0]?rs[0].t:NOW,NOW-30*DAY)-2*DAY,x=t=>L+(t-t0)/(NOW-t0)*iw,y=v=>T+ih-(v-.3)/5.4*ih;
 let s=`<rect x="${L}" y="${y(f.hi+.5)}" width="${iw}" height="${y(f.lo-.5)-y(f.hi+.5)}" fill="rgba(138,174,114,.12)"/>`+tx(L+4,y(f.hi+.5)+11,"band "+band(f),"axs",'style="fill:var(--go)"');
 [1,2,3,4,5].forEach(v=>s+=tx(L-6,y(v)+3,v,"axs",'text-anchor="end"')+ln(L,y(v),L+iw,y(v),"rgba(242,231,211,.05)"));
 COM.forEach(c=>{if(c.t<t0)return;const X=x(c.t);s+=ln(X,T-6,X,T+ih,"rgba(235,217,168,.3)",'stroke-dasharray="2 3"')+tx(X,T-8,c.id.slice(0,4),"axs",'text-anchor="middle" style="fill:var(--gold)"')});
 rs.forEach(r=>{const o=r.res[f.id],X=x(r.t),col=o.dn+o.up?(o.dn>=o.up?"var(--tide)":"var(--coral)"):M[m].c;s+=ln(X,y(o.min),X,y(o.max),col,'stroke-width="1.5" opacity=".5"')+`<circle cx="${X}" cy="${y(o.mean)}" r="3.5" fill="${col}"${tip(`<div class="th">${dl(r.t)} · ${COM[r.c].id}</div>mean ${o.mean.toFixed(2)} · range ${o.min.toFixed(1)}–${o.max.toFixed(1)}<br>${o.inb}/${o.n} in band`)}/>`});
 for(let i=0;i<=4;i++){const t=t0+(NOW-t0)*i/4;s+=tx(x(t),H-5,ds2(t),"axs",`text-anchor="${i===0?"start":i===4?"end":"middle"}"`)}
 el.innerHTML=`<svg width="${W}" height="${H}">${s}</svg><p class="note" style="margin-top:4px">Dot = mean rating across the run's repeats, line = lowest to highest. ${M[m].l} on ${f.id}.</p>`}

/* ---------- runs tab ---------- */
function vRuns(){const ss=SESS.slice().reverse(),shown=S.more?ss:ss.slice(0,10);
 const sRow=s=>{const rs=s.runs,run=rs.some(r=>r.st==="run"),fail=rs.every(r=>r.st==="fail"),cost=sum(rs.map(r=>r.cost||0)),by={};rs.forEach(r=>(by[r.m]=by[r.m]||[]).push(r));
  const chips=Object.entries(by).map(([m,a])=>{const v=avg(a.filter(ok));return`<span>${sw(m)}${M[m].l}${a.length>1?` <em>×${a.length}</em>`:""} <b style="font-weight:500;color:${v==null?"var(--ink-3)":v>=.97?"var(--ink)":v>=.92?"var(--marginal)":"var(--coral)"}">${v==null?"—":P(v)}</b></span>`}).join("");
  const op=S.open===s.id;
  return`<div class="sr${op?" on":""}" data-a="open" data-k="${s.id}"><span class="w"><b>${ago(s.t)}</b><span>${dl(s.t)} ${hm(s.t)}</span></span><span><span class="kd">${s.trig}</span></span><span class="cmm x">${cmTag(s.c)}<span>${COM[s.c].m}</span></span><span class="mc">${chips}</span><span class="n x">${run?"~":""}${gbp(cost)}</span><span>${run?'<span class="st run">Running</span>':fail?'<span class="st fail">Failed</span>':'<span class="st">Done</span>'}</span>
${op?`<div class="sx"><div class="sxr"><span>Run</span><span class="n">In band</span><span class="n x">Below / above</span><span class="n">Cost</span><span class="n x">Time</span><span class="x"></span></div>${rs.map(r=>`<div class="sxr"><span class="ty">${sw(r.m)}${M[r.m].l} <span class="mut mn" style="font-size:11px">#${r.id} · ${r.nfix}×${r.rpf}</span></span>${r.st==="run"?`<span class="n" style="grid-column:span 2"><span class="prog"><i style="width:${Math.round(r.prog*100)}%"></i></span></span><span class="n">~${gbp(r.cost)}</span><span class="n x"></span><span class="x"></span>`:r.st==="fail"?`<span class="n bad">—</span><span class="n x bad" style="font-size:11.5px">${r.note}</span><span class="n">—</span><span class="n x"></span><span class="x"></span>`:`<span class="n">${P(r.pass)}</span><span class="n x">${r.dn} / ${r.up}</span><span class="n">${gbp(r.cost)}</span><span class="n x">${mmss(r.dur)}</span><span class="x" style="text-align:right"><button class="lnk" data-a="tab" data-k="fixtures" data-m="${r.m}" style="font-size:12px">Fixtures →</button></span>`}</div>`).join("")}</div>`:""}</div>`};
 return`<div class="card"><div class="ch"><h3>Eval sessions</h3><span class="c">runs started together are grouped · ${RUNS.length} runs in ${SESS.length} sessions</span></div><div class="ss"><div class="sr th"><span>When</span><span>Trigger</span><span class="x">Version</span><span>Models · in band</span><span class="n x">Cost</span><span>Status</span></div>${shown.map(sRow).join("")}</div>${!S.more&&ss.length>10?`<button class="btn bs more" data-a="more">Show ${ss.length-10} older sessions</button>`:""}</div>`}

/* ---------- shell ---------- */
const app=document.getElementById("app");
function render(){TIPS=[];const nW=SESS.filter(s=>s.trig==="weekly").slice(-1)[0];
 app.innerHTML=`<div class="hd"><span class="t">Sky Eval</span><span class="r"><button class="btn bp" data-a="chk">Run a check</button></span></div>
<div class="sub"><span>Sky-rating calibration</span>·<span>${FIX.length} fixtures</span>·<span>weekly, Sun 04:00, on <b>Sonnet 5.5, Haiku 4.5, Haiku 5.5</b></span><a href="#" style="font-size:12.5px">Edit in Scheduler</a></div>
${S.chk?panel():""}
<div class="vt">${[["models","Models",""],["fixtures","Fixtures",FIX.length+""],["runs","Runs",SESS.length+" sessions"]].map(([k,l,s])=>`<button data-a="tab" data-k="${k}" class="${S.tab===k?"on":""}">${l}${s?` <small>${s}</small>`:""}</button>`).join("")}</div>
${S.tab==="models"?vModels():S.tab==="fixtures"?vFix():vRuns()}`;draw();save()}
function draw(){const a=document.getElementById("acc");a&&drawAcc(a);const d=document.getElementById("drift");d&&drawDrift(d)}
let raf;new ResizeObserver(()=>{cancelAnimationFrame(raf);raf=requestAnimationFrame(draw)}).observe(app);
function startCheck(){const only=S.fs==="miss"?lastMissed():null,t=NOW,sess={id:"s"+RID,trig:"manual",t,last:t,c:HEAD,runs:[]};
 const pend=S.cm.map(m=>{const r={id:RID++,m,t,trig:"manual",c:HEAD,st:"run",prog:0,rpf:S.rpf,nfix:only?only.length:FIX.length,cost:M[m].cost*(only?only.length:10)/10*S.rpf/8,dur:0};RUNS.push(r);sess.runs.push(r);return r});
 SESS.push(sess);S.chk=false;S.tab="runs";S.open=sess.id;render();
 const iv=setInterval(()=>{let all=true;pend.forEach((r,i)=>{if(r.st!=="run")return;r.prog=Math.min(1,r.prog+.12/(1+i*.5));if(r.prog>=1){const g=gen(r.m,NOW,"manual",S.rpf,only);RID--;g.id=r.id;Object.assign(r,g)}else all=false});render();if(all)clearInterval(iv)},450)}
document.addEventListener("click",e=>{const b=e.target.closest("[data-a]");if(!b)return;const a=b.dataset.a,k=b.dataset.k;
 if(a==="tab"){S.tab=k;if(b.dataset.m)S.fm=b.dataset.m;e.stopPropagation()}
 else if(a==="chk"){S.chk=!S.chk;if(S.chk)scrollTo(0,0)}
 else if(a==="chkm"){S.cm=k.split(",");S.rpf=3;S.fs="all";S.chk=true;scrollTo(0,0)}
 else if(a==="cmt")S.cm=S.cm.includes(k)?S.cm.filter(x=>x!==k):[...S.cm,k];
 else if(a==="cmp")S.cm=LANES.map(l=>l.m);
 else if(a==="rpf")S.rpf=+k;else if(a==="fs")S.fs=k;
 else if(a==="go")return startCheck();
 else if(a==="hl")S.hl=S.hl===k?null:k;
 else if(a==="cc")S.cc=+k;
 else if(a==="fm"){S.fm=k}
 else if(a==="fo")S.fo=S.fo===k?null:k;
 else if(a==="open"){if(e.target.closest(".sx"))return;S.open=S.open===k?null:k}
 else if(a==="more")S.more=true;
 else return;render()});
render();
})();

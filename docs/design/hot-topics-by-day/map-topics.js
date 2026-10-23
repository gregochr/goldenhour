/* Hot topics on the map: which topics are live on the current window, and where.
   No region data: "where" is derived from the topic's own `needs` (coast, lake) against the
   location set, plus the named structures for Big seas. Sky-wide topics say so. */
(function(){
const T2={t:null,open:true};
const SEAS={ic:'⚡',n:'Big seas',sh:'Big seas',c:'#8FC0C7'};
const days=[...new Set(EV.filter(e=>e.k==='solar').map(e=>e.day))];
const colOf=e=>{const d=days.indexOf(e.day);if(d<0)return -1;if(e.k==='hw')return d*2+(e.time<'12:00'?0:1);return d*2+(e.k==='solar'&&e.am?0:1)};
const hm=s=>{const[a,b]=s.split(':').map(Number);return a*60+b};
const hwEvs=()=>EV.map((e,i)=>({e,i})).filter(x=>x.e.k==='hw');
const evAt=c=>EV.findIndex(e=>e.k==='solar'&&colOf(e)===c);
const spotsFor=(k,e)=>{
 if(k==='seas'){const x=seaNow(e);if(!x)return[];const ns=new Set(x.at.map(a=>a.n));return SPOTS.filter(s=>ns.has(s.n))}
 const t=TOPICS[k];if(!t||!t.needs)return null;return basePool().filter(s=>s[t.needs])};
function topicsOn(e){
 const out=[];if(!e)return out;
 if(e.k==='hw'){const sx=seaNow(e);return sx?[{k:'seas',...SEAS,d:'High water '+sx.hw+', waves '+sx.hs+' m from '+sx.dir}]:out}
 const se=e.k==='solar'?e:EV.slice(0,EV.indexOf(e)).reverse().find(x=>x.k==='solar');
 if(se)(WTOPICS[WINS[se.wi].id]||[]).forEach(x=>out.push({k:x.t,...TOPICS[x.t],d:x.d}));
 if(e.k==='aur'&&!out.some(x=>x.k==='aur'))out.push({k:'aur',...TOPICS.aur});
 const sx=seaNow(e);if(sx)out.unshift({k:'seas',...SEAS,d:'Waves onto piers and lighthouses at high water '+sx.hw+(e.name===sx.align?', on the light':'')});
 return out;
}
const onCol=(k,c)=>{const i=evAt(c);if(i<0)return false;return topicsOn(EV[i]).some(x=>x.k===k)};

/* chips under the window control */
const gw=$('gwin'),chips=document.createElement('div');chips.id='tchips';gw.appendChild(chips);
function paintChips(){
 const e=ev(),ts=topicsOn(e);
 if(T2.t&&!ts.some(x=>x.k===T2.t))T2.t=null;
 if(!ts.length){chips.innerHTML='<span class="tcn">No hot topics on this window</span>';return}
 chips.innerHTML=ts.map(t=>{const sp=spotsFor(t.k,e);
  const where=sp===null?'sky-wide':sp.length+' '+(t.k==='seas'?'structures':t.needs==='coast'?'coastal':'lake & valley');
  const hwT=t.k==='seas'&&seaNow(e)&&seaNow(e).align==='High water'?' · HW '+seaNow(e).hw:'';
  return `<button class="tch${T2.t===t.k?' on':''}" data-tk="${t.k}" style="--c:${t.c}" title="${esc(t.d||t.n)}"><span>${t.ic}</span><b>${esc(t.sh)}${hwT}</b><em>${where}</em></button>`}).join('');
}
chips.addEventListener('click',e=>{const b=e.target.closest('[data-tk]');if(!b)return;e.stopPropagation();
 T2.t=T2.t===b.dataset.tk?null:b.dataset.tk;paintAll()});

/* rings on the qualifying locations */
const mw=$('mapwrap'),rl=document.createElement('div');rl.id='trings';mw.insertBefore(rl,$('labs'));
const sky=document.createElement('div');sky.id='tsky';mw.appendChild(sky);
function paintRings(){
 const e=ev(),k=T2.t;rl.innerHTML='';sky.className='';mw.classList.toggle('tsel',!!k);
 if(!k)return;
 const t=k==='seas'?SEAS:TOPICS[k],sp=spotsFor(k,e);
 if(sp===null){sky.style.setProperty('--c',t.c);sky.innerHTML=`${t.ic} <b>${esc(t.n)}</b> · sky-wide — every location in range sees it or none does`;sky.className='on';return}
 const sz=map.getSize();
 rl.innerHTML=sp.map(s=>{const p=map.latLngToContainerPoint([s.lat,s.lng]);
  if(p.x<-20||p.y<-20||p.x>sz.x+20||p.y>sz.y+20)return'';
  return `<i style="left:${p.x}px;top:${p.y}px;--c:${t.c}"></i>`}).join('');
}

/* the four days, docked */
const lp=document.createElement('div');lp.id='tlanes';mw.appendChild(lp);
function rowsAll(){const seen=new Map();
 EV.forEach(e=>topicsOn(e).forEach(t=>{if(!seen.has(t.k))seen.set(t.k,t)}));
 return [...seen.values()]}
function paintLanes(){
 const cur=colOf(ev()),rows=rowsAll();
 let h=`<button class="tlh" data-tog="1"><span>Hot topics · next four days</span><span class="cv">${T2.open?'▾':'▴'}</span></button>`;
 if(T2.open){
  h+=`<div class="tlg" style="grid-template-rows:auto auto repeat(${rows.length},24px)">`;
  if(cur>=0){const hwN=ev().k==='hw',c0=hwN?cur-(cur%2):cur;h+=`<div class="tlnow" style="grid-column:${c0+2}/${c0+(hwN?4:3)};grid-row:1/${rows.length+3}"></div>`}
  h+=`<div style="grid-area:1/1"></div>`+days.map((d,i)=>`<div class="tld" style="grid-area:1/${i*2+2}/2/${i*2+4}"><i>${d.slice(0,3)}</i><b>${d.slice(4)}</b></div>`).join('');
  for(let c=0;c<8;c++)h+=`<div class="tlx" style="grid-area:2/${c+2}">${c%2?'↓':'↑'}</div>`;
  rows.forEach((t,r)=>{const gr=r+3;
   h+=`<button class="tln${T2.t===t.k?' on':''}" data-row="${t.k}" style="grid-area:${gr}/1;--c:${t.c}"><span>${t.ic}</span>${esc(t.sh)}</button>`;
   let c=0;while(c<8){if(!onCol(t.k,c)){c++;continue}let e2=c;while(e2+1<8&&onCol(t.k,e2+1))e2++;
    const on=cur>=c&&cur<=e2;
    h+=`<button class="tlb${on?' cur':''}${T2.t===t.k&&on?' sel':''}" data-k="${t.k}" data-c="${on?cur:c}" style="grid-area:${gr}/${c+2}/${gr+1}/${e2+3};--c:${t.c}" title="${esc(t.n)}"></button>`;c=e2+1}
  });
  const sr=rows.findIndex(t=>t.k==='seas');
  if(sr>=0)hwEvs().forEach(({e,i})=>{const d=days.indexOf(e.day);if(d<0)return;
   const day=EV.filter(x=>x.k==='solar'&&x.day===e.day),a=day.find(x=>x.am),b=day.find(x=>!x.am);
   const s0=hm(a?a.time:'07:30'),s1=hm(b?b.time:'17:45'),x=Math.max(4,Math.min(96,(hm(e.time)-s0)/(s1-s0)*100));
   h+=`<div class="tlhw" style="grid-area:${sr+3}/${d*2+2}/${sr+4}/${d*2+4};--x:${x}%"><span>HW ${e.time}</span><button class="${i===S.ei?'cur':''}" data-hw="${i}" title="High water ${e.time} · waves over the piers"></button></div>`});
  h+='</div>';
 }
 lp.innerHTML=h;
 mw.style.setProperty('--tlh',lp.offsetHeight+'px');
}
lp.addEventListener('click',e=>{e.stopPropagation();
 if(e.target.closest('[data-tog]')){T2.open=!T2.open;paintLanes();return}
 const w=e.target.closest('[data-hw]');if(w){T2.t='seas';setEv(+w.dataset.hw);return}
 const b=e.target.closest('[data-c]');if(b){const i=evAt(+b.dataset.c);T2.t=b.dataset.k;if(i>=0&&i!==S.ei)setEv(i);else paintAll();return}
 const r=e.target.closest('[data-row]');if(r){const k=r.dataset.row;
  if(topicsOn(ev()).some(x=>x.k===k)){T2.t=T2.t===k?null:k;paintAll();return}
  for(let c=0;c<8;c++)if(onCol(k,c)){T2.t=k;setEv(evAt(c));return}}
});

function paintAll(){paintChips();paintRings();paintLanes()}
const _set=setEv;setEv=function(i){_set(i);paintAll()};
map.on('move zoom viewreset resize moveend zoomend',paintRings);
document.addEventListener('keydown',e=>{if(e.key==='ArrowLeft'||e.key==='ArrowRight')setTimeout(paintAll,0)});
new ResizeObserver(()=>mw.style.setProperty('--tlh',lp.offsetHeight+'px')).observe(lp);
paintAll();
})();

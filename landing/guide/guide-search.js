/* Search across the guides. No build step: on first focus it fetches each guide page,
   indexes every section (h2) and every defined term (dt), then filters as you type. */
(function(){
  var PAGES=['getting-started.html','reading-the-forecast.html','at-a-glance.html','coming-up.html','map.html','night-sky.html','ask-photocast.html','settings.html'];
  var input=document.getElementById('g-q'),out=document.getElementById('g-results'),status=document.getElementById('g-status');
  if(!input)return;
  var idx=null,loading=null;
  var embed=document.documentElement.classList.contains('embed')||new URLSearchParams(location.search).has('embed');
  function clean(el){var c=el.cloneNode(true);c.querySelectorAll('.pro').forEach(function(p){p.remove()});return c.textContent.replace(/\s+/g,' ').trim();}
  function load(){
    if(loading)return loading;
    loading=Promise.all(PAGES.map(function(p){
      return fetch(p).then(function(r){return r.text()}).then(function(t){
        var d=new DOMParser().parseFromString(t,'text/html');
        var title=clean(d.querySelector('h1')).replace(/\.$/,'');
        var rows=[];
        d.querySelectorAll('section.g-sec').forEach(function(s){
          var h=s.querySelector('h2');if(!h)return;
          var head=clean(h),pro=!!h.querySelector('.pro');
          rows.push({page:p,title:title,id:s.id,head:head,pro:pro,text:clean(s).slice(head.length)});
          s.querySelectorAll('dl.terms>div').forEach(function(div){
            var dt=div.querySelector('dt'),dd=div.querySelector('dd');if(!dt||!dd)return;
            rows.push({page:p,title:title,id:s.id,head:clean(dt),sub:head,pro:!!dt.querySelector('.pro')||pro,text:clean(dd)});
          });
        });
        return rows;
      }).catch(function(){return []});
    })).then(function(a){idx=[].concat.apply([],a);return idx;});
    return loading;
  }
  function esc(s){return s.replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
  function mark(s,words){var h=esc(s);words.forEach(function(w){h=h.replace(new RegExp('('+w.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+')','ig'),'<mark>$1</mark>')});return h;}
  function snippet(text,words){
    var l=text.toLowerCase(),i=-1;
    words.forEach(function(w){var j=l.indexOf(w);if(j>=0&&(i<0||j<i))i=j;});
    if(i<0)return text.slice(0,140)+(text.length>140?'…':'');
    var a=Math.max(0,i-50),b=Math.min(text.length,a+150);
    return (a>0?'…':'')+text.slice(a,b)+(b<text.length?'…':'');
  }
  function search(){
    var q=input.value.trim().toLowerCase();
    if(q.length<2){out.innerHTML='';status.textContent='';return;}
    if(!idx){status.textContent='Loading…';load().then(search);return;}
    var words=q.split(/\s+/).filter(Boolean);
    var hits=idx.map(function(r){
      var h=r.head.toLowerCase(),t=(r.sub||'').toLowerCase()+' '+r.text.toLowerCase(),score=0;
      for(var k=0;k<words.length;k++){var w=words[k];if(h.indexOf(w)>=0)score+=h.indexOf(w)===0?6:4;else if(t.indexOf(w)>=0)score+=1;else return null;}
      if(r.sub)score+=1;
      return {r:r,score:score};
    }).filter(Boolean).sort(function(a,b){return b.score-a.score}).slice(0,10);
    status.textContent=hits.length?(hits.length+(hits.length===10?'+':'')+' result'+(hits.length===1?'':'s')):'Nothing found. Try another word, such as tide, drive or aurora.';
    out.innerHTML=hits.map(function(x){
      var r=x.r,href=r.page+(embed?'?embed=1':'')+'#'+r.id;
      return '<li><a href="'+href+'"><span class="gs-where">'+esc(r.title)+(r.sub?' · '+esc(r.sub):'')+'</span><span class="gs-head">'+mark(r.head,words)+(r.pro?' <span class="pro">Pro</span>':'')+'</span><span class="gs-snip">'+mark(snippet(r.text,words),words)+'</span></a></li>';
    }).join('');
  }
  input.addEventListener('focus',load,{once:true});
  input.addEventListener('input',search);
  input.addEventListener('keydown',function(e){if(e.key==='Enter'){var a=out.querySelector('a');if(a){e.preventDefault();location.href=a.getAttribute('href');}}});
})();

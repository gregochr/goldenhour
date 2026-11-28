/* Redirects for guide ids and pages that moved. Loaded by the two retired stubs (at-a-glance.html, coming-up.html)
   and by planning.html, which was split into planning / this-week / further-ahead. The stubs also carry a meta refresh
   (3 s, so the script always decides first), so they work without script too; only this script keeps the old #section
   (mapped to the page that now holds it) and the ?embed=1 the in-app guide panel adds.
   MOVED is the one map of "id that left planning.html" to "page that holds it now". Plain ES5; the same file is
   required by frontend/src/test/guideMoved.test.js (module.exports), so it must stay free of build tooling. */
(function(){
  var MOVED={
    'this-week':'this-week','your-places':'this-week','days':'this-week','windows':'this-week','open':'this-week','bestbet':'this-week',
    'further-ahead':'further-ahead','topics':'further-ahead','new':'further-ahead','filters':'further-ahead','entries':'further-ahead',
    'tides':'further-ahead','actions':'further-ahead','watch-a-date':'further-ahead',
    'shot-warn-lanes':'this-week','shot-warn-coming-up':'further-ahead'
  };
  var FROM_COMING_UP={'':'further-ahead',list:'further-ahead',watchlist:'your-places'};
  function has(o,k){return Object.prototype.hasOwnProperty.call(o,k);}
  /* Where a request for page+search+hash should go instead, or null to stay put. page is a file name ("planning.html"). */
  function resolveMoved(page,search,hash){
    var id=String(hash||'').replace(/^#/,'');
    var stub=page==='coming-up.html'||page==='at-a-glance.html';
    if(page==='coming-up.html'&&has(FROM_COMING_UP,id))id=FROM_COMING_UP[id];
    if(page!=='planning.html'&&!stub)return null;
    var target=has(MOVED,id)?MOVED[id]:'planning';
    if(page==='planning.html'&&target==='planning')return null;
    return target+'.html'+(search||'')+(id?'#'+id:'');
  }
  if(typeof module!=='undefined'&&module.exports){module.exports={MOVED:MOVED,resolveMoved:resolveMoved};return;}
  function go(){
    var to=resolveMoved(location.pathname.split('/').pop(),location.search,location.hash);
    if(to)location.replace(to);
  }
  go();
  window.addEventListener('hashchange',go);
})();

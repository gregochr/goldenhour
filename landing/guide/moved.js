/* Redirect for the two guide pages that became "Planning a shoot" (at-a-glance.html and coming-up.html).
   The stubs also carry a meta refresh, so the redirect works without script too, but only this script keeps
   the old #section (mapped to its new id) and the ?embed=1 the in-app guide panel adds. */
(function(){
  var FROM_COMING_UP={'':'further-ahead',list:'further-ahead',watchlist:'your-places'};
  var page=location.pathname.split('/').pop();
  var id=location.hash.replace(/^#/,'');
  if(page==='coming-up.html'&&Object.prototype.hasOwnProperty.call(FROM_COMING_UP,id))id=FROM_COMING_UP[id];
  location.replace('planning.html'+location.search+(id?'#'+id:''));
})();

/* Embed mode for the in-app help panel. The app loads a guide with ?embed=1:
   the site masthead and footer hide, guide-to-guide links keep ?embed=1, and anything
   leaving the guides (sign-up, pricing, other site pages) opens in a new tab.
   Embedded navigation never adds a history entry: in-frame navigations join the app page's session
   history, so the app's Back handling would otherwise page through the guide. Guide-to-guide links use
   location.replace, #section links use replaceState + scrollIntoView. */
(function(){
  var embed=new URLSearchParams(location.search).has('embed');
  if(!embed)return;
  document.documentElement.classList.add('embed');
  function wire(){
    document.querySelectorAll('a[href]').forEach(function(a){
      var href=a.getAttribute('href');
      if(href.charAt(0)==='#')return;
      var url=new URL(href,location.href);
      var inGuide=url.origin===location.origin&&/\/guide\//.test(url.pathname);
      if(inGuide){url.searchParams.set('embed','1');a.setAttribute('href',url.pathname.split('/').pop()+url.search+url.hash);}
      else{a.target='_blank';a.rel='noopener';}
    });
    report();
  }
  function report(){
    var h1=document.querySelector('h1');
    try{parent.postMessage({type:'photocast-guide',page:location.pathname.split('/').pop(),title:h1?h1.textContent.trim():document.title,hash:location.hash},'*');}catch(e){}
  }
  function guideLink(a){
    if(a.hasAttribute('download')||(a.target&&a.target!=='_self'))return null;
    var url=new URL(a.getAttribute('href'),location.href);
    if(url.origin!==location.origin||!/\/guide\//.test(url.pathname))return null;
    url.searchParams.set('embed','1');
    return url.href;
  }
  document.addEventListener('click',function(e){
    if(e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
    var a=e.target.closest&&e.target.closest('a[href]');
    if(!a)return;
    var href=a.getAttribute('href');
    if(href.charAt(0)==='#'){
      e.preventDefault();
      var id=href.slice(1),target=null;
      try{id=decodeURIComponent(id);}catch(x){}
      if(id)target=document.getElementById(id);
      if(id&&!target)return;
      if(target)target.scrollIntoView();else window.scrollTo(0,0);
      history.replaceState(history.state,'',id?href:location.pathname+location.search);
      report();
      return;
    }
    var dest=guideLink(a);
    if(!dest)return;
    e.preventDefault();
    location.replace(dest);
  });
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',wire);else wire();
})();

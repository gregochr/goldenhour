/* Embed mode for the in-app help panel. The app loads a guide with ?embed=1:
   the site masthead and footer hide, guide-to-guide links keep ?embed=1, and anything
   leaving the guides (sign-up, pricing, other site pages) opens in a new tab. */
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
    var h1=document.querySelector('h1');
    try{parent.postMessage({type:'photocast-guide',page:location.pathname.split('/').pop(),title:h1?h1.textContent.trim():document.title,hash:location.hash},'*');}catch(e){}
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',wire);else wire();
})();

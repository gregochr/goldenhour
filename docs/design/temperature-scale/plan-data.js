/* PhotoCast plan data — regions, locations, windows, narratives.
   Reconciled to the Spot Search v3 model: five regions, three of them configured as
   home (planned from DH3 4NG), two away with their own local base. */
(function(){
const REGIONS=[
 {id:'ntw',n:'Northumberland & Tyneside',al:['northumberland','tyneside','north east'],home:1,base:'DH3 4NG'},
 {id:'penn',n:'North Pennines',al:['pennines','teesdale','durham dales','weardale'],home:1,base:'DH3 4NG'},
 {id:'nymc',n:'N. York Moors & Coast',al:['north york moors','moors','yorkshire coast','cleveland'],home:1,base:'DH3 4NG'},
 {id:'lakes',n:'The Lake District',al:['lakes','the lakes','lakeland','cumbria'],base:'Keswick',
  lead:'You are in the right place at the right time. Tomorrow at first light is the strongest window the region gets all week — Wastwater flat enough for the screes to reflect, Buttermere close behind it. Tonight is the weakest of the three evenings, so if you are camping, walk in tonight and shoot tomorrow.'},
 {id:'dales',n:'Yorkshire Dales',al:['dales','wensleydale','swaledale','ribblesdale'],base:'Hawes',
  lead:'A morning region this week. Tomorrow sunrise carries it, with inversions likely in the dale bottoms and clear high ground above them. The evenings never rise past ordinary.'},
 {id:'borders',n:'Scottish Borders',al:['borders','scotland','st abbs','tweed','berwickshire'],base:'Melrose',
  lead:'Close enough to treat like a home region and rarely thought of as one. The sea cliffs at St Abb\u2019s carry the two good evenings; the Tweed valley is a morning proposition.'},
 {id:'peak',n:'Peak District',al:['peak','peaks','derbyshire','edale','kinder'],base:'Bakewell',
  lead:'Two and a half hours, and the edges face west \u2014 so it is an evening region for you rather than a sunrise one. Inversions in Edale are the reason people go at dawn, and this week is not one of those weeks.'},
 {id:'highlands',n:'Highlands & Skye',al:['highlands','skye','glencoe','assynt','wester ross'],base:'Fort William',
  lead:'Six hours and change, so this is a trip rather than an evening. Once you are here the whole week reads differently \u2014 the weather that flattens County Durham is the weather that lights the west coast.'}];
const HOME={id:'home',n:'DH3 4NG · all your regions',home:1,all:1,base:'DH3 4NG'};

const WINS=[
 {id:'w1',lbl:'Tonight',dow:'TUE',dn:18,when:'Sunset',time:'20:28',
  tide:{f:'high water, <b>falling</b>|HW 19:52 · <b>36m before sunset</b>|<span class="dim">3.2 m · 0.3 m below an average tide</span>',path:'M0 5 C 12 5 17 20 30 20 C 43 20 48 4 61 4 C 74 4 79 20 92 20 C 99 20 102 14 104 11',mx:26,my:17},
  lead:'Cloud breaking from the west with about an hour of usable light, and the tide falling through the whole window.'},
 {id:'w2',dow:'WED',dn:19,when:'Tomorrow sunrise',time:'05:42',
  tide:{f:'mid tide, <b>rising</b>|LW 03:31 · <b>2h11 before sunrise</b>|<span class="dim">3.4 m · about average</span>',path:'M0 20 C 12 20 17 5 30 5 C 43 5 48 20 61 20 C 74 20 79 6 104 6',mx:52,my:13},
  lead:'The flattest morning of the four — light air, mist likely on still water, and the clearest skies of the week.'},
 {id:'w3',dow:'WED',dn:19,when:'Tomorrow sunset',time:'20:25',
  tide:{f:'low water, <b>rising</b>|LW 20:04 · <b>21m before sunset</b>|<span class="dim">2.9 m · 0.6 m below an average tide</span>',path:'M0 6 C 10 6 15 20 27 20 C 40 20 45 4 58 4 C 71 4 76 20 89 20 C 97 20 100 14 104 11',mx:27,my:20},
  lead:'Low water lands on the window, so there is wet sand the length of the coast to double whatever the sky gives you.'},
 {id:'w4',dow:'THU',dn:20,when:'Thursday sunrise',time:'05:44',
  lead:'The front arrives with the light. Not worth the alarm.'},
 {id:'w5',dow:'THU',dn:20,when:'Thursday sunset',time:'20:29',
  tide:{f:'high water, <b>rising</b>|HW 20:46 · <b>14m after sunset</b>|<span class="dim">3.0 m · 0.5 m below an average tide · at St. Mary\u2019s Lighthouse</span>',path:'M0 19 C 12 19 17 4 30 4 C 43 4 48 20 61 20 C 74 20 79 3 92 3 C 99 3 102 9 104 13',mx:88,my:4},
  lead:'High water fourteen minutes after sunset against a clean western horizon — the best alignment of the week.'},
 {id:'w6',dow:'FRI',dn:21,when:'Friday sunrise',time:'05:46',
  lead:'Clear and cold, with low mist in the valleys and a clean eastern horizon.'}];

/* confidence falls with lead time — the heat is drawn hazier as it does, so a day-4 guess
   cannot look as authoritative as tonight. Movement is against the previous run. */
const CONF=[0.95,0.88,0.82,0.72,0.65,0.57];
const RUNAGE='52m';
const BASE={
 ntw:[4.1,3.9,4.0,2.2,4.4,2.3],
 penn:[3.3,3.7,3.4,2.1,3.6,2.6],
 nymc:[3.5,3.1,3.6,2.4,3.8,2.1],
 lakes:[2.7,4.3,2.8,2.1,3.0,2.2],
 dales:[2.9,3.7,3.2,2.0,3.3,2.5],
 borders:[3.8,3.2,3.9,2.3,4.0,2.4],
 peak:[3.2,2.8,3.4,2.2,3.7,2.3],
 highlands:[2.4,3.1,2.6,3.9,2.8,4.4]};
const DELTA={
 ntw:[0.3,-0.2,0.4,0,0.5,-0.1],
 penn:[0,0.3,-0.1,0,0.2,0.2],
 nymc:[0,0.2,-0.1,0,0.2,0],
 lakes:[0.1,0.6,-0.3,0,-0.2,0.3],
 dales:[-0.2,0.4,0,-0.1,0,0.2],
 borders:[0.2,-0.1,0.3,0,0.4,0],
 peak:[-0.1,0.2,0.2,0,0.3,-0.1],
 highlands:[0,0.3,-0.2,0.7,0,0.5]};

/* [name, lat, lng, coast, lake, minFromHome, milesFromHome, localMin] */
const ANCH={
 ntw:[
  ["St Mary\u2019s Lighthouse",55.072,-1.451,1,0,24,11,24,['whitley','whitley bay','bait island']],
  ['Bamburgh Castle',55.609,-1.709,1,0,78,52,78,['bam','castle']],
  ['Marsden Bay',54.977,-1.384,1,0,28,14,28,['marsden rock','souter']],
  ['Blyth Beach',55.128,-1.503,1,0,41,19,41,['blyth','bathing boxes']],
  ['Roker Pier & Lighthouse',54.923,-1.365,1,0,25,9,25,['roker','sunderland pier']],
  ['Amble',55.334,-1.581,1,0,57,34,57,['amble','warkworth']],
  ['Alnwick Castle',55.416,-1.706,0,0,61,40,61,['alnwick']],
  ['Alnmouth',55.386,-1.611,1,0,65,38,65,['alnmouth']],
  ['Penshaw Monument',54.879,-1.480,0,0,12,5,12,['penshaw','herrington']],
  ['Copt Hill',54.845,-1.445,0,0,10,4,10,['copt','houghton']]],
 penn:[
  ['Simonside',55.293,-1.925,0,0,19,10,19,['simonside crags','rothbury']],
  ["Hadrian\u2019s Wall \u2014 Sycamore Gap",55.006,-2.383,0,0,66,47,66,['sycamore','the wall','hadrian']],
  ['Derwent Reservoir',54.865,-1.983,0,1,34,17,34,['derwent','pow hill']],
  ['High Force',54.650,-2.185,0,0,71,44,71,['high force','teesdale','bowlees']],
  ['Allen Banks',54.965,-2.320,0,0,54,32,54,['allen banks','staward']],
  ['Kielder Observatory',55.234,-2.560,0,1,95,64,95,['kielder','kielder water']]],
 nymc:[
  ['Roseberry Topping',54.503,-1.104,0,0,62,38,62,['roseberry','newton under roseberry']],
  ['Saltburn Pier',54.583,-0.973,1,0,74,47,74,['saltburn']],
  ['Whitby Abbey',54.487,-0.613,1,0,88,56,88,['whitby']],
  ["Robin Hood\u2019s Bay",54.432,-0.535,1,0,95,62,95,['robin hoods bay','bay town']],
  ['Sandsend Ness',54.503,-0.660,1,0,85,54,85,['sandsend']],
  ['Redcar',54.618,-1.070,1,0,66,42,66,['redcar']],
  ['Infinity Bridge',54.556,-1.317,0,0,52,31,52,['infinity','stockton']],
  ['Ravenscar',54.400,-0.487,1,0,99,66,99,['ravenscar']],
  ['Rosedale',54.335,-0.885,0,0,92,58,92,['rosedale','blakey']]],
 lakes:[
  ['Wastwater',54.440,-3.300,0,1,185,152,52,['wast','wasdale','screes']],
  ['Buttermere',54.540,-3.277,0,1,172,141,22,['butter','gatesgarth']],
  ['Derwentwater \u2014 Ashness',54.578,-3.145,0,1,168,138,8,['derwentwater','ashness','keswick']],
  ['Ullswater',54.577,-2.900,0,1,158,128,24,['ullswater','glenridding']],
  ['Castlerigg Stone Circle',54.603,-3.098,0,0,170,139,6,['castlerigg','stone circle']],
  ['Crummock Water',54.567,-3.301,0,1,174,143,26,['crummock']],
  ['Blea Tarn',54.437,-3.088,0,1,178,146,34,['blea','langdale']],
  ['Elterwater',54.428,-3.030,0,1,174,143,30,['elter','chapel stile']]],
 borders:[
  ["St Abb\u2019s Head",55.913,-2.133,1,0,132,98,20,['st abbs','abbs head']],
  ['Eyemouth',55.873,-2.089,1,0,128,95,14,['eyemouth']],
  ["Scott\u2019s View",55.573,-2.686,0,0,118,88,26,['scotts view','bemersyde']],
  ['Smailholm Tower',55.596,-2.635,0,0,120,90,24,['smailholm']],
  ['Kelso Abbey',55.598,-2.432,0,0,112,84,18,['kelso']],
  ['Coldingham Bay',55.887,-2.140,1,0,130,96,17,['coldingham']]],
 peak:[
  ['Mam Tor',53.349,-1.810,0,0,150,122,15,['mam tor','edale']],
  ['Stanage Edge',53.353,-1.632,0,0,148,120,18,['stanage','hathersage']],
  ['Winnats Pass',53.336,-1.803,0,0,152,123,16,['winnats','castleton']],
  ['Kinder Scout',53.385,-1.872,0,0,155,126,22,['kinder','kinder scout']],
  ['Chatsworth',53.228,-1.611,0,0,158,128,24,['chatsworth','baslow']],
  ['Ladybower',53.370,-1.700,0,1,146,119,12,['ladybower','derwent dam']]],
 highlands:[
  ['Glencoe \u2014 Three Sisters',56.667,-5.100,0,0,380,290,30,['glencoe','three sisters']],
  ['Eilean Donan',57.274,-5.516,1,0,425,330,45,['eilean donan','dornie']],
  ['Quiraing, Skye',57.640,-6.283,0,0,480,375,40,['quiraing','skye','trotternish']],
  ['Loch Assynt',58.170,-5.100,0,1,510,400,55,['assynt','ardvreck']],
  ['Buachaille Etive M\u00f2r',56.647,-4.902,0,0,372,285,22,['buachaille','etive']]],
 dales:[
  ['Malham Cove',54.066,-2.150,0,0,138,108,42,['malham']],
  ['Aysgarth Falls',54.290,-2.000,0,0,118,92,14,['aysgarth']],
  ['Ribblehead Viaduct',54.210,-2.370,0,0,128,99,22,['ribblehead','ribblesdale']],
  ['Buckden Pike',54.192,-2.086,0,0,126,97,26,['buckden']],
  ['Semerwater',54.281,-2.213,0,1,121,94,12,['semerwater','semer water']],
  ['Ingleborough',54.167,-2.398,0,0,133,104,28,['ingleborough']]]};

/* per-window prose for a location, where it exists — never a restatement of a number */
const WHY={
 "St Mary\u2019s Lighthouse":{0:'Causeway is walkable to 22:04 and the falling tide keeps the foreground shining.',2:'Low water bares the rock shelf just before the light goes, and the pool below the lighthouse doubles the sky.',4:'High water arrives fourteen minutes after sunset, pushing the reflection right up to the island.'},
 'Bamburgh Castle':{0:'Castle takes the last light from the south-west while the falling tide leaves an unbroken sheet of wet sand under it.',1:'Low water and a smooth sea — the long reflection along the beach, with no wind to break it.',4:'Clean western horizon and the highest water of the week under the walls.'},
 'Marsden Bay':{0:'Stack sits clear of the cliff at mid tide and the falling water keeps the foreground shining.',2:'Low water opens the sand between the stacks — the only window this week that does.'},
 'Blyth Beach':{2:'The bathing boxes line up against low water twenty minutes before the light goes.',4:'High water reaches the boxes as the sky turns.'},
 'Roker Pier & Lighthouse':{1:'Pier leads the eye and the lighthouse is still lit before sunrise — dependable in broken cloud.',4:'Nine miles, high water, and a clean western sky behind the lighthouse.'},
 'Simonside':{2:'Clear western horizon from the crags; the light on the rock is the reason, not the sky.',4:'Nineteen minutes from home for the best evening of the week.'},
 "Hadrian\u2019s Wall \u2014 Sycamore Gap":{0:'Ridge line clears the low cloud that will sit in the valleys tonight, though the light goes early.',1:'First light along the wall with cloud below you in the valleys — the reason to set the alarm.'},
 'Derwent Reservoir':{1:'Still water at first light with mist likely on the surface — the calmest morning of the four.'},
 'Roseberry Topping':{0:'The profile against a burning western sky; the climb is 25 minutes, so leave early.',1:'Cloud inversion likely in the Cleveland plain below — the shot only mornings give you.',4:'The best evening light on the profile all week.'},
 'Whitby Abbey':{2:'Low water on the window and the abbey lit from the west across the harbour.',4:'Cliff-top aspect holds the colour after the beaches lose it.'},
 'Wastwater':{1:'The screes only reflect when the water is dead flat, and tomorrow at 05:42 is the flattest morning of the four.'},
 'Buttermere':{1:'Calm at first light with mist likely off the water below Haystacks.',2:'Fleetwith Pike lit from the west and the pines standing clear of their reflection.'},
 'Derwentwater \u2014 Ashness':{0:'From the jetties: cloud breaking from the west with an hour of usable light and the water already settling.',1:'Mist on the lake at dawn, Skiddaw clear above it.'},
 'Castlerigg Stone Circle':{1:'First light on Blencathra with the circle still in shadow. Arrive by 04:45.',5:'Clear eastern horizon and low mist in the vale behind the stones.'},
 'Ullswater':{1:'Still water the length of the lake, and the steamer pier gives you a foreground.'},
 'Semerwater':{1:'Inversion likely in the dale bottom with the water flat under it.'},
 'Aysgarth Falls':{1:'Good flow after the week\u2019s rain, and the woodland shelters the water whatever the wind does.'},
 'Malham Cove':{1:'First light hits the cove face directly, and the limestone pavement above holds the mist.'}};

/* region narrative per window — says what the picture cannot */
const NARR={
 ntw:{0:'Low cloud clears from the west and mid-level cloud catches fire on cue. The strongest local window before the front.',1:'Clean eastern horizon the length of the coast, and the calmest air of the week.',2:'Low water right on the window leaves enough wet sand to double whatever the sky gives you.',4:'High water fourteen minutes after sunset against a clean western horizon — the best alignment of the week.'},
 penn:{1:'Cloud sits in the valleys with the tops clear above it, which is the only morning this week that happens.',4:'Clear western horizon from the crags, and the light on the rock is the reason rather than the sky.'},
 nymc:{0:'Clean eastern horizon with mid-level cloud to catch the light.',2:'The moors sit under the cloud edge; the coast south of Saltburn is the half worth driving to.',4:'Cliff-top aspects hold the colour after the beaches lose it.'},
 lakes:{1:'Near-still air and clear skies — the reason to set an alarm.',5:'Still water on the western lakes, though the light goes quickly once it comes.'},
 dales:{1:'The highest hit-rate of the week. Overcast forecast, but transitional.',4:'Valley inversions hold into the evening. High ground clears, the dale bottoms do not.'},
 borders:{0:'The cliffs at St Abb\u2019s face north-east, so the last light rakes along them rather than hitting them flat.',2:'Low water under the stacks, and the colour holds well after the sun has gone.',4:'The cleanest western horizon anywhere in reach this week.'},
 peak:{0:'The edges face west and the gritstone takes the last light directly.',4:'Best evening of the week here, with the wind dropping enough for Ladybower to go still.'},
 highlands:{3:'The front that flattens County Durham on Thursday is the front that lights the west coast \u2014 the only place in the catalogue having a good morning.',5:'Still water on the western lochs and the longest usable light of the week.'}};

function mulberry32(a){return function(){a|=0;a=a+0x6D2B79F5|0;let t=Math.imul(a^a>>>15,1|a);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296}}
const rnd=mulberry32(20260818);
const cl=(v,a,b)=>Math.max(a,Math.min(b,v));
const SPOTS=[];
for(const rid in ANCH)for(const a of ANCH[rid]){
 const[n,lat,lng,coast,lake,min,mi,lmin,al]=a;
 SPOTS.push({rid,n,lat,lng,coast,lake,min,mi,lmin,al:al||[],named:1,j:rnd()*1.0-0.4});
 const k=2+Math.floor(rnd()*3);
 /* scatter inherits its anchor's travel figures with a little spread, so reach means
    something for every rated location the heat draws, not just the named ones */
 for(let i=0;i<k;i++)SPOTS.push({rid,n:'',lat:lat+(rnd()*0.17-0.085),lng:lng+(rnd()*0.24-0.12),coast,lake,
  min:Math.max(8,Math.round(min+(rnd()*26-13))),mi:Math.max(3,Math.round(mi+(rnd()*16-8))),
  lmin:Math.max(5,Math.round(lmin+(rnd()*18-9))),al:[],named:0,j:rnd()*1.7-0.85});
}
/* Bortle-style darkness, 1 (worst) to 5 (best), set per location rather than derived from
   its region: darkness is about the light dome you are standing in, and a region is far too
   coarse a proxy for it — Roker Pier and Kielder are both "Northumberland".
   Coast and upland away from the conurbation score well; anything inside it does not. */
const DARKBASE={ntw:2.1,penn:4.2,nymc:3.9,lakes:4.4,dales:4.3,borders:4.5,peak:3.4,highlands:4.8};
for(const s of SPOTS){
 const pull=s.min<25?-1.5:s.min<45?-0.8:s.min<75?-0.2:0.2;   // distance from the DH3 light dome
 s.bortle=cl(Math.round((DARKBASE[s.rid]+pull+(s.j*0.35))*10)/10,1,5);
 s.dark=s.bortle>=3.8?1:0;   // "dark sky only" means genuinely dark, not merely rural
}
for(const s of SPOTS)s.r=BASE[s.rid].map((b,i)=>{
 const cb=s.coast&&(i===0||i===2||i===4)?0.35:0;
 const lb=s.lake&&(i===1||i===5)?0.3:0;
 return cl(Math.round(b+s.j+cb+lb),1,5)});

/* GLANCE is what lets the catalogue grow without the summary degrading: a region is in your
   planning area if you could plausibly drive to it. Northern England, the Borders and the
   Peak clear three hours; Skye does not, so adding it cannot flatten a thumbnail to three
   green pixels — you reach it by moving the origin, like any away region.
   Both tabs frame themselves with this, so they cannot disagree about "your area". */
const GLANCE=180;
const nearestMin=rid=>d3.min(SPOTS.filter(s=>s.named&&s.rid===rid),s=>s.min);
const areaRids=()=>REGIONS.filter(r=>r.home||nearestMin(r.id)<=GLANCE).map(r=>r.id);
const beyondRids=()=>REGIONS.filter(r=>!r.home&&nearestMin(r.id)>GLANCE).map(r=>r.id);
const ridsOf=o=>(o&&!o.all)?[o.id]:areaRids();
const spotsIn=rids=>SPOTS.filter(s=>rids.includes(s.rid));

window.PLAN={REGIONS,HOME,WINS,CONF,RUNAGE,DELTA,NARR,WHY,SPOTS,SETUP:20,
 GLANCE,nearestMin,areaRids,beyondRids,ridsOf,spotsIn,
 name:Object.fromEntries(REGIONS.map(r=>[r.id,r.n])),
 region:Object.fromEntries(REGIONS.map(r=>[r.id,r]))};
})();

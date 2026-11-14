'use strict';
const PURPOSE = Object.freeze({unclassified:'미분류',business:'일반업무',commute:'출퇴근',personal:'개인용'});
const DEMO_VEHICLE_ID='c6a0145d-06e8-49e4-8ab1-62ba1c0aad89';
function modelLabel(v){return v?.id===DEMO_VEHICLE_ID?'제네시스 GV90':v?.model||'차량 미선택';}
const DEMO_VEHICLE=Object.freeze({name:'제네시스 GV90',plate:'12가 3456',team:'영업팀 공용차',company:'오토로그 데모 주식회사',registration:'000-00-00000'});
let VEHICLE=DEMO_VEHICLE;
const MONTH='2026-11', KEY='autologpro-companion-edits-v1';
const DEMO_DRIVER={name:'김대표',department:'영업팀',email:'demo@example.test'};
let DRIVER=DEMO_DRIVER;
const specs=[
 ['11-11','04:14',25,177,'unclassified'],['11-11','02:59',25,124,'personal'],
 ['11-11','01:44',25,243,'commute'],['11-11','00:29',25,98,'business'],
 ['11-10','20:44',25,124,'unclassified'],['11-10','19:29',25,243,'personal'],
 ['11-10','13:05',31,367,'business'],['11-09','09:10',47,450,'business'],
 ['11-06','18:21',33,315,'commute'],['11-06','08:04',31,250,'personal'],
 ['11-04','16:20',25,228,'business'],['11-04','09:05',22,155,'business'],
 ['11-02','15:05',20,70,'business'],['11-02','07:20',22,91,'personal'],
 ['10-29','17:40',23,168,'commute'],['10-29','08:25',25,173,'commute']
];
let odo=523685;
const seed=specs.map(([date,time,duration,distance,purpose],index)=>{
  const trip={id:'TR-2026'+date.replace('-','')+'-'+time.replace(':',''),date:'2026-'+date,time,duration,distance,purpose,start:odo-distance,end:odo,memo:index===3?'거래처 방문':''};
  odo-=distance; return Object.freeze(trip);
});
const edits=readEdits();
function readEdits(){
  try {
    const v=JSON.parse(localStorage.getItem(KEY)||'{}');
    if(!v||Array.isArray(v)||typeof v!=='object')return {};
    const allowed=Object.create(null);
    for(const t of seed){
      const e=v[t.id];
      if(e&&Object.prototype.hasOwnProperty.call(PURPOSE,e.purpose)&&typeof e.memo==='string')allowed[t.id]={purpose:e.purpose,memo:e.memo.slice(0,200)};
    }
    return allowed;
  } catch {return {};}
}
const host=window.CompanionHost;
let connected=!!host?.connected?.(), remote=[], deliveries=[], lastSync=null;
let accountId='', role='', vehicles=[], selectedVehicle='', knownNotices=new Set(), cloudCursor=null, pollCount=0;
const qrId=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const PAIRING_KEY='alp-vehicle-qr-pending';
const VEHICLE_PREF='alp-selected-vehicle';
function getLocal(key){try{return JSON.parse(localStorage.getItem(key)||'null');}catch{return null;}}
function setLocal(key,value){try{if(value)localStorage.setItem(key,JSON.stringify(value));else localStorage.removeItem(key);}catch{}}
function pendingPair(){
 const v=getLocal(PAIRING_KEY);
 return v&&qrId.test(v.id)&&v.until>Date.now()?v.id:'';
}
function rememberVehicle(){
 if(accountId&&qrId.test(selectedVehicle))setLocal(VEHICLE_PREF,{account:accountId,id:selectedVehicle});
}
let pairSyncAgain=false;
let generation=0;
let syncFailed=false, syncRunning=false, remoteMonth=null, dataVersion=0;
let prefs={};try{prefs=JSON.parse(localStorage.getItem('alp-automation-options')||'{}')||{};}catch{}
const callbacks=new Map();let seq=0, mutation=false, undoEntry=null, undoTimer=null;
function server(op,args={}){
 return new Promise((resolve,reject)=>{
  if(!host?.server){reject(503);return;}
  const key=String(++seq);
  const timeout=setTimeout(()=>{callbacks.delete(key);reject(503);},30000);
  callbacks.set(key,(value,error)=>{clearTimeout(timeout);error?reject(error):resolve(value);});
  host.server(op,JSON.stringify(args),key);
 });
}
function storeOptions(){try{localStorage.setItem('alp-automation-options',JSON.stringify(prefs));}catch{}}
function remoteTrip(t){
 if(!t||typeof t.id!=='string'||!/^[0-9a-f-]{36}$/i.test(t.id)||typeof t.vehicle_id!=='string'||
 !Object.prototype.hasOwnProperty.call(PURPOSE,t.purpose))return null;
 const start=Date.parse(t.start_time),end=Date.parse(t.end_time);
 if(!Number.isFinite(start)||!Number.isFinite(end)||end<start)return null;
 const date=new Date(start+9*3600000).toISOString();
 const values=[t.distance_km,t.start_odo_km,t.end_odo_km].map(Number);
 if(values.some(n=>!Number.isFinite(n)))return null;
 return {id:t.id,key:t.id,vehicle:t.vehicle_id,date:date.slice(0,10),time:date.slice(11,16),
   duration:Math.max(1,Math.round((end-start)/60000)),distance:values[0]*10,start:values[1]*10,end:values[2]*10,
   place:t.end_place||'',purpose:t.purpose,memo:t.memo||'',driver:t.driver_name||'',department:t.department_name||'',
   simulated:t.source==='SIMULATED'||t.is_simulated===true,updated:t.updated_at||''};
}
function allTrips(){return connected?remote.filter(t=>t.vehicle===selectedVehicle):seed.map(t=>({...t,vehicle:'demo',...(t.id.endsWith('2044')?{place:'오토 정비소'}:{}),...(edits[t.id]||{})}));}
function shown(rows){return rows.filter(t=>t.purpose!=='personal');}
function activeMonth(){return connected?(remoteMonth||new Date(Date.now()+9*3600000).toISOString().slice(0,7)):MONTH;}
function applyVehicle(){
 const v=vehicles.find(x=>x.id===selectedVehicle);
 const label=role==='employee'?'내 운행':role==='fleet_manager'?'관리 차량':'회사 차량';
 VEHICLE={...VEHICLE,name:e(modelLabel(v)),plate:e(v?.plate_no||'—'),team:label};
}
function vehicleSelect(){
 if(!connected)return '';
 return '<label class="vehicle-select">차량 <select id="cloud-vehicle" aria-label="차량 선택">'+
 (vehicles.length?vehicles.map(v=>'<option value="'+e(v.id)+'"'+(v.id===selectedVehicle?' selected':'')+'>'+e(v.plate_no)+' · '+e(modelLabel(v))+'</option>').join(''):'<option>조회할 수 있는 차량 없음</option>')+'</select></label>';
}
function instantMicros(value){
 const text=String(value||'');
 const m=text.match(/^(.*?)(?:[.]([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})$/);
 if(!m)return [Date.parse(text)||0,0];
 const whole=Date.parse(m[1]+m[3]);
 return [Number.isFinite(whole)?whole:0,Number((m[2]||'').padEnd(6,'0').slice(0,6))];
}
function compareInstant(a,b){
 const x=instantMicros(a),y=instantMicros(b);
 return x[0]-y[0]||x[1]-y[1];
}
async function sync(manual=false){
 if(!connected){if(manual)toast('Supabase 로그인 전이라 예시 기록을 보여줘요.');return;}
 if(syncRunning||mutation)return;
 syncRunning=true;const run=generation, revision=dataVersion;
 try{
  const first=!lastSync, full=first||manual||(++pollCount%12===0);
  let current=full?null:cloudCursor;
  if(full){
   const info=await server('context');if(run!==generation)return;
   accountId=info.user_id;role=info.role;
   VEHICLE={...DEMO_VEHICLE,company:e(info.company?.name||'—'),registration:e(info.company?.biz_no||'—')};
   DRIVER={name:e(info.display_name||'—'),department:e(info.department?.name||'—'),email:'회사 계정'};
   const rows=await server('vehicles');if(run!==generation)return;
   vehicles=Array.isArray(rows)?rows:[];
   if(role==='fleet_manager'){const scopes=await server('access');if(run!==generation)return;const allowed=new Set((scopes||[]).map(x=>x.vehicle_id));vehicles=vehicles.filter(v=>allowed.has(v.id));}
  }
  const known=new Map((full?[]:remote).map(t=>[t.id,t]));
  let cursor=current, nextArgs={};
  if(current?.updated_at){
   const overlap=new Date(Date.parse(current.updated_at)-120000).toISOString();
   nextArgs={since:overlap};
  }
  while(true){
   const batch=await server('trips',nextArgs);if(run!==generation)return;
   for(const item of batch.trips||[]){
    const t=remoteTrip(item),prior=t&&known.get(t.id);
    if(t&&(!prior||compareInstant(t.updated,prior.updated)>=0))known.set(t.id,t);
   }
   const next=batch.next_cursor;
   if(next&&(!cursor||compareInstant(next.updated_at,cursor.updated_at)>0||
       (compareInstant(next.updated_at,cursor.updated_at)===0&&next.id>cursor.id)))cursor=next;
   if(!batch.has_more||!next)break;
   if(nextArgs.since===next.updated_at&&nextArgs.cursor===next.id)throw 502;
   nextArgs={since:next.updated_at,cursor:next.id};
  }
  if(dataVersion!==revision)return;
  remote=[...known.values()].sort((a,b)=>(b.date+b.time).localeCompare(a.date+a.time));
  cloudCursor=cursor;
  const target=full?pendingPair():'';
  const accepted=!!target&&vehicles.some(v=>v.id===target);
  if(target)setLocal(PAIRING_KEY,null);
  if(accepted){selectedVehicle=target;rememberVehicle();}
  if(!vehicles.some(v=>v.id===selectedVehicle)){
   const saved=getLocal(VEHICLE_PREF);
   selectedVehicle=saved?.account===accountId&&vehicles.some(v=>v.id===saved.id)?saved.id:
      (remote.find(t=>vehicles.some(v=>v.id===t.vehicle))?.vehicle||vehicles[0]?.id||'');
  }
  applyVehicle();
  if(role==='admin'&&full){
   try{const list=await server('deliveries');if(run!==generation)return;deliveries=Array.isArray(list)?list:[];}catch{deliveries=[];}
  }
  if(!remoteMonth){remoteMonth=activeMonth();state.month=remoteMonth;state.range=monthRange(remoteMonth);}
  if(prefs.notification&&host?.cloudAlert&&accountId){
   for(const t of remote){
    if(knownNotices.has(t.id))continue;
    knownNotices.add(t.id);
    if(!first&&t.purpose==='unclassified')host.cloudAlert(t.id,accountId);
   }
  }else for(const t of remote)knownNotices.add(t.id);
  lastSync=Date.now();syncFailed=false;
  if(accepted){
   state.detail=null;state.month=activeMonth();state.filter='all';topTab('home');
   toast('QR에 표시된 차량을 열었어요.');
  }else{
   if(state.page!=='detail'&&(first||manual||['home','trips','inbox'].includes(state.page)))render({top:false});
   if(target)toast('이 계정으로 열 수 없는 차량이에요.');
   else if(manual)toast('서버에서 운행기록을 새로 불러왔어요.');
  }
 }catch(err){
  if(run!==generation)return;
  syncFailed=true;
  if(err===401&&!host?.connected?.()){window.alpNativeEvent('disconnected',{});toast('세션이 만료됐어요. 다시 로그인하세요.');return;}
  if(state.page!=='detail')render({top:false});
  if(manual)toast('서버 기록을 가져오지 못했어요. ('+err+')');
 }finally{
  if(run===generation){
   syncRunning=false;
   if(pairSyncAgain&&!mutation){pairSyncAgain=false;setTimeout(()=>{if(connected&&pendingPair())sync(true);},0);}
  }
 }
}
function applyLaunch(x){
 if(x.demoAction&&!connected){try{const a=JSON.parse(x.demoAction);commitPurpose(a.id,a.purpose);}catch{}}
 if(x.open){state.month=activeMonth();state.filter='unclassified';topTab('trips');}
}
window.alpNativeEvent=(name,x)=>{
 if(name==='reply'){const cb=callbacks.get(x.callback);callbacks.delete(x.callback);if(cb)cb(x.result,x.error);return;}
 if(name==='vehicleQr'){
  if(!qrId.test(x.id))return;
  setLocal(PAIRING_KEY,{id:x.id,until:Date.now()+20*60000});
  if(connected){
   if(syncRunning||mutation){pairSyncAgain=true;}
   else sync(true);
  }else{
   go('login',{push:false});
   host?.nativeAction?.('server');
  }
  return;
 }
 if(name==='connected'||name==='disconnected'){
  generation++;syncRunning=false;pairSyncAgain=false;connected=name==='connected';remote=[];deliveries=[];lastSync=null;remoteMonth=null;selectedVehicle='';vehicles=[];cloudCursor=null;knownNotices.clear();accountId='';role='';undoEntry=null;syncFailed=false;
  state.detail=null;state.month=activeMonth();state.range=monthRange(state.month);
  VEHICLE=DEMO_VEHICLE;DRIVER=DEMO_DRIVER;topTab('settings');if(connected)sync();return;
 }
 if(name==='resume'){applyLaunch(x);if(connected)sync();return;}
 if(name==='permission'){prefs[x.feature]=x.granted;storeOptions();toast(x.granted?'기능을 사용할 수 있어요.':'권한 없이도 앱에서 직접 분류할 수 있어요.');if(state.page!=='detail')render({top:false});}
};
function nextInbox(){return shown(tripsForMonth(state.month)).find(t=>t.purpose==='unclassified');}
function suggestion(t){
 if(!t||t.purpose!=='unclassified')return null;
 let events=[];
 if(prefs.calendar&&host?.calendar){try{const at=ALPRules.stamp(t);events=JSON.parse(host.calendar(at-10*60000,at+t.duration*60000+45*60000));}catch{}}
 return ALPRules.suggest(t,allTrips(),events,prefs.service);
}
function suggestionMarkup(t){
 const x=suggestion(t);
 return x?'<div class="suggest"><small>'+e(x.label)+'</small>'+action('suggest',PURPOSE[x.purpose]+' 적용','secondary-btn','data-id="'+e(t.id)+'"')+(x.memo?'<p class="hint">'+e(x.memo)+'</p>':'')+'</div>':'';
}
function modeLabel(){return connected?'Supabase · '+(role==='employee'?'내 운행':role==='admin'?'회사 관리자':role==='fleet_manager'?'차량 관리자':'연결 확인 중'):'예시 데이터 · 기기 저장';}
function syncLabel(){return syncFailed?'연결 오류 · 새로고침 가능':lastSync?'마지막 동기화 '+new Date(lastSync).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit'}):modeLabel();}

function lookup(id){return allTrips().find(t=>t.id===id);}
function tripsForMonth(month){return allTrips().filter(t=>t.date.startsWith(month));}
function pending(rows=allTrips()){return rows.filter(t=>t.purpose==='unclassified').length;}
function metrics(rows){return rows.reduce((m,t)=>{m.total+=t.distance;if(t.purpose==='business')m.business+=t.distance;if(t.purpose==='commute')m.commute+=t.distance;return m;},{total:0,business:0,commute:0});}
function km(n){return (n/10).toLocaleString('ko-KR',{minimumFractionDigits:1,maximumFractionDigits:3});}
function percentage(m){return m.total ? (100*(m.business+m.commute)/m.total).toFixed(1)+'%' : '—';}
function e(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function formatDate(d,year=false){const [y,m,day]=d.split('-').map(Number);const weekday='일월화수목금토'[new Date(Date.UTC(y,m-1,day)).getUTCDay()];return (year?y+'년 ':'')+m+'월 '+day+'일 ('+weekday+')';}
function monthLabel(m){const [y,n]=m.split('-');return y+'년 '+Number(n)+'월';}
function monthRange(m){const [y,n]=m.split('-').map(Number);const last=new Date(Date.UTC(y,n,0)).getUTCDate();return {start:m+'-01',end:m+'-'+last};}
const icons={
 car:'<path d="m5 10 1.7-4.5A1.5 1.5 0 0 1 8.1 4.5h7.8a1.5 1.5 0 0 1 1.4 1L19 10M4 17v2m16-2v2M4 13a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v4H4zM7 14h1m8 0h1"/>',
 home:'<path d="m3 10 9-7 9 7v10h-6v-6H9v6H3z"/>',
 trips:'<path d="M4 16V6a2 2 0 0 1 2-2h9l4 4v3M7 9h7M7 13h4"/><path d="M12 19v-3l1-3h6l1 3v3m-8-3h8m-8 2h8m-7 1v1m6-1v1"/>',
 report:'<path d="M6 3h8l4 4v14H6zm8 0v5h4M9 12h6m-6 4h6"/>',
 settings:'<circle cx="12" cy="12" r="3"/><path d="m10 3-.6 2.6-2 .9L5 5l-2 3 2.1 1.8v2.5L3 14l2 3 2.4-1.2 2 .9L10 20h4l.6-3.3 2-.9L19 17l2-3-2.1-1.7V9.8L21 8l-2-3-2.4 1.5-2-.9L14 3z"/>',
 right:'<path d="m9 6 6 6-6 6"/>',back:'<path d="m15 6-6 6 6 6"/>',
 sync:'<path d="M20 8a8 8 0 0 0-13-3L4 8m0-5v5h5m-5 8a8 8 0 0 0 13 3l3-3m-5 0h5v5"/>',
 sort:'<path d="M8 4v16m-3-3 3 3 3-3m5 3V4m-3 3 3-3 3 3"/>',
 clock:'<circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/>',
 user:'<circle cx="12" cy="8" r="3"/><path d="M5 20v-2a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v2z"/>',
 download:'<path d="M12 3v12m-4-4 4 4 4-4M5 16v4h14v-4"/>',
 share:'<circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="5" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="m8.3 10.7 7.4-4.3m-7.4 7 7.4 4.2"/>'
};
function icon(k,sm=false){return '<svg aria-hidden="true" class="icon'+(sm?' sm':'')+'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">'+icons[k]+'</svg>';}
function status(p){return '<span class="trip-status status-'+p+'">'+PURPOSE[p]+'</span>';}
function action(a,t,cls='plain-link',data=''){return '<button type="button" class="'+cls+'" data-action="'+a+'" '+data+'>'+t+'</button>';}
function bar(title,{back=false,brand=false,end=''}={}){
  return '<header class="topbar'+(back?' with-back':'')+'">'+(back?action('back',icon('back'),'icon-btn back-btn','aria-label="뒤로 가기"'):'')+
    (brand?'<span class="brand">'+icon('car')+'</span>':'')+'<h1>'+title+'</h1><span class="spacer"></span>'+end+'</header>';
}
const tabs={home:['홈','home'],trips:['운행기록','trips'],report:['보고서','report'],settings:['설정','settings']};
function navigation(page){return '<nav class="nav" aria-label="하단 메뉴">'+Object.entries(tabs).map(([k,[title,i]])=>action('tab',icon(i)+title,'nav-btn','data-page="'+k+'"'+(page===k?' aria-current="page"':''))).join('')+'</nav>';}
function tripRow(t,home=false){return action('open-trip',
  '<span><time datetime="'+t.date+'T'+t.time+'">'+formatDate(t.date)+' '+t.time+'</time><small>'+t.duration+'분 운행</small></span>'+
  '<span class="trip-values"><strong class="trip-distance">'+km(t.distance)+' km</strong>'+(t.simulated?'<small>시연</small>':'')+status(t.purpose)+'</span>'+icon('right',true),
  'trip','data-id="'+t.id+'"'+(home?' data-origin="home"':'')+' aria-label="'+formatDate(t.date)+' '+t.time+' '+km(t.distance)+' km '+PURPOSE[t.purpose]+'"');}
function metricCards(m){return '<dl class="metrics"><div><dt>총 주행거리</dt><dd>'+km(m.total)+' <small>km</small></dd></div><div><dt>업무용 주행</dt><dd>'+km(m.business+m.commute)+' <small>km</small></dd></div><div><dt>업무사용비율</dt><dd>'+percentage(m)+'</dd></div></dl>';}
function info(label,value){return '<div class="info-row"><span class="muted">'+label+'</span><span>'+value+'</span></div>';}
const state={page:'home',month:MONTH,filter:'all',ascending:false,detail:null,range:monthRange(MONTH),history:[]};
const app=document.getElementById('app');
const footer=html=>'<footer class="actions">'+html+'</footer>';
function home(){
 const month=activeMonth(), rows=tripsForMonth(month), todo=pending(rows);
 return bar('Auto Log Pro',{brand:true,end:action('sync',icon('sync'),'icon-btn','aria-label="새로고침"')})+'<main>'+vehicleSelect()+(!connected?qrWelcome():'')+
  '<section class="card"><div class="vehicle-head"><div><p class="vehicle-meta">'+VEHICLE.team+' · '+VEHICLE.plate+'</p><h2>'+VEHICLE.name+'</h2><p class="vehicle-meta">'+e(syncLabel())+'</p></div>'+((!connected||selectedVehicle===DEMO_VEHICLE_ID||/GV90/i.test(VEHICLE.name))?'<img class="car-img" src="genesis_gv90.png" alt="제네시스 GV90">':'')+'</div>'+
  '<div class="odo"><span class="muted">누적 주행거리</span><span><strong class="number">'+km((shown(allTrips())[0]?.end)||0)+'</strong> <span class="muted">km</span></span></div></section>'+
  (todo?action('todo','<span>미분류 운행 '+todo+'건 확인</span>'+icon('right',true),'todo-link'):'<p class="hint">'+(rows.length?'모든 운행 목적을 분류했어요.':'아직 표시할 운행이 없어요.')+'</p>')+
  '<section class="card"><div class="section-top"><h2>이번 달 운행 요약</h2><small class="muted">'+monthLabel(month)+'</small></div>'+metricCards(metrics(rows))+'</section>'+
  '<section class="card list-card"><div class="section-top"><h2>최근 운행 기록</h2>'+action('all-trips','전체보기 '+icon('right',true))+'</div><div>'+shown(allTrips()).slice(0,3).map(t=>tripRow(t,true)).join('')+'</div></section></main>';
}
function trips(){
 const rows=tripsForMonth(state.month);
 const visible=shown(rows).filter(t=>state.filter==='all'||t.purpose===state.filter);
 if(state.ascending)visible.reverse();
 const count=pending(rows);
 return bar('운행기록',{end:'<select aria-label="운행기록 조회 월" id="trip-month">'+[...new Set([activeMonth(),state.month,...(connected?allTrips().map(t=>t.date.slice(0,7)):['2026-10','2026-09'])])].sort().reverse().map(m=>'<option value="'+m+'"'+(state.month===m?' selected':'')+'>'+monthLabel(m)+'</option>').join('')+'</select>'+action('sync',icon('sync'),'icon-btn','aria-label="새로고침"')})+
 '<div class="filters" aria-label="운행 목적 필터">'+[['all','전체'],['unclassified','미분류 '+count],['business','일반업무'],['commute','출퇴근']].map(([k,label])=>action('filter',label,state.filter===k?'active':'','data-filter="'+k+'" aria-pressed="'+(state.filter===k)+'"')).join('')+'</div>'+
 '<main>'+vehicleSelect()+(state.filter==='unclassified'&&count?action('inbox','미분류 이어서 분류','secondary-btn wide'):'')+'<div class="list-heading"><span>'+visible.length+'건의 운행내역</span>'+action('sort',icon('sort',true)+(state.ascending?'오래된순':'최신순'),'','aria-label="정렬 변경"')+'</div>'+
 (visible.length?'<section class="card list-card">'+visible.map(t=>tripRow(t)).join('')+'</section>':'<div class="empty"><p>표시할 운행이 없어요.</p><p class="hint">다른 월이나 운행 목적을 선택해보세요.</p></div>')+'</main>';
}
function detail(){
 const d=state.detail,t=lookup(d.id);
 if(!t||t.redacted||t.purpose==='personal')return bar('운행 상세',{back:true})+'<main><p>표시할 운행이 없어요.</p></main>';
 const enabled=d.purpose!=='unclassified'&&d.memo.trim()!==t.memo;
 return bar('운행 상세',{back:true})+'<main>'+
 '<section class="card"><div class="detail-lead"><div><small class="muted">주행거리</small><p class="number big-distance">'+km(t.distance)+' <small>km</small></p></div>'+status(t.purpose)+'</div><p class="hint">'+icon('clock',true)+' '+formatDate(t.date,true)+' '+t.time+' · '+t.duration+'분 소요</p></section>'+
 '<section class="card"><div class="section-top"><h2>차량 주행 기록</h2><small class="muted">차량에서 자동 기록</small></div><div class="detail-odo"><div><small class="muted">출발 계기판</small><strong class="tabular">'+km(t.start)+' km</strong></div><div><small class="muted">도착 계기판</small><strong class="tabular">'+km(t.end)+' km</strong></div></div>'+
 info('운전자',e(t.driver)||(connected?'운전자 미확인':DRIVER.name))+info('배정 차량',VEHICLE.name+' ('+VEHICLE.plate+')')+'</section>'+
 '<section class="card"><fieldset class="purpose-group"><legend>운행 목적 선택</legend><p class="hint">이번 운행의 목적을 선택해 주세요.</p>'+
 (connected?['business','commute','personal']:['business','commute']).map(k=>'<label class="radio-row"><input type="radio" name="purpose" value="'+k+'"'+(d.purpose===k?' checked':'')+'> <span class="status-'+k+'">'+PURPOSE[k]+'</span></label>') .join('')+'</fieldset>'+suggestionMarkup(t)+
 '<label class="memo-label" for="memo">메모 (선택)</label><textarea class="memo" id="memo" maxlength="200" placeholder="메모를 입력하세요">'+e(d.memo)+'</textarea></section></main>'+
 footer(action('save-trip','메모 저장','primary','id="save-trip"'+(enabled?'':' disabled')));
}
function reportRows(){
 return allTrips().filter(t=>{
  if(t.simulated)return false;
  if(!t.redacted)return t.date>=state.range.start&&t.date<=state.range.end;
  const bucket=monthRange(t.date.slice(0,7));
  return state.range.start<=bucket.start&&state.range.end>=bucket.end;
 }).reverse();
}
function validRange(){const {start,end}=state.range;return /^\d{4}-\d{2}-\d{2}$/.test(start)&&/^\d{4}-\d{2}-\d{2}$/.test(end)&&start<=end;}
function report(){
 const formats=connected?'<label>파일 형식 <select id="report-format"><option value="xlsx">Excel (.xlsx)</option><option value="csv">CSV (.csv)</option><option value="html">HTML (.html)</option></select></label>':'';
 return bar('보고서',{end:connected?action('sync',icon('sync'),'icon-btn','aria-label="서버 보고서 새로고침"'):''})+'<main>'+vehicleSelect()+
 '<section class="card"><small class="muted">대상 차량</small><h2>'+VEHICLE.name+'</h2><p class="hint">'+VEHICLE.plate+' · '+VEHICLE.team+'</p></section>'+
 '<section class="card inputs"><h2>조회 기간</h2><div class="period-actions">'+action('period','이번 달','','data-month="'+activeMonth()+'"')+action('period','지난달','','data-month="'+new Date(Date.parse(activeMonth()+'-01')-86400000).toISOString().slice(0,7)+'"')+'</div>'+
 '<div class="date-fields"><label>시작일<input type="date" id="range-start" value="'+e(state.range.start)+'"></label><label>종료일<input type="date" id="range-end" value="'+e(state.range.end)+'"></label></div>'+formats+'<p class="form-error" id="range-error"></p></section>'+
 '<section class="notice"><p id="report-count"></p><p class="hint">시연 운행은 보고서에서 제외돼요. 미분류와 비업무는 총 주행거리에만 포함돼요.</p></section>'+
 (connected?'<div class="automation-actions">'+action('cloud-save','서버 파일 저장','primary')+action('cloud-share','서버 파일 공유','secondary-btn')+'</div>':'')+
 action('preview','운행기록부 미리보기','primary wide','id="preview-btn"')+
 (connected&&role==='admin'?'<section class="card"><h2>회사 발송 내역</h2><p class="hint">수신처와 예약은 서버 관리자가 설정해요.</p>'+
 (deliveries.length?deliveries.map(d=>'<p class="hint">'+e(d.period_start)+' ~ '+e(d.period_end)+' · '+e(d.channel==='email'?'이메일':'사내 시스템')+' · '+e(({sent:'발송 완료',failed:'실패',queued:'대기'})[d.status]||d.status)+'</p>').join(''):'<p class="hint">표시할 발송 내역이 없어요.</p>')+'</section>':'')+'</main>';
}
function preview(){
 const rows=reportRows(), m=metrics(rows);
 return bar('업무용승용차 운행기록부',{back:true})+'<main><section class="card"><div class="section-top"><h2>'+VEHICLE.name+'</h2><small class="muted">'+VEHICLE.plate+'</small></div><p class="hint">'+state.range.start+' ~ '+state.range.end+' · '+modeLabel()+'</p>'+
 info('법인명',VEHICLE.company)+info('사업자등록번호',VEHICLE.registration)+info('사업연도',e(state.range.start.slice(0,4))+'년')+'</section>'+
 '<section class="card"><div class="section-top"><h2>선택 기간 합계</h2></div>'+metricCards(m)+'</section>'+
 '<section class="card"><div class="section-top"><h2>운행 내역 '+shown(rows).length+'건</h2><small class="muted">단위 km</small></div>'+
 '<div class="table-wrap" tabindex="0" aria-label="운행기록부 표"><table class="report-table"><thead><tr><th>일자</th><th>부서·운전자</th><th>주행 전</th><th>주행 후</th><th>주행거리</th><th>출퇴근</th><th>일반업무</th><th>구분</th><th>메모</th></tr></thead><tbody>'+
 shown(rows).map(t=>'<tr><td>'+t.date+'</td><td>'+(e(t.department)||DRIVER.department)+' · '+(e(t.driver)||(connected?'운전자 미확인':DRIVER.name))+'</td><td>'+km(t.start)+'</td><td>'+km(t.end)+'</td><td>'+km(t.distance)+'</td><td>'+km(t.purpose==='commute'?t.distance:0)+'</td><td>'+km(t.purpose==='business'?t.distance:0)+'</td><td>'+status(t.purpose)+'</td><td>'+e(t.memo)+'</td></tr>').join('')+
 '</tbody></table></div></section><p class="hint">CSV 내보내기는 별도이며, 월말 자동 제출은 회사 내부 보관을 뜻해요. 국세청으로 전송하지 않아요.</p></main>'+
 footer(connected?action('cloud-save','서버 엑셀 저장','secondary-btn')+action('cloud-share','서버 엑셀 공유','primary'):action('export',icon('download',true)+' CSV 저장','secondary-btn')+action('share',icon('share',true)+' 공유','primary'));
}
function setting(a,label,value,i){return action(a,(i?icon(i,true):'')+'<span>'+label+'</span><span class="setting-val">'+value+'</span>'+icon('right',true),'setting');}
function qrWelcome(){return '<section class="card inputs"><h2>차량 연결</h2><p class="hint">차량 화면에서 QR을 열고 스캔하세요. 회사 계정으로 로그인하면 선택한 차량이 열려요.</p>'+action('scan','차량 QR 스캔','primary wide')+action('configure','회사 계정으로 로그인','secondary-btn wide')+'</section>';}
function settings(){return bar('설정')+'<main><section class="card settings-list">'+setting('account','내 계정',DRIVER.name,'user')+setting('company','소속 회사',VEHICLE.company)+setting('vehicle','배정 차량',VEHICLE.plate,'car')+setting('sync','동기화',e(syncLabel()),'sync')+'</section>'+
 '<section class="card settings-list">'+setting('configure','Supabase 로그인',connected?'연결됨':'미연결','sync')+
 setting('scan','차량 QR 스캔','차량 선택','car')+
 '<div class="automation-actions">'+action('native','캘린더 권한','secondary-btn','data-native="calendar"')+action('native','알림 권한','secondary-btn','data-native="notification"')+'</div>'+
 '<p class="hint">QR은 차량을 선택해요. 기록은 로그인한 계정의 권한에 따라 표시돼요. 알림은 앱이 새 운행을 동기화했을 때 표시돼요.</p>'+
 '<p class="hint">캘린더 제목은 추천을 적용할 때만 메모에 들어가요. 권한을 거절해도 직접 분류할 수 있어요.</p></section>'+
 '<section class="card inputs"><h2>정비 예약</h2><label>예약일<input id="service-date" type="date" value="'+e(prefs.service?.date||'')+'"></label><label>정비소 이름<input id="service-shop" maxlength="60" value="'+e(prefs.service?.shop||'')+'"></label>'+
 action('service','정비 일정 저장','secondary-btn wide')+'<p class="hint">예약일과 운행 목적지의 정비소 이름이 일치하면 일반업무를 추천해요.</p></section>'+
 (!connected?'<section class="card"><h2>알림 시연</h2>'+action('demo-alert','시연 알림 보내기','secondary-btn wide')+'</section>':'')+
 '<div class="account-mark">'+modeLabel()+' · v0.3.4</div>'+(!connected?action('logout','로그인 화면 보기','secondary-btn wide'):'')+'</main>';}
function inbox(){
 const t=nextInbox();
 return bar('미분류 이어서 분류',{back:true})+'<main><p class="hint">건별 분류 · 왼쪽은 출퇴근, 오른쪽은 일반업무</p>'+
 (t?'<section class="card swipe-card" id="swipe-card" data-id="'+e(t.id)+'"><h2>'+km(t.distance)+' km</h2><p>'+formatDate(t.date)+' '+e(t.time)+'</p>'+suggestionMarkup(t)+
 '<div class="automation-actions">'+action('quick','← 출퇴근','secondary-btn','data-pick="commute" data-id="'+e(t.id)+'"')+action('quick','일반업무 →','primary','data-pick="business" data-id="'+e(t.id)+'"')+'</div></section>':'<div class="empty">이 달의 미분류 운행을 모두 처리했어요.</div>')+'</main>';
}
function subsettings(page){
 const values={account:['내 계정',[[ '이름',DRIVER.name],['이메일',DRIVER.email],['부서',DRIVER.department],['역할',connected?e(role):'운전자 (예시)']]],company:['소속 회사',[['법인명',VEHICLE.company],['사업자등록번호',VEHICLE.registration]]],vehicle:['배정 차량',[['차종',VEHICLE.name],['자동차등록번호',VEHICLE.plate],['용도',VEHICLE.team],['누적 주행거리',km((shown(allTrips())[0]?.end)||0)+' km']]]};
 const [title,rows]=values[page];
 return bar(title,{back:true})+'<main><section class="card">'+rows.map(([k,v])=>info(k,v)).join('')+'</section></main>';
}
function login(){return '<main class="login"><span class="brand">'+icon('car')+'</span><h1>Auto Log Pro</h1><p>'+ (pendingPair()?'차량 QR을 확인했어요. 회사 계정으로 로그인하면 이 차량이 열려요.':'회사 차량을 연결하려면 QR을 스캔하거나 회사 계정으로 로그인하세요.')+'</p><div class="inputs">'+action('configure','회사 계정으로 로그인','primary wide')+action('scan','차량 QR 스캔','secondary-btn wide')+action('demo-login','데모로 시작','plain-link')+'</div></main>';}
function render({top=true}={}){
 const pages={home,trips,detail,report,preview,settings,inbox,account:()=>subsettings('account'),company:()=>subsettings('company'),vehicle:()=>subsettings('vehicle'),login};
 const primary=Object.prototype.hasOwnProperty.call(tabs,state.page);
 app.className=primary?'with-nav':(['detail','preview'].includes(state.page)?'with-footer':'');
 app.innerHTML=pages[state.page]()+ (primary?navigation(state.page):'');
 if(state.page==='report')updateReportControls();
 if(top)window.scrollTo(0,0);
}
function toast(text){
 clearTimeout(undoTimer);undoEntry=null;
 const el=document.getElementById('toast'); el.textContent=text; el.classList.add('show');
 clearTimeout(toast.timer);toast.timer=setTimeout(()=>el.classList.remove('show'),2300);
}
function go(page,{push=true}={}){
 if(push&&state.page!==page)state.history.push({page:state.page,y:window.scrollY});
 state.page=page;render();
}
function back(){
 const last=state.history.pop();
 if(!last){if(state.page==='home')return false;go('home',{push:false});return true;}
 state.page=last.page;render({top:false});window.scrollTo(0,last.y);return true;
}
window.appHandleBack=back;
function topTab(page){state.history=page==='home'?[]:[{page:'home',y:0}];go(page,{push:false});}
function updateSave(){
 const d=state.detail,t=lookup(d.id),b=document.getElementById('save-trip');
 if(b&&t)b.disabled=mutation||t.purpose==='unclassified'||d.memo.trim()===t.memo;
}
function saveLocal(id,purpose,memo){
 const old=edits[id];edits[id]={purpose,memo};
 try{localStorage.setItem(KEY,JSON.stringify(edits));}catch(err){if(old)edits[id]=old;else delete edits[id];throw err;}
}
function showUndo(){
 const el=document.getElementById('toast');el.innerHTML='<span>운행 목적을 저장했어요.</span> '+action('undo','되돌리기','undo');
 el.classList.add('show');clearTimeout(toast.timer);clearTimeout(undoTimer);
 undoTimer=setTimeout(()=>{el.classList.remove('show');undoEntry=null;},3000);
}
async function commitPurpose(id,purpose,extraMemo,{undo=false,memoOnly=false}={}){
 const t=lookup(id);if(!t||t.redacted||mutation)return;
 const memo=(extraMemo??t.memo).trim().slice(0,200);
 if(t.purpose===purpose&&memo===t.memo)return;
 mutation=true;const run=generation;
 try{
  let newRevision;
  if(connected){
   const patch={};if(t.purpose!==purpose)patch.purpose=purpose;if(t.memo!==memo)patch.memo=memo;
   const response=await server('edit',{id:t.id,patch});
   const updated=remoteTrip(response.trip);
   if(run!==generation)return;
   if(!updated)throw 503;
   newRevision=updated.updated;dataVersion++;remote=remote.map(x=>x.id===id?updated:x);
   if(updated.purpose!==purpose||updated.memo!==memo){
    if(state.page==='detail'&&state.detail.id===id){state.detail.purpose=updated.purpose;state.detail.memo=updated.memo;}
    mutation=false;render({top:false});toast('서버의 최신 분류 또는 메모를 표시했어요.');return;
   }
  }else saveLocal(id,purpose,memo);
  if(state.page==='detail'&&state.detail.id===id){state.detail.purpose=purpose;state.detail.memo=memo;}
  mutation=false;render({top:false});
  if(undo||memoOnly){undoEntry=null;clearTimeout(undoTimer);toast(undo?'이전 분류로 되돌렸어요.':'메모를 저장했어요.');}
  else {undoEntry={id,purpose:t.purpose,memo:t.memo,rev:newRevision};showUndo();}
 }catch(err){if(run!==generation)return;if(state.page==='detail'&&state.detail.id===id)state.detail.purpose=t.purpose;render({top:false});toast(err===409?'다른 화면에서 변경됐거나 마감된 기록이에요.':'운행을 저장하지 못했어요.');if(connected)sync();}
 finally{mutation=false;if(pairSyncAgain&&connected&&pendingPair()&&!syncRunning){pairSyncAgain=false;sync(true);}}
}
function saveTrip(){const d=state.detail,t=lookup(d.id);if(t&&t.purpose!=='unclassified')commitPurpose(t.id,t.purpose,d.memo,{memoOnly:true});}
document.getElementById('toast').addEventListener('click',ev=>{
 if(ev.target.closest('[data-action="undo"]')&&undoEntry){
  const u=undoEntry;undoEntry=null;clearTimeout(undoTimer);
  const t=lookup(u.id);
  if(t&&(!connected||t.updated===u.rev))commitPurpose(u.id,u.purpose,u.memo,{undo:true});else toast('기록이 변경되어 되돌릴 수 없어요.');
 }
});
let drag=null;
app.addEventListener('pointerdown',ev=>{const c=ev.target.closest('#swipe-card');if(c&&!ev.target.closest('button')){drag={id:c.dataset.id,x:ev.clientX,y:ev.clientY};try{c.setPointerCapture(ev.pointerId);}catch{}}});
app.addEventListener('pointercancel',()=>{drag=null;});
app.addEventListener('pointerup',ev=>{if(!drag)return;const d=drag;drag=null;const x=ev.clientX-d.x;if(Math.abs(x)>60&&Math.abs(ev.clientY-d.y)<45)commitPurpose(d.id,x>0?'business':'commute');});
function updateReportControls(){
 const valid=validRange(),count=valid?reportRows().length:0;
 document.getElementById('range-error').textContent=valid?'':'시작일과 종료일을 확인해 주세요.';
 document.getElementById('report-count').textContent=valid?'운행 '+count+'건 · 미분류 '+pending(reportRows())+'건':'조회 기간을 설정해 주세요.';
 document.getElementById('preview-btn').disabled=!valid||!count;
}
function csvCell(v,formulaSafe=false){
 let s=String(v??'');
 if(formulaSafe&&/^[\s\uFEFF]*[=+\-@]/.test(s))s="'"+s;
 return '"'+s.replace(/"/g,'""')+'"';
}
function buildCSV(){
 const rows=reportRows(),m=metrics(rows);
 const fmt=n=>Number((n/10).toFixed(3)).toString();
 const content=[
 ['업무용승용차 운행기록부'+(!connected?' (데모)':'')],
 ['법인명',VEHICLE.company,'사업자등록번호',VEHICLE.registration,'사업연도','2026'],
 ['차종',VEHICLE.name,'자동차등록번호',VEHICLE.plate,'시작일',state.range.start,'종료일',state.range.end],
 ['사용일자(요일)','운전자 부서','운전자 성명','주행 전 계기판(km)','주행 후 계기판(km)','주행거리(km)','출퇴근용(km)','일반업무용(km)','분류','비고'],
 ...shown(rows).map(t=>[formatDate(t.date,true),t.department||DRIVER.department,t.driver||DRIVER.name,fmt(t.start),fmt(t.end),fmt(t.distance),fmt(t.purpose==='commute'?t.distance:0),fmt(t.purpose==='business'?t.distance:0),PURPOSE[t.purpose],t.memo]),
 ['선택 기간 합계','','','','',fmt(m.total),fmt(m.commute),fmt(m.business)],
 ['업무용 사용거리 합계(km)',fmt(m.business+m.commute),'업무사용비율',percentage(m)],
 [!connected?'예시 데이터 · 회사 제출용으로 사용하지 마세요.':'회사 내부 확인용']
 ];
 return '\uFEFF'+content.map((row,i)=>row.map((v,j)=>csvCell(v,(i>=4&&(j===1||j===2||j===9))||(i===1&&(j===1||j===3))||(i===2&&(j===1||j===3)))).join(',')).join('\r\n')+'\r\n';
}
function exportReport(mode){
 const file='AutoLogPro_'+state.range.start+'_'+state.range.end+'.csv', csv=buildCSV();
 if(window.CompanionHost&&typeof window.CompanionHost.exportReport==='function'){window.CompanionHost.exportReport(mode,file,csv);return;}
 const blob=new Blob([csv],{type:'text/csv;charset=utf-8'});
 if(mode==='share'){
   const f=new File([blob],file,{type:'text/csv'});
   if(navigator.canShare&&navigator.canShare({files:[f]})){navigator.share({files:[f]}).catch(err=>{if(err.name!=='AbortError')toast('공유할 수 없어요. CSV로 저장해 주세요.');});}
   else toast('이 브라우저는 파일 공유를 지원하지 않아요. CSV 저장을 이용해 주세요.');
   return;
 }
 const url=URL.createObjectURL(blob), a=document.createElement('a');a.href=url;a.download=file;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);
}
app.addEventListener('click',ev=>{
 const b=ev.target.closest('[data-action]');if(!b||b.disabled)return;
 switch(b.dataset.action){
 case 'tab':topTab(b.dataset.page);break;
 case 'back':back();break;
 case 'todo':{const latest=allTrips().find(t=>t.purpose==='unclassified');state.month=latest?latest.date.slice(0,7):MONTH;state.filter='unclassified';go('trips');break;}
 case 'all-trips':state.month=activeMonth();state.filter='all';go('trips');break;
 case 'filter':state.filter=b.dataset.filter;render();break;
 case 'sort':state.ascending=!state.ascending;render();break;
 case 'open-trip':{const t=lookup(b.dataset.id);if(t&&!t.redacted){state.detail={id:t.id,purpose:t.purpose,memo:t.memo};go('detail');}break;}
 case 'save-trip':saveTrip();break;
 case 'period':state.range=monthRange(b.dataset.month);render({top:false});break;
 case 'preview':if(validRange()&&reportRows().length)go('preview');break;
 case 'cloud-save':case 'cloud-share':{
  if(!validRange()||!selectedVehicle){toast('차량과 조회 기간을 확인하세요.');break;}
  host?.cloudReport?.(selectedVehicle,state.range.start,state.range.end,document.getElementById('report-format')?.value||'xlsx',b.dataset.action==='cloud-share');
  break;
 }
 case 'export':exportReport('save');break;
 case 'share':exportReport('share');break;
 case 'sync':sync(true);break;
 case 'configure':host?.nativeAction?.('server');break;
 case 'scan':if(host?.nativeAction)host.nativeAction('scan');else toast('휴대폰 앱에서 스캔할 수 있어요.');break;
 case 'native':host?.nativeAction?.(b.dataset.native);break;
 case 'inbox':go('inbox');break;
 case 'quick':commitPurpose(b.dataset.id,b.dataset.pick);break;
 case 'suggest':{const t=lookup(b.dataset.id),x=suggestion(t);if(x)commitPurpose(t.id,x.purpose,(state.page==='detail'&&state.detail.id===t.id?state.detail.memo:'')||t.memo||x.memo);break;}
 case 'service':{
  const date=document.getElementById('service-date').value,shop=document.getElementById('service-shop').value.trim();
  if(!date||!shop){toast('예약일과 정비소 이름을 입력하세요.');break;}
  prefs.service={date,shop};storeOptions();toast('정비 일정을 저장했어요.');break;
 }
 case 'demo-alert':{const t=nextInbox()||allTrips().find(x=>x.purpose==='unclassified');if(t)host?.demoAlert?.(t.id);else toast('미분류 운행이 없어요.');break;}
 case 'account':case 'company':case 'vehicle':go(b.dataset.action);break;
 case 'logout':go('login');break;
 case 'demo-login':topTab('home');break;
 }
});
app.addEventListener('change',ev=>{
 const t=ev.target;
 if(t.id==='cloud-vehicle'){selectedVehicle=t.value;rememberVehicle();applyVehicle();render({top:false});}
 else if(t.id==='trip-month'){state.month=t.value;render();}
 else if(t.name==='purpose'&&state.page==='detail'){commitPurpose(state.detail.id,t.value,state.detail.memo);}
 else if(t.id==='range-start'||t.id==='range-end'){state.range[t.id==='range-start'?'start':'end']=t.value;updateReportControls();}
});
app.addEventListener('input',ev=>{if(ev.target.id==='memo'&&state.page==='detail'){state.detail.memo=ev.target.value;updateSave();}});
app.addEventListener('submit',ev=>ev.preventDefault());
render();
try{if(host?.takeLaunch)applyLaunch(JSON.parse(host.takeLaunch()));}catch{}
if(connected)sync();

setInterval(()=>{if(connected&&document.visibilityState!=='hidden'&&['home','trips','inbox','report'].includes(state.page))sync();},15000);

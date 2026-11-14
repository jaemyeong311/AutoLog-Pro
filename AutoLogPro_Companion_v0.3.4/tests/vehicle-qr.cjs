'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {JSDOM}=require(process.env.JSDOM_MODULE||'jsdom');
const base=path.resolve(__dirname,'../app/src/main/assets');
const text=n=>fs.readFileSync(path.join(base,n),'utf8');
const first='10000000-0000-4000-8000-000000000001';
const scanned='20000000-0000-4000-8000-000000000002';
const foreign='30000000-0000-4000-8000-000000000003';
const account='40000000-0000-4000-8000-000000000004';
const id='50000000-0000-4000-8000-000000000005';
const now=new Date();
const item={id,vehicle_id:first,start_time:new Date(+now-3600000).toISOString(),end_time:now.toISOString(),distance_km:19,start_odo_km:100,end_odo_km:119,purpose:'business',updated_at:now.toISOString()};
const wait=()=>new Promise(r=>setTimeout(r,15));
function setup(signed=false,saved={}){
 const dom=new JSDOM(text('index.html').replace(/<script[^>]*><\/script>/g,''),{url:'https://appassets.androidplatform.net/index.html',runScripts:'outside-only'});
 const w=dom.window;w.scrollTo=()=>{};
 for(const [k,v] of Object.entries(saved))w.localStorage.setItem(k,v);
 let calls=0;const actions=[];
 w.CompanionHost={connected:()=>signed,nativeAction:x=>actions.push(x),server(op,args,callback){
  calls++;let result;
  if(op==='context')result={user_id:account,role:'employee',company:{name:'테스트 회사'}};
  if(op==='vehicles')result=[{id:first,plate_no:'첫 차량',model:'차량 A'},{id:scanned,plate_no:'선택 차량',model:'차량 B'}];
  if(op==='trips')result={trips:[item],has_more:false};
  queueMicrotask(()=>w.alpNativeEvent('reply',{callback,error:0,result}));
 }};
 w.eval(text('automation.js')+'\n'+text('app.js'));
 return {w,actions,calls:()=>calls,query:x=>w.document.querySelector(x),close:()=>w.close()};
}
(async()=>{
 const p=setup();
 p.query('[data-action=scan]').click();
 assert.deepEqual(p.actions,['scan']);
 p.w.alpNativeEvent('vehicleQr',{id:scanned});
 assert.deepEqual(p.actions,['scan','server']);
 assert.equal(p.calls(),0,'a signed-out scan cannot request records');
 assert.match(p.query('main').textContent,/차량 QR을 확인/);
 const saved=Object.fromEntries(Object.keys(p.w.localStorage).map(k=>[k,p.w.localStorage.getItem(k)]));
 p.close();
 const q=setup(false,saved);
 try{
  q.w.alpNativeEvent('connected',{});await wait();
  assert.equal(q.query('#cloud-vehicle').value,scanned);
  assert.match(q.query('.vehicle-head').textContent,/선택 차량/);
  assert.equal(q.w.document.querySelectorAll('.trip').length,0,'do not show another car’s trips');
  assert.equal(q.w.localStorage.getItem('alp-vehicle-qr-pending'),null);
  q.w.alpNativeEvent('vehicleQr',{id:foreign});await wait();
  assert.equal(q.query('#cloud-vehicle').value,scanned);
  assert.match(q.query('#toast').textContent,/열 수 없는 차량/);
  q.w.alpNativeEvent('vehicleQr',{id:first});await wait();
  assert.equal(q.query('#cloud-vehicle').value,first);
  assert.equal(q.w.document.querySelectorAll('.trip').length,1);
  const persisted=Object.fromEntries(Object.keys(q.w.localStorage).map(k=>[k,q.w.localStorage.getItem(k)]));
  const reopen=setup(true,persisted);
  try{await wait();assert.equal(reopen.query('#cloud-vehicle').value,first);}finally{reopen.close();}
 }finally{q.close();}
 console.log('PASS: scan → login → exact vehicle, no pre-login fetch, inaccessible vehicle rejected, signed-in switch and persistence.');
})().catch(e=>{console.error(e);process.exitCode=1;});

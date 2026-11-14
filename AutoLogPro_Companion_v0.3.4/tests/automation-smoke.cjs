'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {JSDOM}=require(process.env.JSDOM_MODULE||'jsdom');
const base=path.resolve(__dirname,'../app/src/main/assets');
const txt=x=>fs.readFileSync(path.join(base,x),'utf8');
const rules=require(path.join(base,'automation.js'));
const t={id:'1',vehicle:'car-1',date:'2026-11-11',time:'08:40',duration:20,purpose:'unclassified'};
const now=rules.stamp(t);
assert.equal(rules.suggest(t,[t]).purpose,'commute');
assert.equal(rules.suggest(t,[t],[{start:now+40*60000,end:now+60*60000,title:'고객사 미팅'}]).memo,'고객사 미팅');
assert.equal(rules.suggest({...t,place:'대전 오토 정비소'},[t],[],{date:t.date,shop:'오토 정비소'}).purpose,'business');
assert.equal(rules.suggest({...t,place:'모르는 곳',time:'14:00'},[t],[],{date:t.date,shop:'오토 정비소'}),null);
assert.equal(rules.suggest({...t,time:'10:15'},[{...t,id:'2',time:'09:20',purpose:'business'}]).purpose,'business');
const wait=()=>new Promise(r=>setTimeout(r,0));
function start(host={}){
 const dom=new JSDOM(txt('index.html').replace(/<script[^>]*><\/script>/g,''),{url:'https://appassets.androidplatform.net/index.html',runScripts:'outside-only'});
 const w=dom.window;
 w.scrollTo=()=>{};
 w.CompanionHost=host;
 const scr=w.document.createElement('script');scr.textContent=txt('automation.js')+'\n'+txt('app.js');
 w.eval(scr.textContent);
 return {dom,w,el:s=>w.document.querySelector(s),click:s=>w.document.querySelector(s).click()};
}
(async()=>{
 const events=[];
 const demo=start({nativeAction(x){events.push(x);},demoAlert(id){events.push(id);},exportReport(...x){events.push(x);}});
 const {w,el,click}=demo;
 try{
  assert.match(el('h1').textContent,/Auto Log Pro/);
  assert.match(el('.metrics').textContent,/293\.5/);
  click('[data-action=todo]');
  assert.equal(w.document.querySelectorAll('.trip').length,2);
  click('[data-id="TR-20261111-0414"]');
  assert.match(el('.detail-odo').textContent,/52,350\.8.*52,368\.5/);
  click('input[value=business]');
  assert.match(el('.detail-lead').textContent,/일반업무/);
  click('[data-action=undo]');
  assert.match(el('.detail-lead').textContent,/미분류/);
  click('input[value=business]');
  click('[data-action=back]');
  assert.equal(w.document.querySelectorAll('.trip').length,1);
  click('[data-action=inbox]');
  const card=el('#swipe-card');
  card.dispatchEvent(new w.MouseEvent('pointerdown',{bubbles:true,clientX:100,clientY:100}));
  card.dispatchEvent(new w.MouseEvent('pointerup',{bubbles:true,clientX:195,clientY:101}));
  assert.match(el('.empty').textContent,/모두 처리/);
  click('[data-action=undo]');
  assert.ok(el('#swipe-card'));
  click('[data-action=back]');
  click('[data-page=settings]');
  click('[data-native=calendar]');
  click('[data-action=demo-alert]');
  assert.deepEqual(events.slice(0,2),['calendar','TR-20261110-2044']);
  el('#service-date').value='2026-11-10';el('#service-shop').value='오토 정비소';
  click('[data-action=service]');
  click('[data-page=trips]');
  click('[data-id="TR-20261110-2044"]');
  assert.match(el('.suggest').textContent,/등록한 정비소 방문/);
  click('[data-action=suggest]');
  assert.match(el('#memo').value,/정비 입고/);
  click('[data-action=back]');
  click('[data-page=report]');
  click('[data-action=preview]');
  click('[data-action=export]');
  assert.match(events.at(-1)[2],/업무용승용차 운행기록부/);
  assert.doesNotMatch(events.at(-1)[2],/개인용/);
 }finally{demo.dom.window.close();}
 const car='10000000-0000-4000-8000-000000000001',uid='20000000-0000-4000-8000-000000000002';
 const id='30000000-0000-4000-8000-000000000003';
 const updatedAt='2026-11-14T04:50:00.123456+00:00';
 const item={id,vehicle_id:car,start_time:new Date(Date.now()-500000).toISOString(),end_time:new Date(Date.now()-200000).toISOString(),start_odo_km:500.25,end_odo_km:514.25,distance_km:14,purpose:'unclassified',memo:'',updated_at:updatedAt,source:'GEAR'};
 let synced={...item};const calls=[];let edits=0;
 const cloud=start({connected:()=>true,cloudReport(...args){calls.push(args);},server(op,params,callback){
  const args=JSON.parse(params);let result;
  if(op==='context')result={user_id:uid,role:'admin',company:{name:'테스트 회사'},display_name:'직원',department:{name:'개발팀'}};
  if(op==='trips')result={trips:[synced],has_more:false,next_cursor:{updated_at:synced.updated_at,id}};
  if(op==='vehicles')result=[{id:car,plate_no:'90가 1234',model:'아반떼 하이브리드'}];
  if(op==='deliveries')result=[{period_start:'2026-10-01',period_end:'2026-10-31',channel:'email',status:'sent'}];
  if(op==='edit'){
   assert.deepEqual(Object.keys(args).sort(),['id','patch']);assert.equal(args.id,id);
   assert.deepEqual(Object.keys(args.patch),['purpose']);
   synced={...synced,...args.patch,updated_at:'2026-11-14T04:50:00.12345'+(++edits)+'+00:00'};
   result={trip:synced,applied:{purpose:true}};
  }
  queueMicrotask(()=>cloud.w.alpNativeEvent('reply',{callback,error:0,result}));
 }});
 try{
  await wait();
  assert.match(cloud.el('.vehicle-head').textContent,/90가 1234/);
  cloud.click('[data-id="'+id+'"]');
  cloud.click('input[value=business]');
  await wait();
  assert.equal(synced.purpose,'business');assert.equal(synced.start_odo_km,500.25);
  cloud.click('[data-action=undo]');
  await wait();assert.equal(synced.purpose,'unclassified');
  cloud.click('[data-action=back]');
  cloud.click('[data-page=report]');
  assert.match(cloud.el('main').textContent,/발송 완료/);
  cloud.click('[data-action=cloud-save]');
  assert.equal(calls[0][0],car);assert.equal(calls[0][3],'xlsx');
 }finally{cloud.dom.window.close();}
 console.log('PASS: demo and linked flows, one-tap save/undo, swipe, calendar/maintenance rules, private aggregate, report and notifications bridge.');
})().catch(e=>{console.error(e);process.exitCode=1;});

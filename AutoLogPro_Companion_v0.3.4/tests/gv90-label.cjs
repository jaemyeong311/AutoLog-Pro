'use strict';
const assert=require('node:assert/strict');
const {JSDOM}=require(process.env.JSDOM_MODULE||'jsdom');
const {readFileSync}=require('node:fs');
const {resolve}=require('node:path');
const base=resolve(__dirname,'../app/src/main/assets');
const read=name=>readFileSync(resolve(base,name),'utf8');
const dom=new JSDOM(read('index.html').replace(/<script[^>]*><\/script>/g,''),{url:'https://appassets.androidplatform.net/index.html',runScripts:'outside-only'});
const w=dom.window;
w.scrollTo=()=>{};
const target='c6a0145d-06e8-49e4-8ab1-62ba1c0aad89';
const other='10000000-0000-4000-8000-000000000001';
w.CompanionHost={connected:()=>false,server(op,_args,callback){
 const result={
 context:{user_id:'20000000-0000-4000-8000-000000000002',role:'employee',company:{name:'시연'}},
 vehicles:[{id:target,plate_no:'12가3456',model:'아반떼 하이브리드'},{id:other,plate_no:'90가1111',model:'다른 차량'}],
 trips:{trips:[],has_more:false}
 }[op];
 queueMicrotask(()=>w.alpNativeEvent('reply',{callback,error:0,result}));
}};
w.eval(read('automation.js')+'\n'+read('app.js'));
(async()=>{
 try {
  const q=s=>w.document.querySelector(s);
  assert.equal(q('.vehicle-head h2').textContent,'제네시스 GV90');
  assert.equal(q('.car-img').getAttribute('src'),'genesis_gv90.png');
  w.alpNativeEvent('connected',{});
  await new Promise(r=>setTimeout(r,30));
  q('[data-page=home]').click();
  assert.equal(q('.vehicle-head h2').textContent,'제네시스 GV90');
  assert.ok(q('.car-img'));
  assert.match(q('#cloud-vehicle').textContent,/제네시스 GV90/);
  assert.doesNotMatch(q('#cloud-vehicle').textContent,/아반떼/);
  q('#cloud-vehicle').value=other;
  q('#cloud-vehicle').dispatchEvent(new w.Event('change',{bubbles:true}));
  assert.equal(q('.vehicle-head h2').textContent,'다른 차량');
  assert.equal(q('.car-img'),null);
  console.log('PASS: GV90 offline and server-backed display, real IDs and other vehicle labels retained.');
 }finally{w.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});

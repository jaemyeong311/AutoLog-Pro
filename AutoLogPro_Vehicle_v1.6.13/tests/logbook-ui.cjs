const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
(async()=>{
 const b=await chromium.launch({executablePath:process.env.CHROME_HEADLESS||undefined,args:['--no-sandbox']});
 try {
 const page=await b.newPage({viewport:{width:1050,height:620}});
 const errors=[];page.on('pageerror',err=>errors.push(err.message));
 await page.addInitScript(()=>{
   let voiceClassify=false;
   const profile={model:'현대 아반떼 하이브리드'};
   const mk=(id,month,purpose,dist,driver)=>({id,year:2026,month,day:13,closed:true,date:'11.13 (금) 18:00',fullDate:'2026.11.13 (금) 18:00',dateIso:'2026-'+String(month).padStart(2,'0')+'-13',route:'목적지',dist,time:'25분',startOdo:100,endOdo:100+dist,purpose,driver,driverDepartment:'영업팀'});
   const trips=[mk(1,11,'business',70,'김운전'),mk(2,11,'personal',10,'숨겨진 개인운전자'),mk(3,10,'commute',20,'이운전')];
   window.AutoLogNative={
     getTheme:()=> 'light', toggleVoiceClassify:()=>{voiceClassify=!voiceClassify;window.refreshFromAndroid();}, getState:()=>JSON.stringify({voiceClassify,profile,reportProfile:profile,reportFiscalStart:'2026-01-01',reportFiscalEnd:'2026-12-31',reportDemo:false,trips,maintenance:[],odometer:200,driver:profile.driver||'미입력'}),
     saveLogbookProfile: v=>{Object.assign(profile,JSON.parse(v));return true;},
     setReportPeriod:()=>{}, classify:(id,purpose)=>{trips.find(t=>t.id===id).purpose=purpose==='excluded'?'personal':purpose;return true;}
   };
 });
 await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/index.html')).href);
 await page.waitForFunction(()=>document.querySelector('.car-render-img').naturalWidth>0);
 await page.evaluate(()=>navigateTo('settings'));
 assert.equal(await page.locator('#setting-voice-status').textContent(),'꺼짐');
 await page.getByRole('button',{name:/음성으로 운행 목적 분류/}).click();
 assert.equal(await page.locator('#setting-voice-status').textContent(),'켜짐');
 await page.getByText('회사·차량 정보',{exact:true}).click();
 await page.locator('#profile-company').fill('테스트 법인');
 await page.locator('#profile-businessNo').fill('123-45-67890');
 await page.locator('#profile-department').fill('영업팀');
 await page.locator('#profile-driver').fill('김운전');
 await page.locator('#profile-plate').fill('12가 3456');
 await page.getByRole('button',{name:'정보 저장'}).click();
 assert.equal(await page.locator('#settings-logbook-profile').textContent(),'테스트 법인');
 await page.evaluate(()=>navigateTo('reports'));
 await page.locator('#month-input').evaluate(e=>{e.value='2026-11';e.dispatchEvent(new Event('change'));});
 assert.equal(await page.locator('#report-stat-total').textContent(),'100 km');
 assert.equal(await page.locator('#report-stat-biz').textContent(),'90 km');
 assert.equal(await page.locator('#report-stat-ratio').textContent(),'90.0%');
 assert.equal(await page.locator('#report-table-body tr').count(),1);
 assert.match(await page.locator('#report-table-body').textContent(),/영업팀/);
 assert.doesNotMatch(await page.locator('#report-table-body').textContent(),/숨겨진/);
 await page.evaluate(()=>{navigateTo('logs');setLogFilter('all');});
 assert.equal(await page.locator('#logs-table-body tr').count(),1);
 assert.equal(await page.locator('[data-filter=personal]').count(),0);
 await page.evaluate(()=>openTripDetail(1));
 await page.locator('[data-val=excluded]').click();
 await page.evaluate(()=>saveTripPurpose());
 await page.evaluate(()=>navigateTo('reports'));
 await page.waitForTimeout(420);
 assert.equal(await page.locator('#report-stat-total').textContent(),'100 km');
 assert.equal(await page.locator('#report-stat-biz').textContent(),'20 km');
 assert.deepEqual(errors,[]);
 if(process.env.VEHICLE_SCREENSHOT) await page.screenshot({path:process.env.VEHICLE_SCREENSHOT});
 console.log('PASS: Avante asset, company/driver form, monthly detail, annual totals, and excluded trips');
 }finally{await b.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});

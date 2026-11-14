/* Original UI supplied by user; dynamic data and native actions only. */
(function(){
const native=window.AutoLogNative || null;
const originalRender=renderAllViews;
const originalStats=getCalculatedStats;
const originalCount=getUnclassifiedCount;
const now=new Date();let [year,month]=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit'}).formatToParts(now).filter(p=>p.type==='year'||p.type==='month').reduce((r,p)=>(r[p.type==='year'?0:1]=Number(p.value),r),[]);
let maintenance=[],odometer=0,driver='미입력',accelDemoAvailable=false,accelDemoEnabled=false,profile={},reportProfile={},fiscalStart='',fiscalEnd='',reportDemo=true,voiceClassify=false,automationStatus='서버 미연결',cloudConnected=false;
const el=id=>document.getElementById(id);
const number=n=>Number(n||0).toLocaleString('ko-KR',{maximumFractionDigits:1});
const fmt=n=>Number(n||0).toFixed(1).replace(/\.0$/,'');
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const trips=()=>appState.trips.filter(t=>t.closed&&t.year===year&&t.month===month);
const visible=rows=>rows.filter(t=>t.purpose!=='personal');
const fiscalTrips=()=>appState.trips.filter(t=>t.closed&&t.dateIso>=fiscalStart&&t.dateIso<=fiscalEnd);
const work=t=>t.purpose==='business'||t.purpose==='commute';
const sum=rows=>rows.reduce((n,t)=>n+t.dist,0);
const distance=(p)=>trips().filter(t=>t.purpose===p).reduce((s,t)=>s+t.dist,0);
function summary(){let total=trips().reduce((s,t)=>s+t.dist,0),biz=distance('business')+distance('commute');return {totalKm:total,bizKm:biz,ratio:(total?biz/total*100:0).toFixed(1)};}
window.getCalculatedStats=function(){return native?summary():originalStats();};
window.getUnclassifiedCount=function(){return native?trips().filter(t=>t.purpose==='unclassified').length:originalCount();};
function badge(p){const x=purposeMap[p]||purposeMap.unclassified;return `<span class="tag-badge ${x.badgeClass}">${x.label}</span>`;}
function refresh(){if(!native){originalRender();return;}try{const state=JSON.parse(native.getState());appState.trips=state.trips;maintenance=state.maintenance;odometer=state.odometer;driver=state.driver;profile=state.profile||{};reportProfile=state.reportProfile||{};fiscalStart=state.reportFiscalStart;fiscalEnd=state.reportFiscalEnd;reportDemo=state.reportDemo;voiceClassify=!!state.voiceClassify;automationStatus=state.automationStatus||'서버 미연결';cloudConnected=!!state.cloudConnected;window.coreStatus=state.detectMode||'NONE';accelDemoAvailable=!!state.accelDemoAvailable;accelDemoEnabled=!!state.accelDemoEnabled;}catch(e){console.error(e);}
const unclassified=getUnclassifiedCount();
if(el('home-unclass-btn-text'))el('home-unclass-btn-text').textContent=unclassified?'미분류 운행 '+unclassified+'건 확인':'미분류 운행 없음';
// A native refresh may rerender data while a filter is selected. Keep visual selection
// and dataset based on the same appState.activeFilter (the original HTML starts on 미분류).
document.querySelectorAll('.filter-pill').forEach(pill => {
  const selected=pill.dataset.filter===appState.activeFilter;
  pill.classList.toggle('active',selected);
  pill.setAttribute('aria-selected',String(selected));
});
const s=summary();
if(el('automation-status'))el('automation-status').textContent=automationStatus;
const voiceStatus=el('setting-voice-status');
if(voiceStatus)voiceStatus.textContent=voiceClassify?'켜짐':'꺼짐';
const accelStatus=el('setting-accel-demo-status');
if(accelStatus){accelStatus.textContent=!accelDemoAvailable?'디버그 빌드 전용':accelDemoEnabled?'사용 중 · 추정거리':'사용 안 함';accelStatus.style.color=accelDemoEnabled?'#346a9b':'var(--text-secondary)';}
const autoStatus=el('setting-autolog-status');if(autoStatus)autoStatus.textContent=window.coreStatus==='GEAR'?'기어 신호 구독 중 (값 확인 필요)':window.coreStatus==='FALLBACK'?'대체 신호 구독 중 (값 확인 필요)':'차량 신호 미확인 · 시연 가능';
const annual=fiscalTrips(),yearTotal=sum(annual),yearWork=sum(annual.filter(work));
const vals=[['home-stat-total',s.totalKm],['home-stat-biz',s.bizKm],['report-stat-total',yearTotal],['report-stat-biz',yearWork]];
vals.forEach(([id,v])=>{if(el(id))el(id).textContent=number(v)+' km';});
if(el('home-stat-ratio'))el('home-stat-ratio').textContent=s.ratio+'%';
if(el('report-stat-ratio'))el('report-stat-ratio').textContent=(yearTotal?yearWork*100/yearTotal:0).toFixed(1)+'%';
if(el('report-sheet-period'))el('report-sheet-period').textContent='사업연도 '+fiscalStart+' ~ '+fiscalEnd;
if(el('report-sheet-title'))el('report-sheet-title').textContent='업무용승용차 운행기록부'+(reportDemo?' · 시연용':'');
if(el('report-sheet-meta'))el('report-sheet-meta').textContent=[reportProfile.company||'법인명 미입력',reportProfile.businessNo||'사업자등록번호 미입력',reportProfile.model||'제네시스 GV90',reportProfile.plate||'자동차등록번호 미입력'].join(' · ');
if(el('home-vehicle-model'))el('home-vehicle-model').textContent=profile.model||'제네시스 GV90';
if(el('maintenance-vehicle'))el('maintenance-vehicle').textContent=[profile.model,profile.plate].filter(Boolean).join(' · ');
if(el('settings-logbook-profile'))el('settings-logbook-profile').textContent=profile.company||'설정하기 ›';
const caption=document.querySelector('.demo-caption');if(caption)caption.style.display=reportDemo?'':'none';
const odo=document.querySelector('.odo-number');if(odo)odo.textContent=number(odometer);
if(el('home-unclassified-count'))el('home-unclassified-count').textContent=getUnclassifiedCount()+'건';
const rows=visible(appState.trips.filter(t=>t.closed));
if(el('home-recent-table-body'))el('home-recent-table-body').innerHTML=rows.slice(0,3).map(t=>`<tr class="clickable" onclick="openTripDetail(${t.id})"><td>${esc(t.date)}</td><td style="font-weight:500;color:white">${esc(t.route)}</td><td>${fmt(t.dist)} km</td><td>${esc(t.time)}</td><td>${badge(t.purpose)}</td></tr>`).join('');
let filtered=visible(trips());if(appState.activeFilter!=='all')filtered=filtered.filter(t=>t.purpose===appState.activeFilter);
if(el('logs-table-body'))el('logs-table-body').innerHTML=filtered.map(t=>`<tr class="clickable" onclick="openTripDetail(${t.id})"><td style="width:150px">${esc(t.date)}</td><td style="font-weight:500;color:white">${esc(t.route)}</td><td style="width:100px">${fmt(t.dist)} km</td><td style="width:100px">${esc(t.time)}</td><td style="width:120px">${badge(t.purpose)}</td><td style="width:60px;text-align:right">›</td></tr>`).join('')||'<tr><td colspan="6">기록이 없습니다.</td></tr>';
if(el('report-table-body'))el('report-table-body').innerHTML=visible(trips().filter(t=>t.dateIso>=fiscalStart&&t.dateIso<=fiscalEnd)).map(t=>`<tr class="clickable" onclick="openTripDetail(${t.id})"><td>${esc(t.date)}</td><td>${esc(t.driverDepartment)}</td><td>${esc(t.driver)}</td><td>${number(t.startOdo)}</td><td>${number(t.endOdo)}</td><td>${fmt(t.dist)}</td><td>${t.purpose==='commute'?fmt(t.dist):'—'}</td><td>${t.purpose==='business'?fmt(t.dist):'—'}</td><td>${badge(t.purpose)}</td></tr>`).join('')||'<tr><td colspan="9">표시할 운행이 없습니다.</td></tr>';
if(el('report-unclass-count'))el('report-unclass-count').textContent=getUnclassifiedCount()+'건';
if(el('report-warning-card'))el('report-warning-card').style.display=getUnclassifiedCount()?'block':'none';
['stat-screen-total','stat-screen-biz','stat-screen-ratio'].forEach((id,i)=>{if(el(id))el(id).innerHTML=(i===0?number(s.totalKm):i===1?number(s.bizKm):s.ratio)+` <span style="font-size:20px;font-weight:500;color:var(--text-secondary)">${i===2?'%':'km'}</span>`;});
const cards=el('view-stats')?.querySelectorAll('.stat-card-highlight');
if(cards?.length===3){
  const trend=cards[0].lastElementChild;
  if(trend)trend.textContent='저장된 운행기록 기준';
  const unitSummary=cards[2].lastElementChild;
  if(unitSummary)unitSummary.textContent=`업무용 ${number(s.bizKm)}km / 총 ${number(s.totalKm)}km`;
}
const categories=[distance('business'),distance('commute'),distance('unclassified')];
const blocks=document.querySelectorAll('.stats-content-grid .chart-card:nth-child(2) > div:nth-child(2) > div');blocks.forEach((b,i)=>{const val=b.querySelector('.stats-purpose-value'),bar=b.querySelector(':scope > div:nth-child(2) > div');const pct=s.totalKm?categories[i]/s.totalKm*100:0;if(val)val.textContent=`${number(categories[i])} km (${pct.toFixed(1)}%)`;if(bar)bar.style.width=pct+'%';});
const weeks=[0,0,0,0];trips().forEach(t=>weeks[Math.min(3,Math.floor((t.day-1)/7))]+=t.dist);const maximum=Math.max(...weeks,1);document.querySelectorAll('.bar-chart-mock .bar-col').forEach((b,i)=>{const v=b.querySelector('.bar-val-text'),f=b.querySelector('.bar-fill');if(v)v.textContent=fmt(weeks[i])+'km';if(f)f.style.height=Math.max(2,weeks[i]/maximum*87)+'%';});
if(el('maint-table-body'))el('maint-table-body').innerHTML=maintenance.map(m=>`<tr><td>${esc(m.date)}</td><td class="maint-item-cell" title="${esc(m.description)} · ${esc(m.shop)}"><span class="maint-item-main">${esc(m.description)}</span><span class="maint-shop-subline">${esc(m.shop || '업체 미입력')}</span></td><td>${number(m.odo)} km</td><td>₩ ${Number(m.cost).toLocaleString()}</td><td><span class="receipt-unavailable">미첨부</span></td></tr>`).join('');
const ms=document.querySelectorAll('.maint-stat-box .maint-stat-val');if(ms.length===3){ms[0].textContent=maintenance[0]?.date||'-';ms[1].innerHTML='<span class="maint-next-distance">'+number((maintenance[0]?.odo||odometer)+5000)+' km</span><span class="maint-next-note">시연용 권장값</span>';ms[2].textContent='₩ '+maintenance.filter(e=>e.year===now.getFullYear()).reduce((a,e)=>a+e.cost,0).toLocaleString();}
if(el('current-driver-name'))el('current-driver-name').textContent=[profile.department,driver].filter(Boolean).join(' · ');
document.querySelectorAll('.month-picker span').forEach(e=>e.textContent=`${year}년 ${month}월`);
}

// App-only appearance preference (NOT a Pleos system theme change).
// Native SharedPreferences prevents the selection from being lost when WebView restarts.
function savedAppearance(){
  try { if(native?.getTheme) return native.getTheme()==='dark'?'dark':'light'; } catch(e) {}
  try { return localStorage.getItem('autolog-appearance')==='dark'?'dark':'light'; } catch(e) {}
  return 'light';
}
function applyAppearance(mode){
  const theme=mode==='light'?'light':'dark';
  document.documentElement.setAttribute('data-theme',theme);
  const l=el('theme-light'),d=el('theme-dark');
  if(l){l.classList.toggle('selected',theme==='light');l.setAttribute('aria-pressed',String(theme==='light'));}
  if(d){d.classList.toggle('selected',theme==='dark');d.setAttribute('aria-pressed',String(theme==='dark'));}
  const meta=document.querySelector('meta[name="theme-color"]');if(meta)meta.content=theme==='light'?'#f6f7f8':'#121417';
  return theme;
}
window.setAppearance=function(mode){
  const theme=applyAppearance(mode);
  try { if(native?.setTheme) native.setTheme(theme); else localStorage.setItem('autolog-appearance',theme); }catch(e){}
};

window.renderAllViews=refresh;window.refreshFromAndroid=refresh;
window.saveTripPurpose=function(){if(!appState.activeTripId)return;const purpose=appState.selectedPurpose;if(native&&!native.classify(appState.activeTripId,purpose)){showToast('저장에 실패했습니다.');return;}const t=appState.trips.find(t=>t.id===appState.activeTripId);if(t)t.purpose=purpose==='excluded'?'personal':purpose;closeDrawer('drawer-log-detail');refresh();showToast(purpose==='excluded'?'목록에서 제외 · 총주행거리에 반영':'운행 목적을 저장했습니다.');};
window.saveCloudTripMemo=function(){
  if(!cloudConnected||!appState.activeTripId)return;
  native?.saveCloudMemo(appState.activeTripId,el('drawer-sync-memo')?.value||'');
};
window.openTripDetail=function(id){const t=appState.trips.find(x=>x.id===id);if(!t||t.purpose==='personal')return;appState.activeTripId=id;appState.selectedPurpose=t.purpose;el('drawer-trip-title').textContent=`${t.fullDate} (${fmt(t.dist)} km / ${t.time})`;el('drawer-trip-route').textContent=t.route;el('drawer-odo-start').textContent=number(t.startOdo)+' km';el('drawer-odo-end').textContent=number(t.endOdo)+' km';el('drawer-driver-name').textContent=[t.driverDepartment,t.driver].filter(Boolean).join(' · ');if(el('trip-endplace'))el('trip-endplace').value=t.endPlace||'';if(el('drawer-sync-memo'))el('drawer-sync-memo').value=t.syncMemo||'';if(el('drawer-sync-memo-group'))el('drawer-sync-memo-group').hidden=!cloudConnected;updatePurposeBtnStyles();openDrawer('drawer-log-detail');};

const profileFields=['company','businessNo','periodStart','periodEnd','model','plate','department','driver'];
window.openLogbookProfile=function(){
  el('logbook-profile-error').textContent='';
  profileFields.forEach(k=>el('profile-'+k).value=profile[k]||(k==='model'?'제네시스 GV90':''));
  openDrawer('drawer-logbook-profile');
};
window.saveLogbookProfile=function(event){
  event.preventDefault();
  const form=Object.fromEntries(profileFields.map(k=>[k,el('profile-'+k).value.trim()]));
  if(!native?.saveLogbookProfile(JSON.stringify(form))){
    el('logbook-profile-error').textContent='주차 상태와 사업자등록번호 10자리, 사업연도 날짜를 확인해 주세요.';
    return;
  }
  closeDrawer('drawer-logbook-profile');refresh();showToast('운행기록부 정보를 저장했습니다.');
};
window.toggleAutoLog=function(){showToast('A v3 차량 상태: '+(window.coreStatus||'NONE')+' · 자동 기록 모드는 이 메뉴에서 변경할 수 없습니다.');};
window.viewReceipt=function(){showToast('영수증 저장·열람은 아직 구현되지 않았습니다.');};
window.saveMaintenance=function(){let desc=el('maint-desc').value.trim(),odo=Number(el('maint-odo').value),cost=Number(el('maint-cost').value),date=el('maint-date').value,shop=el('maint-shop').value;if(!desc||!Number.isFinite(odo)||odo<0||!Number.isFinite(cost)||cost<0){showToast('정비 항목·거리·비용을 확인해 주세요.');return;}if(native){if(!native.addMaintenance(date,desc,odo,cost,shop)){showToast('정비 기록 저장 실패');return;}}closeDrawer('drawer-add-maint');refresh();showToast('정비 기록 저장 완료');};
window.startDemo=function(){if(!native){showToast('Android 앱에서 실행 가능합니다.');return;}if(native.startDemo())showDriving();else showToast('이미 주행 중입니다.');};
window.showDriving=function(){
  let d=el('driving-overlay');
  if(!d){
    d=document.createElement('div');
    d.id='driving-overlay';
    d.setAttribute('role','status');
    d.setAttribute('aria-label','시연 주행 진행 상태');
    d.innerHTML='<div class="driving-status-card">'
      +'<div class="driving-status-eyebrow"><span class="driving-status-indicator" aria-hidden="true"></span>시연 주행</div>'
      +'<strong>운행 기록 중</strong>'
      +'<p class="driving-status-description">주행 기록을 생성하고 있습니다.<br>약 10초 뒤 자동으로 종료됩니다.</p>'
      +'<div class="driving-status-progress" aria-hidden="true"><span class="driving-status-progress-fill"></span></div>'
      +'<span class="driving-status-caption">시연 데이터는 실제 차량 운행과 별도로 저장됩니다.</span>'
      +'</div>';
    el('main-viewport').appendChild(d);
  }
  // Restart the 10-second progress animation on each demo run.
  const bar=d.querySelector('.driving-status-progress-fill');
  if(bar){bar.style.animation='none';void bar.offsetWidth;bar.style.animation='';}
  d.style.display='flex';
};
window.toggleVoiceClassify=function(){if(native?.toggleVoiceClassify)native.toggleVoiceClassify();else showToast('차량 앱에서만 사용할 수 있어요.');};
window.toggleAccelDemo=function(){if(!native){showToast('에뮬레이터에서만 사용할 수 있습니다.');return;}if(!accelDemoAvailable){showToast('디버그 전용 기능입니다.');return;}native.toggleAccelDemo();setTimeout(refresh,120);};
window.stopDriving=function(){const d=el('driving-overlay');if(d)d.style.display='none';refresh();};
window.previewReport=function(){native?native.previewReport():showToast('Android에서 이용 가능합니다.');};
window.exportReport=function(){native?native.exportCsv():showToast('Android에서 이용 가능합니다.');};
window.showQrReport=function(){native?native.openQr():showToast('Android에서 이용 가능합니다.');};
window.saveDestination=function(){if(native&&appState.activeTripId){native.saveDestination(appState.activeTripId,el('trip-endplace').value);showToast('도착지 저장');refresh();}};
window.requestCorrection=function(){if(native&&appState.activeTripId){const v=Number(el('trip-odo').value),r=el('trip-reason').value.trim();if(!r||!v){showToast('정정 거리와 사유를 입력해 주세요.');return;}native.requestCorrection(appState.activeTripId,v,r);showToast('정정 요청이 저장되었습니다.');}};
function extraControls(){const drawer=el('drawer-log-detail')?.querySelector('.drawer-body');if(drawer){const f=document.createElement('div');f.className='form-group';f.innerHTML='<div class="form-label">도착지</div><input id="trip-endplace" class="form-input" placeholder="도착지"><button class="btn-view-all" style="height:42px;margin-top:8px" onclick="saveDestination()">도착지 저장</button><div class="form-label" style="margin-top:16px">원본거리 정정 요청 (원본값 유지)</div><input type="number" class="form-input" id="trip-odo" placeholder="종료 계기판 거리"><input id="trip-reason" class="form-input" style="margin-top:8px" placeholder="정정 사유"><button class="btn-view-all" style="height:42px;margin-top:8px" onclick="requestCorrection()">정정 요청</button>';drawer.appendChild(f);}
const form=el('drawer-add-maint');if(form){const inputs=form.querySelectorAll('.form-input');if(inputs.length>=4){inputs[0].id='maint-date';inputs[0].value=new Date().toISOString().slice(0,10);inputs[1].id='maint-desc';inputs[2].id='maint-odo';inputs[3].id='maint-cost';}const save=form.querySelector('.btn-primary-action');if(save)save.onclick=saveMaintenance;const footer=form.querySelector('.drawer-body');const extra=document.createElement('div');extra.className='form-group';extra.innerHTML='<label class="form-label">정비업체</label><input class="form-input" id="maint-shop" placeholder="업체 이름">';footer.insertBefore(extra,footer.lastElementChild);}
const home=el('view-home');const actions=document.createElement('button');actions.textContent='▶ 시연 주행';actions.className='demo-native';actions.onclick=startDemo;home.appendChild(actions);
const reports=el('view-reports');const qr=document.createElement('button');qr.className='demo-qr';qr.textContent='QR 내보내기';qr.onclick=showQrReport;reports.querySelector('.report-side-panel')?.appendChild(qr);
const settings=el('view-settings');const button=document.createElement('button');button.textContent='시연용 기록 초기화';button.className='demo-reset';button.onclick=()=>{if(native&&confirm('시연용 기록을 초기화할까요?')){native.resetDemo();refresh();}};settings.querySelector('.settings-list-card')?.appendChild(button);
const previews=reports.querySelectorAll('.sub-header button');if(previews[0])previews[0].onclick=previewReport;if(previews[1])previews[1].onclick=exportReport;
document.querySelectorAll('.odo-value-row').forEach(e=>e.onclick=()=>showToast('계기판 원본값은 앱에서 직접 수정할 수 없습니다.'));

document.querySelectorAll('.month-picker').forEach(e=>{e.onclick=()=>{const input=el('month-input');try{input.showPicker();}catch(_){input.focus();input.click();}}});const input=document.createElement('input');input.type='month';input.id='month-input';input.value=`${year}-${String(month).padStart(2,'0')}`;input.style.cssText='position:absolute;right:1px;top:50px;opacity:0;width:1px;height:1px;z-index:-1';input.onchange=()=>{const arr=input.value.split('-');year=Number(arr[0]);month=Number(arr[1]);if(native?.setReportPeriod)native.setReportPeriod(year,month);refresh();};document.body.appendChild(input);
}
window.addEventListener('DOMContentLoaded',()=>{applyAppearance(savedAppearance());extraControls();refresh();});
})();

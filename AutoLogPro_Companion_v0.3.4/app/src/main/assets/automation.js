'use strict';
const ALPRules = (() => {
 const minutes=t=>{const [h,m]=t.time.split(':').map(Number);return h*60+m;};
 const stamp=t=>Date.parse(t.date+'T'+t.time+':00+09:00');
 function suggest(t,rows,events=[],service=null){
  const ts=stamp(t), end=ts+t.duration*60000;
  if(service&&service.date===t.date&&service.shop&&t.place&&t.place.toLowerCase().includes(service.shop.toLowerCase()))
   return {purpose:'business',label:'등록한 정비소 방문',memo:'정비 입고 · '+service.shop};
  const meetings=events.filter(e=>e.start<end+45*60000&&e.end>=ts-10*60000&&/(거래처|고객사|미팅|회의|현장|납품|외근|출장|업무|회의|점검)/.test(e.title)&&!/(개인|가족|친구|병원)/.test(e.title));
  if(meetings.length===1)return {purpose:'business',label:'업무 일정과 시간 일치',memo:meetings[0].title};
  const same=rows.filter(x=>x.id!==t.id&&x.date===t.date&&x.vehicle===t.vehicle&&stamp(x)<ts).sort((a,b)=>stamp(b)-stamp(a));
  if(!same.length&&minutes(t)>=360&&minutes(t)<630&&new Date(ts+9*3600000).getUTCDay()%6!==0)
   return {purpose:'commute',label:'평일 출근 시간대 첫 운행',memo:''};
  const prev=same[0];
  if(prev&&['business','commute'].includes(prev.purpose)){
   const gap=ts-stamp(prev)-prev.duration*60000;
   if(gap>=0&&gap<=90*60000)return {purpose:prev.purpose,label:'같은 차량의 직전 운행 목적',memo:''};
  }
  return null;
 }
 return {stamp,suggest};
})();
if(typeof module!=='undefined')module.exports=ALPRules;

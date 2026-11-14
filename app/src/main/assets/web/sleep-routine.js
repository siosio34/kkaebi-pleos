'use strict';
(function(root){
 const order=['seatRecline','blind','hvacPower','temperature','fan','seatVentilation','brightness','voice','restSound','window'];
 const labels={seatRecline:'시트 자세',blind:'블라인드',hvacPower:'공조 전원',temperature:'온도',fan:'풍량',seatVentilation:'시트 통풍',brightness:'실내 조명',voice:'음성 안내',restSound:'휴식 소리',window:'창문'};
 const working=new Set(['checking','writing','verifying']);
 const confirmed=new Set(['applied','restored','manualPreserved']);
 const states={queued:'대기',checking:'현재 상태 확인',writing:'조절 중',verifying:'결과 확인 중',applied:'설정 확인',restored:'복원 확인',manualPreserved:'직접 바꾼 값 유지',failed:'확인 필요',blocked:'선행 설정 미확인',pendingRestore:'복원 확인 필요',stopped:'중지됨',unsupported:'직접 조절',unknown:'지원 확인 중',unsaved:'위치 저장 필요',invalid:'설정값 확인',unselected:'현재 유지',manual:'직접 닫기'};
 const failed=new Set(['failed','blocked','pendingRestore','stopped']);
 const groupDefinitions=[{id:'motor',label:'자세·차광',keys:['seatRecline','blind']},{id:'environment',label:'빛·통풍',keys:['brightness','seatVentilation']},{id:'hvac',label:'공조',keys:['hvacPower','temperature','fan']}];
 function currentSteps(info,baseline){
  const id=info.controlOperationId,session=info.restSession;
  if(!id||id===baseline||session&&Object.hasOwn(session,'controlOperationId')&&session.controlOperationId!==id)return [];
  const expected=session?.phase==='waking'?'restore':['preparing','resting'].includes(session?.phase)?'apply':null;
  if(expected&&info.controlOperation!==expected)return [];
  return (info.controlSteps||[]).filter(s=>s.operationId===id);
 }
 function freshPosition(row,now=Date.now()){
  return row.progressSource==='sdk-position'&&Number.isFinite(row.observed)&&Number.isFinite(row.observedAt)&&now>=row.observedAt&&now-row.observedAt<=5000;
 }
 function meter(row,now=Date.now()){
  if(confirmed.has(row.state))return {kind:'confirmed',value:1,label:states[row.state]};
  if(!working.has(row.state))return {kind:'none',value:0,label:states[row.state]||'대기'};
  if(freshPosition(row,now)&&Number.isFinite(row.positionProgress))return {kind:'position',value:Math.max(0,Math.min(1,row.positionProgress)),label:row.positionProgress>=1?'목표 위치 도달 · 결과 확인 중':'차량에서 읽은 이동 위치'};
  return {kind:'indeterminate',value:null,label:states[row.state]};
 }
 function rows(info,plan,baseline){
  const actual=currentSteps(info,baseline),byKey=new Map(actual.map(x=>[x.key,x]));
  const source=info.controlOperation==='restore'&&actual.length?[]:plan;
  const result=source.map(row=>byKey.get(row.key)||{...row,state:row.eligible?'queued':row.state==='unsupported'?'unsupported':row.state==='unknown'?'unknown':['seatRecline','blind'].includes(row.key)&&!Number.isFinite(row.value)?'unsaved':row.selected?'invalid':'unselected',requested:row.key==='brightness'?10:row.value,planned:true});
  for(const row of actual)if(!result.some(x=>x.key===row.key))result.push(row);
  for(const row of info.audioSteps||[])result.push({...row,operationId:'audio'});
  result.push({key:'window',state:'manual',planned:true,message:'차량에서 직접 닫아 주세요.'});
  return result.sort((a,b)=>order.indexOf(a.key)-order.indexOf(b.key)).map(row=>({...row,label:labels[row.key]||'차량 설정'}));
 }
 function summary(rows){const requested=rows.filter(x=>!['manual','unsupported','unknown','unselected','unsaved','invalid'].includes(x.state));return {total:requested.length,confirmed:requested.filter(x=>confirmed.has(x.state)).length,failed:requested.filter(x=>failed.has(x.state)).length,active:requested.find(x=>working.has(x.state))||null};}
 function activeHardware(rows){return rows.filter(x=>working.has(x.state)&&groupDefinitions.some(g=>g.keys.includes(x.key)));}
 function groups(rows){return groupDefinitions.map(group=>{const members=group.keys.map(key=>rows.find(x=>x.key===key)).filter(Boolean),stats=summary(members),active=activeHardware(members);return {...group,rows:members,...stats,active,state:active.length?'working':stats.failed?'failed':stats.total&&stats.confirmed===stats.total?'confirmed':stats.total?'queued':'unchanged'};});}
 const api={order,labels,states,working,confirmed,failed,groupDefinitions,currentSteps,freshPosition,meter,rows,summary,activeHardware,groups};
 if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.SleepRoutine=api;
})(typeof window==='undefined'?globalThis:window);

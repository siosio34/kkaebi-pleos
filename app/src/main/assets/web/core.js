(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.RestCore=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
'use strict';
const FRESH=5000, NAPS=[10,15,20,25,30];
const PHASES=['plan','drive','seek','arrival','prepare','settings','scheduling','sync','rest','recovery','more-rest','summary','interrupted'];
function parked(s,now){return !!(s&&(s.mode==='demo'||s.mode==='live'&&s.lifecycleActive===true)&&s.gear==='P'&&typeof s.speedKmh==='number'&&Number.isFinite(s.speedKmh)&&Math.abs(s.speedKmh)<=.1&&[s.gearAt,s.speedAt].every(t=>Number.isFinite(t)&&t>0&&now>=t&&now-t<FRESH)&&s.restricted===false);}
function preset(){return {temperatureEnabled:true,temperature:22,fanEnabled:true,fan:1,dim:true,seatVentilationEnabled:false,seatVentilation:1,hvacPowerEnabled:false,hvacPower:true,soundEnabled:false,voiceEnabled:false,seatReclineEnabled:false,seatRecline:null,blindEnabled:false,blind:null,blindArea:'',windowEnabled:false,window:null};}
function emptyAlarm(){return {state:'idle',id:null,requestId:null,deadline:null,remainingMs:0,message:'',observedAt:0};}
function initial(now=Date.now(),runtime='demo'){return {version:3,runtime,phase:'plan',active:false,lastTick:now,driveMs:0,breakEvery:90,napMinutes:20,sleep:'normal',reason:'',signal:{mode:'unavailable',gear:'UNKNOWN',speedKmh:null,restricted:true},selected:null,confirmedPlace:false,environmentConsent:false,cabinConsent:false,motionConsent:false,autoRestore:true,sleepProfileInitialized:false,preset:preset(),pendingRequest:null,requestStartedAt:null,appliedAlarmId:null,alarmStopRequestedId:null,alarm:emptyAlarm(),alarmReady:runtime==='demo',alarmPermissionMessage:'',controlsSuspended:false,settingsReturn:'plan',logs:[],notice:'',reminderSent:false,effects:[]};}
function log(s,type,text,now){s.logs=[...s.logs,{at:now,type,text}].slice(-100);}
function effect(s,type,data={}){s.effects.push({type,data});}
function activeAlarm(s){return ['scheduled','ringing'].includes(s.alarm.state);}
function capabilityState(signal,key){const c=signal?.controlCapabilities?.[key];if(c?.manualRequired)return 'unsupported';return ['supported','unsupported'].includes(c?.state)?c.state:'unknown';}
function validCapability(signal,key,value){const c=signal?.controlCapabilities?.[key];if(capabilityState(signal,key)!=='supported')return false;const n=typeof value==='boolean'?(value?1:0):value;if(!Number.isFinite(n)||![c.min,c.max,c.step,c.origin].every(Number.isFinite)||c.step<=0||n<c.min||n>c.max)return false;return Math.abs((n-c.origin)/c.step-Math.round((n-c.origin)/c.step))<.001;}
function motionAllowed(s,now=Date.now()){return parked(s.signal,now)&&s.signal.lifecycleActive!==false&&s.signal.systemAnimationsEnabled!==false;}
function scenePose(signal,now=Date.now()){
 const session=signal?.restSession,id=signal?.controlOperationId;
 if(!parked(signal,now)||signal.motionStopUnconfirmed||!id||session?.controlOperationId!==id||signal.controlPhase!=='complete')return 'awake';
 const rows=(signal.controlSteps||[]).filter(x=>x.operationId===id);
 if(rows.some(x=>['failed','pendingRestore','checking','writing','verifying','queued'].includes(x.state)))return 'awake';
 const seat=rows.find(x=>x.key==='seatRecline');
 if(signal.controlOperation==='apply'&&session.phase==='resting'&&seat?.state==='applied')return 'rest';
 if(signal.controlOperation==='restore'&&session.phase==='complete'&&seat?.state==='restored'&&!signal.pendingRestore)return 'wake';
 return 'awake';
}
function sleepPreset(s){
 const p={...s.preset},caps=s.signal?.controlCapabilities||{},pose=s.signal?.restPose||{};
 const fit=(key,value,low,high)=>{const c=caps[key];if(!c||capabilityState(s.signal,key)!=='supported')return null;const lo=Math.max(low,c.min),hi=Math.min(high,c.max);if(![lo,hi,c.step,c.origin].every(Number.isFinite)||c.step<=0||lo>hi)return null;const n=c.origin+Math.round((Math.max(lo,Math.min(hi,value))-c.origin)/c.step)*c.step;return n>=lo&&n<=hi&&validCapability(s.signal,key,n)?Math.round(n*1000)/1000:null;};
 for(const [key,enabled,value,low,high] of [['temperature','temperatureEnabled',p.temperature||22,18,26],['fan','fanEnabled',p.fan||1,1,3],['seatVentilation','seatVentilationEnabled',p.seatVentilation||1,0,3]]){const n=fit(key,value,low,high);p[enabled]=n!==null;if(n!==null)p[key]=n;}
 p.voiceEnabled=s.signal?.tts===true;p.soundEnabled=s.signal?.restSoundAvailable===true;
 p.hvacPower=true;p.hvacPowerEnabled=validCapability(s.signal,'hvacPower',true);p.dim=validCapability(s.signal,'brightness',10);
 for(const key of ['seatRecline','blind']){const stored=pose[key],cap=caps[key],saved=stored&&stored.areaId===cap?.areaId&&validCapability(s.signal,key,stored.value);const fallback=Number.isFinite(cap?.max)?cap.max:null;p[key]=saved?stored.value:fallback;if(key==='blind')p.blindArea=cap?.areaId||'';p[key+'Enabled']=validCapability(s.signal,key,p[key]);}
 p.window=Number.isFinite(caps.window?.min)?caps.window.min:null;p.windowEnabled=validCapability(s.signal,'window',p.window);
 return p;
}
function environmentPlan(s){const p=s.preset;return [
 {key:'temperature',payloadKey:'temperature',label:'온도',value:p.temperature,selected:p.temperatureEnabled},
 {key:'fan',payloadKey:'fan',label:'풍량',value:p.fan,selected:p.fanEnabled},
 {key:'brightness',payloadKey:'dim',label:'실내 조명',value:true,selected:p.dim},
 {key:'seatVentilation',payloadKey:'seatVentilation',label:'시트 통풍',value:p.seatVentilation,selected:p.seatVentilationEnabled},
 {key:'hvacPower',payloadKey:'hvacPower',label:'공조 전원',value:p.hvacPower,selected:p.hvacPowerEnabled},
 {key:'seatRecline',payloadKey:'seatRecline',label:'시트 기울기',value:p.seatRecline,selected:p.seatReclineEnabled},
 {key:'blind',payloadKey:'blind',label:'블라인드',value:p.blind,selected:p.blindEnabled},
 {key:'window',payloadKey:'window',label:'창문',value:p.window,selected:p.windowEnabled}
 ].sort((a,b)=>['seatRecline','blind','window','hvacPower','temperature','fan','seatVentilation','brightness'].indexOf(a.key)-['seatRecline','blind','window','hvacPower','temperature','fan','seatVentilation','brightness'].indexOf(b.key)).map(row=>{const state=s.runtime==='demo'?'supported':capabilityState(s.signal,row.key),locked=s.signal?.pendingRestore===true||s.signal?.controlPhase==='running';const rangeOk=s.runtime==='demo'||!s.signal?.restOrchestration||validCapability(s.signal,row.key,row.key==='brightness'?10:row.value);return {...row,state,eligible:row.selected&&state==='supported'&&rangeOk&&!locked,rangeOk,reason:locked?'이전 설정 확인 필요':state==='unknown'?'지원 확인 전':state==='unsupported'?'직접 조절':!rangeOk?'값 선택·범위 확인':'지원 확인'};});}
function eligibleControls(s){return Object.fromEntries(environmentPlan(s).filter(x=>x.eligible).map(x=>[x.payloadKey,x.value]));}
function controlPayload(s){const payload=s.environmentConsent?eligibleControls(s):{};if('blind' in payload)payload.blindArea=s.preset.blindArea;return payload;}
function command(text){const t=String(text||'').normalize('NFKC').replace(/[.,!?。！？]/g,'').replace(/\s+/g,'').toLowerCase();
 if(/^(깨비)?(알람|타이머)(그만|중지|취소|꺼줘|끄기|멈춰|멈춰줘)$/.test(t))return 'ack-alarm';
 if(/^(깨비)?(휴식|졸음모드)?(취소|그만|중지|조용히|말그만|대화그만|음성꺼줘)$/.test(t))return 'quiet';
 if(/안졸|졸리지않|안쉬|쉬지않|휴식하지않|졸음모드하지마|휴식하지마|괜찮/.test(t))return 'none';
 if(/^(깨비야?)?(나)?(좀)?(졸음모드|졸음모드켜줘|졸려|졸려요|졸리다|졸립니다|졸음운전모드)$/.test(t))return 'drowsy-mode';
 if(/^(깨비)?(휴식|휴식모드|휴식준비|쉬자|쉬고싶어|쉬고싶어요)$/.test(t))return 'rest-mode';
 if(/^(깨비야?)?(창문)(좀|조금)?(열어|열어줘|열어줄래)$/.test(t))return 'window-open';
 if(/^(깨비야?)?(환기|환기해줘|바람|바람좀)?(틀어|틀어줘|세게|세게해줘|환기해줘|환기)$/.test(t))return 'ventilation';
 if(/^(깨비야?)?(말걸지마|말시키지마|조용히해줘)$/.test(t))return 'talk-off';
 if(/^(깨비야?)?(말걸어줘|말좀걸어줘|얘기하자)$/.test(t))return 'talk-on';
 return 'none';}
// Native diagnostics stay intact; only allowlisted summaries reach the driver UI.
function controlIssue(key,message){const text=String(message||'');
 if(/timeout|timed[ -]?out|시간\s*초과/i.test(text))return '응답 시간 초과 · 정차 후 다시 확인해 주세요.';
 if(/securityexception|permission|denied|권한|접근.*거부/i.test(text))return '설정 권한 확인 필요 · 차량에서 권한을 확인해 주세요.';
 if(/알람.*종료|후속.*중단/.test(text))return '알람이 끝나 설정 변경을 멈췄어요.';
 if(/unsupported|not supported|미지원|지원.*(불가|없|되지)|범위|단계|read_write/i.test(text))return '지원 범위 확인 필요 · 이 설정은 직접 조절해 주세요.';
 if(/propertynotavailable|not available|unavailable|값 사용 불가|읽기.*실패|기존 값/i.test(text))return ['temperature','fan','hvacPower'].includes(key)?'값 읽기 실패 · 차량 공조 상태를 확인해 주세요.':'값 읽기 실패 · 차량에서 직접 확인해 주세요.';
 if(/정차|정지|P[·단]|주차 UX|신선/.test(text))return '정차 확인 필요 · P단과 차량 연결을 확인해 주세요.';
 if(/저장|기록.*갱신/.test(text))return '복원 기록 저장 실패 · 차량 설정을 직접 확인해 주세요.';
 if(/확인 실패|응답 없음|미확인/.test(text))return '변경 결과 미확인 · 차량에서 직접 확인해 주세요.';
 return '처리 미완료 · 차량 상태를 직접 확인해 주세요.';
}
function normalizeAlarm(a,now){const state=['idle','scheduled','ringing','failed'].includes(a.alarmState)?a.alarmState:'idle';return {state,id:typeof a.alarmId==='string'?a.alarmId:null,requestId:typeof a.alarmRequestId==='string'?a.alarmRequestId:typeof a.requestId==='string'?a.requestId:null,deadline:Number.isFinite(a.alarmDeadline)?a.alarmDeadline:null,remainingMs:Number.isFinite(a.alarmRemainingMs)?Math.max(0,a.alarmRemainingMs):0,message:typeof a.alarmMessage==='string'?a.alarmMessage.slice(0,500):'',observedAt:now};}
function reconcileAlarm(s,a,now){
 const next=normalizeAlarm(a,now),pending=s.pendingRequest,matching=!!pending&&next.requestId===pending;
 if(!['idle','scheduled','ringing','failed'].includes(a.alarmState))return;
 if(['scheduled','ringing'].includes(next.state)&&!next.id)return;
 s.alarmReady=a.alarmReady===true;s.alarmPermissionMessage=String(a.alarmPermissionMessage||'');
 // An older request response cannot acknowledge this request or trigger vehicle writes.
 if(pending&&next.requestId&&next.requestId!==pending)return;
 if(pending&&next.state==='idle')return;
 if(pending&&['scheduled','ringing','failed'].includes(next.state)&&!matching)return;
 const previous=s.alarm;s.alarm=next;
 if(next.state==='idle'&&s.alarmStopRequestedId){s.alarmStopRequestedId=null;s.phase='recovery';s.notice='알람이 중지되었어요.';return;}
 if(next.id&&s.alarmStopRequestedId&&next.id!==s.alarmStopRequestedId)s.alarmStopRequestedId=null;
 if(next.state==='scheduled'&&s.alarmStopRequestedId===next.id){s.phase='recovery';return;}
 if(next.state==='scheduled'){
  s.phase='rest';s.active=true;s.controlsSuspended=!parked(s.signal,now);
  if(matching){s.pendingRequest=null;s.requestStartedAt=null;log(s,'rest','알람 예약 확인 · '+s.napMinutes+'분 휴식',now);
   if(!s.signal?.restOrchestration&&parked(s.signal,now)&&s.confirmedPlace&&s.appliedAlarmId!==next.id){s.appliedAlarmId=next.id;const payload=controlPayload(s);if(Object.keys(payload).length)effect(s,'applyRest',{...payload,alarmId:next.id});if(s.preset.soundEnabled)effect(s,'restSound',{enabled:true});}
   else if(!parked(s.signal,now))s.notice='알람은 예약되었어요. 정차를 확인하지 못해 차량 설정은 변경하지 않았어요.';
  }
 }else if(next.state==='ringing'){
  s.pendingRequest=null;s.requestStartedAt=null;s.phase='recovery';s.confirmedPlace=false;
  if(previous.state!=='ringing'||previous.id!==next.id){effect(s,'restSound',{enabled:false});log(s,'alarm','기상 알람 · 운전 가능 판정 아님',now);}
 }else if(next.state==='failed'){
  if(matching||['rest','scheduling','sync'].includes(s.phase)){s.pendingRequest=null;s.requestStartedAt=null;s.phase=parked(s.signal,now)?'prepare':'interrupted';s.confirmedPlace=false;s.notice=next.message||'알람을 예약하지 못했어요. 알람 권한과 소리를 확인해 주세요.';effect(s,'restSound',{enabled:false});}
 }else if(['rest','sync'].includes(s.phase)){
  s.phase='interrupted';s.confirmedPlace=false;s.notice='현재 예약된 알람이 없어요. 정차 후 휴식을 다시 준비해 주세요.';
 }
}
function reduce(old,event,now=Date.now()){
 const s={...old,preset:{...old.preset},alarm:{...old.alarm},logs:[...old.logs],effects:[]};const p=()=>parked(s.signal,now);
 switch(event.type){
 case 'signal':case 'snapshot':{
  s.signal=event.signal;
  if(!p()){
   s.confirmedPlace=false;s.controlsSuspended=true;
   if(['prepare','settings'].includes(s.phase))s.phase='interrupted';
   // Wake-up scheduling survives loss of car signals; only optional sounds/control stop.
   if(['rest','scheduling'].includes(s.phase))effect(s,'restSound',{enabled:false});
  }else s.controlsSuspended=false;
  if(event.type==='snapshot')reconcileAlarm(s,event.signal,now);
  break;}
 case 'sleep-preset':if(p()&&!activeAlarm(s)&&!s.pendingRequest&&s.signal?.controlPhase!=='running'&&!s.signal?.pendingRestore){s.preset=sleepPreset(s);s.sleepProfileInitialized=true;s.environmentConsent=false;s.cabinConsent=false;s.motionConsent=false;}break;
 case 'configure':if(!p()||activeAlarm(s)||s.pendingRequest)break;
  if([60,90,120].includes(event.breakEvery))s.breakEvery=event.breakEvery;
  if(NAPS.includes(event.napMinutes))s.napMinutes=event.napMinutes;
  if(['normal','short','sleepy'].includes(event.sleep))s.sleep=event.sleep;
  if(event.preset){const v=event.preset;for(const k of ['temperatureEnabled','fanEnabled','dim','seatVentilationEnabled','hvacPowerEnabled','hvacPower','soundEnabled','voiceEnabled','seatReclineEnabled','blindEnabled','windowEnabled'])if(typeof v[k]==='boolean')s.preset[k]=v[k];if(Number.isFinite(v.temperature)&&v.temperature>=18&&v.temperature<=26&&Number.isInteger(v.temperature*2))s.preset.temperature=v.temperature;if([1,2,3].includes(v.fan))s.preset.fan=v.fan;if([0,1,2,3].includes(v.seatVentilation))s.preset.seatVentilation=v.seatVentilation;for(const k of ['seatRecline','blind','window'])if(Number.isFinite(v[k])&&Number.isInteger(v[k])&&(s.runtime==='demo'||validCapability(s.signal,k,v[k])))s.preset[k]=v[k];if(typeof v.blindArea==='string'&&v.blindArea.length<80)s.preset.blindArea=v.blindArea;}
  break;
 case 'edit-settings':if(p()&&!activeAlarm(s)&&!s.pendingRequest){s.settingsReturn=s.phase==='prepare'?'prepare':'plan';s.phase='settings';}break;
 case 'close-settings':if(p()&&s.phase==='settings')s.phase=s.settingsReturn;break;
 case 'start':if(activeAlarm(s)||s.pendingRequest)break;if(!p()){s.notice='P단 정차와 차량 신호를 확인해 주세요.';break;}s.active=true;s.lastTick=now;s.driveMs=0;s.reminderSent=false;s.phase=s.sleep==='normal'?'drive':'seek';s.reason=s.sleep==='normal'?'':'출발 전 졸림이나 수면 부족을 알려주셨어요. 운전을 미루고 충분히 쉬어 주세요.';log(s,'start','휴식 계획 시작',now);break;
 case 'sleepy':case 'drowsy-mode':if(activeAlarm(s)||s.pendingRequest){s.notice='이미 휴식 알람이 있어요. 알람 화면에서 확인해 주세요.';break;}s.active=true;s.phase='seek';s.reason='졸림을 느끼면 운전을 계속하지 마세요. 안전하게 진입할 수 있는 휴식 장소를 확인해 주세요.';s.notice='';log(s,'self-report','졸음모드 요청',now);break;
 case 'select':if(s.phase==='seek'&&event.place&&typeof event.place.name==='string'){s.selected=event.place;s.phase='arrival';s.confirmedPlace=false;log(s,'place','가상 휴식 장소 선택: '+event.place.name,now);}break;
 case 'arrived':case 'rest-mode':case 'more-rest':if(activeAlarm(s)||s.pendingRequest){s.notice='기존 알람을 먼저 확인하거나 중지해 주세요.';break;}if(!p()){s.notice='안전한 주차 장소에서 P단 정차 후 깨비 휴식을 시작할 수 있어요.';break;}s.phase='prepare';s.confirmedPlace=false;s.environmentConsent=false;s.notice='';break;
 case 'confirm-place':if(p()&&s.phase==='prepare')s.confirmedPlace=event.value===true;break;
 case 'consent-cabin':if(p()&&s.phase==='prepare')s.cabinConsent=event.value===true;break;
 case 'consent-motion':if(p()&&s.phase==='prepare')s.motionConsent=event.value===true;break;
 case 'auto-restore':if(p()&&s.phase==='prepare')s.autoRestore=event.value===true;break;
 case 'consent-environment':if(p()&&s.phase==='prepare'){s.environmentConsent=event.value===true&&!s.signal?.pendingRestore&&s.signal?.controlPhase!=='running';if(event.value&&s.signal?.pendingRestore)s.notice='현재 환경을 유지하고 알람만 다시 예약해요. 다른 설정은 이전 설정 복원 후 선택해 주세요.';}break;
 case 'rest':if(activeAlarm(s)||s.pendingRequest)break;if(!p()||!s.confirmedPlace||s.phase!=='prepare'){s.notice='안전한 주차 장소 확인과 P단 정차가 필요해요.';break;}
  if(s.signal?.controlPhase==='running'){s.notice='차량 설정 작업을 확인하고 있어요. 결과가 나온 뒤 휴식을 시작해 주세요.';break;}
  if(!s.alarmReady){s.notice=s.alarmPermissionMessage||'먼저 알람 권한과 소리를 확인해 주세요.';break;}
  if(typeof event.requestId!=='string'||!/^[A-Za-z0-9_-]{1,64}$/.test(event.requestId))break;
  s.phase='scheduling';s.pendingRequest=event.requestId;s.requestStartedAt=now;s.notice='';if(s.signal?.restOrchestration)effect(s,'startRest',{durationMinutes:s.napMinutes,requestId:event.requestId,plan:controlPayload(s),cabinConsent:s.cabinConsent,motionConsent:s.motionConsent,autoRestore:s.autoRestore&&!s.signal?.pendingRestore,voice:s.preset.voiceEnabled,restSound:s.preset.soundEnabled});else effect(s,'startAlarm',{durationMinutes:s.napMinutes,requestId:event.requestId});break;
 case 'alarm':reconcileAlarm(s,event.alarm,now);break;
 case 'tick':{const dt=Math.max(0,Math.min(now-s.lastTick,10000));s.lastTick=now;
  if(s.active&&s.signal.gear==='D'&&Number.isFinite(s.signal.speedKmh)&&s.signal.speedKmh>.1&&[s.signal.speedAt,s.signal.gearAt].every(t=>Number.isFinite(t)&&now>=t&&now-t<FRESH))s.driveMs+=dt;
  if(s.active&&s.phase==='drive'&&s.driveMs>=s.breakEvery*60000&&!s.reminderSent){s.phase='seek';s.reason='계획한 휴식 시간이 되었어요. 안전하게 쉴 곳을 확인해 주세요.';s.reminderSent=true;log(s,'planned','계획한 주행 시간 도달',now);}
  if(!p()){s.confirmedPlace=false;s.controlsSuspended=true;if(['prepare','settings'].includes(s.phase))s.phase='interrupted';if(!old.controlsSuspended&&['rest','scheduling'].includes(s.phase))effect(s,'restSound',{enabled:false});}
  if(s.pendingRequest&&now-s.requestStartedAt>15000){s.pendingRequest=null;s.phase='sync';s.notice='알람 응답을 확인하고 있어요. 차량 설정은 변경하지 않았어요.';effect(s,'ready');}
  break;}
 case 'wake-rest':case 'ack-alarm':case 'finish-rest':case 'cancel-alarm':{
  if(activeAlarm(s)){s.alarmStopRequestedId=s.alarm.id;effect(s,event.type==='cancel-alarm'?'cancelAlarm':'ackAlarm',{alarmId:s.alarm.id,...(s.signal?.restOrchestration?{restore:event.type==='wake-rest'&&p()&&(typeof s.signal?.restSession?.autoRestore==='boolean'?s.signal.restSession.autoRestore:s.autoRestore)}: {})});effect(s,'restSound',{enabled:false});s.phase='recovery';s.notice='알람 중지 요청을 보냈어요.';}
  break;}
 case 'still-sleepy':if(s.phase==='recovery'&&!activeAlarm(s)&&p()){s.phase='more-rest';log(s,'recovery','졸림이 남아 추가 휴식·교대 선택',now);}else if(activeAlarm(s))s.notice='알람 중지를 먼저 확인해 주세요.';break;
 case 'recovered':if(s.phase==='recovery'&&!activeAlarm(s)&&p()){s.phase='summary';s.active=false;if(!s.signal?.restOrchestration||s.signal?.pendingRestore&&!s.signal?.restSession?.autoRestore)effect(s,'restoreRest');log(s,'recovery','사용자 상태 확인 · 운전 가능 판정 아님',now);}else if(activeAlarm(s))s.notice='알람 중지를 먼저 확인해 주세요.';break;
 case 'stop-controls':effect(s,'stopControls');break;
 case 'restore-controls':if(p()&&!activeAlarm(s)&&!s.pendingRequest&&s.signal?.controlPhase!=='running'){s.phase='recovery';s.notice='이전 설정 복원을 요청했어요. 차량 결과를 확인해 주세요.';effect(s,'restoreRest');}else s.notice='알람을 끄고 P단 정차 후 차량 설정을 복원해 주세요.';break;
 case 'quiet':effect(s,'stopSpeech');effect(s,'restSound',{enabled:false});s.notice=activeAlarm(s)?'대화를 멈췄어요. 예약된 기상 알람은 유지돼요.':'대화를 멈췄어요.';break;
 case 'reset':if(!p())break;if(activeAlarm(s)||s.pendingRequest){s.notice='기존 알람을 먼저 확인하거나 중지해 주세요.';break;}const next=initial(now,s.runtime);next.signal=s.signal;next.logs=s.logs;next.preset=s.preset;next.sleepProfileInitialized=s.sleepProfileInitialized;next.napMinutes=s.napMinutes;next.alarmReady=s.alarmReady;return next;
 case 'clear':if(p())s.logs=[];break;
 }
 return s;
}
function restore(raw,now=Date.now(),runtime='demo'){
 try{const v=JSON.parse(raw);if(!v||![2,3].includes(v.version)||!Array.isArray(v.logs))return initial(now,runtime);
 const s=initial(now,runtime);s.autoRestore=v.autoRestore!==false;s.sleepProfileInitialized=v.sleepProfileInitialized===true;s.logs=v.logs.filter(l=>l&&Number.isFinite(l.at)&&typeof l.type==='string'&&typeof l.text==='string').slice(-100).map(l=>({at:l.at,type:l.type.slice(0,40),text:l.text.slice(0,300)}));s.driveMs=Number.isFinite(v.driveMs)?Math.max(0,Math.min(v.driveMs,86400000)):0;s.breakEvery=[60,90,120].includes(v.breakEvery)?v.breakEvery:90;s.napMinutes=NAPS.includes(v.napMinutes)?v.napMinutes:20;s.sleep=['normal','short','sleepy'].includes(v.sleep)?v.sleep:'normal';s.reason=typeof v.reason==='string'?v.reason.slice(0,500):'';s.active=v.active===true;s.phase=PHASES.includes(v.phase)?v.phase:'plan';s.selected=v.selected&&typeof v.selected.name==='string'?{id:String(v.selected.id||''),name:v.selected.name.slice(0,100)}:null;
 const fake={mode:'demo',lifecycleActive:true,gear:'P',speedKmh:0,gearAt:now,speedAt:now,restricted:false};s.preset=reduce({...s,signal:fake,phase:'plan'},{type:'configure',preset:v.preset||{}},now).preset;
 // Persist the preference only; fresh capability checks still gate every plan/write.
 for(const key of ['seatRecline','blind']){const n=v.preset?.[key];if(Number.isSafeInteger(n)&&n>=-2147483648&&n<=2147483647)s.preset[key]=n;}
 if(['rest','scheduling','sync','recovery'].includes(s.phase)){s.phase=runtime==='native'?'sync':'interrupted';s.notice=runtime==='native'?'기기에 예약된 알람을 확인하고 있어요.':'브라우저 시뮬레이션 타이머는 다시 열면 종료돼요.';}
 else if(['prepare','settings'].includes(s.phase))s.phase='interrupted';
 s.confirmedPlace=false;s.environmentConsent=false;s.pendingRequest=null;s.effects=[];return s;
 }catch{return initial(now,runtime);}
}
return {FRESH,NAPS,parked,preset,initial,reduce,restore,command,activeAlarm,controlPayload,controlIssue,capabilityState,validCapability,motionAllowed,scenePose,sleepPreset,environmentPlan,eligibleControls};
});

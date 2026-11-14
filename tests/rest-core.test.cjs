const {test}=require('node:test');
const a=require('node:assert/strict');
const C=require('../app/src/main/assets/web/core.js');
const t=100000;
const sig={mode:'live',lifecycleActive:true,gear:'P',speedKmh:0,gearAt:t,speedAt:t,restricted:false,controlCapabilities:Object.fromEntries(['temperature','fan','brightness','seatVentilation','hvacPower'].map(key=>[key,{state:'supported'}]))};
const step=(s,type,data={},now=t)=>C.reduce(s,{type,...data},now);
const state=(runtime='native')=>step(C.initial(t,runtime),'snapshot',{signal:{...sig,alarmState:'idle',alarmReady:true}});
const scheduled=(requestId='request-1',extra={})=>({alarmState:'scheduled',alarmId:'alarm-1',alarmRequestId:requestId,alarmDeadline:t+1200000,alarmRemainingMs:1200000,alarmReady:true,...extra});
function prepare(consent=true){let s=step(state(),'rest-mode');s=step(s,'confirm-place',{value:true});return step(s,'consent-environment',{value:consent});}
function pending(consent=true){return step(prepare(consent),'rest',{requestId:'request-1'});}
function rest(consent=true){return step(pending(consent),'alarm',{alarm:scheduled()});}
const types=s=>s.effects.map(e=>e.type);

test('P, fresh zero speed and explicit unrestricted are all required',()=>{
 a.ok(C.parked(sig,t));
 for(const patch of [{gear:'D'},{speedKmh:null},{speedKmh:NaN},{speedKmh:1},{speedAt:t-5000},{gearAt:t-5000},{speedAt:t+1},{restricted:true},{restricted:undefined}])a.equal(C.parked({...sig,...patch},t),false,JSON.stringify(patch));
});
test('missing vehicle signals prevent starting a plan and rest',()=>{
 const s=C.initial(t,'native');a.equal(step(s,'start').active,false);a.equal(step(s,'rest-mode').phase,'plan');
});
test('sleep shortage and self-reported drowsiness bypass scheduled wait',()=>{
 a.equal(step(step(state(),'configure',{sleep:'short'}),'start').phase,'seek');
 const s=step(step(state(),'start'),'drowsy-mode');a.equal(s.phase,'seek');a.match(s.reason,/계속하지/);
});
test('rest requires place confirmation distinct from environment consent',()=>{
 let s=step(state(),'rest-mode');s=step(s,'consent-environment',{value:true});
 a.equal(step(s,'rest',{requestId:'request-1'}).phase,'prepare');
 s=step(s,'confirm-place',{value:true});a.equal(step(s,'rest',{requestId:'request-1'}).phase,'scheduling');
});
test('request schedules alarm first and does not claim rest or apply controls',()=>{
 const s=pending();a.equal(s.phase,'scheduling');a.equal(s.pendingRequest,'request-1');
 a.deepEqual(s.effects,[{type:'startAlarm',data:{durationMinutes:20,requestId:'request-1'}}]);
});
test('matching scheduled ACK enables rest and applies selected controls only once',()=>{
 let s=rest();a.equal(s.phase,'rest');a.deepEqual(s.effects,[{type:'applyRest',data:{temperature:22,fan:1,dim:true,alarmId:'alarm-1'}}]);
 s=step(s,'alarm',{alarm:scheduled()});a.deepEqual(s.effects,[]);
});
test('no environment consent means no car writes after successful alarm',()=>a.deepEqual(types(rest(false)),[]));
test('optional comfort controls preserve zero and explicit false without inventing power on',()=>{
 let s=prepare();s=step(s,'configure',{preset:{temperatureEnabled:false,fanEnabled:false,dim:false,seatVentilationEnabled:true,seatVentilation:0,hvacPowerEnabled:true,hvacPower:false}});
 s=step(s,'rest',{requestId:'request-1'});s=step(s,'alarm',{alarm:scheduled()});
 a.deepEqual(s.effects,[{type:'applyRest',data:{seatVentilation:0,hvacPower:false,alarmId:'alarm-1'}}]);
});
test('mismatched, missing or malformed ACK never applies or clears pending request',()=>{
 for(const alarm of [scheduled('old-request'),scheduled(null),scheduled('request-1',{alarmId:null}),{alarmState:'bogus',alarmReady:true}, {alarmState:'failed',alarmRequestId:'old-request',alarmReady:false}]){
  const s=step(pending(),'alarm',{alarm});a.equal(s.phase,'scheduling');a.equal(s.pendingRequest,'request-1');a.deepEqual(s.effects,[]);
 }
});
test('idle snapshot during reservation does not discard pending ACK',()=>{
 const s=step(pending(),'snapshot',{signal:{...sig,alarmState:'idle',alarmReady:true}});a.equal(s.phase,'scheduling');a.equal(s.pendingRequest,'request-1');
});
test('gear change before ACK preserves alarm but invalidates place and prevents apply',()=>{
 let s=step(pending(),'signal',{signal:{...sig,gear:'D',speedKmh:30,restricted:true}});
 s=step(s,'alarm',{alarm:scheduled()});a.equal(s.phase,'rest');a.equal(s.confirmedPlace,false);a.equal(s.controlsSuspended,true);a.deepEqual(s.effects,[]);
});
test('stale signal when ACK arrives prevents any comfort writes',()=>{
 const s=step(pending(),'alarm',{alarm:scheduled()},t+5001);a.equal(s.phase,'rest');a.equal(s.controlsSuspended,true);a.deepEqual(s.effects,[]);
});
test('moving away and parking again cannot resurrect prior place consent',()=>{
 let s=step(pending(),'signal',{signal:{...sig,gear:'D'}});s=step(s,'signal',{signal:sig});s=step(s,'alarm',{alarm:scheduled()});a.deepEqual(s.effects,[]);a.equal(s.confirmedPlace,false);
});
test('D, lost signals, stale ticks and quiet never cancel a scheduled alarm',()=>{
 const events=[['signal',{signal:{...sig,gear:'D',restricted:true}}],['signal',{signal:{mode:'unavailable',gear:'UNKNOWN'}}],['tick',{}],['quiet',{}]];
 for(const [type,data]of events){const s=step(rest(),type,data,t+5001);a.equal(s.alarm.state,'scheduled');a.ok(!types(s).some(x=>['cancelAlarm','ackAlarm'].includes(x)));a.equal(s.phase,'rest');}
});
test('stale signal stops optional rest sound without deleting alarm',()=>{
 const s=step(rest(),'tick',{},t+5001);a.deepEqual(s.effects,[{type:'restSound',data:{enabled:false}}]);a.equal(s.alarm.id,'alarm-1');
});
test('wallclock jump does not fire a native alarm',()=>{
 const s=step(rest(),'tick',{},t+86400000);a.equal(s.phase,'rest');a.equal(s.alarm.state,'scheduled');a.equal(s.alarm.remainingMs,1200000);
});
test('native ringing snapshot creates recovery once without cancellation',()=>{
 let s=step(rest(),'alarm',{alarm:scheduled('request-1',{alarmState:'ringing',alarmRemainingMs:0})});a.equal(s.phase,'recovery');a.deepEqual(types(s),['restSound']);const n=s.logs.length;
 s=step(s,'alarm',{alarm:scheduled('request-1',{alarmState:'ringing',alarmRemainingMs:0})});a.equal(s.logs.length,n);a.deepEqual(s.effects,[]);
});
test('explicit alarm stop is available in any gear while restoration remains P gated',()=>{
 let s=step(rest(),'signal',{signal:{...sig,gear:'D',restricted:true}});s=step(s,'ack-alarm');a.equal(s.phase,'recovery');a.deepEqual(s.effects[0],{type:'ackAlarm',data:{alarmId:'alarm-1'}});
 a.deepEqual(step(s,'restore-controls').effects,[]);a.deepEqual(step(s,'recovered').effects,[]);
});
test('alarm stop awaits native idle confirmation before recovery action',()=>{
 let s=step(rest(),'finish-rest');a.equal(step(s,'recovered').phase,'recovery');
 s=step(s,'alarm',{alarm:{alarmState:'idle',alarmReady:true}});s=step(s,'recovered');a.equal(s.phase,'summary');a.deepEqual(s.effects,[{type:'restoreRest',data:{}}]);
});
test('still sleepy offers more rest after stopping alarm without certifying readiness',()=>{
 let s=step(rest(),'ack-alarm');s=step(s,'alarm',{alarm:{alarmState:'idle',alarmReady:true}});s=step(s,'still-sleepy');a.equal(s.phase,'more-rest');
 s=step(s,'more-rest');a.equal(s.phase,'prepare');a.equal(s.confirmedPlace,false);a.equal(s.environmentConsent,false);
});
test('failed matching reservation never applies comfort or starts rest',()=>{
 const s=step(pending(),'alarm',{alarm:{alarmState:'failed',alarmRequestId:'request-1',alarmReady:false,alarmMessage:'정확 알람 권한 필요'}});a.equal(s.phase,'prepare');a.equal(s.pendingRequest,null);a.equal(s.confirmedPlace,false);a.ok(!types(s).includes('applyRest'));a.match(s.notice,/권한/);
});
test('persistent failed snapshot does not pull drowsy mode back to prepare',()=>{
 const failed={alarmState:'failed',alarmRequestId:'request-1',alarmReady:false,alarmMessage:'권한 없음'};
 let s=step(pending(),'alarm',{alarm:failed});s=step(s,'drowsy-mode');s=step(s,'snapshot',{signal:{...sig,...failed}});a.equal(s.phase,'seek');
});
test('permission recovery allows a fresh explicit reservation',()=>{
 let s=step(pending(),'alarm',{alarm:{alarmState:'failed',alarmRequestId:'request-1',alarmReady:false}});
 s=step(s,'snapshot',{signal:{...sig,alarmState:'failed',alarmReady:true}});s=step(s,'confirm-place',{value:true});s=step(s,'rest',{requestId:'request-2'});
 a.equal(s.phase,'scheduling');a.equal(s.pendingRequest,'request-2');
 s=step(s,'alarm',{alarm:scheduled('request-2',{alarmId:'alarm-2'})});a.equal(s.phase,'rest');
});
test('missing alarm permission blocks reservation with its actionable message',()=>{
 let s=prepare();s={...s,alarmReady:false,alarmPermissionMessage:'알림 권한을 켜 주세요'};s=step(s,'rest',{requestId:'request-1'});a.equal(s.phase,'prepare');a.deepEqual(s.effects,[]);a.match(s.notice,/알림 권한/);
});
test('timeout asks native authority to resync and a late ACK never reapplies hardware',()=>{
 let s=step(pending(),'tick',{},t+16000);a.equal(s.phase,'sync');a.equal(s.pendingRequest,null);a.ok(types(s).includes('ready'));
 s=step(s,'snapshot',{signal:{...sig,gearAt:t+16001,speedAt:t+16001,...scheduled()}},t+16001);a.equal(s.phase,'rest');a.deepEqual(s.effects,[]);
});
test('relaunch discards stored vehicle signals and adopts native reservation without reapplying',()=>{
 let s=C.restore(JSON.stringify(rest()),t+100,'native');a.equal(s.phase,'sync');a.equal(s.signal.mode,'unavailable');a.equal(s.alarm.state,'idle');a.equal(s.environmentConsent,false);
 s=step(s,'snapshot',{signal:{...sig,...scheduled()}},t+100);a.equal(s.phase,'rest');a.deepEqual(s.effects,[]);
});
test('browser restart explains simulation loss and does not synthesize alarm',()=>{
 const s=C.restore(JSON.stringify(rest()),t+100,'demo');a.equal(s.phase,'interrupted');a.equal(s.alarm.state,'idle');a.deepEqual(s.effects,[]);
});
test('new rest, plan and reset requests cannot replace active or pending alarms',()=>{
 for(const s of [pending(),rest()])for(const type of ['rest-mode','more-rest','arrived','drowsy-mode','start','reset','rest']){
  const next=step(s,type,{requestId:'request-2'});a.equal(next.phase,s.phase,type);a.equal(next.pendingRequest,s.pendingRequest);a.equal(next.alarm.id,s.alarm.id);a.deepEqual(next.effects,[]);
 }
});
test('settings is a separate parked screen and returns to preparation',()=>{
 let s=step(prepare(),'edit-settings');a.equal(s.phase,'settings');s=step(s,'configure',{napMinutes:25,preset:{temperature:24}});a.equal(s.napMinutes,25);a.equal(s.preset.temperature,24);s=step(s,'close-settings');a.equal(s.phase,'prepare');
});
test('unsafe or stale prepare/settings invalidates place confirmation',()=>{
 for(const phase of ['prepare','settings']){const s=step({...prepare(),phase},'tick',{},t+5000);a.equal(s.phase,'interrupted');a.equal(s.confirmedPlace,false);}
});
test('preset numeric values and booleans reject invalid or out-of-range data',()=>{
 const s=step(state(),'configure',{napMinutes:1,preset:{temperature:NaN,fan:4,seatVentilation:-1,dim:'true',hvacPowerEnabled:1}});a.deepEqual(s.preset,C.preset());a.equal(s.napMinutes,20);
 for(const n of C.NAPS)a.equal(step(state(),'configure',{napMinutes:n}).napMinutes,n);
});
test('preset editing is blocked while moving and while an alarm is scheduled',()=>{
 for(const s of [step(state(),'signal',{signal:{...sig,gear:'D'}}),rest()])a.equal(step(s,'configure',{preset:{temperature:26}}).preset.temperature,22);
});
test('preferences persist but consents, pending requests and stored vehicle authority do not',()=>{
 const original=step(prepare(),'configure',{napMinutes:30,preset:{temperature:25,seatVentilationEnabled:true,seatVentilation:2}});
 const s=C.restore(JSON.stringify(original),t+1,'native');a.equal(s.preset.temperature,25);a.equal(s.preset.seatVentilation,2);a.equal(s.napMinutes,30);a.equal(s.confirmedPlace,false);a.equal(s.environmentConsent,false);a.equal(s.pendingRequest,null);a.equal(s.signal.mode,'unavailable');
});
test('exact voice allowlist handles drowsy/rest and cancel or negative text first',()=>{
 for(const text of ['깨비 졸음모드','졸려요','깨비, 졸려!'])a.equal(C.command(text),'drowsy-mode',text);
 for(const text of ['깨비 휴식','휴식 준비','쉬고 싶어요'])a.equal(C.command(text),'rest-mode',text);
 for(const text of ['안 졸려','졸리지 않아','깨비 졸음모드 하지마','휴식하지 마','졸려요라고 친구가 말했어','안 졸린 건 아니야'])a.equal(C.command(text),'none',text);
 for(const text of ['휴식 취소','깨비 졸음모드 취소','조용히'])a.equal(C.command(text),'quiet',text);
 for(const text of ['깨비 알람 중지','타이머 꺼줘'])a.equal(C.command(text),'ack-alarm',text);
 a.equal(C.command('환기해줘'),'ventilation');
});
test('unknown or huge stored data is bounded and sanitized',()=>{
 a.equal(C.restore('{broken',t).phase,'plan');
 const s=C.restore(JSON.stringify({...C.initial(t),logs:[null,{at:t,type:'test',text:'ok'},{at:'broken'}],driveMs:'oops',breakEvery:0,napMinutes:999,preset:{temperature:Infinity,fan:-1}}),t);a.equal(s.logs.length,1);a.equal(s.driveMs,0);a.equal(s.breakEvery,90);a.equal(s.napMinutes,20);a.equal(s.preset.temperature,22);
});
test('observed driving does not count long scheduling or stale signal gaps',()=>{
 let s=step(state(),'start');s=step(s,'signal',{signal:{...sig,gear:'D',speedKmh:80,speedAt:t+100000,gearAt:t+100000}},t+100000);s=step(s,'tick',{},t+100000);a.equal(s.driveMs,10000);s=step(s,'tick',{},t+106000);a.equal(s.driveMs,10000);
});
test('planned threshold produces one actionable reminder',()=>{
 let s=step(state(),'start');s.driveMs=90*60000;s=step(s,'tick');a.equal(s.phase,'seek');a.equal(s.reminderSent,true);const n=s.logs.length;s=step(s,'tick');a.equal(s.logs.length,n);
});
test('local log remains bounded and can be erased only when parked',()=>{
 let s=state();for(let i=0;i<110;i++)s=step(s,'drowsy-mode');a.equal(s.logs.length,100);a.equal(step(s,'clear').logs.length,0);
 s=step(s,'signal',{signal:{...sig,gear:'D'}});a.equal(step(s,'clear').logs.length,100);
});

test('scheduled snapshots during alarm-stop race keep recovery until native idle confirmation',()=>{
 let s=step(rest(),'ack-alarm');s=step(s,'snapshot',{signal:{...sig,...scheduled()}});a.equal(s.phase,'recovery');a.equal(s.alarmStopRequestedId,'alarm-1');a.deepEqual(s.effects,[]);
 s=step(s,'snapshot',{signal:{...sig,alarmState:'idle',alarmReady:true}});a.equal(s.phase,'recovery');a.equal(s.alarmStopRequestedId,null);a.equal(s.alarm.state,'idle');a.equal(step(s,'recovered').phase,'summary');
});
test('request IDs match the native bridge allowlist and length',()=>{
 for(const requestId of ['', 'x'.repeat(65), '../request', 'request with spaces', '휴식']){const s=step(prepare(),'rest',{requestId});a.equal(s.phase,'prepare');a.deepEqual(s.effects,[]);}
 a.equal(step(prepare(),'rest',{requestId:'rest_123-abc'}).phase,'scheduling');
});

test('vehicle error presentation distinguishes permission, timeout, support and unavailable values',()=>{
 a.match(C.controlIssue('temperature','android.car.hardware.property.PropertyNotAvailableException: Property ID HVAC_TEMPERATURE_SET unavailable'),/값 읽기 실패.*공조 상태/);
 a.match(C.controlIssue('seatVentilation','PropertyNotAvailableException'),/값 읽기 실패.*직접/);
 a.match(C.controlIssue('fan','TimeoutCancellationException: Timed out waiting for 2000 ms'),/응답 시간 초과/);
 a.match(C.controlIssue('temperature','SecurityException: permission denied'),/권한 확인 필요/);
 a.match(C.controlIssue('brightness','UnsupportedOperationException: not supported'),/지원 범위 확인 필요/);
 a.match(C.controlIssue('fan','지원·읽기\/쓰기·요청 범위 확인 실패'),/지원 범위 확인 필요/);
});
test('unknown driver-facing errors never echo raw SDK text and preserve diagnostic input',()=>{
 const raw='java.lang.IllegalStateException: PRIVATE_DIAGNOSTIC_123 '+('x'.repeat(5000));
 const input={state:'failed',message:raw};const text=C.controlIssue('temperature',input.message);
 a.equal(input.message,raw);a.ok(text.length<70);a.match(text,/처리 미완료/);a.doesNotMatch(text,/PRIVATE_DIAGNOSTIC|Exception|xxx/);
 a.match(C.controlIssue('fan','알람이 종료되어 차량 설정 적용을 중단했습니다'),/알람이 끝나/);
});

test('native environment plan excludes unknown and unsupported controls without changing preferences',()=>{
 let s=prepare();s.signal={...sig,controlCapabilities:{temperature:{state:'supported'},fan:{state:'unsupported'},brightness:{state:'unknown'}}};
 const original={...s.preset};a.deepEqual(C.eligibleControls(s),{temperature:22});a.deepEqual(s.preset,original);
 const plan=C.environmentPlan(s);a.equal(plan.length,7);a.equal(plan.find(x=>x.key==='fan').state,'unsupported');a.equal(plan.find(x=>x.key==='brightness').state,'unknown');
});
test('missing capability and manual-only native controls never enter automatic payload',()=>{
 let s=prepare();s.signal={...sig,controlCapabilities:{temperature:{state:'supported',manualRequired:true}}};
 a.deepEqual(C.controlPayload(s),{});a.equal(C.capabilityState(s.signal,'temperature'),'unsupported');a.equal(C.capabilityState(s.signal,'fan'),'unknown');
});
test('capability lost between consent and alarm ACK keeps alarm and skips vehicle writes',()=>{
 let s=pending();s=step(s,'signal',{signal:{...sig,controlCapabilities:{}}});s=step(s,'alarm',{alarm:scheduled()});
 a.equal(s.phase,'rest');a.equal(s.alarm.state,'scheduled');a.ok(!types(s).includes('applyRest'));
});
test('additional rest with a pending restore keeps current environment and schedules only alarm',()=>{
 let s=prepare(false);s=step(s,'snapshot',{signal:{...sig,pendingRestore:true,alarmState:'idle',alarmReady:true}});
 s=step(s,'consent-environment',{value:true});a.equal(s.environmentConsent,false);a.match(s.notice,/현재 환경/);
 s=step(s,'rest',{requestId:'request-1'});a.deepEqual(types(s),['startAlarm']);s=step(s,'alarm',{alarm:scheduled()});a.equal(s.phase,'rest');a.ok(!types(s).includes('applyRest'));
});
test('pending journal cannot be bypassed with stale consent',()=>{
 let s=pending();s=step(s,'signal',{signal:{...sig,pendingRestore:true}});s=step(s,'alarm',{alarm:scheduled()});a.deepEqual(types(s),[]);
});
test('running restoration defers a new rest reservation but never cancels an existing alarm',()=>{
 let s=prepare();s.signal={...sig,controlPhase:'running',controlOperation:'restore'};
 s=step(s,'rest',{requestId:'new-request'});a.equal(s.phase,'prepare');a.deepEqual(s.effects,[]);a.match(s.notice,/작업/);
 s=step(rest(),'snapshot',{signal:{...sig,controlPhase:'running',controlOperation:'restore',...scheduled()}});a.equal(s.alarm.state,'scheduled');a.ok(!types(s).includes('cancelAlarm'));
});
test('explicit demo controls retain off and zero values while remaining a simulation',()=>{
 let s=prepare();s.runtime='demo';s.signal={...sig,controlCapabilities:{}};s.preset={...s.preset,temperatureEnabled:false,fanEnabled:false,dim:false,seatVentilationEnabled:true,seatVentilation:0,hvacPowerEnabled:true,hvacPower:false};
 a.deepEqual(C.eligibleControls(s),{seatVentilation:0,hvacPower:false});
});

const managedCaps=Object.fromEntries([['temperature',18,26,.5],['fan',1,3,1],['brightness',0,100,1],['seatVentilation',0,3,1],['hvacPower',0,1,1],['seatRecline',0,100,1],['blind',0,100,1]].map(([key,min,max,step])=>[key,{state:'supported',min,max,step,origin:0}]));
function managedPrepare(){let s=step(C.initial(t,'native'),'snapshot',{signal:{...sig,restOrchestration:true,systemAnimationsEnabled:true,controlCapabilities:managedCaps,alarmState:'idle',alarmReady:true}});s=step(s,'rest-mode');s=step(s,'confirm-place',{value:true});return step(s,'consent-environment',{value:true});}
test('native orchestration sends one complete plan and no duplicate post-ACK vehicle effects',()=>{
 let s=managedPrepare();s=step(s,'consent-cabin',{value:true});s=step(s,'consent-motion',{value:true});s=step(s,'rest',{requestId:'native-1'});
 a.equal(s.effects.length,1);a.equal(s.effects[0].type,'startRest');a.deepEqual(s.effects[0].data.plan,{temperature:22,fan:1,dim:true});a.equal(s.effects[0].data.cabinConsent,true);a.equal(s.effects[0].data.motionConsent,true);a.equal(s.effects[0].data.autoRestore,true);
 s=step(s,'alarm',{alarm:scheduled('native-1')});a.equal(s.phase,'rest');a.deepEqual(s.effects,[]);
});
test('only explicit wake requests restoration; alarm-only stop remains sound-only',()=>{
 let s=step(managedPrepare(),'rest',{requestId:'native-1'});s=step(s,'alarm',{alarm:scheduled('native-1')});
 a.deepEqual(step(s,'ack-alarm').effects[0],{type:'ackAlarm',data:{alarmId:'alarm-1',restore:false}});
 a.deepEqual(step(s,'wake-rest').effects[0],{type:'ackAlarm',data:{alarmId:'alarm-1',restore:true}});
 a.equal(step({...s,autoRestore:false},'wake-rest').effects[0].data.restore,false);
});
test('native capability values require exact finite range and step; physical targets start unselected',()=>{
 const s=managedPrepare();a.equal(s.preset.seatRecline,null);a.equal(s.preset.blind,null);a.equal(s.preset.seatReclineEnabled,false);a.equal(s.preset.blindEnabled,false);
 a.ok(C.validCapability(s.signal,'temperature',22.5));a.equal(C.validCapability(s.signal,'temperature',22.25),false);a.equal(C.validCapability(s.signal,'temperature',28),false);
 for(const change of [{min:null},{max:NaN},{step:0},{origin:null}])a.equal(C.validCapability({...s.signal,controlCapabilities:{temperature:{...managedCaps.temperature,...change}}},'temperature',22),false);
});
test('native foreground, live connection and reduced system motion are required',()=>{
 for(const change of [{lifecycleActive:false},{lifecycleActive:undefined},{mode:'unavailable'}])a.equal(C.parked({...sig,...change},t),false);
 const s=managedPrepare();a.equal(C.motionAllowed(s,t),true);a.equal(C.motionAllowed({...s,signal:{...s.signal,systemAnimationsEnabled:false}},t),false);a.equal(C.motionAllowed(s,t+5000),false);
});
test('native stop controls leaves the alarm authoritative and alive',()=>{
 let s=step(managedPrepare(),'rest',{requestId:'native-1'});s=step(s,'alarm',{alarm:scheduled('native-1')});s=step(s,'stop-controls');a.deepEqual(s.effects,[{type:'stopControls',data:{}}]);a.equal(s.alarm.state,'scheduled');
});

test('cabin form changes only on current routine verified seat apply or restore',()=>{
 const base={...sig,controlOperationId:'current',controlPhase:'complete',controlOperation:'apply',restSession:{phase:'resting',controlOperationId:'current'},controlSteps:[{operationId:'current',key:'seatRecline',state:'applied'}]};
 a.equal(C.scenePose(base,t),'rest');
 for(const patch of [{controlPhase:'running'},{restSession:{phase:'preparing',controlOperationId:'current'}},{restSession:{phase:'resting',controlOperationId:'old'}},{controlSteps:[{operationId:'old',key:'seatRecline',state:'applied'}]},{controlSteps:[{operationId:'current',key:'temperature',state:'applied'}]},{controlSteps:[...base.controlSteps,{operationId:'current',key:'fan',state:'failed'}]},{motionStopUnconfirmed:true},{gearAt:t-6000}])a.equal(C.scenePose({...base,...patch},t),'awake',JSON.stringify(patch));
 a.equal(C.scenePose({...base,alarmState:'ringing'},t),'rest');
 const restored={...base,controlOperation:'restore',pendingRestore:false,restSession:{phase:'complete',controlOperationId:'current'},controlSteps:[{operationId:'current',key:'seatRecline',state:'restored'}]};
 a.equal(C.scenePose(restored,t),'wake');
 for(const patch of [{pendingRestore:true},{restSession:{phase:'waking',controlOperationId:'current'}},{controlSteps:[{operationId:'current',key:'seatRecline',state:'manualPreserved'}]}])a.equal(C.scenePose({...restored,...patch},t),'awake');
});

test('restarted pending journal requires explicit restore and timer-only rest never inherits auto-restore',()=>{
 const stored={...C.initial(t,'native'),phase:'rest',logs:[],autoRestore:true};let s=C.restore(JSON.stringify(stored),t,'native');
 s=step(s,'snapshot',{signal:{...sig,restOrchestration:true,pendingRestore:true,alarmState:'idle',alarmReady:true,controlPhase:'idle'}});
 a.equal(s.phase,'interrupted');a.equal(types(s).includes('restoreRest'),false);
 const explicit=step(s,'restore-controls');a.equal(explicit.phase,'recovery');a.deepEqual(explicit.effects,[{type:'restoreRest',data:{}}]);
 a.deepEqual(step({...s,signal:{...s.signal,gearAt:t-6000}},'restore-controls').effects,[]);
 const running=step({...s,signal:{...s.signal,controlPhase:'running'}},'restore-controls');a.deepEqual(running.effects,[]);
 s=step(s,'rest-mode');s=step(s,'confirm-place',{value:true});s=step(s,'rest',{requestId:'new-timer'});a.equal(s.effects[0].type,'startRest');a.deepEqual(s.effects[0].data.plan,{});a.equal(s.effects[0].data.autoRestore,false);a.equal(s.autoRestore,true);
});

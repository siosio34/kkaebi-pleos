'use strict';
(function(root){
 const baseline=()=>({seat:0,light:.72,window:.45,blind:0,fan:.5,temperature:24,vent:0});
 const steps=[
  {id:'plan',chapter:0,title:'출발 전, 쉬어갈 계획',text:'90분 뒤 휴식을 떠올려요. 졸리면 계획보다 먼저 쉬어요.',duration:3000},
  {id:'drowsy',chapter:1,title:'“깨비, 졸음모드”',text:'졸림을 느꼈어요. 안전하게 쉴 곳을 확인하도록 안내해요.',duration:4000},
  {id:'seek',chapter:1,title:'가까운 안전한 곳으로',text:'차량 내비에서 진행 방향과 진입 경로를 확인해요. 장소 검색은 연결 준비 중이에요.',duration:3000},
  {id:'park',chapter:2,title:'안전하게 주차했어요',text:'P단·정지와 주변을 확인한 뒤, 선택한 환경을 준비해요.',duration:3000},
  {id:'window',chapter:3,key:'window',label:'창문',title:'창문부터 닫아요',text:'유리가 도어 안에서 올라와요. 이 창문 동작은 시연 전용이에요.',to:{window:0},duration:4500},
  {id:'blind',chapter:3,key:'blind',label:'블라인드',title:'바깥빛을 가려요',text:'블라인드가 위에서 아래로 펼쳐져요.',to:{blind:1},duration:4500},
  {id:'seat',chapter:3,key:'seatRecline',label:'시트',title:'편안한 자세로',text:'등받이가 축을 중심으로 천천히 기울어요.',to:{seat:.78},duration:4500},
  {id:'light',chapter:3,key:'brightness',label:'조명',title:'실내를 은은하게',text:'조명과 실내 표면의 밝기가 함께 낮아져요.',to:{light:.08},duration:4500},
  {id:'temperature',chapter:3,key:'temperature',label:'온도',title:'온도를 맞춰요',text:'시연 설정을 24°C에서 22°C로 바꿔요.',to:{temperature:22},duration:3500},
  {id:'fan',chapter:3,key:'fan',label:'풍량',title:'바람은 부드럽게',text:'공조의 바람을 낮춰요. 공기 흐름이 잦아들어요.',to:{fan:.18},duration:3500},
  {id:'vent',chapter:3,key:'seatVentilation',label:'통풍',title:'시트 통풍을 켜요',text:'좌석에서 올라오는 바람을 따로 조절해요.',to:{vent:.33},duration:3500},
  {id:'voice',chapter:3,title:'“20분 뒤 깨워드릴게요”',text:'휴식 안내 문구의 시연이에요. 실제 시스템 알람은 예약하지 않아요.',duration:2500},
  {id:'rest',chapter:4,title:'이제, 편히 쉬어요',text:'휴식 시간을 짧게 보여주는 시연이에요. 실제 20분이 지난 것은 아니에요.',duration:4000},
  {id:'alarm',chapter:5,title:'일어날 시간이에요',text:'알람 화면을 보여줘요. 깨어났다는 확인 후 원래 설정을 복원해요.',duration:3000},
  {id:'wake-light',chapter:5,key:'brightness',label:'조명',title:'빛으로 천천히 깨워요',text:'저장했던 실내 밝기로 돌아와요.',to:{light:.72},duration:4000},
  {id:'wake-blind',chapter:5,key:'blind',label:'블라인드',title:'바깥을 다시 보여줘요',text:'블라인드를 천천히 올려요.',to:{blind:0},duration:4000},
  {id:'wake-seat',chapter:5,key:'seatRecline',label:'시트',title:'처음 자세로 돌아와요',text:'등받이가 저장했던 위치로 복원돼요.',to:{seat:0},duration:4000},
  {id:'wake-window',chapter:5,key:'window',label:'창문',title:'창문도 이전 위치로',text:'열려 있던 만큼 내려가요. 실제 앱에서 창문은 수동 조절 대상이에요.',to:{window:.45},duration:4000},
  {id:'wake-climate',chapter:5,key:'fan',label:'공조',title:'공조 설정을 돌려놓아요',text:'온도·풍량·통풍을 시연 시작값으로 되돌려요.',to:{temperature:24,fan:.5,vent:0},duration:4000},
  {id:'check',chapter:6,title:'출발은 몸 상태를 보고',text:'아직 졸리면 더 쉬거나 운전자를 바꿔요. 깨비는 운전 가능 여부를 판정하지 않아요.',duration:4000}
 ];
 // The original Engine contract remains available to saved v0.7 journeys.
 class LegacyEngine{constructor(){this.reset();}reset(){this.index=0;this.elapsed=0;this.values=baseline();this.from={...this.values};this.running=false;this.finished=false;}start(){if(this.finished)this.reset();this.running=true;}pause(){this.running=false;}next(){this.values={...this.values,...(steps[this.index].to||{})};if(this.index===steps.length-1){this.finished=true;this.running=false;return;}this.index++;this.elapsed=0;this.from={...this.values};}tick(dt,reduced=false){if(!this.running||this.finished||!Number.isFinite(dt))return;let remaining=Math.max(0,dt);while(remaining>0&&this.running&&!this.finished){const s=steps[this.index],consumed=Math.min(remaining,s.duration-this.elapsed);this.elapsed+=consumed;remaining-=consumed;const u=reduced?1:Math.min(1,this.elapsed/(s.duration*.76)),p=1-Math.pow(1-u,3);for(const [k,v]of Object.entries(s.to||{}))this.values[k]=this.from[k]+(v-this.from[k])*p;if(this.elapsed>=s.duration)this.next();}}set(key,value){this.pause();this.values[key]=value;this.from={...this.values};}snapshot(){return {index:this.index,elapsed:this.elapsed,step:steps[this.index],values:{...this.values},from:{...this.from},running:this.running,finished:this.finished,total:steps.length};}}
 const routineBaseline=()=>({...baseline(),hvacPower:0,voice:0,sound:0,alarmSet:0});
 const components=[
  {key:'seatRecline',valueKey:'seat',label:'시트',target:.78,duration:4000,restoreDuration:3500,note:'등받이 기울기 · 모형값'},
  {key:'window',valueKey:'window',label:'창문',target:0,duration:3500,restoreDuration:3000,note:'시연 전용 · 실제 앱은 수동 조절'},
  {key:'blind',valueKey:'blind',label:'블라인드',target:1,duration:3500,restoreDuration:3000,note:'바깥빛 가림 · 모형값'},
  {key:'hvacPower',valueKey:'hvacPower',label:'공조 전원',target:1,duration:2200,restoreDuration:1800,note:'전원 상태 예시'},
  {key:'temperature',valueKey:'temperature',label:'온도',target:22,duration:2700,restoreDuration:2200,note:'설정 온도 예시'},
  {key:'fan',valueKey:'fan',label:'풍량',target:.18,duration:2700,restoreDuration:2200,note:'공조 바람 · 모형값'},
  {key:'seatVentilation',valueKey:'vent',label:'시트 통풍',target:.33,duration:2700,restoreDuration:2200,note:'좌석 바람 · 모형값'},
  {key:'brightness',valueKey:'light',label:'조명',target:.08,duration:3500,restoreDuration:3000,note:'휴식 밝기 · 모형값'},
  {key:'voice',valueKey:'voice',label:'음성 안내',target:1,duration:1400,restoreDuration:1000,note:'안내 문구 예시 · 실제 음성 없음'},
  {key:'sound',valueKey:'sound',label:'휴식 사운드',target:1,duration:1400,restoreDuration:1000,note:'음량 줄이기 예시 · 실제 재생 없음'},
  {key:'alarmSet',valueKey:'alarmSet',label:'기상 알람',target:1,duration:1400,restoreDuration:1000,note:'20분 뒤 예시 · 실제 예약 없음'}
 ];
 const targets=Object.fromEntries(components.map(c=>[c.valueKey,c.target]));
 const operation=(c,restore)=>({id:(restore?'restore-':'prepare-')+c.key,key:c.key,valueKey:c.valueKey,label:c.label,chapter:restore?5:3,direction:restore?'restore':'prepare',title:restore?c.label+'을 이전 설정으로':c.label+' 준비 중',text:c.note,to:{[c.valueKey]:restore?routineBaseline()[c.valueKey]:c.target},duration:restore?c.restoreDuration:c.duration});
 const preparation=components.map(c=>operation(c,false)),restoration=components.map(c=>operation(c,true));
 const rest={id:'rest',chapter:4,title:'수면 환경이 준비됐어요',text:'20분 휴식 예시 · 일어났어요를 누르면 이전 설정으로 복원해요.',duration:4000};
 const alarm={id:'alarm',chapter:5,title:'일어날 시간이에요',text:'기상 화면 예시 · 실제 알람은 울리지 않아요.',duration:2000};
 const check={id:'check',chapter:6,title:'이전 설정으로 돌아왔어요',text:'몸 상태를 확인해요. 아직 졸리면 더 쉬거나 운전자를 바꿔요.',duration:3100};
 const sleepSteps=[...preparation,rest,alarm,...restoration,check];
 const journeySteps=[...steps.slice(0,4),...preparation,{...rest,text:'20분 휴식을 4초로 줄인 시연 · 이어서 자동 기상·복원해요.'},alarm,...restoration,check];
 const format=(key,value)=>key==='temperature'?Number(value).toFixed(1).replace('.0','')+'°C':key==='hvacPower'?(value>=.5?'켜짐':'꺼짐'):key==='voice'?(value>=.5?'휴식 안내':'대기'):key==='sound'?(value>=.5?'음량 낮춤':'이전 음량'):key==='alarmSet'?(value>=.5?'20분 뒤':'예약 없음'):Math.round(value*100)+'%';
 class RoutineEngine{
  constructor(mode){this.mode=mode;this.steps=mode==='journey'?journeySteps:sleepSteps;this.reset();}
  reset(){this.index=0;this.elapsed=0;this.values=routineBaseline();this.from={...this.values};this.running=false;this.started=false;this.finished=false;this.awaitingWake=false;}
  start(){if(this.awaitingWake)return;if(this.finished)this.reset();this.started=true;this.running=true;}
  pause(){this.running=false;}
  next(){if(this.awaitingWake)return;const step=this.steps[this.index];Object.assign(this.values,step.to||{});if(this.index===this.steps.length-1){this.finished=true;this.running=false;return;}this.index++;this.elapsed=0;this.from={...this.values};if(this.mode==='sleep'&&this.steps[this.index].id==='rest'){this.awaitingWake=true;this.running=false;}}
  wake(){if(!this.awaitingWake)return;this.awaitingWake=false;this.next();this.running=true;}
  tick(dt,reduced=false){if(!this.running||this.finished||!Number.isFinite(dt))return;let remaining=Math.max(0,dt);while(remaining>0&&this.running&&!this.finished){const step=this.steps[this.index],consumed=Math.min(remaining,step.duration-this.elapsed);this.elapsed+=consumed;remaining-=consumed;const fraction=this.elapsed/step.duration,u=reduced?1:Math.max(0,Math.min(1,(fraction-.08)/.68)),p=1-Math.pow(1-u,3);for(const [key,value]of Object.entries(step.to||{}))this.values[key]=u===1?value:this.from[key]+(value-this.from[key])*p;if(this.elapsed>=step.duration)this.next();}}
  set(key,value){this.pause();if(Number.isFinite(value)&&Object.hasOwn(this.values,key)){this.values[key]=value;this.from={...this.values};}}
  snapshot(){const step=this.steps[this.index],restoring=step.chapter>=5,direction=restoring?'restore':'prepare',progress=Math.max(0,Math.min(1,this.elapsed/step.duration));const rows=components.map(c=>{const operationIndex=this.steps.findIndex(s=>s.key===c.key&&s.direction===direction),done=this.index>operationIndex,active=this.started&&this.index===operationIndex;const state=done?'complete':active?(progress<.08?'waiting':progress<.76?'adjusting':'confirming'):'waiting',before=restoring?targets[c.valueKey]:routineBaseline()[c.valueKey],target=restoring?routineBaseline()[c.valueKey]:targets[c.valueKey];return {key:c.key,label:c.label,state,before,target,current:this.values[c.valueKey],progress:done?1:active?progress:0,detail:c.note,model:true,active,direction,valueKey:c.valueKey};});return {index:this.index,elapsed:this.elapsed,step,values:{...this.values},from:{...this.from},running:this.running,started:this.started,finished:this.finished,awaitingWake:this.awaitingWake,total:this.steps.length,mode:this.mode,phase:step.id==='rest'?'rest':this.finished||step.id==='check'?'complete':restoring?'restore':step.chapter<3?'journey':'prepare',direction,activeKey:step.key||null,rows};}
 }
 const parallelGroups=[
  {id:'position',label:'자세·차광',keys:['seatRecline','window','blind']},
  {id:'environment',label:'빛·통풍',keys:['brightness','seatVentilation']},
  {id:'climate',label:'공조',keys:['hvacPower','temperature','fan']}
 ];
 const parallelTiming={
  prepare:{seatRecline:[6000,['alarmSet']],window:[4400,['alarmSet']],blind:[4000,['seatRecline']],brightness:[3600,['alarmSet']],seatVentilation:[3500,['brightness']],hvacPower:[2200,['alarmSet']],temperature:[3000,['hvacPower']],fan:[4000,['temperature']],voice:[1200,['alarmSet']],sound:[1600,['voice']],alarmSet:[400,[]]},
  restore:{seatRecline:[5000,['blind']],window:[4000,['alarmSet']],blind:[3500,['alarmSet']],brightness:[3000,['alarmSet']],seatVentilation:[3000,['brightness']],hvacPower:[1800,['alarmSet']],temperature:[3000,['hvacPower']],fan:[4000,['temperature']],voice:[1000,['alarmSet']],sound:[1000,['voice']],alarmSet:[400,[]]}
 };
 function makeSchedule(direction){const placed=new Map(),timing=parallelTiming[direction];function place(key){if(placed.has(key))return placed.get(key);const component=components.find(item=>item.key===key),[duration,dependsOn]=timing[key],startAt=Math.max(0,...dependsOn.map(dependency=>place(dependency).endAt)),group=parallelGroups.find(item=>item.keys.includes(key));const item={...component,duration,dependsOn:[...dependsOn],startAt,endAt:startAt+duration,groupId:group?.id||'support',before:direction==='restore'?component.target:routineBaseline()[component.valueKey],target:direction==='restore'?routineBaseline()[component.valueKey]:component.target};placed.set(key,item);return item;}return components.map(component=>place(component.key));}
 const parallelSchedules={prepare:makeSchedule('prepare'),restore:makeSchedule('restore')};
 const parallelOperation=direction=>({id:direction,chapter:direction==='prepare'?3:5,direction,title:direction==='prepare'?'함께 준비하는 수면 환경':'이전 설정으로 함께 복원',text:'독립된 항목은 함께, 연결된 항목은 확인 뒤 진행해요.',duration:Math.max(...parallelSchedules[direction].map(item=>item.endAt)),to:direction==='prepare'?targets:routineBaseline()});
 const parallelSleepSteps=[parallelOperation('prepare'),rest,alarm,parallelOperation('restore'),check];
 const parallelJourneySteps=[...steps.slice(0,4),parallelOperation('prepare'),{...rest,text:'20분 휴식을 4초로 줄인 시연 · 이어서 자동 기상·복원해요.'},alarm,parallelOperation('restore'),check];
 class ParallelEngine extends RoutineEngine{
  constructor(mode){super(mode);this.steps=mode==='journey'?parallelJourneySteps:parallelSleepSteps;this.reset();}
  tick(dt,reduced=false){if(!this.running||this.finished||!Number.isFinite(dt))return;let remaining=Math.max(0,dt);while(remaining>0&&this.running&&!this.finished){const step=this.steps[this.index],consumed=Math.min(remaining,step.duration-this.elapsed);this.elapsed+=consumed;remaining-=consumed;if(step.direction){for(const item of parallelSchedules[step.direction]){const elapsed=this.elapsed-item.startAt;if(elapsed<0)continue;const fraction=Math.min(1,elapsed/item.duration),u=reduced?1:Math.max(0,Math.min(1,(fraction-.08)/.68)),p=1-Math.pow(1-u,3);this.values[item.valueKey]=u===1?item.target:item.before+(item.target-item.before)*p;}}if(this.elapsed>=step.duration)this.next();}}
  snapshot(){const step=this.steps[this.index],direction=step.chapter>=5?'restore':'prepare',operationIndex=this.steps.findIndex(item=>item.direction===direction),after=this.index>operationIndex,current=this.started&&this.index===operationIndex;const rows=parallelSchedules[direction].map(item=>{const elapsed=this.elapsed-item.startAt,progress=after?1:current?Math.max(0,Math.min(1,elapsed/item.duration)):0,active=current&&elapsed>=0&&elapsed<item.duration,complete=after||current&&elapsed>=item.duration,state=complete?'complete':active?(progress<.08?'waiting':progress<.76?'adjusting':'confirming'):'waiting';return {key:item.key,label:item.label,state,before:item.before,target:item.target,current:this.values[item.valueKey],progress,detail:item.note,model:true,active,direction,valueKey:item.valueKey,groupId:item.groupId,dependsOn:[...item.dependsOn],startAt:item.startAt,endAt:item.endAt};});const activeKeys=rows.filter(row=>row.active).map(row=>row.key),groups=parallelGroups.map(group=>{const members=rows.filter(row=>row.groupId===group.id),active=members.filter(row=>row.active),completed=members.filter(row=>row.state==='complete').length;return {...group,activeKeys:active.map(row=>row.key),completed,total:members.length,progress:members.reduce((sum,row)=>sum+row.progress,0)/members.length,state:completed===members.length?'complete':active.length?'active':'waiting'};});return {index:this.index,elapsed:this.elapsed,step,values:{...this.values},from:{...this.from},running:this.running,started:this.started,finished:this.finished,awaitingWake:this.awaitingWake,total:this.steps.length,mode:this.mode,schedule:'parallel',phase:step.id==='rest'?'rest':this.finished||step.id==='check'?'complete':step.chapter<3?'journey':direction,direction,activeKey:activeKeys[0]||null,activeKeys,groups,rows};}
 }
 class Engine{constructor(config){return config?.mode==='sleep'||config?.mode==='journey'?(config.schedule==='sequential'?new RoutineEngine(config.mode):new ParallelEngine(config.mode)):new LegacyEngine();}}
 const api={Engine,steps,baseline,components,sleepSteps,journeySteps,routineBaseline,format,parallelGroups,parallelSchedules,parallelSleepSteps,parallelJourneySteps};if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.CabinTour=api;
})(typeof window==='undefined'?globalThis:window);

const {test}=require('node:test');
const assert=require('node:assert/strict');
const T=require('../app/src/main/assets/web/demo/tour.js');
const prepareDuration=T.components.reduce((sum,c)=>sum+c.duration,0);
const restoreDuration=T.components.reduce((sum,c)=>sum+c.restoreDuration,0);
test('v0.8 sequential compatibility: sleep preset starts at the seat and exposes all preparation targets before starting',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'}),s=engine.snapshot();
 assert.equal(s.step.key,'seatRecline');assert.equal(s.started,false);assert.equal(s.rows.length,11);
 assert.deepEqual(s.rows.map(r=>r.key),['seatRecline','window','blind','hvacPower','temperature','fan','seatVentilation','brightness','voice','sound','alarmSet']);
 assert.ok(s.rows.every(r=>r.state==='waiting'&&r.progress===0&&r.model));
 assert.equal(s.rows.find(r=>r.key==='temperature').target,22);assert.equal(s.rows[0].target,.78);
});
test('v0.8 sequential compatibility: each preparation operates only its active model part and exposes all four stages',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'});engine.start();
 for(const component of T.components){const before={...engine.values};assert.equal(engine.snapshot().activeKey,component.key);assert.equal(engine.snapshot().rows.find(r=>r.active).state,'waiting');
  engine.tick(component.duration*.12);let s=engine.snapshot();assert.equal(s.rows.find(r=>r.active).state,'adjusting');
  for(const key of Object.keys(before).filter(key=>key!==component.valueKey))assert.equal(engine.values[key],before[key],component.key+' should preserve '+key);
  engine.tick(component.duration*.68);s=engine.snapshot();assert.equal(s.rows.find(r=>r.active).state,'confirming');assert.equal(engine.values[component.valueKey],component.target);
  engine.tick(component.duration*.2+1e-6);assert.equal(engine.snapshot().rows.find(r=>r.key===component.key).state,'complete');
 }
 assert.equal(engine.snapshot().awaitingWake,true);
});
test('v0.8 sequential compatibility: sleep mode consumes overshoot only through preparation, waits indefinitely, then explicitly restores every value',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'});engine.start();engine.tick(90000);let s=engine.snapshot();
 assert.equal(s.phase,'rest');assert.equal(s.awaitingWake,true);assert.equal(s.running,false);assert.equal(s.elapsed,0);assert.ok(s.rows.every(r=>r.state==='complete'));
 engine.start();engine.tick(90000);assert.deepEqual(engine.snapshot(),s);
 engine.wake();engine.tick(2000);s=engine.snapshot();assert.equal(s.direction,'restore');assert.equal(s.activeKey,'seatRecline');assert.ok(s.rows.every(r=>r.state==='waiting'));
 engine.tick(restoreDuration+3100);s=engine.snapshot();assert.equal(s.finished,true);assert.ok(s.rows.every(r=>r.state==='complete'));assert.deepEqual(s.values,T.routineBaseline());
});
test('v0.8 sequential compatibility: the seat-first full journey preserves a real 75-second schedule including compressed rest',()=>{
 assert.equal(T.journeySteps.reduce((sum,s)=>sum+s.duration,0),75000);
 const engine=new T.Engine({mode:'journey',schedule:'sequential'});engine.start();engine.tick(13250);assert.equal(engine.snapshot().activeKey,'seatRecline');assert.equal(engine.elapsed,250);
 engine.tick(75000-13250);assert.equal(engine.finished,true);assert.deepEqual(engine.values,T.routineBaseline());
 const uneven=new T.Engine({mode:'journey',schedule:'sequential'});uneven.start();for(let elapsed=0;elapsed<75000;elapsed+=250)uneven.tick(250);assert.equal(uneven.finished,true);
});
test('v0.8 sequential compatibility: wake restoration repeats each stage and leaves other component values untouched',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'});engine.start();engine.tick(prepareDuration);engine.wake();engine.tick(2000);
 for(const component of T.components){const before={...engine.values};let row=engine.snapshot().rows.find(r=>r.active);assert.equal(row.key,component.key);assert.equal(row.direction,'restore');assert.equal(row.state,'waiting');
  engine.tick(component.restoreDuration*.2);row=engine.snapshot().rows.find(r=>r.active);assert.equal(row.state,'adjusting');for(const key of Object.keys(before).filter(key=>key!==component.valueKey))assert.equal(engine.values[key],before[key]);
  engine.tick(component.restoreDuration*.6);assert.equal(engine.snapshot().rows.find(r=>r.active).state,'confirming');assert.equal(engine.values[component.valueKey],T.routineBaseline()[component.valueKey]);
  engine.tick(component.restoreDuration*.2+1e-6);assert.equal(engine.snapshot().rows.find(r=>r.key===component.key).state,'complete');
 }
 assert.deepEqual(engine.values,T.routineBaseline());
});
test('v0.8 sequential compatibility: reduced motion settles the active value without skipping confirmation or leaking into the next component',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'});engine.start();engine.tick(1,true);assert.equal(engine.values.seat,.78);assert.equal(engine.values.window,.45);assert.equal(engine.index,0);engine.tick(3199,true);assert.equal(engine.snapshot().rows[0].state,'confirming');assert.equal(engine.index,0);
});
test('v0.8 sequential compatibility: paused sleep progress and invalid timestamps remain stable; reset clears restoration ledger',()=>{
 const engine=new T.Engine({mode:'sleep',schedule:'sequential'});engine.start();engine.tick(1500);engine.pause();const s=engine.snapshot();engine.tick(90000);assert.deepEqual(engine.snapshot(),s);
 engine.start();engine.tick(NaN);engine.tick(Infinity);engine.tick(-200);assert.equal(engine.elapsed,1500);engine.tick(prepareDuration);engine.wake();engine.tick(restoreDuration+4100);engine.reset();assert.deepEqual(engine.values,T.routineBaseline());assert.ok(engine.snapshot().rows.every(r=>r.state==='waiting'));assert.equal(engine.started,false);
});

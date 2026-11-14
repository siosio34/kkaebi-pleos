const {test}=require('node:test'),assert=require('node:assert/strict');
const T=require('../app/src/main/assets/web/demo/tour.js');
const prepareDuration=T.parallelSleepSteps.find(step=>step.id==='prepare').duration;
const restoreDuration=T.parallelSleepSteps.find(step=>step.id==='restore').duration;
test('v0.9 parallel schedule confirms alarm example before simultaneous hardware and voice',()=>{
 const e=new T.Engine({mode:'sleep'});e.start();e.tick(399);let s=e.snapshot();assert.deepEqual(s.activeKeys,['alarmSet']);assert.equal(s.rows.find(r=>r.key==='seatRecline').state,'waiting');assert.equal(s.values.seat,0);
 e.tick(1);s=e.snapshot();assert.equal(s.rows.find(r=>r.key==='alarmSet').state,'complete');assert.deepEqual(s.activeKeys,['seatRecline','window','hvacPower','brightness','voice']);
 e.tick(900);s=e.snapshot();assert.ok(s.values.seat>0&&s.values.seat<.78);assert.ok(s.values.window>0&&s.values.window<.45);assert.ok(s.values.light>0.08&&s.values.light<.72);assert.equal(s.values.blind,0);assert.equal(s.values.vent,0);assert.equal(s.values.temperature,24);assert.equal(s.values.fan,.5);
 assert.deepEqual(s.groups.map(g=>g.id),['position','environment','climate']);assert.ok(s.groups.every(g=>g.state==='active'));assert.equal(s.groups[0].activeKeys.length,2);assert.equal(s.activeKey,s.activeKeys[0]);
});
test('v0.9 dependencies wait for completion, not target arrival, and groups finish independently',()=>{
 const e=new T.Engine({mode:'sleep'});e.start();e.tick(2200);let s=e.snapshot();assert.equal(s.values.hvacPower,1);assert.equal(s.rows.find(r=>r.key==='hvacPower').state,'confirming');assert.equal(s.values.temperature,24);
 e.tick(400);s=e.snapshot();assert.equal(s.rows.find(r=>r.key==='hvacPower').state,'complete');assert.ok(s.activeKeys.includes('temperature'));assert.equal(s.rows.find(r=>r.key==='fan').state,'waiting');
 e.tick(3000);s=e.snapshot();assert.equal(s.rows.find(r=>r.key==='temperature').state,'complete');assert.ok(s.activeKeys.includes('fan'));assert.equal(s.rows.find(r=>r.key==='blind').state,'waiting');assert.ok(s.activeKeys.includes('seatVentilation'));
 e.tick(800);s=e.snapshot();assert.equal(s.rows.find(r=>r.key==='seatRecline').state,'complete');assert.ok(s.activeKeys.includes('blind'));
 e.tick(1100);s=e.snapshot();assert.equal(s.groups.find(g=>g.id==='environment').state,'complete');assert.equal(s.groups.find(g=>g.id==='position').state,'active');assert.equal(s.groups.find(g=>g.id==='climate').state,'active');
 for(const row of s.rows)for(const dependency of row.dependsOn)assert.ok(row.startAt>=s.rows.find(r=>r.key===dependency).endAt);
});
test('v0.9 preparation holds at10.4s; only explicit wake restores all groups',()=>{
 assert.equal(prepareDuration,10400);assert.equal(restoreDuration,9200);const e=new T.Engine({mode:'sleep'});e.start();e.tick(90000);let s=e.snapshot();assert.equal(s.phase,'rest');assert.equal(s.awaitingWake,true);assert.equal(s.running,false);assert.ok(s.rows.every(r=>r.state==='complete'));assert.ok(s.groups.every(g=>g.state==='complete'));assert.deepEqual(s.activeKeys,[]);
 e.start();e.tick(90000);assert.deepEqual(e.snapshot(),s);e.wake();e.tick(3300);s=e.snapshot();assert.equal(s.direction,'restore');assert.ok(s.activeKeys.length>=3);assert.equal(s.values.seat,.78);assert.ok(s.values.light>.08);assert.ok(s.values.window>0);assert.ok(s.values.blind>0&&s.values.blind<1);assert.ok(s.rows.find(r=>r.key==='seatRecline').startAt>=s.rows.find(r=>r.key==='blind').endAt);
 e.tick(restoreDuration+3100);s=e.snapshot();assert.equal(s.finished,true);assert.deepEqual(s.values,T.routineBaseline());assert.ok(s.rows.every(r=>r.state==='complete'));
});
test('v0.9 reduced motion settles eligible rows but keeps dependencies and group progress',()=>{
 const e=new T.Engine({mode:'sleep'});e.start();e.tick(200,true);assert.equal(e.values.alarmSet,1);assert.equal(e.values.seat,0);e.tick(300,true);let s=e.snapshot();assert.equal(s.values.seat,.78);assert.equal(s.values.window,0);assert.equal(s.values.light,.08);assert.equal(s.values.hvacPower,1);assert.equal(s.values.blind,0);assert.equal(s.values.temperature,24);assert.equal(s.values.fan,.5);assert.ok(s.groups.every(g=>g.state==='active'));
 e.pause();const frozen=e.snapshot();e.tick(90000,true);assert.deepEqual(e.snapshot(),frozen);
});
test('v0.9 journey preserves elapsed overshoot across grouped preparation and restoration',()=>{
 const duration=T.parallelJourneySteps.reduce((sum,step)=>sum+step.duration,0);assert.equal(duration,41700);const e=new T.Engine({mode:'journey'});e.start();e.tick(14500);let s=e.snapshot();assert.equal(s.phase,'prepare');assert.equal(s.elapsed,1500);assert.ok(s.activeKeys.length>=3);e.tick(duration-14500);assert.equal(e.finished,true);assert.deepEqual(e.values,T.routineBaseline());
 const uneven=new T.Engine({mode:'journey'});uneven.start();let elapsed=0;while(elapsed<duration){const dt=Math.min(253,duration-elapsed);uneven.tick(dt);elapsed+=dt;}assert.deepEqual(uneven.values,T.routineBaseline());assert.equal(uneven.finished,true);
});
test('v0.9 all eleven rows independently visit waiting, adjusting, confirming and complete',()=>{
 const e=new T.Engine({mode:'sleep'}),seen=new Map(T.components.map(c=>[c.key,new Set(['waiting'])]));e.start();for(let t=0;t<prepareDuration;t+=20){e.tick(20);for(const row of e.snapshot().rows)seen.get(row.key).add(row.state);}for(const [key,states]of seen)assert.deepEqual([...states],['waiting','adjusting','confirming','complete'],key);
 e.wake();e.tick(2000);for(const states of seen.values()){states.clear();states.add('waiting');}for(let t=0;t<restoreDuration;t+=20){e.tick(20);for(const row of e.snapshot().rows)seen.get(row.key).add(row.state);}for(const [key,states]of seen)assert.deepEqual([...states],['waiting','adjusting','confirming','complete'],key);
});
